// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeparatorsTest {

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }

    private fun ints(vararg v: Int): ByteArray = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    // The first bytes of a segment as the service's are laid out: styp, sidx, then the moof, whose trun
    // gives each frame's length and size after a data offset, and the start of the mdat.
    private fun segment(sizes: IntArray, trunFlags: Int = 0x301, tfhd: ByteArray = ints(0x020000, 1)): ByteArray {
        val each = sizes.flatMap { listOf(40, it) }.toIntArray()
        val trun = if (trunFlags and 0x200 != 0) box("trun", ints(trunFlags, sizes.size, 0), ints(*each)) else box("trun", ints(trunFlags, sizes.size, 0), ints(*IntArray(sizes.size) { 40 }))
        val moof = box("moof", box("mfhd", ints(0, 7)), box("traf", box("tfhd", tfhd), box("tfdt", ints(0, 0)), box("senc", ints(0, 0)), trun))
        return box("styp", "msdh".toByteArray()) + box("sidx", ints(0, 1, 90000)) + moof + ints(100_000) + "mdat".toByteArray()
    }

    @Test
    fun `reads the sizes of the frames from a segment's header`() {
        val sizes = intArrayOf(9869, 1336, 111, 150, 108, 1732)
        val d = segment(sizes)

        assertArrayEquals(sizes, Separators.sizes(d))
        // A header cut short is not read, but says how much more is needed.
        val cut = d.copyOf(d.size - 30)
        assertNull(Separators.sizes(cut))
        assertEquals(d.size - 8, Separators.moofEnd(cut))
        // Nor is one with no moof where it should be.
        assertNull(Separators.moofEnd(box("styp", "msdh".toByteArray())))
    }

    @Test
    fun `sizes all the same come from the fragment's header`() {
        val d = segment(IntArray(3), trunFlags = 0x101, tfhd = ints(0x020010, 1, 500))

        assertArrayEquals(intArrayOf(500, 500, 500), Separators.sizes(d))
    }

    @Test
    fun `a trun that lists more frames than it holds is not read`() {
        val d = segment(intArrayOf(1, 2, 3))
        val at = d.indexOf("trun") + 8
        ByteBuffer.wrap(d).putInt(at, 1000)

        assertNull(Separators.sizes(d))
    }

    private fun ByteArray.indexOf(text: String): Int =
        (0..size - text.length).first { String(this, it, text.length, Charsets.ISO_8859_1) == text }

    @Test
    fun `a black is a dozen tiny frames and then a key frame`() {
        val frames = IntArray(96) { if (it % 4 == 0) 2500 else 300 }
        for (i in 30 until 42) frames[i] = 32
        frames[42] = 9000
        // A picture held as long, ended by a frame that is not a key frame, is no black.
        for (i in 60 until 72) frames[i] = 32
        frames[72] = 400
        // Nor one whose frames are not all as small as black ones, as a cartoon's held picture.
        for (i in 45 until 57) frames[i] = if (i % 2 == 0) 32 else 100
        frames[57] = 9000
        // Nor is one held longer.
        for (i in 75 until 95) frames[i] = 20
        frames[95] = 12000

        assertEquals(listOf(10_000L + 42 * 40), Separators.ends(frames, 10_000L, 40.0))
        // A key frame of a little over four kilobytes after a black, as seen on M6, ends it all the same.
        frames[42] = 4600
        assertEquals(listOf(10_000L + 42 * 40), Separators.ends(frames, 10_000L, 40.0))
    }

    private fun seconds(first: Long, vararg gaps: Int): List<Long> = gaps.runningFold(first) { at, gap -> at + gap * 1000L }

    @Test
    fun `a break is a series of separators, a cartoon's held frames are not`() {
        // Gulli on 2026-11-25: a break, then held frames of a cartoon, as many in under a minute.
        val ads = seconds(0, 10, 15, 15, 26, 31, 31, 30, 51, 15, 15, 16, 15, 16)
        val cartoon = seconds(800_000, 23, 5, 16, 15, 2)

        assertEquals(listOf(ads[1]..ads[ads.size - 2]), Separators.breaks(cartoon + ads))
    }

    @Test
    fun `a separator more than a minute from the others is not of their break`() {
        val ads = seconds(0, 15, 15, 30, 15, 20)
        val late = ads.last() + Separators.GAP_MS + 1

        assertEquals(listOf(ads[1]..ads[ads.size - 2]), Separators.breaks(ads + late))
        assertTrue(Separators.breaks(ads.drop(1) + late).isEmpty())
    }

    @Test
    fun `a stray separator at either end leaves the break in the adverts`() {
        val ads = seconds(100_000, 15, 15, 30, 15, 20, 30)
        val before = ads.first() - 50_000
        val after = ads.last() + 40_000

        assertEquals(listOf(ads.first()..ads.last()), Separators.breaks(listOf(before) + ads + after))
    }
}
