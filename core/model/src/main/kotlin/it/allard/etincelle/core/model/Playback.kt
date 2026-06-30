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
    /** Live only: how far behind the live edge (ms) playback should begin instead of at the edge.
     * Carries a manual rewind across a cast hand-off, and starts the current show from its beginning
     * ("depuis le début"). The cast receiver gets it as live_rewind_sec; the local player seeks to the
     * programme start once the live window is known. 0 = live edge. */
    val liveRewindOffsetMs: Long = 0L,
    /** The originating ids, so a stream can be re-resolved (e.g. when a Cast transfer hits an
     * expired token). At most one is set: [originChannelId] for live, [originVodId] for VOD/replay,
     * [originRecordingAssetId] for a DVR recording. */
    val originChannelId: String? = null,
    val originVodId: String? = null,
    val originRecordingAssetId: String? = null,
    /** Server session keep-alive endpoints to ping while playing (may be null). */
    val heartbeatUrl: String? = null,
    val concurrencyHeartbeatUrl: String? = null,
    /** Server playhead (continue-watching) progress write: POST [progressPayload] to [progressUrl]
     * with its "lastOffset" entry set to the position in seconds. Null for live, which has no resume. */
    val progressUrl: String? = null,
    val progressPayload: Map<String, Any?>? = null,
)
