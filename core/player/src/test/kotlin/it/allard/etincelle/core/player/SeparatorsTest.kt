// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val low = Separators.BLACKS.getValue(270)

        assertEquals(listOf(10_000L + 42 * 40), Separators.ends(frames, 10_000L, 40.0, low))
        // A key frame of a little over four kilobytes after a black, as seen on M6, ends it all the same.
        frames[42] = 4600
        assertEquals(listOf(10_000L + 42 * 40), Separators.ends(frames, 10_000L, 40.0, low))
    }

    @Test
    fun `each rendition has its own black`() {
        // M6 on 2026-11-26 at 13:52:59: a black and the first frame of the next advert, at 270 and 1080
        // lines. At 270 its frames are a few bytes over on average; at 1080 they are all bigger.
        val low = intArrayOf(952, 673, 71, 34, 82, 32, 32, 32, 36, 32, 32, 32, 36, 32, 16689)
        val high = intArrayOf(16199, 15090, 2538, 91, 223, 91, 141, 118, 88, 93, 86, 86, 88, 86, 165207)

        assertTrue(Separators.ends(low, 0L, 40.0, Separators.BLACKS.getValue(270)).isEmpty())
        assertEquals(listOf(14 * 40L), Separators.ends(high, 0L, 40.0, Separators.BLACKS.getValue(1080)))
        assertTrue(Separators.ends(high, 0L, 40.0, Separators.BLACKS.getValue(270)).isEmpty())
        // A key frame big at 270 lines but not at 1080 does not end a black there.
        high[14] = 9000
        assertTrue(Separators.ends(high, 0L, 40.0, Separators.BLACKS.getValue(1080)).isEmpty())
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
    fun `two runs of blacks are one across a black missed, a cartoon's last ones are not`() {
        // M6 on 2026-11-25: four blacks, one missed (95 s), six more.
        val m6 = seconds(0, 21, 30, 51, 95, 30, 51, 19, 15, 20)
        assertEquals(listOf(m6[1]..m6[m6.size - 2]), Separators.breaks(m6))
        // Canal J the same day: two blacks of a cartoon, 67 s before the adverts' own.
        val ads = seconds(107_000, 15, 16, 15, 16, 30, 15, 16, 15)
        assertEquals(listOf(ads[1]..ads[ads.size - 2]), Separators.breaks(listOf(0L, 40_000L) + ads))
    }

    @Test
    fun `a break is one across a card with a single black, not across one too far`() {
        // M6 on 2026-11-25 at 21:01: seven blacks, the "Publicité" card and trailers with one, more
        // adverts, and two blacks a minute on, which stay out.
        val m6 = seconds(0, 26, 20, 25, 26, 15, 21, 65, 77, 20, 41, 59, 15, 31, 45, 36, 16, 30, 26, 15, 31, 61, 20)
        assertEquals(listOf(m6[1]..m6[m6.size - 4]), Separators.breaks(m6))
        // Gulli at 23:47: five blacks, its sponsor's card with one, five more.
        val gulli = seconds(0, 31, 20, 11, 15, 63, 61, 20, 16, 25, 16)
        assertEquals(listOf(gulli[1]..gulli[gulli.size - 2]), Separators.breaks(gulli))
        // Two breaks of their own where the single black is too far from one of them.
        val first = seconds(0, 15, 15, 30, 15, 20)
        val lone = first.last() + Separators.GAP_MS + 10_000
        val second = seconds(lone + Separators.WIDE_GAP_MS + 1_000, 15, 15, 30, 15, 20)
        assertEquals(
            listOf(first[1]..first[first.size - 2], second[1]..second[second.size - 2]),
            Separators.breaks(first + lone + second),
        )
        // The same, too far from the first.
        val early = first.last() + Separators.WIDE_GAP_MS + 1_000
        val third = seconds(early + Separators.GAP_MS + 10_000, 15, 15, 30, 15, 20)
        assertEquals(
            listOf(first[1]..first[first.size - 2], third[1]..third[third.size - 2]),
            Separators.breaks(first + early + third),
        )
        // And as far as can be from both.
        val edge = first.last() + Separators.WIDE_GAP_MS
        val fourth = seconds(edge + Separators.WIDE_GAP_MS, 15, 15, 30, 15, 20)
        assertEquals(listOf(first[1]..fourth[fourth.size - 2]), Separators.breaks(first + edge + fourth))
    }

    @Test
    fun `a separator found in several renditions counts once`() {
        // Five are no break, however many renditions find them, a frame apart or not.
        val five = seconds(0, 20, 25, 30, 20)
        assertTrue(Separators.breaks(five + five + five.map { it + 40 }).isEmpty())
        // Six are one, from the second to the one before the last.
        val six = seconds(0, 15, 15, 30, 15, 20)
        assertEquals(listOf(six[1]..six[4]), Separators.breaks(six + six.map { it + 40 } + six.drop(2)))
    }

    @Test
    fun `a break keeps where its first separator ends, for where it began`() {
        val ads = seconds(100_000, 15, 15, 30, 15, 20, 30)
        assertEquals(listOf(SeparatedBreak(ads[1]..ads[ads.size - 2], ads[0])), Separators.found(ads))
        // A stray one before is its first: the break may have begun up to that much earlier.
        val before = ads.first() - 50_000
        assertEquals(listOf(SeparatedBreak(ads.first()..ads[ads.size - 2], before)), Separators.found(listOf(before) + ads))
    }

    @Test
    fun `a segment that cannot be read is asked for again a few times, then once a minute`() {
        assertFalse(SeparatorBreaks.again(null, 0))
        assertTrue(SeparatorBreaks.again(SeparatorBreaks.Miss(1, 0), 4_000))
        assertTrue(SeparatorBreaks.again(SeparatorBreaks.Miss(2, 0), 4_000))
        assertFalse(SeparatorBreaks.again(SeparatorBreaks.Miss(3, 0), 4_000))
        assertFalse(SeparatorBreaks.again(SeparatorBreaks.Miss(3, 0), 59_999))
        assertTrue(SeparatorBreaks.again(SeparatorBreaks.Miss(3, 0), 60_000))
        assertTrue(SeparatorBreaks.again(SeparatorBreaks.Miss(9, 120_000), 180_000))
    }

    @Test
    fun `a stray separator at either end leaves the break in the adverts`() {
        val ads = seconds(100_000, 15, 15, 30, 15, 20, 30)
        val before = ads.first() - 50_000
        val after = ads.last() + 40_000

        assertEquals(listOf(ads.first()..ads.last()), Separators.breaks(listOf(before) + ads + after))
    }
}
