// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

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
        if (behindLiveEdgeMs(player, window) <= LIVE_REWIND_THRESHOLD_MS) return 0L
        val offset = player.currentLiveOffset
        return if (offset != C.TIME_UNSET && offset > 0) offset else 0L
    }
}
