// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException
import java.io.IOException

// A full day of guide so a channel's "tout voir" grid covers at least 24h.
private const val GUIDE_WINDOW_MS = 24L * 60 * 60 * 1000
private const val GUIDE_CHANNEL_LIMIT = 40
private const val PROGRESS_OFFSET_KEY = "lastOffset"
private const val PROGRESS_END_MS = 15_000L
private const val PROGRESS_MIN_MS = 10_000L
// The official app asks who is watching again once the last choice is two days old.
private const val PROFILE_PICK_TTL_MS = 2L * 24 * 60 * 60 * 1000
// See-all target for the injected recordings rail; loadPage routes any selectedTab=recordings url to
// the genre-grouped recordings page.
private const val RECORDINGS_PAGE_URL = "etincelle:recordings?selectedTab=recordings"

// The count closing a search result rail's title, as in "Programmes (8)".
private val RAIL_COUNT = Regex("""\((\d+)\)\s*$""")

/** Fubo implementation of auth + content + playback, with persisted sessions and token refresh. */
class FuboRepository(
    private val api: FuboApi,
    private val session: SessionManager,
    private val store: SessionStore,
    private val progress: ProgressStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : MolotovRepository {
    constructor(network: NetworkClient, store: SessionStore, progress: ProgressStore) : this(
        network.retrofit.create(FuboApi::class.java),
        network.session,
        store,
        progress,
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
            val user = api.user().data ?: throw AppError.Unknown("Empty user response")
            val userId = user.id ?: throw AppError.Unknown("Missing user id")
            val profileId = user.profiles?.firstOrNull()?.id ?: throw AppError.Unknown("Missing profile")
            return UserSession(accessToken, refreshToken, userId, profileId).also {
                session.session = it
                store.save(it)
            }
        } catch (e: Exception) {
            session.session = previous
            throw e
        }
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
    private suspend fun <T> mfaCall(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        val error = e.backendError()
        throw if (error?.code == "MFA_TOKEN_EXPIRED") AppError.Unauthorized else AppError.Unknown(codeMessage(error))
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

    /** Loads a persisted session into memory; returns true if one was present. */
    override suspend fun restoreSession(): Boolean {
        val saved = store.read() ?: return false
        session.session = saved
        return true
    }

    override suspend fun logout() {
        session.session = null
        store.clear()
        // Wipe continue-watching positions so a different account on this device does not resume into
        // the previous account's history.
        progress.clearAll()
        // Drop the cached channel data so the next account does not see the previous one's channels.
        dropAccountCaches()
        recordingGenres.clear()
    }

    override fun currentSession(): UserSession? = session.session

    override suspend fun liveChannels(hours: Int): List<LiveChannel> = withRefresh { api.deviceEpg(hours * 60).toLiveChannels() }

    override suspend fun homeRecommendations(): List<HomeProgram> = withRefresh { api.homeRecommendations().toHomePrograms() }

    override suspend fun continueWatching(): List<HomeProgram> = withRefresh { api.continueWatching().toHomePrograms() }

    override suspend fun profiles(): Profiles = withRefresh { api.profiles().toProfiles() }

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
        rememberProfileIds()
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
        val next = profiles().profiles.firstOrNull()
        if (session.session?.profileId == id && next != null) selectProfile(next.id) else rememberProfileIds()
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
        val apps = appsRail()
        val recordings = recordingsOrEmpty()
        val extra = buildList {
            if (recordings.isNotEmpty()) {
                // One card per show (collapsed); "tout voir" opens the genre-grouped recordings page.
                val cards = recordings.collapsedByShow().map { (rec, count) -> rec.toCollapsedCard(count) }
                add(ContentRail("recordings", "Mes enregistrements", cards, seeAllUrl = RECORDINGS_PAGE_URL))
            }
            if (apps != null) add(apps)
        }
        if (extra.isEmpty()) page else page.copy(rails = extra + page.rails)
    }

    override suspend fun loadPage(url: String): ContentPage = withRefresh {
        // The "Vos enregistrements" rail's see-all points at the my-stuff page (selectedTab=recordings),
        // which carries no standard sections; serve the DVR list as a page instead.
        if (url.contains("selectedTab=recordings")) {
            recordingsPage()
        } else {
            labelChannels(api.pageByUrl(url).toPage())
        }
    }

    // The genre of each recorded show, fetched once from its detail page and cached for the session.
    private val recordingGenres = mutableMapOf<String, String?>()

    // Caps how many show-detail fetches run at once when the recordings page is built on a cold cache.
    private val genreFetchLimit = Semaphore(6)

    private suspend fun recordingGenre(rec: Recording): String? {
        val seriesId = rec.seriesId
        val key = seriesId ?: rec.programId ?: return null
        recordingGenres[key]?.let { return it }
        if (recordingGenres.containsKey(key)) return null
        // loadRecordings already refreshed the token, so a failure here is the show's own (e.g. a
        // missing detail); treat it as no genre rather than failing the whole page.
        val genre = genreFetchLimit.withPermit {
            try {
                if (seriesId != null) {
                    api.seriesDetail(seriesId).toProgramDetail(channelId = null, vodId = seriesId, isLive = false).genre
                } else {
                    api.programDetail(key).toProgramDetail(channelId = null, vodId = key, isLive = false).genre
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        recordingGenres[key] = genre
        return genre
    }

    // Recordings as a page separated by genre, each show collapsed to one card. Genres are fetched in
    // parallel (cached); a show with no known genre falls into "Autres", listed last.
    private suspend fun recordingsPage(): ContentPage = coroutineScope {
        val shows = loadRecordings().collapsedByShow()
        val withGenre = shows.map { (rec, count) ->
            async { Triple(rec, count, recordingGenre(rec)) }
        }.awaitAll()
        val sections = withGenre
            // Group on the nullable genre so a real backend genre named "Autres" is not merged with the
            // unknown-genre bucket; unknown (null) is labelled and sorted last only at render time.
            .groupBy { it.third }
            .entries
            .sortedWith(compareBy({ it.key == null }, { it.key?.lowercase() }))
            .map { (genre, items) ->
                ContentRail("rec-${genre ?: "__unknown"}", genre ?: "Autres", items.map { (rec, count, _) -> rec.toCollapsedCard(count) })
            }
        ContentPage("Vos enregistrements", sections, isGrid = true)
    }

    // The channels page, fetched once and cached; it carries both the channel directory and the
    // broadcaster "Apps" section surfaced on the home page.
    private var channelsPageCache: PageResponse? = null
    private suspend fun channelsPage(): PageResponse? {
        channelsPageCache?.let { return it }
        return runCatching { api.channelsPage() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.also { channelsPageCache = it }
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
        api.epg(
            startTime = rfc3339Utc(now),
            endTime = rfc3339Utc(now + GUIDE_WINDOW_MS),
            limit = GUIDE_CHANNEL_LIMIT,
        ).toGuidePage()
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
        val detail = when (kind) {
            DetailKind.PROGRAM -> {
                // A show opens on one episode/airing; follow its "Détails du programme" series link to
                // list the series' available episodes so the viewer can pick another.
                val resp = api.programDetail(id)
                episodes = resp.seriesLink()?.let { seriesEpisodes(it) }.orEmpty()
                resp.toProgramDetail(channelId = null, vodId = id, isLive = false)
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
                val sections = runCatching {
                    api.channelDetail(id, "id-tab-see-also").toRails()
                        .filter { it.title != null }
                        .map { it.copy(seeAllUrl = null) }
                }.getOrDefault(emptyList())
                resp.toProgramDetail(channelId = id, vodId = null, isLive = true).copy(sections = sections)
            }
        }
        val detailProgramId = detail.programId
        val matches = when (kind) {
            DetailKind.PROGRAM ->
                if (detailProgramId == null) emptyList() else recordingsOrEmpty().filter { it.programId == detailProgramId }
            DetailKind.SERIES -> recordingsOrEmpty().filter { it.seriesId == id }
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

    /**
     * The catch-up episodes of a series (its "Regarder maintenant" tab), or empty if it has none. The
     * whole lookup is best-effort: it must never fail the detail open, since the episode list is only a
     * supplement to the show's own page.
     */
    private suspend fun seriesEpisodes(seriesId: String): List<ContentCard> = runCatching {
        val series = api.seriesDetail(seriesId)
        if (series.hasWatchNowTab()) api.seriesDetail(seriesId, "id-tab-watch-now").toEpisodes() else emptyList()
    }.getOrDefault(emptyList())

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
     * The recordings for a page that only shows them next to its own content (home, details). An account
     * without the recording option gets a 404 from dvr/v2/list, which must not take the whole page down,
     * so any failure but a dead session reads as no recordings.
     */
    private suspend fun recordingsOrEmpty(): List<Recording> = try {
        loadRecordings()
    } catch (e: AppError) {
        if (e is AppError.Unauthorized) throw e
        emptyList()
    }

    override suspend fun loadRecordings(): List<Recording> = withRefresh {
        // status=all returns an empty body, so fetch both statuses and merge, deduped by asset id.
        val recorded = api.dvrList(status = "recorded").toRecordings()
        val scheduled = api.dvrList(status = "scheduled").toRecordings()
        (recorded + scheduled).distinctBy { it.assetId }
    }

    override suspend fun resolveRecording(assetId: String): PlaybackSource = withRefresh {
        // A DVR recording is seekable VOD-like content, so remember and resume its position like VOD.
        api.playbackAsset(id = assetId, type = "dvr").toPlaybackSource()
            .copy(resumeKey = assetId, startPositionMs = progress.read(assetId), originRecordingAssetId = assetId)
    }

    override suspend fun resolveLiveChannel(channelId: String): PlaybackSource = withRefresh {
        val resp = api.playbackAsset(channelId = channelId, type = "live")
        resp.toPlaybackSource().copy(
            originChannelId = channelId,
            programWindow = resp.accessRightsV2?.live?.toProgramWindow(),
        )
    }

    override suspend fun liveProgramWindow(channelId: String): ProgramWindow? = withRefresh {
        api.playbackAsset(channelId = channelId, type = "live").accessRightsV2?.live?.toProgramWindow()
    }

    override suspend fun resolveVod(vodId: String): PlaybackSource = withRefresh {
        api.playbackAsset(id = vodId, type = "vod").toPlaybackSource()
            .copy(resumeKey = vodId, startPositionMs = progress.read(vodId), originVodId = vodId)
    }

    override suspend fun savePlaybackPosition(key: String, positionMs: Long) {
        if (positionMs > 0) progress.save(key, positionMs) else progress.clear(key)
    }

    override suspend fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long) {
        val url = source.progressUrl ?: return
        val payload = source.progressPayload ?: return
        // Mirror the local resume policy (PlaybackProgress.positionToSave) so the server and the local
        // store agree: skip a barely-started item or one with an unknown duration. Near the end, mark
        // it watched at its full duration, like the official app; otherwise report the position.
        if (durationMs <= 0 || positionMs < PROGRESS_MIN_MS) return
        val nearEnd = positionMs >= durationMs - PROGRESS_END_MS
        val offsetSeconds = (if (nearEnd) durationMs else positionMs) / 1000
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
        val tokens = api.refresh("Bearer $refreshToken").tokens
        val newAccess = tokens.accessToken ?: throw AppError.Unauthorized
        // Apply the tokens to the session as it is now: a profile chosen during the call stays chosen.
        val latest = session.session ?: throw AppError.Unauthorized
        val updated = latest.copy(accessToken = newAccess, refreshToken = tokens.refreshToken ?: refreshToken)
        session.session = updated
        store.save(updated)
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
            try {
                refreshTokens(usedToken)
            } catch (refreshError: Exception) {
                // Only a genuine auth rejection means the session is dead. A transient network/5xx
                // failure during refresh must leave the saved session intact (do not log out offline).
                if (refreshError.isAuthFailure()) {
                    logout()
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
                    logout()
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
    else -> AppError.Unknown("Chargement impossible, réessayez")
}

/** The programme air-window in epoch ms, preferring the ms form; null unless both bounds are sane. */
internal fun AccessRightsV2WindowDto.toProgramWindow(): ProgramWindow? {
    val start = startTimeMs ?: rfc3339UtcToEpochMillis(startTime)
    val end = endTimeMs ?: rfc3339UtcToEpochMillis(endTime)
    return if (start != null && end != null && end > start) ProgramWindow(start, end) else null
}

internal fun PlaybackResponse.toPlaybackSource(): PlaybackSource {
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
        title = program?.title,
        heartbeatUrl = heartbeat?.url,
        concurrencyHeartbeatUrl = concurrency?.heartbeatUrl,
        progressUrl = playhead?.url,
        progressPayload = playhead?.payload,
    )
}
