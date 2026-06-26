// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.model.ProgramWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure live-bar geometry: the bar is scaled to the current programme, not the DVR window. */
class LivePlaybackTest {

    private val hour = 3_600_000L
    private val window4h = 4 * hour

    // A 1h show currently 20 min in, on a 4h DVR window whose live edge sits at its end (epoch 0 start).
    private val programStart = window4h - 20 * 60_000L
    private val oneHourShow = ProgramWindow(programStart, programStart + hour)

    @Test
    fun `scales the bar to the programme with the live edge part-way`() {
        val g = LivePlayback.liveBarGeometry(
            currentPositionMs = window4h, // playhead at the live edge
            liveEdgeMs = window4h,
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            programWindow = oneHourShow,
        )!!
        assertTrue(g.hasProgramBand)
        assertEquals(20f / 60f, g.playedFraction, 1e-4f) // 20 of the show's 60 minutes
        assertEquals(20f / 60f, g.liveFraction, 1e-4f)
        assertEquals(0f, g.seekFloorFraction, 1e-4f) // the show began inside the buffer
    }

    @Test
    fun `rewinding moves the playhead behind the live edge`() {
        val g = LivePlayback.liveBarGeometry(
            currentPositionMs = window4h - 10 * 60_000L, // 10 min behind live
            liveEdgeMs = window4h,
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            programWindow = oneHourShow,
        )!!
        assertEquals(10f / 60f, g.playedFraction, 1e-4f)
        assertEquals(20f / 60f, g.liveFraction, 1e-4f)
        assertTrue(g.playedFraction < g.liveFraction)
    }

    @Test
    fun `a show older than the buffer gets an un-rewindable floor`() {
        // The window opens 1h into a 6h show, so its first sixth can no longer be rewound to.
        val sixHourShow = ProgramWindow(0L, 6 * hour)
        val g = LivePlayback.liveBarGeometry(
            currentPositionMs = window4h,
            liveEdgeMs = window4h,
            windowDurationMs = window4h,
            windowStartTimeMs = hour,
            programWindow = sixHourShow,
        )!!
        assertEquals(hour.toFloat() / (6 * hour), g.seekFloorFraction, 1e-4f) // 1/6
        assertTrue(g.seekFloorFraction > 0f)
    }

    @Test
    fun `falls back to the bare window when the programme is unknown`() {
        val g = LivePlayback.liveBarGeometry(
            currentPositionMs = 3 * hour,
            liveEdgeMs = window4h,
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            programWindow = null,
        )!!
        assertFalse(g.hasProgramBand)
        assertEquals(0.75f, g.playedFraction, 1e-4f)
        assertEquals(1f, g.liveFraction, 1e-4f)
        assertEquals(0f, g.seekFloorFraction, 1e-4f)
    }

    @Test
    fun `returns null for a zero-length window`() {
        assertNull(LivePlayback.liveBarGeometry(0L, 0L, 0L, 0L, oneHourShow))
    }

    @Test
    fun `seeking at the played fraction round-trips back to the playhead`() {
        val target = LivePlayback.seekTargetMs(
            fraction = 20f / 60f,
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            liveEdgeMs = window4h,
            programWindow = oneHourShow,
        )
        assertEquals(window4h, target) // the live-edge playhead
    }

    @Test
    fun `seeking into the un-aired tail snaps to the live edge`() {
        val target = LivePlayback.seekTargetMs(
            fraction = 0.9f, // well past the live edge at 1/3
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            liveEdgeMs = window4h,
            programWindow = oneHourShow,
        )
        assertEquals(window4h, target)
    }

    @Test
    fun `seeking to the programme start lands at the matching window position`() {
        val target = LivePlayback.seekTargetMs(
            fraction = 0f,
            windowDurationMs = window4h,
            windowStartTimeMs = 0L,
            liveEdgeMs = window4h,
            programWindow = oneHourShow,
        )
        assertEquals(programStart, target)
    }
}
