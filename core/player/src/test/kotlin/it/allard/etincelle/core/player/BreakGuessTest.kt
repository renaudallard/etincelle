// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakGuessTest {

    private val segment = 3_840L

    // Segments of a recording, from stretches given as a number of segments and whether they speak.
    private fun spoken(vararg stretches: Pair<Int, Boolean>) =
        stretches.flatMap { (count, speaks) -> List(count) { speaks } }.toBooleanArray()

    @Test
    fun `a caption segment speaks when a paragraph has words`() {
        val words = """<tt><body><div><p begin="0" end="1" region="r"> <span style="s">-Bonjour.<br />Ca va ?</span> </p></div></body></tt>"""
        val empty = """<tt><body><div><p begin="0" end="1"> <span style="s"> </span> </p></div></body></tt>"""
        assertTrue(BreakGuess.speaks(words.toByteArray()))
        assertFalse(BreakGuess.speaks(empty.toByteArray()))
        assertFalse(BreakGuess.speaks("<tt><body><div></div></body></tt>".toByteArray()))
        assertFalse(BreakGuess.speaks(ByteArray(0)))
    }

    @Test
    fun `runs hold short silences and stop at long ones`() {
        val flags = booleanArrayOf(true, false, false, true, false, false, false, true, true)
        assertEquals(listOf(0..3, 7..8), BreakGuess.runs(flags, 2))
        assertEquals(listOf(0..8), BreakGuess.runs(flags, 3))
        assertEquals(emptyList<IntRange>(), BreakGuess.runs(BooleanArray(4), 3))
    }

    @Test
    fun `a stretch with few captions between two of programme is a break`() {
        // 20 min of programme, 6 min of adverts of which two captioned ones, 15 min of programme.
        val recording = spoken(312 to true, 30 to false, 6 to true, 40 to false, 5 to true, 13 to false, 234 to true)
        assertEquals(listOf(312 * segment..406 * segment), BreakGuess.captionBreaks(recording, 0, segment))
    }

    @Test
    fun `a pause in the programme is no break, nor the gap between two programmes`() {
        // 30 s without a word, then a quarter of an hour of nothing.
        val recording = spoken(200 to true, 8 to false, 200 to true, 240 to false, 200 to true)
        assertEquals(emptyList<LongRange>(), BreakGuess.captionBreaks(recording, 0, segment))
    }

    @Test
    fun `a programme without captions says nothing by them`() {
        // Only its adverts are captioned.
        assertNull(BreakGuess.captionBreaks(spoken(350 to false, 6 to true, 30 to false, 8 to true, 400 to false), 0, segment))
    }

    @Test
    fun `a break is kept where both signals see it`() {
        val captions = listOf(2_081_000L..2_442_000L)
        val logo = listOf(2_081_000L..2_429_000L)
        assertEquals(listOf(2_081_000L..2_429_000L), BreakGuess.combine(captions, logo))
        // Captioned adverts at both ends of a break pass for the programme: the logo knows better.
        assertEquals(listOf(1_289_000L..1_673_000L), BreakGuess.combine(listOf(1_344_000L..1_624_000L), listOf(1_289_000L..1_673_000L)))
        // Too far apart for that, at one end: the shorter break is kept there.
        assertEquals(listOf(1_289_000L..1_624_000L), BreakGuess.combine(listOf(1_300_000L..1_624_000L), listOf(1_289_000L..1_900_000L)))
        // One alone, when the other has nothing to go by.
        assertEquals(logo, BreakGuess.combine(null, logo))
        assertEquals(captions, BreakGuess.combine(captions, null))
        assertEquals(emptyList<LongRange>(), BreakGuess.combine(null, null))
        // Seen by one and not by the other, which could tell: not kept.
        assertEquals(emptyList<LongRange>(), BreakGuess.combine(captions, listOf(600_000L..900_000L)))
        assertEquals(emptyList<LongRange>(), BreakGuess.combine(captions, emptyList()))
    }

    private val width = 64
    private val height = 40

    // A picture of flat grey [shade], with a logo of stripes in its bottom left corner or not.
    private fun picture(shade: Int, logo: Boolean) = ByteArray(width * height) { p ->
        val x = p % width
        val y = p / width
        val inLogo = logo && x in 2..9 && y in height - 7 until height - 1
        (if (inLogo && x % 2 == 0) 250 else shade).toByte()
    }

    // Pictures every 4 s: programme with its logo, then [adverts] pictures without, then programme.
    private fun track(before: Int, adverts: Int, after: Int, logoInAdverts: IntRange = IntRange.EMPTY): LogoTrack {
        val count = before + adverts + after
        val track = LogoTrack(count, width, height)
        for (i in 0 until count) {
            val advert = i in before until before + adverts
            track.add(i, picture(40 + i % 100, logo = !advert || i - before in logoInAdverts))
        }
        return track
    }

    @Test
    fun `a stretch without the logo, with programme around it, is a break`() {
        // 100 pictures of programme, 90 of adverts (6 min), 100 of programme.
        assertEquals(listOf(400_000L..760_000L), track(100, 90, 100).breaks(0, 4_000))
    }

    @Test
    fun `an advert with something in the logo's corner does not end the break`() {
        assertEquals(listOf(400_000L..760_000L), track(100, 90, 100, logoInAdverts = 40..44).breaks(0, 4_000))
    }

    @Test
    fun `pictures left out count as the programme's, and those wanted are told`() {
        // Strips of 25 pictures, every other one read: 150 of programme, 90 of adverts, 135 of programme.
        val count = 375
        val track = LogoTrack(count, width, height)
        fun give(strip: Int) = (strip * 25 until strip * 25 + 25).forEach { track.add(it, picture(40 + it % 100, logo = it !in 150 until 240)) }
        (0 until 15 step 2).forEach(::give)
        val gone = track.gone()!!
        // The break shows in the strips read within it, and nowhere else.
        assertEquals(setOf(6, 8), (0 until 15).filter { strip -> (strip * 25 until strip * 25 + 25).any { gone[it] } }.toSet())
        listOf(5, 7, 9).forEach(::give)
        assertEquals(listOf(600_000L..960_000L), track.breaks(0, 4_000))
    }

    @Test
    fun `too short a stretch, or one at the end of the recording, is no break`() {
        assertEquals(emptyList<LongRange>(), track(100, 20, 100).breaks(0, 4_000))
        assertEquals(emptyList<LongRange>(), track(200, 90, 0).breaks(0, 4_000))
    }

    @Test
    fun `a logo learnt from some pictures is told in others`() {
        val track = LogoTrack(60, width, height)
        for (i in 0 until 60) track.add(i, picture(40 + i, logo = i % 10 != 0))
        val logo = track.logo()!!
        assertTrue(logo.score(picture(70, logo = true)) > 0.9 * logo.usual)
        assertTrue(logo.score(picture(70, logo = false)) < 0.1 * logo.usual)
    }

    @Test
    fun `a stretch without the logo is a break once the programme is seen on both sides`() {
        fun flags(before: Int, without: Int, after: Int) = BooleanArray(before + without + after) { it in before until before + without }
        assertEquals(listOf(1_000_080_000L..1_000_440_000L), BreakGuess.logoBreaks(flags(20, 90, 20), 1_000_000_000L, 4_000))
        // Still going on, or begun before what is known: not yet.
        assertEquals(emptyList<LongRange>(), BreakGuess.logoBreaks(flags(20, 90, 5), 1_000_000_000L, 4_000))
        assertEquals(emptyList<LongRange>(), BreakGuess.logoBreaks(flags(5, 90, 20), 1_000_000_000L, 4_000))
    }

    // Pictures from stretches given as a number of them and whether they are without the logo.
    private fun gone(vararg stretches: Pair<Int, Boolean>) = stretches.flatMap { (count, gone) -> List(count) { gone } }.toBooleanArray()

    @Test
    fun `a few pictures without the logo near a break neither end it late nor undo it`() {
        // Three pictures of a bright scene, five or ten pictures after the adverts.
        assertEquals(listOf(80_000L..440_000L), BreakGuess.logoBreaks(gone(20 to false, 90 to true, 5 to false, 3 to true, 30 to false), 0, 4_000))
        assertEquals(listOf(80_000L..440_000L), BreakGuess.logoBreaks(gone(20 to false, 90 to true, 10 to false, 3 to true, 30 to false), 0, 4_000))
    }

    @Test
    fun `a badge over the logo a minute after the adverts is not more of them`() {
        // The programme is back for 12 pictures, then has a badge over its logo for 11.
        assertEquals(listOf(80_000L..440_000L), BreakGuess.logoBreaks(gone(20 to false, 90 to true, 12 to false, 11 to true, 30 to false), 0, 4_000))
    }

    @Test
    fun `an advert with something in the logo's corner is bridged when short, and splits the break when long`() {
        assertEquals(listOf(80_000L..460_000L), BreakGuess.logoBreaks(gone(20 to false, 30 to true, 7 to false, 58 to true, 30 to false), 0, 4_000))
        // Nine pictures of it: the first part is too short to be offered, the second is.
        assertEquals(listOf(236_000L..448_000L), BreakGuess.logoBreaks(gone(20 to false, 30 to true, 9 to false, 53 to true, 30 to false), 0, 4_000))
    }

    @Test
    fun `pictures with nothing that stays say nothing`() {
        val track = LogoTrack(120, width, height)
        for (i in 0 until 120) track.add(i, picture(40 + i, logo = false))
        assertNull(track.breaks(0, 4_000))
    }
}
