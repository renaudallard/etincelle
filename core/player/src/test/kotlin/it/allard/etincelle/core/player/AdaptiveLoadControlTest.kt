// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the pure buffer-sizing math: heap-scaled target, scaled floor, and trim severity. */
class AdaptiveLoadControlTest {

    private val mb = 1024 * 1024
    private fun heap(mbCount: Int): Long = mbCount.toLong() * mb

    // android.content.ComponentCallbacks2 trim levels (more severe = larger value).
    private val runningModerate = 5
    private val runningLow = 10
    private val runningCritical = 15
    private val uiHidden = 20
    private val complete = 80

    @Test
    fun `a normal device gets a 25 percent slice of the heap`() {
        assertEquals(64 * mb, AdaptiveLoadControl.targetBufferBytes(heap(256), lowRam = false))
    }

    @Test
    fun `a low-ram device gets a smaller 15 percent slice`() {
        assertEquals((128L * mb * 15 / 100).toInt(), AdaptiveLoadControl.targetBufferBytes(heap(128), lowRam = true))
    }

    @Test
    fun `a large heap is capped at the 256MB ceiling`() {
        assertEquals(256 * mb, AdaptiveLoadControl.targetBufferBytes(heap(2048), lowRam = false))
    }

    @Test
    fun `a weak device is not pinned above stock - the floor scales with the heap`() {
        // 64MB heap, low-ram: 15% ~ 9.6MB; the floor (10% ~ 6.4MB) must not lift it to the 40MB cap.
        val target = AdaptiveLoadControl.targetBufferBytes(heap(64), lowRam = true)
        assertEquals((64L * mb * 15 / 100).toInt(), target)
        assertTrue("a weak device must not be pinned to the 40MB floor cap", target < 40 * mb)
    }

    @Test
    fun `more severe pressure frees more of the buffer`() {
        val full = 200 * mb
        assertEquals(full, AdaptiveLoadControl.trimmedCapBytes(0, full))
        assertEquals(full / 4 * 3, AdaptiveLoadControl.trimmedCapBytes(runningModerate, full))
        assertEquals(full / 2, AdaptiveLoadControl.trimmedCapBytes(runningLow, full))
        assertEquals(full / 4, AdaptiveLoadControl.trimmedCapBytes(runningCritical, full))
    }

    @Test
    fun `a backgrounded app gets the most aggressive shrink`() {
        val full = 200 * mb
        assertEquals(full / 4, AdaptiveLoadControl.trimmedCapBytes(uiHidden, full))
        assertEquals(full / 4, AdaptiveLoadControl.trimmedCapBytes(complete, full))
    }

    @Test
    fun `the trim cap never drops below the 16MB floor`() {
        assertEquals(16 * mb, AdaptiveLoadControl.trimmedCapBytes(runningCritical, 40 * mb))
    }

    @Test
    fun `the trim floor never lifts the cap above a tiny steady-state buffer`() {
        // A sub-106MB low-ram device whose full buffer is under 16MB must not have its cap raised
        // above the full buffer, which would make the pressure valve a no-op.
        val full = 12 * mb
        assertEquals(full, AdaptiveLoadControl.trimmedCapBytes(runningCritical, full))
    }
}
