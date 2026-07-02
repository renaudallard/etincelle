// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.model.PairingCode
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.model.coerceWholeNumbers
import it.allard.etincelle.core.network.NetworkClient
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
// See-all target for the injected recordings rail; loadPage routes any selectedTab=recordings url to
// the genre-grouped recordings page.
private const val RECORDINGS_PAGE_URL = "etincelle:recordings?selectedTab=recordings"

/** Fubo implementation of auth + content + playback, with persisted sessions and token refresh. */
class FuboRepository(
    private val api: FuboApi,
    private val session: SessionManager,
    private val store: SessionStore,
    private val progress: ProgressStore,
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

    /** Signs in, loads the user/profile id, and persists the session. */
    override suspend fun login(email: String, password: String): UserSession {
        val tokens = try {
            api.signin(SigninRequest(email, password))
        } catch (e: HttpException) {
            // Only the sign-in call reflects the credentials: a 401 is a wrong email/password and a 403
            // is a region block (the backend is geo-gated), not an expired session.
            throw when (e.code()) {
                400, 401 -> AppError.Unknown("Email ou mot de passe incorrect")
                403 -> AppError.GeoBlocked
                else -> e.toAppError()
            }
        } catch (e: IOException) {
            throw AppError.Network("Pas de connexion, vérifiez votre réseau")
        }
        val accessToken = tokens.accessToken ?: throw AppError.Unknown("Email ou mot de passe incorrect")
        // The user/profile load runs after credentials are accepted, so its errors are not credential
        // failures: map them normally (a 403 here is Forbidden, a 401 an expired token).
        return translateErrors { finishLogin(accessToken, tokens.refreshToken) }
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
        val tokens = api.pollSignInCode(deviceId, SignInCodePollRequest(code)).data ?: return null
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
        channelsPageCache = null
        channelDirectory = null
        recordingGenres.clear()
    }

    override fun currentSession(): UserSession? = session.session

    override suspend fun loadHome(): ContentPage = withRefresh {
        val page = api.homePage().toPage()
        val apps = appsRail()
        val recordings = loadRecordings()
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

    /** Search returns the same show several times (one per channel); keep one card per title. */
    private fun ContentPage.groupByTitle(): ContentPage = copy(
        rails = rails.map { rail ->
            val seen = HashSet<String>()
            rail.copy(cards = rail.cards.filter { card -> card.title?.trim()?.lowercase()?.let(seen::add) ?: true })
        },
    )

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
                if (detailProgramId == null) emptyList() else loadRecordings().filter { it.programId == detailProgramId }
            DetailKind.SERIES -> loadRecordings().filter { it.seriesId == id }
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

    override suspend fun record(action: RecordAction) = withRefresh {
        // Replay the backend's own record api_call (url + payload) verbatim; coerce the JSON numbers
        // Moshi decoded to Double back to whole numbers so the body matches what the backend sent. The
        // response body is unused, but a raw ResponseBody must be closed or the connection leaks.
        @Suppress("UNCHECKED_CAST")
        val body = coerceWholeNumbers(action.payload) as Map<String, Any?>
        api.postAction(action.url, body).close()
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
        val tokens = api.refresh("Bearer $refreshToken")
        val newAccess = tokens.accessToken ?: throw AppError.Unauthorized
        val updated = current.copy(accessToken = newAccess, refreshToken = tokens.refreshToken ?: refreshToken)
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

private fun HttpException.toAppError(): AppError = when (code()) {
    401 -> AppError.Unauthorized
    // Forbidden on a session the server still recognizes (geo/entitlement); not an expired session.
    403 -> AppError.Forbidden
    404 -> AppError.Unknown("Contenu introuvable")
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
