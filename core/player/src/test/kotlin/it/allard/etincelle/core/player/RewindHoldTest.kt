// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers where a rewind with the arrows stops: the start of the programme, and how it goes past. */
class RewindHoldTest {

    private val sec = 1_000L
    private val end = 14_400 * sec
    // The programme started 10 min before the live point, at 17:05 (a time of day, epoch ms).
    private val start = end - 600 * sec
    private val startOfDay = 1_800_000_000_000L
    private val back = -10 * sec

    // Presses of Left from [base], each landing where the next starts, as presses that add up do.
    private fun RewindHold.presses(base: Long, n: Int): Long {
        var at = base
        repeat(n) { i -> at = press(at, back, end, start, startOfDay, letGo = i == 0) }
        return at
    }

    @Test
    fun `a rewind stops at the start of the programme`() {
        val hold = RewindHold()
        assertEquals(start, hold.presses(end, 90))
        assertTrue(hold.parked)
    }

    @Test
    fun `a press soon after the stop goes past it`() {
        val hold = RewindHold()
        hold.presses(end, 90)
        // The seek made, playback goes on for 3 s, then Left again.
        assertEquals(start - 7 * sec, hold.press(start + 3 * sec, back, end, start, startOfDay, letGo = true))
        assertFalse(hold.parked)
        // Presses coming in after it go on back.
        assertEquals(start - 17 * sec, hold.press(start - 7 * sec, back, end, start, startOfDay, letGo = false))
    }

    @Test
    fun `presses still coming in stay at the start`() {
        val hold = RewindHold()
        hold.presses(end, 90)
        assertEquals(start, hold.press(start, back, end, start, startOfDay, letGo = false))
    }

    @Test
    fun `a press long after the stop stops there again`() {
        val hold = RewindHold()
        hold.presses(end, 90)
        assertEquals(start + 10 * sec, hold.press(start + 20 * sec, back, end, start, startOfDay, letGo = true))
        assertEquals(start, hold.press(start + 10 * sec, back, end, start, startOfDay, letGo = false))
        assertTrue(hold.parked)
    }

    @Test
    fun `back in the programme after going past, the start holds again`() {
        val hold = RewindHold()
        hold.presses(end, 90)
        hold.press(start + 3 * sec, back, end, start, startOfDay, letGo = true)
        // Playback runs back into the programme, 5 s in, and Left is pressed.
        assertEquals(start, hold.press(start + 5 * sec, back, end, start, startOfDay, letGo = true))
    }

    @Test
    fun `a stop at one programme does not let a rewind past the next`() {
        val hold = RewindHold()
        hold.presses(end, 90)
        // The next programme began 30 min later, playback 5 s into it.
        val next = start + 1_800 * sec
        assertEquals(next, hold.press(next + 5 * sec, back, end + 1_800 * sec, next, startOfDay + 1_800 * sec, letGo = true))
    }

    @Test
    fun `a start past the live point read is not stopped at`() {
        val hold = RewindHold()
        // Playback ran a moment past the live point; the programme began between the two.
        assertEquals(end - 5 * sec, hold.press(end + 5 * sec, back, end, end + 2 * sec, startOfDay, letGo = true))
    }

    @Test
    fun `before the programme, or without one, nothing holds`() {
        val hold = RewindHold()
        assertEquals(start - 20 * sec, hold.press(start - 10 * sec, back, end, start, startOfDay, letGo = true))
        assertEquals(0L, RewindHold().press(5 * sec, back, end, null, null, letGo = true))
    }

    @Test
    fun `forward presses are not held back`() {
        val hold = RewindHold()
        assertEquals(start + 30 * sec, hold.press(start, 30 * sec, end, start, startOfDay, letGo = true))
        assertEquals(end, hold.press(end - 10 * sec, 30 * sec, end, start, startOfDay, letGo = true))
    }
}
