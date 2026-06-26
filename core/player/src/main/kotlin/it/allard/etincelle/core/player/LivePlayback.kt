// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import it.allard.etincelle.core.model.ProgramWindow

/**
 * Where the live seek bar's marks sit on a bar whose full width is the current programme:
 * [playedFraction] is the playhead and [liveFraction] the live edge, both as fractions [0,1] of the
 * programme's air-window, so the live edge sits part-way along and the not-yet-aired remainder is the
 * tail past it. [seekFloorFraction] is the earliest rewindable point within the programme (the DVR
 * buffer only reaches so far back), 0 when the whole show is still buffered. When [hasProgramBand] is
 * false the programme window is unknown and the fractions fall back to the bare seekable-window scale.
 */
data class LiveBarGeometry(
    val playedFraction: Float,
    val liveFraction: Float,
    val seekFloorFraction: Float,
    val hasProgramBand: Boolean,
)

/** Live DVR helpers and tuning shared by the phone player, the TV player, and the cast controller. */
object LivePlayback {

    /** Rewind / forward skip steps for the player's transport controls. */
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L

    /** How far behind the live-play position (ms) counts as "watching the past": past this the
     * back-to-live affordance shows and a cast hand-off carries the rewind. A small margin past the
     * manifest refresh jitter so it does not trigger while playing live. */
    const val LIVE_REWIND_THRESHOLD_MS = 15_000L

    /**
     * How far (ms) playback sits behind the live-play position of the current live window, i.e. how
     * much has been rewound into the DVR window. Returns 0 at (or ahead of) the live edge, when the
     * stream is not live, or before the timeline is known.
     *
     * Measured against the window's default position (where a live join lands), not the absolute live
     * edge, so it is independent of the stream's steady-state edge delay - an SSAI live stream sits
     * tens of seconds behind the true edge even while "at the live edge", which an absolute-offset
     * check would mistake for a rewind. [window] is a caller-owned scratch instance to fill.
     */
    fun behindLiveEdgeMs(player: Player, window: Timeline.Window): Long {
        if (!player.isCurrentMediaItemLive) return 0L
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0L
        timeline.getWindow(player.currentMediaItemIndex, window)
        if (!window.isLive()) return 0L
        val behind = window.defaultPositionMs - player.currentPosition
        return if (behind > 0) behind else 0L
    }

    /**
     * The offset (ms) to hand a Chromecast so it resumes at [player]'s content position, or 0 when the
     * viewer is effectively at the live edge (rewound less than [LIVE_REWIND_THRESHOLD_MS]).
     *
     * The rewind is GATED on [behindLiveEdgeMs] (measured from the live-play position, so the stream's
     * steady-state edge delay does not look like a rewind), but the returned VALUE is the offset from
     * the true live edge ([Player.getCurrentLiveOffset]) because the receiver seeks relative to the
     * end of its seekable range (the true edge). Using the same edge reference on both sides makes the
     * receiver land where the phone was rather than the edge-delay closer to live.
     */
    fun castRewindOffsetMs(player: Player, window: Timeline.Window): Long {
        val behind = behindLiveEdgeMs(player, window)
        if (behind <= LIVE_REWIND_THRESHOLD_MS) return 0L
        val offset = player.currentLiveOffset
        // A CastPlayer commonly reports no live offset (C.TIME_UNSET), so a cast-to-cast transfer (or a
        // cast recovery) would otherwise drop the rewind and snap to the live edge. Fall back to the
        // behind-edge measure, which keeps the viewer's position (to within the stream's edge delay).
        return if (offset != C.TIME_UNSET && offset > 0) offset else behind
    }

    /**
     * The marks for a live seek bar scaled to the current programme, or null when the stream is not a
     * live seekable window yet. The bar spans the programme's air-window [[ProgramWindow.startMs],
     * [ProgramWindow.endMs]], so the live edge sits part-way along it and the not-yet-aired remainder
     * is the tail past it. The window maps to wall-clock via [Timeline.Window.windowStartTimeMs] (the
     * Fubo live manifest is epoch-normalised); without it (C.TIME_UNSET) or without a [programWindow]
     * the bar falls back to the bare seekable window. [window] is a caller-owned scratch instance to fill.
     */
    fun liveBarGeometry(player: Player, window: Timeline.Window, programWindow: ProgramWindow?): LiveBarGeometry? {
        if (!player.isCurrentMediaItemLive) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getWindow(player.currentMediaItemIndex, window)
        if (!window.isLive()) return null
        val durationMs = window.durationMs
        if (durationMs <= 0L) return null
        val windowStart = window.windowStartTimeMs
        if (programWindow == null || windowStart == C.TIME_UNSET) {
            // No programme to scale to: span the bare seekable window instead.
            val played = (player.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
            val live = (window.defaultPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            return LiveBarGeometry(played, live, 0f, hasProgramBand = false)
        }
        val programStart = programWindow.startMs
        val programDurationMs = (programWindow.endMs - programStart).toFloat()
        if (programDurationMs <= 0f) return null
        // Anchor the player's window positions to wall-clock, then express each as a fraction of the
        // programme's air-window so the show fills the whole bar.
        fun fraction(positionMs: Long): Float =
            ((windowStart + positionMs - programStart).toFloat() / programDurationMs).coerceIn(0f, 1f)
        val played = fraction(player.currentPosition)
        val live = fraction(window.defaultPositionMs)
        // The DVR buffer only reaches back to the window start, so earlier parts of the show can no
        // longer be rewound to; mark that floor (0 when the show began inside the buffer).
        val seekFloor = ((maxOf(programStart, windowStart) - programStart).toFloat() / programDurationMs).coerceIn(0f, 1f)
        return LiveBarGeometry(played, live, seekFloor, hasProgramBand = true)
    }

    /**
     * Seeks to [fraction] of the current programme's air-window (or of the bare seekable window when
     * [programWindow] is null), never before the window start nor past the live edge.
     */
    fun seekToFraction(player: Player, window: Timeline.Window, fraction: Float, programWindow: ProgramWindow?) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        timeline.getWindow(player.currentMediaItemIndex, window)
        val durationMs = window.durationMs
        if (durationMs <= 0L) return
        val f = fraction.coerceIn(0f, 1f)
        val windowStart = window.windowStartTimeMs
        val target = if (programWindow != null && windowStart != C.TIME_UNSET) {
            // Map the programme fraction back to wall-clock, then to a position in the seekable window.
            val wallClockMs = programWindow.startMs + (f * (programWindow.endMs - programWindow.startMs)).toLong()
            wallClockMs - windowStart
        } else {
            (f * durationMs).toLong()
        }
        player.seekTo(target.coerceIn(0L, window.defaultPositionMs))
    }

    /** The live edge as wall-clock epoch ms, or null when the window carries no epoch anchor. Used to
     * tell when the live edge has crossed the current programme's end and the bar must re-scope. */
    fun liveEdgeEpochMs(player: Player, window: Timeline.Window): Long? {
        if (!player.isCurrentMediaItemLive) return null
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        timeline.getWindow(player.currentMediaItemIndex, window)
        if (!window.isLive()) return null
        val windowStart = window.windowStartTimeMs
        if (windowStart == C.TIME_UNSET) return null
        return windowStart + window.defaultPositionMs
    }
}
