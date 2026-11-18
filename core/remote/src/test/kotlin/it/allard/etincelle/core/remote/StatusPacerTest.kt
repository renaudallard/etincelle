// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPacerTest {

    @Test
    fun theFirstStatusIsSent() {
        assertTrue(StatusPacer().due("idle", 0, false, 5_000))
    }

    @Test
    fun steadyPlaybackIsOnlyToldAsAKeepAlive() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("playing", 60_000, true, 0))
        for (s in 1..9) assertFalse(pacer.due("playing", 60_000 + s * 1_000L, true, s * 1_000L))
        assertTrue(pacer.due("playing", 70_000, true, 10_000))
        assertFalse(pacer.due("playing", 71_000, true, 11_000))
    }

    @Test
    fun anIdleTvIsOnlyToldAsAKeepAlive() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("idle", 0, false, 0))
        assertFalse(pacer.due("idle", 0, false, 9_999))
        assertTrue(pacer.due("idle", 0, false, 10_000))
    }

    @Test
    fun aChangeIsSentAtOnce() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("playing", 60_000, true, 0))
        assertTrue(pacer.due("paused", 61_000, false, 1_000))
        assertFalse(pacer.due("paused", 61_000, false, 2_000))
        assertTrue(pacer.due("playing", 61_000, true, 3_000))
    }

    @Test
    fun aSeekIsSentAtOnce() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("playing", 60_000, true, 0))
        assertTrue(pacer.due("playing", 31_000, true, 1_000))
        assertFalse(pacer.due("playing", 32_000, true, 2_000))
        // Paused, the position stands still: any move was sought.
        assertTrue(pacer.due("paused", 32_000, false, 3_000))
        assertFalse(pacer.due("paused", 32_000, false, 4_000))
        assertTrue(pacer.due("paused", 92_000, false, 5_000))
    }

    @Test
    fun aPlayerSlightlyOffTheClockIsNotASeek() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("playing", 60_000, true, 0))
        assertFalse(pacer.due("playing", 60_400, true, 1_000))
        assertFalse(pacer.due("playing", 66_900, true, 6_000))
    }

    @Test
    fun aResetSendsTheNextOne() {
        val pacer = StatusPacer()
        assertTrue(pacer.due("idle", 0, false, 0))
        pacer.reset()
        assertTrue(pacer.due("idle", 0, false, 1_000))
        assertFalse(pacer.due("idle", 0, false, 2_000))
    }
}
