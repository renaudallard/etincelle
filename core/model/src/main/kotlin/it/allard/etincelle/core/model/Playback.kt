// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** DRM requirement for a stream, expressed in player-agnostic terms. */
sealed interface DrmSpec {
    data object None : DrmSpec

    /**
     * Widevine with an HTTP license server. [licenseHeaders] are sent verbatim on the license
     * request (Fubo/DRMtoday puts the auth token in the `x-dt-auth-token` header here).
     */
    data class Widevine(
        val licenseUrl: String,
        val licenseHeaders: Map<String, String> = emptyMap(),
    ) : DrmSpec
}

/** The air-window (epoch ms, UTC) of a live channel's currently-on-screen programme, used to scope
 * the live seek bar to the show. [startMs] is its scheduled start, [endMs] its scheduled end. */
data class ProgramWindow(val startMs: Long, val endMs: Long)

/** What a phone asks the TV app to play when it controls it: a live channel, a catch-up programme or a
 * recording, each named by its id. */
enum class PlayKind { LIVE, VOD, RECORDING }

/** Everything the player needs to start a stream, mapped from the backend playback response. */
data class PlaybackSource(
    val manifestUrl: String,
    val drm: DrmSpec,
    val isLive: Boolean,
    val title: String? = null,
    /** Live only: the on-screen programme's air-window, so the seek bar can mark the show within the
     * ~4h DVR window. Null when the backend gives no programme times; the bar then spans the window. */
    val programWindow: ProgramWindow? = null,
    /** Non-null for VOD/replay: the key under which to remember the resume position. */
    val resumeKey: String? = null,
    /** Where to start playback (ms); 0 starts at the beginning / live edge. */
    val startPositionMs: Long = 0L,
    /** Asked for from its beginning, which [startPositionMs] at 0 alone does not say: it is also what a
     * programme never watched here has. Another device playing it then leaves its own resume point be. */
    val fromStart: Boolean = false,
    /** Live only: how far behind the live edge (ms) playback should begin instead of at the edge.
     * Carries a manual rewind across a cast hand-off, and starts the current show from its beginning
     * ("depuis le début"). The cast receiver gets it as live_rewind_sec; the phone's own player seeks to
     * the programme start, and the TV app that far back, once the live window is known. 0 = live edge. */
    val liveRewindOffsetMs: Long = 0L,
    /** The originating ids, so a stream can be re-resolved (e.g. when a Cast transfer hits an
     * expired token). At most one is set: [originChannelId] for live, [originVodId] for VOD/replay,
     * [originRecordingAssetId] for a DVR recording. */
    val originChannelId: String? = null,
    val originVodId: String? = null,
    val originRecordingAssetId: String? = null,
    /** The page of the episode a recording was played from, which knows the episode that follows it
     * when the recording itself does not. Null for a recording played by itself, and for anything else. */
    val originPageVodId: String? = null,
    /** Server session keep-alive endpoints to ping while playing (may be null). */
    val heartbeatUrl: String? = null,
    val concurrencyHeartbeatUrl: String? = null,
    /** Server playhead (continue-watching) progress write: POST [progressPayload] to [progressUrl]
     * with its "lastOffset" entry set to the position in seconds. Null for live, which has no resume. */
    val progressUrl: String? = null,
    val progressPayload: Map<String, Any?>? = null,
    /** The episode that follows, offered at the end of this one; null when nothing follows. */
    val upNext: UpNext? = null,
    /** Live only: where the service keeps pictures of what the channel showed; null when it has none. */
    val liveThumbnails: LiveThumbnails? = null,
) {
    /**
     * The pictures to guess this live channel's ad breaks from. None on a channel whose stream marks
     * its breaks, exact as marked, nor on one without adverts: a guess could only add what surrounds
     * them, trailers and short programmes, and run into the show.
     */
    val breakPictures: LiveThumbnails?
        get() = liveThumbnails?.takeIf { isLive && originChannelId !in NO_GUESS_CHANNELS }

    /** Whether this live channel puts the blacks between its adverts that give its breaks away. */
    val separatedBreaks: Boolean
        get() = isLive && originChannelId in SEPARATED_CHANNELS
}

// TF1, TMC, TFX, TF1 Séries Films, LCI, France 2, 3 and 5: their streams mark their breaks.
// France 4, franceinfo and Arte: no advert break was ever seen on them, and what a guess offered
// there was trailers, the weather or a short programme.
private val NO_GUESS_CHANNELS = setOf("600046", "600036", "600040", "600067", "600005", "600019", "600049", "600018", "600042", "600136", "600034")

// M6, Gulli, Canal J, TIJI, Paris Première, Automoto, Toute l'Histoire and AB 1: 0.48 s of black
// between two adverts, every break of two samples of each found by them and skipped within the
// adverts in November 2026. Not W9 or 6ter, whose breaks have none.
private val SEPARATED_CHANNELS = setOf("600045", "600058", "600137", "600138", "600021", "600027", "600052", "600006")

/**
 * The service's pictures of a live channel: one every [intervalSec], kept for as long as the channel
 * can be rewound, each at [baseUrl] followed by its time in seconds since 1970.
 */
data class LiveThumbnails(val baseUrl: String, val intervalSec: Int)

/**
 * This episode with the one that follows it. A resume point past the slate means the episode was
 * watched to its credits, so it starts over rather than going straight on to the next one.
 */
fun PlaybackSource.withUpNext(next: UpNext?): PlaybackSource = copy(
    upNext = next,
    startPositionMs = if (next != null && startPositionMs >= next.atSec * 1000) 0L else startPositionMs,
)

/**
 * The next episode, as the backend's "up next" slate offers it: shown once playback reaches [atSec]
 * (from the start of the content), counting down [showSec] seconds before [nextVodId] plays.
 */
data class UpNext(
    val atSec: Long,
    val showSec: Long,
    val title: String?,
    val subtitle: String?,
    /** The countdown line, with %s standing for the seconds left (e.g. "Commence dans %s"). */
    val countdown: String,
    val playLabel: String,
    val creditsLabel: String,
    val nextVodId: String,
) {
    /**
     * Seconds left before the next episode starts at [positionMs], or null while the slate is not due.
     * The countdown follows the position, so a pause holds it and a seek back before [atSec] cancels it.
     * It also ends a little before the content does (the official app keeps 2.5 s), so the next episode
     * is chosen before playback stops.
     */
    fun secondsLeft(positionMs: Long, durationMs: Long): Long? {
        val at = atSec * 1000
        if (positionMs < at) return null
        var deadline = at + showSec * 1000
        if (durationMs > 0) deadline = minOf(deadline, durationMs - END_MARGIN_MS).coerceAtLeast(at)
        return ((deadline - positionMs + 999) / 1000).coerceAtLeast(0)
    }

    private companion object {
        const val END_MARGIN_MS = 2_500L
    }
}

/**
 * [other] is the same programme, whatever stream each was resolved to: one asked for again after an
 * error still is. Never true of a source that does not say what it is.
 */
fun PlaybackSource.isSameProgramme(other: PlaybackSource): Boolean {
    if (originChannelId == null && originVodId == null && originRecordingAssetId == null) return false
    return originChannelId == other.originChannelId &&
        originVodId == other.originVodId &&
        originRecordingAssetId == other.originRecordingAssetId
}
