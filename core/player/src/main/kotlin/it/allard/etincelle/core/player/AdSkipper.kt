// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline

// How long after a stream is ready to play a seek backwards is still taken for the app's own.
private const val SETTLING_MS = 5_000L

/**
 * What a player screen does about the ad break playback is in (see [AdBreaks]): with [auto], as the
 * settings ask, a break is skipped to its end once there is somewhere to skip to; otherwise, and for a
 * break gone back into, for as long as playback stays in it, it is handed to the screen to offer with
 * a button.
 *
 * Gone back into is told from a seek backwards, which this hears as a listener of the player: someone
 * who rewinds into the ads, or within them, wants to see them, or to get past them backwards, and
 * would otherwise be thrown forward to their end. The app's own seeks are no such choice. Most take a
 * stream just loaded back to where it was (a place handed over, a programme resumed, an error got
 * over) and are told by that; one made later says so with [ownSeek].
 */
class AdSkipper(private val auto: Boolean) : Player.Listener {
    private val breaks = AdBreaks()
    // The break playback was in when last looked at, and whether it is left to play.
    private var inBreak = 0L
    private var kept = false
    // The viewer went backwards since then.
    private var rewound = false
    // When the stream loaded was first seen ready to play; 0 until it is.
    private var readyAt = 0L

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE) readyAt = 0L
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) readyAt = 0L
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || newPosition.positionMs >= oldPosition.positionMs) return
        if (readyAt != 0L && SystemClock.elapsedRealtime() - readyAt >= SETTLING_MS) rewound = true
    }

    /** The seek just made was the screen's own, not the viewer's. */
    fun ownSeek() {
        rewound = false
    }

    /**
     * To be called as the screen looks at its player, every second: skips the break playback is in
     * when it is to be, and returns the one to offer a button for, or null.
     */
    fun tick(player: Player, window: Timeline.Window): AdBreak? {
        if (readyAt == 0L && player.playbackState == Player.STATE_READY) readyAt = SystemClock.elapsedRealtime()
        val back = rewound
        rewound = false
        val ad = breaks.current(player, window)
        if (ad == null) {
            inBreak = 0L
            kept = false
            return null
        }
        kept = back || (kept && ad.id == inBreak)
        inBreak = ad.id
        if (!ad.skippable) return null
        if (!auto || kept) return ad
        player.seekTo(ad.endMs)
        return null
    }

    /**
     * The button was used: to the end of the break playback is in. Looked up again rather than taken
     * from what [tick] gave, whose positions are those of a window that has slid since.
     */
    fun skip(player: Player, window: Timeline.Window) {
        breaks.current(player, window)?.takeIf { it.skippable }?.let { player.seekTo(it.endMs) }
    }
}
