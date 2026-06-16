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
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.network.NetworkClient
import it.allard.etincelle.core.network.ProgressStore
import it.allard.etincelle.core.network.SessionManager
import it.allard.etincelle.core.network.SessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

// A full day of guide so a channel's "tout voir" grid covers at least 24h.
private const val GUIDE_WINDOW_MS = 24L * 60 * 60 * 1000
private const val GUIDE_CHANNEL_LIMIT = 40

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

    /** Signs in, loads the user/profile id, and persists the session. */
    override suspend fun login(email: String, password: String): UserSession {
        val tokens = api.signin(SigninRequest(email, password))
        val accessToken = tokens.accessToken ?: throw AppError.Unauthorized
        session.session = UserSession(accessToken, tokens.refreshToken, userId = "", profileId = "")

        val user = api.user().data ?: throw AppError.Unknown("Empty user response")
        val userId = user.id ?: throw AppError.Unknown("Missing user id")
        val profileId = user.profiles?.firstOrNull()?.id ?: throw AppError.Unknown("Missing profile")

        return UserSession(accessToken, tokens.refreshToken, userId, profileId).also {
            session.session = it
            store.save(it)
        }
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
        // Drop the cached channel data so the next account does not see the previous one's channels.
        channelsPageCache = null
        channelDirectory = null
    }

    override fun currentSession(): UserSession? = session.session

    override suspend fun loadHome(): ContentPage = withRefresh {
        val page = api.homePage().toPage()
        val apps = appsRail()
        val recordings = loadRecordings()
        val extra = buildList {
            if (recordings.isNotEmpty()) {
                add(ContentRail("recordings", "Mes enregistrements", recordings.map { it.toCard() }))
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

    private suspend fun recordingsPage(): ContentPage =
        ContentPage("Vos enregistrements", listOf(ContentRail("recordings", null, loadRecordings().map { it.toCard() })))

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
                val resp = api.programDetail(id)
                // A programme episode links to its series; pull that series' catch-up episodes, if any.
                resp.seriesLink()?.let { episodes = api.seriesDetail(it, "id-tab-watch-now").toEpisodes() }
                resp.toProgramDetail(channelId = null, vodId = id, isLive = false)
            }
            DetailKind.SERIES -> {
                val resp = api.seriesDetail(id)
                // Series with catch-up expose their episodes on a separate "Regarder maintenant" tab.
                if (resp.hasWatchNowTab()) episodes = api.seriesDetail(id, "id-tab-watch-now").toEpisodes()
                resp.toProgramDetail(channelId = null, vodId = id, isLive = false)
            }
            DetailKind.CHANNEL -> api.channelDetail(id).toProgramDetail(channelId = id, vodId = null, isLive = true)
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
            episodes = episodes,
            posterUrl = detail.posterUrl ?: matches.firstOrNull()?.imageUrl,
            isSeries = kind == DetailKind.SERIES,
        )
    }

    override suspend fun recordEpisode(assetId: String) = withRefresh {
        // The response body is unused, but a raw ResponseBody must be closed or the connection leaks.
        api.addRecording(
            AddRecordingRequest(
                params = AddRecordingParams(assetId = assetId),
                metadatas = mapOf("asset.asset_id" to assetId),
            ),
        ).close()
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
        api.playbackAsset(channelId = channelId, type = "live").toPlaybackSource()
            .copy(originChannelId = channelId)
    }

    override suspend fun resolveVod(vodId: String): PlaybackSource = withRefresh {
        api.playbackAsset(id = vodId, type = "vod").toPlaybackSource()
            .copy(resumeKey = vodId, startPositionMs = progress.read(vodId), originVodId = vodId)
    }

    override suspend fun savePlaybackPosition(key: String, positionMs: Long) {
        if (positionMs > 0) progress.save(key, positionMs) else progress.clear(key)
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
        try {
            block()
        } catch (e: HttpException) {
            if (e.code() != 401) throw e
            val usedToken = session.session?.accessToken
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
            // The retry runs with the fresh token; if it is itself rejected, the session is truly dead.
            try {
                block()
            } catch (retryError: HttpException) {
                if (retryError.code() == 401 || retryError.code() == 403) {
                    logout()
                    throw AppError.Unauthorized
                }
                throw retryError
            }
        }
    }

    private fun Throwable.isAuthFailure(): Boolean =
        this is AppError.Unauthorized || (this is HttpException && (code() == 401 || code() == 403))

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
    401, 403 -> AppError.Unauthorized
    404 -> AppError.Unknown("Contenu introuvable")
    in 500..599 -> AppError.Network("Service indisponible, réessayez")
    else -> AppError.Unknown("Chargement impossible, réessayez")
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
    )
}
