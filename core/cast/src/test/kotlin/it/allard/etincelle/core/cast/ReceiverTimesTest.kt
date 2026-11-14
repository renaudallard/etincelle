// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the receiver time arithmetic behind the direct cast player's position and live window. */
class ReceiverTimesTest {

    // A live stream like France 2 on a Chromecast: a 4 h window, the playhead 1.5 s behind the edge.
    private val live = ReceiverTimes(time = 14385.0, start = 1.5, end = 14386.5)

    @Test
    fun theWindowAndThePositionAreInMilliseconds() {
        assertEquals(14_385_000L, live.windowMs)
        assertEquals(14_383_500L, live.livePositionMs)
    }

    @Test
    fun playingMovesThePlayheadAndTheWindowTogether() {
        val later = live.advance(10.0, playing = true, moving = true)
        assertEquals(ReceiverTimes(14395.0, 11.5, 14396.5), later)
        assertEquals(live.livePositionMs, later.livePositionMs)
    }

    @Test
    fun pausedTheWindowMovesOnAndThePositionFallsBehind() {
        val later = live.advance(10.0, playing = false, moving = true)
        assertEquals(14385.0, later.time, 0.0)
        assertEquals(live.livePositionMs - 10_000, later.livePositionMs)
    }

    @Test
    fun aFixedWindowStaysPut() {
        assertEquals(ReceiverTimes(14395.0, 1.5, 14386.5), live.advance(10.0, playing = true, moving = false))
    }

    @Test
    fun thePositionStaysInsideTheWindow() {
        assertEquals(0L, live.copy(time = 0.0).livePositionMs)
        assertEquals(live.windowMs, live.copy(time = 20_000.0).livePositionMs)
    }

    @Test
    fun aSeekTargetIsMappedIntoTheWindow() {
        assertEquals(1.5 + 600.0, live.liveSeekTarget(600_000), 1e-9)
        // The default position is the live edge; past either end is clamped.
        assertEquals(live.end, live.liveSeekTarget(C.TIME_UNSET), 0.0)
        assertEquals(live.end, live.liveSeekTarget(live.windowMs + 30_000), 0.0)
        assertEquals(live.start, live.liveSeekTarget(-10_000), 0.0)
    }
}
