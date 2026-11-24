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
        val logo = listOf(Guess(2_081_000L..2_429_000L))
        assertEquals(listOf(2_081_000L..2_429_000L), BreakGuess.combine(captions, logo).ranges())
        // Captioned adverts at both ends of a break pass for the programme: the logo knows better.
        assertEquals(listOf(1_289_000L..1_673_000L), BreakGuess.combine(listOf(1_344_000L..1_624_000L), listOf(Guess(1_289_000L..1_673_000L))).ranges())
        // Too far apart for that, at one end: the shorter break is kept there.
        assertEquals(listOf(1_289_000L..1_624_000L), BreakGuess.combine(listOf(1_300_000L..1_624_000L), listOf(Guess(1_289_000L..1_900_000L))).ranges())
        // One alone, when the other has nothing to go by.
        assertEquals(logo, BreakGuess.combine(null, logo))
        assertEquals(captions, BreakGuess.combine(captions, null).ranges())
        assertEquals(emptyList<Guess>(), BreakGuess.combine(null, null))
        // Seen by one and not by the other, which could tell: not kept.
        assertEquals(emptyList<Guess>(), BreakGuess.combine(captions, listOf(Guess(600_000L..900_000L))))
        assertEquals(emptyList<Guess>(), BreakGuess.combine(captions, emptyList()))
    }

    @Test
    fun `a sure break of the logo stays so only where the captions agree on both edges`() {
        val logo = listOf(Guess(1_289_000L..1_673_000L, sure = true))
        assertEquals(logo, BreakGuess.combine(listOf(1_344_000L..1_624_000L), logo))
        assertEquals(listOf(Guess(1_289_000L..1_624_000L)), BreakGuess.combine(listOf(1_300_000L..1_624_000L), listOf(Guess(1_289_000L..1_900_000L, sure = true))))
        assertEquals(logo, BreakGuess.combine(null, logo))
        // The captions alone are never sure.
        assertEquals(listOf(Guess(1_289_000L..1_673_000L)), BreakGuess.combine(listOf(1_289_000L..1_673_000L), null))
    }

    @Test
    fun `a break is sure when one clean stretch with the programme plainly on both sides`() {
        fun sure(gone: BooleanArray, lowerGone: BooleanArray? = null) = BreakGuess.logoBreaks(gone, 0, 4_000, lowerGone).map { it.sure }
        assertEquals(listOf(true), sure(gone(20 to false, 90 to true, 20 to false)))
        // Something bridged in it.
        assertEquals(listOf(false), sure(gone(20 to false, 30 to true, 7 to false, 58 to true, 30 to false)))
        // A short piece given up at an end.
        assertEquals(listOf(false), sure(gone(20 to false, 90 to true, 5 to false, 8 to true, 20 to false)))
        // Narrowed by the second logo, or not.
        val whole = BooleanArray(130) { it in 20 until 110 }
        assertEquals(listOf(false), sure(whole, BooleanArray(130) { it in 20 until 80 }))
        assertEquals(listOf(true), sure(whole, BooleanArray(130) { true }))
        // Five pictures without the logo among the fifteen before it: a break, but not a sure one.
        val near = gone(20 to false, 5 to true, 9 to false, 90 to true, 20 to false)
        assertEquals(listOf(false), sure(near))
        // Nor with a second logo gone with the first.
        assertEquals(listOf(false), sure(near, near))
    }

    private val width = 64
    private val height = 40

    // A picture of flat grey [shade], with a logo of stripes in its bottom left corner or not (white,
    // or [faint] above the grey), and a fainter one in its top right corner, [upper] above the grey
    // (0 for none).
    private fun picture(shade: Int, logo: Boolean, upper: Int = 0, faint: Int = 0) = ByteArray(width * height) { p ->
        val x = p % width
        val y = p / width
        val inLogo = logo && x in 2..9 && y in height - 7 until height - 1
        val inUpper = upper > 0 && x in width - 10 until width - 2 && y in 1..6
        (if (inLogo && x % 2 == 0) (if (faint > 0) shade + faint else 250) else if (inUpper && x % 2 == 0) shade + upper else shade).toByte()
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
        assertEquals(listOf(400_000L..760_000L), track(100, 90, 100).breaks(0, 4_000).ranges())
    }

    @Test
    fun `adverts and trailers between two programmes are a break up to twenty minutes`() {
        // 19 min without the logo, about as long as M6's block before its early evening sitcom, then 21 min.
        assertEquals(listOf(1_200_000L..2_340_000L), track(300, 285, 300).breaks(0, 4_000).ranges())
        assertEquals(emptyList<LongRange>(), track(350, 315, 350).breaks(0, 4_000).ranges())
    }

    @Test
    fun `an advert with something in the logo's corner does not end the break`() {
        assertEquals(listOf(400_000L..760_000L), track(100, 90, 100, logoInAdverts = 40..44).breaks(0, 4_000).ranges())
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
        assertEquals(listOf(600_000L..960_000L), track.breaks(0, 4_000).ranges())
    }

    @Test
    fun `the logo of an upper corner is taken before a stronger one below`() {
        // The channel's logo through the programme, and a show's own that comes a minute late after the
        // adverts: 100 pictures of programme, 90 of adverts, 15 with the upper logo only, 100 with both.
        val track = LogoTrack(305, width, height)
        for (i in 0 until 305) track.add(i, picture(40 + i % 100, logo = i < 100 || i >= 205, upper = if (i in 100 until 190) 0 else 60))
        assertEquals(listOf(400_000L..760_000L), track.breaks(0, 4_000).ranges())
    }

    @Test
    fun `a picture with the show's own logo is the programme's`() {
        // Four minutes of programme too bright for the channel's logo to show, the show's own still there.
        val bright = LogoTrack(260, width, height)
        for (i in 0 until 260) bright.add(i, picture(40 + i % 100, logo = true, upper = if (i in 100 until 160) 0 else 60))
        assertEquals(emptyList<LongRange>(), bright.breaks(0, 4_000).ranges())
        // The show's logo back 40 s before the channel's: the break ends with the first of the two.
        val late = LogoTrack(300, width, height)
        for (i in 0 until 300) late.add(i, picture(40 + i % 100, logo = i !in 100 until 190, upper = if (i in 100 until 200) 0 else 60))
        assertEquals(listOf(400_000L..760_000L), late.breaks(0, 4_000).ranges())
    }

    @Test
    fun `the show's own logo counts in a recording read in two passes`() {
        // Strips of 25: adverts in pictures 100 to 199, and four minutes too bright for the channel's
        // logo from 300, the show's own still there.
        val track = LogoTrack(600, width, height)
        fun give(strip: Int) = (strip * 25 until strip * 25 + 25).forEach { i ->
            track.add(i, picture(40 + i % 100, logo = i !in 100 until 200, upper = if (i in 100 until 200 || i in 300 until 360) 0 else 60))
        }
        (0 until 24 step 2).forEach(::give)
        val gone = track.gone()!!
        assertEquals(setOf(4, 6, 12, 14), (0 until 24).filter { strip -> (strip * 25 until strip * 25 + 25).any { gone[it] } }.toSet())
        listOf(3, 5, 7, 11, 13, 15).forEach(::give)
        assertEquals(listOf(400_000L..800_000L), track.breaks(0, 4_000).ranges())
    }

    @Test
    fun `the logo learnt carries the show's own when it stands out enough`() {
        val both = LogoTrack(60, width, height)
        for (i in 0 until 60) both.add(i, picture(40 + i, logo = true, upper = 60))
        val lower = both.logo()!!.lower!!
        assertTrue(lower.score(picture(70, logo = true)) > 0.9 * lower.usual)
        assertTrue(lower.score(picture(70, logo = false)) < 0.1 * lower.usual)
        // Too faint a one is left out, and what the channel's logo says stands.
        val faint = LogoTrack(260, width, height)
        for (i in 0 until 260) faint.add(i, picture(40 + i % 100, logo = true, upper = if (i in 100 until 160) 0 else 60, faint = 20))
        assertEquals(null, faint.logo()!!.lower)
        assertEquals(listOf(400_000L..640_000L), faint.breaks(0, 4_000).ranges())
        // As is one that stands out only as much as the channel's must, as a band of text along the bottom.
        val band = LogoTrack(260, width, height)
        for (i in 0 until 260) band.add(i, picture(40 + i % 100, logo = true, upper = if (i in 100 until 160) 0 else 60, faint = 26))
        assertEquals(null, band.logo()!!.lower)
        assertEquals(listOf(400_000L..640_000L), band.breaks(0, 4_000).ranges())
    }

    @Test
    fun `a short piece held to either end of a break by the bridge is given up`() {
        // The logo back five pictures, then hidden eight: a bright scene after the break, not part of it.
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 90 to true, 5 to false, 8 to true, 20 to false), 0, 4_000))
        // The same before it, and at both ends, and two of them in a row.
        assertEquals(listOf(132_000L..492_000L), logoRanges(gone(20 to false, 8 to true, 5 to false, 90 to true, 20 to false), 0, 4_000))
        assertEquals(listOf(132_000L..492_000L), logoRanges(gone(20 to false, 8 to true, 5 to false, 90 to true, 5 to false, 9 to true, 20 to false), 0, 4_000))
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 90 to true, 5 to false, 6 to true, 4 to false, 6 to true, 20 to false), 0, 4_000))
        // Forty seconds and more is adverts again.
        assertEquals(listOf(80_000L..500_000L), logoRanges(gone(20 to false, 90 to true, 5 to false, 10 to true, 20 to false), 0, 4_000))
        // Of short pieces only, the first is kept: something is left of every break found.
        assertEquals(listOf(80_000L..116_000L), logoRanges(gone(20 to false, 9 to true, 5 to false, 9 to true, 5 to false, 9 to true, 5 to false, 9 to true, 20 to false), 0, 4_000))
        // With a second logo, it is what is left without it that gives up its short end.
        val second = gone(20 to true, 90 to true, 5 to true, 8 to true, 20 to true)
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 103 to true, 20 to false), 0, 4_000, second.also { for (i in 110 until 115) it[i] = false }))
    }

    @Test
    fun `a break is narrowed to the pictures without the second logo`() {
        val gone = BooleanArray(130) { it in 20 until 110 }
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone, 0, 4_000))
        assertEquals(listOf(80_000L..320_000L), logoRanges(gone, 0, 4_000, BooleanArray(130) { it in 20 until 80 }))
        // Too little of it left for a break.
        assertEquals(emptyList<LongRange>(), logoRanges(gone, 0, 4_000, BooleanArray(130) { it in 20 until 50 }))
        // What the second logo misses outside a break of the first makes none.
        assertEquals(emptyList<LongRange>(), logoRanges(BooleanArray(130), 0, 4_000, gone))
    }

    @Test
    fun `an advert with its brand in the channel's corner does not part a break`() {
        // The channel's logo seen 36 s mid-break, the show's gone the while: two breaks made one.
        val upper = gone(20 to false, 40 to true, 9 to false, 40 to true, 20 to false)
        val lower = gone(20 to false, 89 to true, 20 to false)
        assertEquals(listOf(80_000L..436_000L), logoRanges(upper, 0, 4_000, lower))
        assertEquals(listOf(false), BreakGuess.logoBreaks(upper, 0, 4_000, lower).map { it.sure })
        // The show's seen with it: the programme, between two breaks.
        val programme = gone(20 to false, 40 to true, 9 to false, 40 to true, 20 to false)
        assertEquals(listOf(80_000L..240_000L, 276_000L..436_000L), logoRanges(upper, 0, 4_000, programme))
        // Nor into a break longer than one may be: the two are left as they are.
        val long = gone(20 to false, 150 to true, 9 to false, 150 to true, 20 to false)
        val longLower = gone(20 to false, 309 to true, 20 to false)
        assertEquals(listOf(80_000L..680_000L, 716_000L..1_316_000L), logoRanges(long, 0, 4_000, longLower))
    }

    @Test
    fun `a channel's logo kept from before is told still seen or not`() {
        val track = LogoTrack(60, width, height)
        for (i in 0 until 60) track.add(i, picture(40 + i, logo = false, upper = 60))
        val logo = track.logo()!!
        assertTrue(logo.channel)
        assertTrue(logo.seenIn(List(10) { picture(70 + it, logo = false, upper = 60) }, 0.4))
        assertFalse(logo.seenIn(List(10) { picture(70 + it, logo = false) }, 0.4))
        // Seen on half the pictures, the others adverts.
        assertTrue(logo.seenIn(List(10) { picture(70 + it, logo = false, upper = if (it % 2 == 0) 60 else 0) }, 0.4))
    }

    @Test
    fun `a last piece of adverts parted by an advert's brand stays part of the break`() {
        // The channel's logo seen three pictures near the end, the show's gone the while: an advert's
        // brand, not the programme back; the last eight pictures are still adverts.
        val upper = gone(20 to false, 60 to true, 3 to false, 8 to true, 20 to false)
        assertEquals(listOf(80_000L..364_000L), logoRanges(upper, 0, 4_000, gone(20 to false, 71 to true, 20 to false)))
        assertEquals(listOf(false), BreakGuess.logoBreaks(upper, 0, 4_000, gone(20 to false, 71 to true, 20 to false)).map { it.sure })
        // Both logos seen: the programme back for a moment, and the short piece after it given up.
        val back = gone(20 to false, 60 to true, 3 to false, 8 to true, 20 to false)
        assertEquals(listOf(80_000L..320_000L), logoRanges(upper, 0, 4_000, back))
        // The show's logo matched for a picture now and then, the channel's gone: still adverts.
        val noise = gone(20 to false, 103 to true, 20 to false).also { for (i in listOf(110, 112, 114)) it[i] = false }
        assertEquals(listOf(80_000L..492_000L), logoRanges(gone(20 to false, 103 to true, 20 to false), 0, 4_000, noise))
    }

    @Test
    fun `a trailer carrying the second logo does not part a break`() {
        // The channel's logo gone throughout; its pictogram on a trailer in the middle, 32 s of it.
        val upper = gone(20 to false, 82 to true, 20 to false)
        val lower = gone(20 to false, 37 to true, 8 to false, 37 to true, 20 to false)
        assertEquals(listOf(80_000L..408_000L), logoRanges(upper, 0, 4_000, lower))
        assertEquals(listOf(false), BreakGuess.logoBreaks(upper, 0, 4_000, lower).map { it.sure })
        // Shown longer than a trailer is, it is the programme: two parts, each too short to be a break.
        val longer = gone(20 to false, 37 to true, 12 to false, 37 to true, 20 to false)
        assertEquals(emptyList<LongRange>(), logoRanges(gone(20 to false, 86 to true, 20 to false), 0, 4_000, longer))
    }

    @Test
    fun `adverts that keep the channel's logo seen through half a break are found by the show's`() {
        // The channel's logo kept seen by the adverts' own in its corner: pieces too short to count.
        val pieces = (0 until 6).flatMap { listOf(9 to true) + if (it < 5) listOf(8 to false) else emptyList() }
        val upper = gone(20 to false, *pieces.toTypedArray(), 20 to false)
        val lower = gone(20 to false, 94 to true, 20 to false)
        assertEquals(emptyList<LongRange>(), logoRanges(upper, 0, 4_000))
        assertEquals(listOf(80_000L..456_000L), logoRanges(upper, 0, 4_000, lower))
        assertEquals(listOf(false), BreakGuess.logoBreaks(upper, 0, 4_000, lower).map { it.sure })
        // The channel's logo there through most of it: the show's alone is no break.
        val mostly = gone(20 to false, *(0 until 6).flatMap { listOf(5 to true, 12 to false) }.toTypedArray(), 32 to false)
        assertEquals(emptyList<LongRange>(), logoRanges(mostly, 0, 4_000, gone(20 to false, 102 to true, 32 to false)))
        // Nor when the programme after it does not plainly show the channel's: it may be hidden there too.
        val hidden = gone(20 to false, *pieces.toTypedArray(), 8 to false, 6 to true, 6 to false)
        assertEquals(emptyList<LongRange>(), logoRanges(hidden, 0, 4_000, gone(20 to false, 94 to true, 20 to false)))
        // A scene of the programme without either logo, held to an end by the bridge, is given up.
        val after = gone(20 to false, *pieces.toTypedArray(), 7 to false, 9 to true, 30 to false)
        assertEquals(listOf(80_000L..456_000L), logoRanges(after, 0, 4_000, gone(20 to false, 94 to true, 7 to false, 9 to true, 30 to false)))
        val before = gone(30 to false, 5 to true, 7 to false, *pieces.toTypedArray(), 30 to false)
        assertEquals(listOf(168_000L..544_000L), logoRanges(before, 0, 4_000, gone(30 to false, 5 to true, 7 to false, 94 to true, 30 to false)))
    }

    @Test
    fun `the corner found first is kept as more pictures are given`() {
        // A faint upper logo, just strong enough in every other strip of 25: with the strips of adverts
        // read next it no longer is, and the show's own, back a minute late, would be taken instead.
        val track = LogoTrack(600, width, height)
        fun give(strip: Int) = (strip * 25 until strip * 25 + 25).forEach { i ->
            val adverts = i in 100 until 200
            track.add(i, picture(40 + i % 100, logo = i < 100 || i >= 215, upper = if (adverts) 0 else if (i % 8 < 5) 26 else 22))
        }
        (0 until 24 step 2).forEach(::give)
        val gone = track.gone()!!
        assertEquals(setOf(4, 6), (0 until 24).filter { strip -> (strip * 25 until strip * 25 + 25).any { gone[it] } }.toSet())
        listOf(3, 5, 7).forEach(::give)
        assertEquals(listOf(400_000L..800_000L), track.breaks(0, 4_000).ranges())
    }

    @Test
    fun `too short a stretch, or one at the end of the recording, is no break`() {
        assertEquals(emptyList<LongRange>(), track(100, 20, 100).breaks(0, 4_000).ranges())
        assertEquals(emptyList<LongRange>(), track(200, 90, 0).breaks(0, 4_000).ranges())
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
        assertEquals(listOf(1_000_080_000L..1_000_440_000L), logoRanges(flags(20, 90, 20), 1_000_000_000L, 4_000))
        // Still going on, or begun before what is known: not yet.
        assertEquals(emptyList<LongRange>(), logoRanges(flags(20, 90, 5), 1_000_000_000L, 4_000))
        assertEquals(emptyList<LongRange>(), logoRanges(flags(5, 90, 20), 1_000_000_000L, 4_000))
    }

    private fun List<Guess>?.ranges() = this?.map { it.range }

    // The breaks [BreakGuess.logoBreaks] finds, without whether each is sure.
    private fun logoRanges(gone: BooleanArray, firstMs: Long, pictureMs: Long, lowerGone: BooleanArray? = null) =
        BreakGuess.logoBreaks(gone, firstMs, pictureMs, lowerGone).map { it.range }

    // Pictures from stretches given as a number of them and whether they are without the logo.
    @Test
    fun `a brand parts no break even where a part beyond it is short`() {
        val lower = gone(20 to false, 79 to true, 20 to false)
        assertEquals(listOf(80_000L..396_000L), logoRanges(gone(20 to false, 40 to true, 9 to false, 30 to true, 20 to false), 0, 4_000, lower))
        assertEquals(listOf(80_000L..396_000L), logoRanges(gone(20 to false, 30 to true, 9 to false, 40 to true, 20 to false), 0, 4_000, lower))
        // A trailer between two brands.
        val upper = gone(20 to false, 40 to true, 8 to false, 9 to true, 8 to false, 40 to true, 20 to false)
        val show = gone(20 to false, 40 to true, 8 to true, 9 to false, 8 to true, 40 to true, 20 to false)
        assertEquals(listOf(80_000L..500_000L), logoRanges(upper, 0, 4_000, show))
        assertEquals(listOf(false), BreakGuess.logoBreaks(upper, 0, 4_000, show).map { it.sure })
        // A new programme whose show's logo is not the one learnt looks like a brand: a short scene of
        // it without the channel's logo is no part of the break before.
        assertEquals(listOf(80_000L..320_000L), logoRanges(gone(20 to false, 60 to true, 10 to false, 6 to true, 40 to false), 0, 4_000, gone(20 to false, 116 to true)))
        // Joined, no break, as too near what is known: the part that is one by itself stays.
        assertEquals(listOf(124_000L..284_000L), logoRanges(gone(10 to false, 12 to true, 9 to false, 40 to true, 20 to false), 0, 4_000, gone(10 to false, 61 to true, 20 to false)))
    }

    private fun gone(vararg stretches: Pair<Int, Boolean>) = stretches.flatMap { (count, gone) -> List(count) { gone } }.toBooleanArray()

    @Test
    fun `a few pictures without the logo near a break neither end it late nor undo it`() {
        // Three pictures of a bright scene, five or ten pictures after the adverts.
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 90 to true, 5 to false, 3 to true, 30 to false), 0, 4_000))
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 90 to true, 10 to false, 3 to true, 30 to false), 0, 4_000))
    }

    @Test
    fun `a badge over the logo a minute after the adverts is not more of them`() {
        // The programme is back for 12 pictures, then has a badge over its logo for 11.
        assertEquals(listOf(80_000L..440_000L), logoRanges(gone(20 to false, 90 to true, 12 to false, 11 to true, 30 to false), 0, 4_000))
    }

    @Test
    fun `an advert with something in the logo's corner is bridged when short, and splits the break when long`() {
        assertEquals(listOf(80_000L..460_000L), logoRanges(gone(20 to false, 30 to true, 7 to false, 58 to true, 30 to false), 0, 4_000))
        // Nine pictures of it: the first part is too short to be offered, the second is.
        assertEquals(listOf(236_000L..448_000L), logoRanges(gone(20 to false, 30 to true, 9 to false, 53 to true, 30 to false), 0, 4_000))
    }

    // Glances of pictures each unlike the others, but for those at [same], all of one card.
    private fun glances(count: Int, same: Set<Int> = emptySet(), card: ByteArray = noise(9_999)): Array<ByteArray?> =
        Array(count) { if (it in same) card else noise(it) }

    private fun noise(seed: Int): ByteArray {
        val random = java.util.Random(seed.toLong())
        return ByteArray(BreakGuess.GLANCE_WIDE * BreakGuess.GLANCE_HIGH) { (20 + random.nextInt(200)).toByte() }
    }

    @Test
    fun `a glance tells the same picture again, and a card of one colour but not a black one`() {
        val luma = ByteArray(240 * 135) { p -> (p % 240 + p / 240).toByte() }
        val glance = BreakGuess.glance(luma, 240, 135)
        assertEquals(BreakGuess.GLANCE_WIDE * BreakGuess.GLANCE_HIGH, glance.size)
        assertTrue(BreakGuess.alike(glance, BreakGuess.glance(luma.copyOf(), 240, 135)))
        assertFalse(BreakGuess.alike(noise(1), noise(2)))
        val red = ByteArray(450) { 76 }
        assertTrue(BreakGuess.alike(red, ByteArray(450) { 78 }))
        assertFalse(BreakGuess.alike(red, ByteArray(450) { 90 }))
        assertFalse(BreakGuess.alike(red, noise(3)))
        assertFalse(BreakGuess.alike(ByteArray(450) { 16 }, ByteArray(450) { 16 }))
    }

    @Test
    fun `a break opened and closed by the same card is sure, drawn in to the card`() {
        // Something bridged in it: not sure by the logo.
        val bridged = gone(20 to false, 30 to true, 7 to false, 58 to true, 30 to false)
        assertEquals(listOf(Guess(80_000L..460_000L)), BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145)))
        // The card at its first picture and near its end, in it.
        val carded = BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145, setOf(20, 113)))
        assertEquals(listOf(Guess(80_000L..456_000L, sure = true)), carded)
        // Only one edge anchored.
        assertEquals(listOf(false), BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145, setOf(20, 60))).map { it.sure })
        // The same picture before and after it, outside it: the programme on both sides says nothing.
        assertEquals(listOf(false), BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145, setOf(15, 118))).map { it.sure })
        // Black pictures, as any fade gives.
        val black = ByteArray(450) { 16 }
        assertEquals(listOf(false), BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145, setOf(20, 113), black)).map { it.sure })
        // Without glances, or a picture of the pair not read.
        assertEquals(listOf(false), BreakGuess.logoBreaks(bridged, 0, 4_000).map { it.sure })
        assertEquals(listOf(false), BreakGuess.logoBreaks(bridged, 0, 4_000, glances = glances(145, setOf(20, 113)).also { it[113] = null }).map { it.sure })
    }

    @Test
    fun `an anchored break with the channel's logo seen long in it is not sure`() {
        // Twelve pictures of the channel's logo, the show's gone the while, part it: joined as a brand.
        val upper = gone(20 to false, 40 to true, 12 to false, 40 to true, 20 to false)
        val lower = gone(20 to false, 92 to true, 20 to false)
        val guesses = BreakGuess.logoBreaks(upper, 0, 4_000, lower, glances(132, setOf(20, 110)))
        assertEquals(listOf(80_000L..448_000L), guesses.map { it.range })
        assertEquals(listOf(false), guesses.map { it.sure })
    }

    @Test
    fun `pictures with nothing that stays say nothing`() {
        val track = LogoTrack(120, width, height)
        for (i in 0 until 120) track.add(i, picture(40 + i, logo = false))
        assertNull(track.breaks(0, 4_000).ranges())
    }
}
