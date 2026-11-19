// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.HomeProgram
import it.allard.etincelle.core.model.LiveChannel
import it.allard.etincelle.core.model.LoginResult
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.model.PairingCode
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.model.coerceWholeNumbers
import it.allard.etincelle.core.network.NetworkClient
import it.allard.etincelle.core.network.ProfilePick
import it.allard.etincelle.core.network.ProgressStore
import it.allard.etincelle.core.network.SessionManager
import it.allard.etincelle.core.network.SessionStore
import it.allard.etincelle.core.network.isTrustedApiHost
import it.allard.etincelle.core.remote.RemoteProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException

// A full day of guide so a channel's "tout voir" grid covers at least 24h.
private const val GUIDE_WINDOW_MS = 24L * 60 * 60 * 1000
// The guide pages its channels (183 in November 2026, about 3.5 MB of JSON per 40).
private const val GUIDE_PAGE_SIZE = 100
// Bounds the paging should the backend ever hand back an offset that does not move on.
private const val GUIDE_MAX_PAGES = 10
// What the user reads when a page could not be loaded for a reason of no use to them.
// The stretch of guide asked for to learn what is on air on a channel.
private const val ON_AIR_SPAN_MS = 60_000L
// How long a channel about to play waits for it.
private const val ON_AIR_WAIT_MS = 3_000L
private const val PROGRESS_OFFSET_KEY = "lastOffset"
private const val PROGRESS_END_MS = 15_000L
private const val PROGRESS_MIN_MS = 10_000L
// The official app asks who is watching again once the last choice is two days old.
private const val PROFILE_PICK_TTL_MS = 2L * 24 * 60 * 60 * 1000
// How long the channels page, with the applications it lists as locked or not, is taken as current.
private const val CHANNELS_PAGE_MAX_AGE_MS = 10L * 60 * 1000
// The recordings page of "Mes vidéos", which the Enregistrements tab and the recordings rows open.
private const val RECORDINGS_PATH = "papi/v1/my-stuff/status/recordings"
// How many times the user is asked for at the end of a sign-in, and how long between two.
private const val USER_LOAD_TRIES = 3
private const val USER_LOAD_RETRY_MS = 1_000L

// The count closing a search result rail's title, as in "Programmes (8)".
private val RAIL_COUNT = Regex("""\((\d+)\)\s*$""")

/** Fubo implementation of auth + content + playback, with persisted sessions and token refresh. */
class FuboRepository(
    private val api: FuboApi,
    private val session: SessionManager,
    private val store: SessionStore,
    private val progress: ProgressStore,
    private val clock: () -> Long = System::currentTimeMillis,
    // Whether a host is sent the session (see AuthInterceptor).
    private val ownHost: (String) -> Boolean = { true },
) : MolotovRepository {
    constructor(network: NetworkClient, store: SessionStore, progress: ProgressStore) : this(
        network.retrofit.create(FuboApi::class.java),
        network.session,
        store,
        progress,
        ownHost = String::isTrustedApiHost,
    )

    /**
     * Finishes a sign-in once tokens are in hand: sets the access token so api.user() authenticates,
     * loads the user/profile id, and persists the full session. If the user load fails it clears that
     * half-initialized session (empty ids) again, so a failed login leaves no bad session behind.
     */
    private suspend fun finishLogin(accessToken: String, refreshToken: String?): UserSession {
        val previous = session.session
        session.session = UserSession(accessToken, refreshToken, userId = "", profileId = "")
        try {
            val user = userAtSignIn().data ?: throw AppError.Unknown("Empty user response")
            val userId = user.id ?: throw AppError.Unknown("Missing user id")
            val profileId = user.profiles?.firstOrNull()?.id ?: throw AppError.Unknown("Missing profile")
            // Another account than the one whose session had died here: its resume points go now.
            if (expiredUserId != null && expiredUserId != userId) progress.clearAll()
            expiredUserId = null
            return UserSession(accessToken, refreshToken, userId, profileId).also {
                session.session = it
                store.save(it)
            }
        } catch (e: Exception) {
            session.session = previous
            throw e
        }
    }

    // The tokens in hand were given for a password, a code typed or one confirmed on a phone, none of
    // which serves twice: losing them to a network that fails for a moment means going through the
    // sign-in again. So the user is asked for a few times before they are given up.
    private suspend fun userAtSignIn(): UserResponse {
        var failure: IOException? = null
        repeat(USER_LOAD_TRIES) { attempt ->
            if (attempt > 0) delay(USER_LOAD_RETRY_MS)
            try {
                return api.user()
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("no user")
    }

    /** Signs in, loads the user/profile id, and persists the session; or asks for a code (two-factor). */
    override suspend fun login(email: String, password: String): LoginResult {
        val response = try {
            api.signin(SigninRequest(email, password))
        } catch (e: HttpException) {
            // Only the sign-in call reflects the credentials, so a 4xx here is a rejected sign-in
            // rather than an expired session; signinMessage() sorts out which of the several states
            // a 401 actually is. The backend's own error table pairs a blocked account with 403
            // (BLOCKED_USER) and a region, VPN or subscription-market block with 451, so the two are
            // distinct answers and not interchangeable.
            throw when (e.code()) {
                400, 401 -> AppError.Unknown(signinMessage(e.backendError()))
                403 -> AppError.Unknown("Ce compte est bloqué")
                451 -> blockedError(e.backendError())
                else -> e.toAppError()
            }
        } catch (e: IOException) {
            throw AppError.Network("Pas de connexion, vérifiez votre réseau")
        }
        response.mfaToken?.let { return LoginResult.CodeRequired(mfaChallenge(it)) }
        val tokens = response.tokens
        val accessToken = tokens.accessToken ?: throw AppError.Unknown(WRONG_CREDENTIALS)
        // The user/profile load runs after credentials are accepted, so its errors are not credential
        // failures: map them normally (a 403 here is Forbidden, a 401 an expired token).
        return LoginResult.SignedIn(translateErrors { finishLogin(accessToken, tokens.refreshToken) })
    }

    // The code page for a two-factor sign-in; it names the code field and where to send the code.
    private suspend fun mfaChallenge(token: String): MfaChallenge = mfaCall {
        api.mfaPage(token).toChallenge(token) ?: throw AppError.Unknown(MFA_UNAVAILABLE)
    }

    override suspend fun verifyCode(challenge: MfaChallenge, code: String): UserSession {
        @Suppress("UNCHECKED_CAST")
        val body = coerceWholeNumbers(challenge.verifyPayload + (challenge.inputName to code.trim())) as Map<String, Any?>
        val tokens = mfaCall { api.mfaVerify(challenge.verifyUrl, challenge.token, body) }.tokens
        val accessToken = tokens.accessToken ?: throw AppError.Unknown(codeMessage(null))
        return translateErrors { finishLogin(accessToken, tokens.refreshToken) }
    }

    override suspend fun resendCode(challenge: MfaChallenge): MfaChallenge {
        val url = challenge.resendUrl ?: return challenge
        val response = mfaCall { api.mfaResend(url, challenge.token) }
        return challenge.copy(token = response.mfaToken ?: challenge.token)
    }

    // A two-factor call: a code the backend refuses gets its own message, and an expired MFA token
    // means signing in again (Unauthorized with no session takes the app back to the sign-in screen).
    // A block or an outage is no verdict on the code, so it keeps the message it has elsewhere.
    private suspend fun <T> mfaCall(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val error = e.backendError()
        throw when {
            error?.code == "MFA_TOKEN_EXPIRED" -> AppError.Unauthorized
            e.code() == 451 -> blockedError(error)
            e.code() in 500..599 -> e.toAppError()
            else -> AppError.Unknown(codeMessage(error))
        }
    } catch (e: IOException) {
        throw AppError.Network("Pas de connexion, vérifiez votre réseau")
    }

    /** TV pairing: fetch a fresh code and its lifetime for the user to confirm on their phone. A fresh
     * device id per attempt makes each generated code distinct (the backend ties the code to it). */
    override suspend fun startCodeLogin(): PairingCode {
        val deviceId = java.util.UUID.randomUUID().toString()
        val resp = api.signInCode(deviceId)
        val code = resp.code ?: throw AppError.Unknown("Empty pairing code")
        val ttl = ((resp.expiresAt ?: 0L) - (resp.issuedAt ?: 0L)).coerceAtLeast(60L)
        return PairingCode(code, ttl, deviceId)
    }

    /**
     * TV pairing: poll a code (with the device id that generated it). While pending the response
     * carries no tokens, so this returns null; once confirmed, the tokens arrive and we finish the
     * same way as a password sign-in.
     */
    override suspend fun pollCodeLogin(code: String, deviceId: String): UserSession? {
        val tokens =
            api.pollSignInCode(deviceId, SignInCodePollRequest(code)).data?.tokens ?: return null
        val accessToken = tokens.accessToken ?: return null
        return finishLogin(accessToken, tokens.refreshToken)
    }

    /** Confirms a TV's pairing code from this signed-in account (authenticated PUT). */
    override suspend fun confirmTvCode(code: String) = withRefresh {
        api.confirmSignInCode(SignInCodePollRequest(code)).close()
    }

    override suspend fun sessionSaved(): Boolean = store.holds()

    /** Loads a persisted session into memory; returns true if one was present. */
    override suspend fun restoreSession(): Boolean = refreshMutex.withLock {
        // Read under the lock a sign-out clears it under: read before that and put back after it, the
        // session of the account just left would be in memory again.
        val saved = store.read() ?: return@withLock false
        // Another restore, then a renewal of its tokens, may have happened meanwhile (the TV's
        // background jobs restore too), and putting older tokens back would spend a refresh token twice
        // and sign the device out. Only an empty seat is filled.
        if (session.session == null) session.session = saved
        true
    }

    override suspend fun logout() {
        endSession()
        // Wipe continue-watching positions so a different account on this device does not resume into
        // the previous account's history.
        progress.clearAll()
        expiredUserId = null
    }

    // The account whose session the server ended, which did not ask to leave: its resume points are
    // kept for it to find on signing in again, and wiped should another account sign in instead.
    private var expiredUserId: String? = null

    // The session died of itself (its refresh was refused): signed out, without what [logout] wipes.
    private suspend fun expire() {
        expiredUserId = session.session?.userId?.takeIf { it.isNotEmpty() } ?: expiredUserId
        endSession()
    }

    private suspend fun endSession() {
        refreshMutex.withLock {
            session.session = null
            store.clear()
        }
        // Drop the cached channel data so the next account does not see the previous one's channels.
        dropAccountCaches()
    }

    override fun currentSession(): UserSession? = session.session

    override suspend fun liveChannels(hours: Int): List<LiveChannel> = withRefresh { api.deviceEpg(hours * 60).toLiveChannels() }

    override suspend fun homeRecommendations(): List<HomeProgram> = withRefresh { api.homeRecommendations().toHomePrograms() }

    override suspend fun continueWatching(): List<HomeProgram> = withRefresh { api.continueWatching().toHomePrograms() }

    override suspend fun profiles(): Profiles = withRefresh { api.profiles().toProfiles() }

    override suspend fun remoteKey(): ByteArray? {
        // A sign-in under way has a session before it has its user.
        val userId = session.session?.userId?.takeIf { it.isNotEmpty() } ?: return null
        return RemoteProtocol.accountKey(userId, profiles().profiles.map { it.id })
    }

    override suspend fun profilesToPick(): Profiles? {
        val list = profiles()
        val current = session.session ?: return null
        val ids = list.profiles.map { it.id }
        if (ids.isEmpty()) return null
        if (ids.size == 1) {
            // Nothing to choose: just make sure the one profile is the one in use.
            if (current.profileId != ids[0]) selectProfile(ids[0])
            return null
        }
        val pick = store.readPick()
        val stale = current.profileId !in ids || pick == null || pick.ids != ids.joinToString(",") ||
            clock() - pick.atMs > PROFILE_PICK_TTL_MS
        return list.takeIf { stale }
    }

    override suspend fun selectProfile(id: String) {
        if (session.session == null) return
        val ids = runCatching { profiles().profiles.map { it.id } }.getOrDefault(emptyList())
        // Read the session only now: the call above may have renewed the tokens, which an earlier
        // copy would write back over.
        val current = session.session ?: return
        current.copy(profileId = id).let {
            session.session = it
            store.save(it)
        }
        store.savePick(ProfilePick(clock(), ids.joinToString(",")))
        dropAccountCaches()
    }

    override suspend fun createProfile(name: String, avatarId: Int?): Profile = withRefresh {
        val created = api.createProfile(ProfileRequest(name, avatarId)).profile?.toProfile()
            ?: throw AppError.Unknown("Profil non créé")
        // The profile exists: failing to note the new list must not report the creation as failed,
        // or a retry would create it twice. The next start then just asks who is watching.
        try {
            rememberProfileIds()
        } catch (e: AppError) {
        }
        created
    }

    override suspend fun updateProfile(id: String, name: String, avatarId: Int?) = withRefresh {
        api.updateProfile(id, ProfileRequest(name, avatarId))
        Unit
    }

    override suspend fun deleteProfile(id: String) {
        withRefresh {
            val response = api.deleteProfile(id)
            if (!response.isSuccessful) throw HttpException(response)
        }
        // The profile is gone; what follows only keeps the choice in line, and the next start asks who
        // is watching if it fails.
        try {
            val next = profiles().profiles.firstOrNull()
            if (session.session?.profileId == id && next != null) selectProfile(next.id) else rememberProfileIds()
        } catch (e: AppError) {
        }
    }

    // The profiles changed from this device: keep the current choice instead of asking again.
    private suspend fun rememberProfileIds() {
        val pick = store.readPick() ?: return
        val ids = profiles().profiles.joinToString(",") { it.id }
        store.savePick(pick.copy(ids = ids))
    }

    // Pages that depend on the profile (favourite channels) must not be served from the cache.
    private fun dropAccountCaches() {
        channelsPageCache = null
        channelDirectory = null
    }

    override suspend fun loadHome(): ContentPage = withRefresh {
        val page = api.homePage().toPage()
        // The backend's home already has its "Vos enregistrements" row; add the broadcaster apps.
        val apps = appsRail() ?: return@withRefresh page
        page.copy(rails = listOf(apps) + page.rails)
    }

    override suspend fun loadPage(url: String): ContentPage = withRefresh {
        // "Tout voir" on a recordings row points at the my-stuff landing page, which carries no
        // sections of its own; its recordings tab is the recordings page.
        if (url.contains("selectedTab=recordings")) {
            api.pageByUrl(RECORDINGS_PATH).toPage()
        } else {
            labelChannels(api.pageByUrl(url).toPage())
        }
    }

    // The channels page, fetched once and cached; it carries both the channel directory and the
    // broadcaster "Apps" section surfaced on the home page.
    private var channelsPageCache: PageResponse? = null
    private var channelsPageAt = 0L
    private suspend fun channelsPage(): PageResponse? {
        // Not for the life of the app, which a TV keeps for days: an application subscribed to since
        // would stay locked there. A clock set back makes the age meaningless: asked again.
        val cached = channelsPageCache
        if (cached != null && clock() - channelsPageAt in 0 until CHANNELS_PAGE_MAX_AGE_MS) return cached
        val asker = session.session
        val fresh = runCatching { api.channelsPage() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            // The one in hand, however old, when the new one could not be had.
            ?: return cached
        // Not kept for the account that signed in while it was on its way.
        if (session.session?.userId == asker?.userId && session.session?.profileId == asker?.profileId) {
            channelsPageCache = fresh
            channelsPageAt = clock()
        }
        return fresh
    }

    // Cached channel id -> name directory, loaded lazily the first time a page carries live cards.
    private var channelDirectory: Map<String, String>? = null

    private suspend fun channelNames(): Map<String, String> {
        channelDirectory?.let { return it }
        val map = channelsPage()?.toChannelDirectory().orEmpty()
        if (map.isNotEmpty()) channelDirectory = map
        return map
    }

    /** The broadcaster "Apps" rail (france.tv, TF1+, ...) shown on the home page. */
    private suspend fun appsRail(): ContentRail? = channelsPage()?.toAppsRail()

    /** Labels each live card (one with a channel id but no subtitle) with its channel name. */
    private suspend fun labelChannels(page: ContentPage): ContentPage {
        val needed = page.rails.any { rail -> rail.cards.any { it.liveChannelId != null && it.subtitle == null } }
        if (!needed) return page
        val names = channelNames()
        if (names.isEmpty()) return page
        return page.copy(
            rails = page.rails.map { rail ->
                rail.copy(
                    cards = rail.cards.map { card ->
                        val name = card.liveChannelId?.let { names[it] }
                        if (name != null && card.subtitle == null) card.copy(subtitle = name) else card
                    },
                )
            },
        )
    }

    override suspend fun loadGuide(): ContentPage = withRefresh {
        val now = System.currentTimeMillis()
        val start = rfc3339Utc(now)
        val end = rfc3339Utc(now + GUIDE_WINDOW_MS)
        val entries = ArrayList<EpgEntryDto>()
        var offset = 0
        for (page in 1..GUIDE_MAX_PAGES) {
            val response = api.epg(startTime = start, endTime = end, limit = GUIDE_PAGE_SIZE, offset = offset.takeIf { it > 0 })
            entries += response.response.orEmpty()
            offset = response.nextOffset?.takeIf { it > offset } ?: break
        }
        // A day of every channel, with two dates to read for each programme: off the caller's thread.
        withContext(Dispatchers.Default) { EpgResponse(entries).toGuidePage() }
    }

    override suspend fun search(query: String): ContentPage = withRefresh { api.search(query).toPage().groupByTitle() }

    /**
     * Search returns the same show several times (one per channel); keep one card per title, in the
     * place of its first copy, preferring a copy the subscription includes.
     */
    private fun ContentPage.groupByTitle(): ContentPage = copy(
        rails = rails.map { rail ->
            // An untitled card is keyed by its position, so it is never merged.
            val cards = rail.cards.withIndex()
                .groupBy { (i, card) -> card.title?.trim()?.lowercase() ?: i }
                .values.map { copies -> (copies.firstOrNull { !it.value.isLocked } ?: copies.first()).value }
            rail.copy(title = rail.title?.let { recount(it, rail.cards.size, cards.size) }, cards = cards)
        },
    )

    // A result rail is titled with its count ("Programmes (8)"): follow the cards kept. A count that
    // is not the number of cards received (a larger total) is left as it is.
    private fun recount(title: String, received: Int, kept: Int): String {
        val count = RAIL_COUNT.find(title)?.groups?.get(1) ?: return title
        if (count.value.toIntOrNull() != received) return title
        return title.replaceRange(count.range, kept.toString())
    }

    override suspend fun fetchProgramDetail(id: String, kind: DetailKind): ProgramDetail = withRefresh {
        var episodes = emptyList<ContentCard>()
        // The show an airing belongs to, whose recordings folder lists the recorded episodes.
        var seriesId: String? = null
        val detail = when (kind) {
            DetailKind.PROGRAM -> coroutineScope {
                // An airing (LIVE_) may be a recording, which has no catch-up stream: only the page's
                // default tab says so, through its player. Best-effort, beside the about tab.
                val recording = if (id.startsWith("LIVE_")) async { recordingOf(id) } else null
                // A show opens on one episode/airing; follow its "Détails du programme" series link to
                // list the series' available episodes so the viewer can pick another.
                val resp = api.programDetail(id)
                seriesId = resp.seriesLink()
                episodes = seriesId?.let { seriesEpisodes(it) }.orEmpty()
                val detail = resp.toProgramDetail(channelId = null, vodId = id, isLive = false)
                detail.copy(recordingAssetId = detail.recordingAssetId ?: recording?.await())
            }
            DetailKind.SERIES -> {
                val resp = api.seriesDetail(id)
                // Series with catch-up expose their episodes on a separate "Regarder maintenant" tab.
                if (resp.hasWatchNowTab()) episodes = api.seriesDetail(id, "id-tab-watch-now").toEpisodes()
                resp.toProgramDetail(channelId = null, vodId = id, isLive = false)
            }
            DetailKind.CHANNEL -> {
                val resp = api.channelDetail(id)
                episodes = resp.seriesLink()?.let { seriesEpisodes(it) }.orEmpty()
                // The default (about) tab carries the live header; the "see also" tab carries the channel's
                // replay / most-viewed / live-and-upcoming carousels. Best-effort and title-filtered (the
                // tab bar is a title-less section), with the see-all dropped (it would navigate out of the
                // open detail). Empty on failure, leaving the plain live detail.
                val sections = try {
                    api.channelDetail(id, "id-tab-see-also").toRails()
                        .filter { it.title != null }
                        .map { it.copy(seeAllUrl = null) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                resp.toProgramDetail(channelId = id, vodId = null, isLive = true).copy(sections = sections)
            }
        }
        val detailProgramId = detail.programId
        val matches = when (kind) {
            DetailKind.PROGRAM -> {
                val show = seriesId
                if (detailProgramId == null || show == null) emptyList() else recordingsOf(show).filter { it.programId == detailProgramId }
            }
            DetailKind.SERIES -> recordingsOf(id)
            DetailKind.CHANNEL -> emptyList()
        }
        // When the show has no real poster, fall back to a recording's own image (a real thumbnail).
        detail.copy(
            recordings = matches,
            // Drop episodes the user has already recorded: a recorded episode's card carries the
            // recording's LIVE_ asset id as its vodId, and its catch-up VOD returns a 5xx for recorded
            // content. They stay in "Vos enregistrements" below, which plays them from the DVR.
            episodes = episodes.filterNot { ep -> ep.vodId != null && matches.any { it.assetId == ep.vodId } },
            posterUrl = detail.posterUrl ?: matches.firstOrNull()?.imageUrl,
            isSeries = kind == DetailKind.SERIES,
        )
    }

    // The recording an airing's page plays, or null when it is none or the page cannot be had.
    private suspend fun recordingOf(id: String): String? = try {
        api.programDetail(id, tab = null).recordingAssetId()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * The catch-up episodes of a series (its "Regarder maintenant" tab), or empty if it has none. The
     * whole lookup is best-effort: it must never fail the detail open, since the episode list is only a
     * supplement to the show's own page.
     */
    private suspend fun seriesEpisodes(seriesId: String): List<ContentCard> = try {
        val series = api.seriesDetail(seriesId)
        if (series.hasWatchNowTab()) api.seriesDetail(seriesId, "id-tab-watch-now").toEpisodes() else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun record(action: RecordAction) = post(action.url, action.payload)

    override suspend fun toggleFavorite(action: RecordAction) = post(action.url, action.payload)

    override suspend fun favoriteOrder(): FavoriteOrder = withRefresh {
        api.favoriteChannels().toFavoriteOrder() ?: throw AppError.Unknown("Favoris indisponibles")
    }

    override suspend fun saveFavoriteOrder(save: RecordAction, channelIds: List<String>) =
        post(save.url, favoriteOrderPayload(save, channelIds))

    // Replays a backend api_call (url + payload) verbatim; coerce the JSON numbers Moshi decoded to
    // Double back to whole numbers so the body matches what the backend sent. The response body is
    // unused, but a raw ResponseBody must be closed or the connection leaks.
    private suspend fun post(url: String, payload: Map<String, Any?>) = withRefresh {
        @Suppress("UNCHECKED_CAST")
        val body = coerceWholeNumbers(payload) as Map<String, Any?>
        api.postAction(url, body).close()
    }

    /**
     * The recordings of a show, from its folder in "Mes vidéos". They only supplement the show's page,
     * so any failure (a show never recorded has no folder) reads as none.
     */
    private suspend fun recordingsOf(seriesId: String): List<Recording> = try {
        api.recordingsFolder(seriesId).toRecordings(seriesId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun resolveRecording(assetId: String): PlaybackSource = withRefresh {
        // A DVR recording is seekable VOD-like content, so remember and resume its position like VOD.
        api.playbackAsset(id = assetId, type = "dvr").toPlaybackSource(ownHost)
            .copy(resumeKey = assetId, startPositionMs = resumePosition(assetId), originRecordingAssetId = assetId)
    }

    override suspend fun resolveLiveChannel(channelId: String): PlaybackSource = withRefresh {
        coroutineScope {
            val onAir = async { onAirWindow(channelId) }
            val resp = api.playbackAsset(channelId = channelId, type = "live")
            resp.toPlaybackSource(ownHost).copy(
                originChannelId = channelId,
                programWindow = onAir.await() ?: resp.accessRightsV2?.live?.toProgramWindow(),
            )
        }
    }

    override suspend fun liveProgramWindow(channelId: String, atMs: Long?): ProgramWindow? = withRefresh {
        // What was on at another time is the guide's alone to say: the stream's own window is that of
        // what is on now.
        // Asked on its own all through a long watch, with nothing else to notice a session run out.
        if (atMs != null) return@withRefresh onAirWindow(channelId, atMs, renew = true)
        onAirWindow(channelId) ?: api.playbackAsset(channelId = channelId, type = "live").accessRightsV2?.live?.toProgramWindow()
    }

    /**
     * The air-window of the programme on [channelId] now, or at [at], as the guide has it, which is where
     * "depuis le début" must start: the stream's own window starts with the planned slot, up to a quarter of an hour
     * before the programme does. Best-effort: null when the guide cannot be read or is slow to answer,
     * leaving that window, as a channel must not wait for it to start.
     */
    private suspend fun onAirWindow(channelId: String, at: Long = System.currentTimeMillis(), renew: Boolean = false): ProgramWindow? = try {
        val now = at
        withTimeoutOrNull(ON_AIR_WAIT_MS) {
            api.epg(startTime = rfc3339Utc(now), endTime = rfc3339Utc(now + ON_AIR_SPAN_MS), limit = 1, stationId = channelId)
        }?.onAirWindow(channelId, now)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // With [renew], a session refused is the caller's to renew (see withRefresh).
        if (renew && e is HttpException && e.code() == 401) throw e
        null
    }

    override suspend fun resolveVod(vodId: String): PlaybackSource = withRefresh {
        api.playbackAsset(id = vodId, type = "vod").toPlaybackSource(ownHost)
            .copy(resumeKey = vodId, startPositionMs = resumePosition(vodId), originVodId = vodId)
    }

    // Resume points are each profile's own: a programme one profile left must not resume there for
    // another of the account. Null signed out.
    private fun profileKey(key: String): String? = session.session?.let { "${it.profileId}/$key" }

    // Those saved before they were per profile, under the bare key, still count until they are replaced.
    private suspend fun resumePosition(key: String): Long =
        profileKey(key)?.let { progress.read(it) }?.takeIf { it > 0 } ?: progress.read(key)

    override suspend fun savePlaybackPosition(key: String, positionMs: Long) {
        // Signed out, as when the session died under a programme, there is no one to save for: the
        // store was just wiped for the next account.
        val own = profileKey(key) ?: return
        progress.clear(key)
        if (positionMs > 0) progress.save(own, positionMs) else progress.clear(own)
    }

    override suspend fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long) {
        val url = source.progressUrl ?: return
        val payload = source.progressPayload ?: return
        // Mirror the local resume policy (PlaybackProgress.positionToSave) so the server and the local
        // store agree: skip a barely-started item or one with an unknown duration. Near the end, or in
        // the credits once the next episode is announced, mark it watched at its full duration, like the
        // official app; otherwise report the position.
        if (durationMs <= 0 || positionMs < PROGRESS_MIN_MS) return
        val watched = positionMs >= durationMs - PROGRESS_END_MS || source.upNext?.let { positionMs >= it.atSec * 1000 } == true
        val offsetSeconds = (if (watched) durationMs else positionMs) / 1000
        if (offsetSeconds <= 0) return // the server rejects lastOffset <= 0
        // Overwrite the offset; restore the other template values to integers (Moshi decoded them as Double).
        val body = payload.mapValues { (key, value) ->
            if (key == PROGRESS_OFFSET_KEY) offsetSeconds else coerceWholeNumbers(value)
        }
        // Best-effort: refresh on a 401 like every other call, and never let a transport error reach
        // the caller; a failed continue-watching ping must not disrupt playback.
        runCatching { withRefresh { api.pingProgress(url, body).close() } }
            .onFailure { if (it is CancellationException) throw it }
    }

    private val refreshMutex = Mutex()

    /**
     * Refreshes the access token, single-flighted: concurrent 401s coalesce into one /refresh so the
     * rotating refresh token is not spent twice. [usedAccessToken] is the token whose request 401'd;
     * if another coroutine already refreshed it, this returns without a second network call.
     */
    private suspend fun refreshTokens(usedAccessToken: String?) = refreshMutex.withLock {
        val current = session.session ?: throw AppError.Unauthorized
        if (usedAccessToken != null && current.accessToken != usedAccessToken) return@withLock
        val refreshToken = current.refreshToken ?: throw AppError.Unauthorized
        // Seen through even when the caller is cancelled, as when a page still loading is left: the
        // server may already have replaced the refresh token, and its answer dropped, or kept in memory
        // only, would be the session lost.
        withContext(NonCancellable) {
            val tokens = api.refresh("Bearer $refreshToken").tokens
            val newAccess = tokens.accessToken ?: throw AppError.Unauthorized
            // Apply the tokens to the session as it is now: a profile chosen during the call stays chosen.
            val latest = session.session ?: throw AppError.Unauthorized
            val updated = latest.copy(accessToken = newAccess, refreshToken = tokens.refreshToken ?: refreshToken)
            session.session = updated
            // The tokens are good and in use whatever becomes of their saving: a store that fails for
            // a moment (the Keystore does) must not fail the call that waited for them with its own
            // words. The next renewal saves again.
            try {
                store.save(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    /** Runs an authenticated call, refreshing the token once on a 401, and mapping errors for the UI. */
    private suspend fun <T> withRefresh(block: suspend () -> T): T = translateErrors {
        // Snapshot the access token this call carries BEFORE running it: if a concurrent 401 refreshes
        // the session while we are suspended in block(), reading the token afterwards would capture the
        // already-rotated one, defeat refreshTokens' single-flight guard, and spend the refresh token a
        // second time.
        val usedToken = session.session?.accessToken
        try {
            block()
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            // The pages name some urls themselves, and one outside the app's own hosts is not sent the
            // session: its 401 says nothing about it. Nor does one reached through a redirect to or
            // from such a host: asked there first, the request went on without the session, and sent
            // on to there, it lost it on the way.
            var hop = e.response()?.raw()
            while (hop != null) {
                if (!ownHost(hop.request.url.host)) throw e
                hop = hop.priorResponse
            }
            // Nobody is signed in here, as when the saved session could not be read just now: the 401
            // says no more than that, and what is saved is not to be cleared over it.
            if (usedToken == null) throw AppError.Unauthorized
            try {
                refreshTokens(usedToken)
            } catch (refreshError: Exception) {
                // Only a genuine auth rejection means the session is dead. A transient network/5xx
                // failure during refresh must leave the saved session intact (do not log out offline).
                if (refreshError.isAuthFailure()) {
                    expire()
                    throw AppError.Unauthorized
                }
                throw refreshError
            }
            // The retry runs with the fresh token: a 401 now means the session is truly dead, so log
            // out. A 403 is a forbidden/not-entitled/geo error on a valid session, so let it surface as
            // an in-place banner like any other 403 rather than ejecting to login.
            try {
                block()
            } catch (retryError: HttpException) {
                if (retryError.code() == 401) {
                    expire()
                    throw AppError.Unauthorized
                }
                throw retryError
            }
        }
    }

    // Only a 401 means the refresh token itself is dead -> log out. A 403 is forbidden/geo on a token the
    // server still recognizes, so it must NOT wipe the session (it surfaces like the retry-path 403).
    private fun Throwable.isAuthFailure(): Boolean =
        this is AppError.Unauthorized || (this is HttpException && code() == 401)

    /** Turns raw transport failures into friendly domain errors; passes [AppError]s through. */
    private suspend fun <T> translateErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: AppError) {
        throw e
    } catch (e: HttpException) {
        throw e.toAppError()
    } catch (e: IOException) {
        throw AppError.Network("Pas de connexion, vérifiez votre réseau")
    } catch (e: JsonDataException) {
        // An answer not shaped as expected: the parser's own words ("Expected BEGIN_OBJECT but was
        // STRING at path $.content...") are nothing to show.
        throw AppError.Unspecified
    }
}

/** A backend error code with its human message, e.g. INVALID_USERNAME_PASSWORD. */
private data class BackendError(val code: String?, val message: String?)

/** Decodes `{"error":{...}}` as a plain map, so no Kotlin reflection adapter is needed here. */
private val errorBodyAdapter by lazy {
    Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )
}

/**
 * The backend error carried by a failed response, or null when it sent none. `lc_message` is the
 * message localised to x-preferred-language and is preferred over the English `message`.
 */
private fun HttpException.backendError(): BackendError? = runCatching {
    val body = response()?.errorBody()?.string().orEmpty()
    val error = if (body.isBlank()) null else errorBodyAdapter.fromJson(body)?.get("error") as? Map<*, *>
    error?.let {
        BackendError(it["code"] as? String, it["lc_message"] as? String ?: it["message"] as? String)
    }
}.getOrNull()

/** Shown when the backend names a wrong password, and whenever it offers no message of its own. */
private const val WRONG_CREDENTIALS = "Email ou mot de passe incorrect"

/**
 * The message for a rejected sign-in. The backend answers 401 for several unrelated states and only
 * INVALID_USERNAME_PASSWORD means a wrong password, so the states it is worth naming get their own
 * text and anything unrecognised falls back to the backend's own message rather than blaming the
 * password.
 */
private fun signinMessage(error: BackendError?): String = when (error?.code) {
    "MFA_TOKEN_EXPIRED" -> "Délai de validation dépassé, reconnectez-vous"
    "FORGOT_CREDENTIALS" -> "Réinitialisation de mot de passe en cours, terminez-la puis réessayez"
    "INVALID_USERNAME_PASSWORD" -> WRONG_CREDENTIALS
    else -> error?.message ?: WRONG_CREDENTIALS
}

private const val MFA_UNAVAILABLE = "Validation en deux étapes indisponible, réessayez"

/** The message for a code the backend refused (two-factor sign-in). */
private fun codeMessage(error: BackendError?): String = when (error?.code) {
    "INVALID_CODE" -> "Code incorrect"
    "EXPIRED_CODE" -> "Code expiré, demandez-en un nouveau"
    else -> error?.message ?: "Code refusé"
}

/**
 * The error for a 451. The backend uses the one status for three different blocks, so only its code
 * says which: the country, a VPN in the way, or a market the subscription does not cover.
 */
private fun blockedError(error: BackendError?): AppError = when (error?.code) {
    "VPN_NETWORK" -> AppError.Unknown("Désactivez votre VPN pour accéder au service")
    "NO_SERVICE_FOR_SUBSCRIPTION" -> AppError.NotEntitled
    else -> AppError.GeoBlocked
}

private fun HttpException.toAppError(): AppError = when (code()) {
    401 -> AppError.Unauthorized
    // Forbidden on a session the server still recognizes (entitlement); not an expired session.
    403 -> AppError.Forbidden
    404 -> AppError.Unknown("Contenu introuvable")
    451 -> blockedError(backendError())
    in 500..599 -> AppError.Network("Service indisponible, réessayez")
    else -> AppError.Unspecified
}

/** The programme air-window in epoch ms, preferring the ms form; null unless both bounds are sane. */
internal fun AccessRightsV2WindowDto.toProgramWindow(): ProgramWindow? {
    val start = startTimeMs ?: rfc3339UtcToEpochMillis(startTime)
    val end = endTimeMs ?: rfc3339UtcToEpochMillis(endTime)
    return if (start != null && end != null && end > start) ProgramWindow(start, end) else null
}

internal fun PlaybackResponse.toPlaybackSource(ownHost: (String) -> Boolean = String::isTrustedApiHost): PlaybackSource {
    val manifest = stream?.url ?: throw AppError.Unknown("Playback response has no stream url")
    val licenseUrl = drmV2?.license?.url ?: drm?.licenseUrl
    val drmSpec = if (stream.drmProtected == true && licenseUrl != null) {
        val headers = drmV2?.license?.headers ?: drm?.licenseUrlHeaders ?: emptyMap()
        DrmSpec.Widevine(licenseUrl, headers)
    } else {
        DrmSpec.None
    }
    return PlaybackSource(
        manifestUrl = manifest,
        drm = drmSpec,
        // A DVR recording plays from the start like VOD, never the live edge, even though its manifest
        // can report live=true; without this a cast clamps it to the live-edge position and fails.
        isLive = type != "dvr" && (stream.live ?: (type == "live")),
        // The show's name: on an episode the title is the episode's own.
        title = program?.heading ?: program?.title,
        heartbeatUrl = heartbeat?.url,
        concurrencyHeartbeatUrl = concurrency?.heartbeatUrl,
        // Only on the service's own hosts: it is posted to with the account's token, by this app and, on
        // a cast, by the receiver, which is handed that token along with it.
        progressUrl = playhead?.url?.takeIf { url -> url.toHttpUrlOrNull()?.host?.let(ownHost) == true },
        progressPayload = playhead?.payload,
    )
}
