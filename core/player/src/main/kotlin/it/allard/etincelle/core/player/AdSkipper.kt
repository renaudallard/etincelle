// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline

// How long after a seek backwards the break playback is found in counts as gone back into.
private const val REWOUND_MS = 3_000L

/**
 * What a player screen does about the ad break playback is in (see [AdBreaks]): with [auto], as the
 * settings ask, a break is skipped to its end as playback comes into it; otherwise, and for a break
 * gone back into, for as long as playback stays in it, it is handed to the screen to offer with a
 * button.
 *
 * Gone back into is told from a seek backwards made just before, which this hears as a listener of
 * the player: someone who rewinds into the ads wants to see them, or to get past them backwards, and
 * would otherwise be thrown forward to their end. A seek of the screen's own (to the start of a
 * programme, to a place handed over) is no such choice: it says so with [ownSeek].
 */
class AdSkipper(private val auto: Boolean) : Player.Listener {
    private val breaks = AdBreaks()
    // The break gone back into, left to play until playback is out of it.
    private var kept = 0L
    private var rewoundAt = 0L

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs < oldPosition.positionMs) {
            rewoundAt = SystemClock.elapsedRealtime()
        }
    }

    /** The seek just made was the screen's own, not the viewer's. */
    fun ownSeek() {
        rewoundAt = 0L
    }

    /**
     * To be called as the screen looks at its player, every second: skips the break playback has come
     * into when it is to be, and returns the one to offer a button for, or null.
     */
    fun tick(player: Player, window: Timeline.Window): AdBreak? {
        val ad = breaks.current(player, window)
        if (ad == null) {
            kept = 0L
            return null
        }
        if (!auto || ad.id == kept) return ad
        if (rewoundAt != 0L && SystemClock.elapsedRealtime() - rewoundAt < REWOUND_MS) {
            kept = ad.id
            return ad
        }
        player.seekTo(ad.endMs)
        return null
    }
}
