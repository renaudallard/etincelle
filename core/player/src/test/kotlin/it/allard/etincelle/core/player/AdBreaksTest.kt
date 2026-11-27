// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.player.AdBreaks.Mark
import it.allard.etincelle.core.player.AdBreaks.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBreaksTest {

    // Two events as TF1's manifest carried them on 2026-11-19: the start of a placement opportunity
    // of 197 s, and the end of it.
    private fun event(binary: String) =
        """<Signal xmlns="http://www.scte.org/schemas/35/2016"><Binary>$binary</Binary></Signal>""".toByteArray()

    private val start = event("/DBEAAAAAAAAAP/wBQb+EqRYAAAuAixDVUVJAPYi03//AAEOe8AMFlRWU1Q6dGYxXzIwMjYxMTE4XzIxMTE0AQEAAFWbAFg=")
    private val end = event("/DAnAAAAAAAAAP/wBQb+E7LTwAARAg9DVUVJAPYi03+/AAA1AQGZ+f30")

    @Test
    fun `tells the start of a break from its end`() {
        assertEquals(true, AdBreaks.opens(start))
        assertEquals(false, AdBreaks.opens(end))
        assertNull(AdBreaks.opens("<Signal><Binary>not a section at all</Binary></Signal>".toByteArray()))
        assertNull(AdBreaks.opens(ByteArray(0)))
    }

    private val minute = 60_000L

    @Test
    fun `a marked period is a break for as long as it says`() {
        val programme = Stretch(0, 30 * minute, listOf(Mark(0, null, opens = false)), signalled = true)
        val ads = Stretch(30 * minute, 36 * minute, listOf(Mark(30 * minute, 6 * minute, opens = true)), signalled = true)
        val after = Stretch(36 * minute, null, listOf(Mark(36 * minute, null, opens = false)), signalled = true)

        assertEquals(listOf(30 * minute..36 * minute), AdBreaks.breaks(listOf(programme, ads, after)))
    }

    @Test
    fun `two halves with a jingle between them are one break`() {
        val first = Stretch(0, 3 * minute, listOf(Mark(0, 3 * minute, true)), true)
        val jingle = Stretch(3 * minute, 3 * minute + 3_000, listOf(Mark(3 * minute, null, false)), true)
        val second = Stretch(3 * minute + 3_000, 6 * minute, listOf(Mark(3 * minute + 3_000, null, true)), true)
        val programme = Stretch(6 * minute, null, listOf(Mark(6 * minute, null, false)), true)

        assertEquals(listOf(0L..6 * minute), AdBreaks.breaks(listOf(first, jingle, second, programme)))
    }

    @Test
    fun `breaks timed inside one period are found, one still on air is not`() {
        val marks = listOf(Mark(10 * minute, 197_000, true), Mark(40 * minute, null, true))
        val period = Stretch(0, null, marks, signalled = true)

        assertEquals(listOf(10 * minute..10 * minute + 197_000), AdBreaks.breaks(listOf(period)))
    }

    @Test
    fun `a break with no length ends at the mark that closes it`() {
        val marks = listOf(Mark(10 * minute, null, true), Mark(13 * minute, null, false))
        val period = Stretch(0, 60 * minute, marks, signalled = true)

        assertEquals(listOf(10 * minute..13 * minute), AdBreaks.breaks(listOf(period)))
    }

    @Test
    fun `a stream that marks nothing gives its breaks away by their shape`() {
        fun plain(from: Long, to: Long?) = Stretch(from, to, emptyList(), signalled = false)
        // 10 min of programme, 3 min, 5 s, 3 min, then programme: as on RMC Story.
        val shaped = listOf(
            plain(0, 10 * minute),
            plain(10 * minute, 13 * minute),
            plain(13 * minute, 13 * minute + 4_900),
            plain(13 * minute + 4_900, 16 * minute),
            plain(16 * minute, null),
        )
        assertEquals(listOf(10 * minute..16 * minute), AdBreaks.breaks(shaped))

        // A long break of BFMTV at night, 26 November 2026: 362 s, 5 s, 369 s. Halves of eight
        // minutes are too long for one.
        fun halves(first: Long, second: Long) = listOf(
            plain(0, 10 * minute),
            plain(10 * minute, 10 * minute + first),
            plain(10 * minute + first, 10 * minute + first + 5_000),
            plain(10 * minute + first + 5_000, 10 * minute + first + 5_000 + second),
            plain(10 * minute + first + 5_000 + second, null),
        )
        assertEquals(listOf(10 * minute..10 * minute + 736_000), AdBreaks.breaks(halves(362_000, 369_000)))
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(halves(480_000, 369_000)))

        // Short periods alone are no break: a 90 s one every ten minutes, as on RMC Mécanic.
        val unshaped = listOf(plain(0, 10 * minute), plain(10 * minute, 11 * minute + 30_000), plain(11 * minute + 30_000, null))
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(unshaped))

        // One unbroken period, as on the M6 channels and in every recording.
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(listOf(plain(0, null))))
    }

    @Test
    fun `a break in one piece counts on a stream that shows it cuts itself at its breaks`() {
        fun plain(from: Long, to: Long?) = Stretch(from, to, emptyList(), signalled = false)
        val s = 1_000L
        // RMC Story, 26 November 2026 from 08:01:38: a break of 76 s, 5 s and 95 s, then breaks of
        // one period of 183 s and 272 s between programme of 719 s and 1320 s, the last on air.
        val morning = listOf(
            plain(0, 600 * s),
            plain(600 * s, 676 * s),
            plain(676 * s, 681 * s),
            plain(681 * s, 776 * s),
            plain(776 * s, 1495 * s),
            plain(1495 * s, 1678 * s),
            plain(1678 * s, 2998 * s),
            plain(2998 * s, 3270 * s),
            plain(3270 * s, null),
        )
        assertEquals(listOf(600 * s..776 * s, 1495 * s..1678 * s, 2998 * s..3270 * s), AdBreaks.breaks(morning))
        // Without a break with a jingle, nothing says the stream is cut at its breaks.
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(morning.drop(4)))
        // Seven minutes or more is programme, and so is a short stretch beside another short one.
        val long = morning.take(5) + listOf(plain(1495 * s, 1925 * s), plain(1925 * s, 3000 * s), plain(3000 * s, null))
        assertEquals(listOf(600 * s..776 * s), AdBreaks.breaks(long))
        val beside = morning.take(5) + listOf(plain(1495 * s, 1700 * s), plain(1700 * s, 2000 * s), plain(2000 * s, null))
        assertEquals(listOf(600 * s..776 * s), AdBreaks.breaks(beside))
    }

    @Test
    fun `the adverts the ad server puts in a break while it airs are a break`() {
        fun plain(from: Long, to: Long?) = Stretch(from, to, emptyList(), signalled = false)
        fun ad(from: Long, to: Long?) = Stretch(from, to, emptyList(), signalled = false, inserted = true)
        val second = 1_000L
        // BFMTV on 26 November 2026 at 05:15, watched while it aired: six adverts of 30 s and 6 s of
        // filler, the 4 s of jingle as aired, seven adverts more, then the programme.
        val first = (0 until 6).map { ad((600 + 30 * it) * second, (630 + 30 * it) * second) }
        val then = (0 until 7).map { ad((790 + 30 * it) * second, (820 + 30 * it) * second) }
        val aired = listOf(plain(0, 600 * second)) + first + listOf(ad(780 * second, 786 * second), plain(786 * second, 790 * second)) +
            then + listOf(plain(1000 * second, null))
        assertEquals(listOf(600 * second..1000 * second), AdBreaks.breaks(aired))

        // One still on air, the manifest's last period with no length yet, is not one yet.
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(listOf(plain(0, 600 * second), ad(600 * second, null))))

        // Its filler is no jingle: a long advert, filler, then a few minutes of programme are not
        // taken for the two halves of a break.
        val filler = listOf(plain(0, 600 * second), ad(600 * second, 690 * second), ad(690 * second, 696 * second), plain(696 * second, 896 * second), plain(896 * second, null))
        assertEquals(listOf(600 * second..696 * second), AdBreaks.breaks(filler))
    }

    @Test
    fun `a live guess ends before the logo is back, a sure one less early`() {
        assertEquals(268_000L, AdBreaks.liveEnd(Guess(0L..300_000L)))
        assertEquals(276_000L, AdBreaks.liveEnd(Guess(0L..300_000L, sure = true)))
    }

    @Test
    fun `a live guess ends with the blacks of its break when they go further`() {
        val guess = Guess(100_000L..400_000L)
        // Begun before the guess ends and ending after it: the same break.
        assertEquals(500_000L, AdBreaks.liveEnd(guess, listOf(200_000L..500_000L)))
        // Begun before the guess even began.
        assertEquals(500_000L, AdBreaks.liveEnd(guess, listOf(50_000L..500_000L)))
        // Ending before the guess does: no further.
        assertEquals(368_000L, AdBreaks.liveEnd(guess, listOf(200_000L..300_000L)))
        // Begun after the guess ends, where the programme may already be back: another break.
        assertEquals(368_000L, AdBreaks.liveEnd(guess, listOf(380_000L..600_000L)))
        // Before the guess, and none at all.
        assertEquals(368_000L, AdBreaks.liveEnd(guess, listOf(0L..90_000L)))
        assertEquals(368_000L, AdBreaks.liveEnd(guess, emptyList()))
        // A sure guess ends 24 s before the logo is back, not 32 s.
        val sure = Guess(100_000L..400_000L, sure = true)
        assertEquals(376_000L, AdBreaks.liveEnd(sure, listOf(200_000L..370_000L)))
        assertEquals(500_000L, AdBreaks.liveEnd(sure, listOf(370_000L..500_000L)))
    }

    // M6, 27 November 2026, 19:01:52: the logo goes at a sponsor's card after the programme, then come
    // trailers, adverts from 19:02:43, their first black at 19:02:58, a break by the blacks from
    // 19:03:14 to 19:06:36, the channel's card, trailers, and the logo is back at 19:07:52.
    private fun t(h: Int, m: Int, s: Int) = ((h * 60 + m) * 60 + s) * 1_000L
    private val logo = Guess(t(19, 1, 52)..t(19, 7, 52))
    private val blacks = SeparatedBreak(t(19, 3, 14)..t(19, 6, 36), t(19, 2, 58))

    @Test
    fun `a skip asked for in a break by the blacks goes on as far as the logo's break it lies in`() {
        // As pressed before the blacks' break: 32 s before the logo is back.
        assertEquals(t(19, 7, 20), AdBreaks.askedEnd(blacks, listOf(logo)) { AdBreaks.liveEnd(it, listOf(blacks.range)) })
        // A logo's break taken to end before the blacks do takes the skip no further.
        assertEquals(null, AdBreaks.askedEnd(blacks, listOf(Guess(t(19, 1, 52)..t(19, 7, 0)))) { AdBreaks.liveEnd(it) })
        // Nor one that begins after the blacks' break.
        assertEquals(null, AdBreaks.askedEnd(blacks, listOf(Guess(t(19, 6, 40)..t(19, 9, 0)))) { AdBreaks.liveEnd(it) })
    }

    @Test
    fun `a break by the logo is offered from the first black within it`() {
        assertEquals(t(19, 2, 58), AdBreaks.offeredFrom(logo, listOf(blacks)))
        assertEquals(t(19, 1, 52), AdBreaks.offeredFrom(logo, emptyList()))
        // Blacks begun before the logo goes leave it as it is.
        assertEquals(t(19, 1, 52), AdBreaks.offeredFrom(logo, listOf(SeparatedBreak(t(19, 2, 10)..t(19, 6, 36), t(19, 1, 40)))))
    }

    @Test
    fun `live, a break by the blacks is skipped unasked to their end, asked as far as the logo's once reached`() {
        // That break in a window begun at 18:00, its live point at 19:30.
        val start = t(18, 0, 0)
        fun at(h: Int, m: Int, s: Int) = t(h, m, s) - start
        val live = at(19, 30, 0)
        assertNull(AdBreaks.guessedBreak(at(19, 2, 30), start, live, listOf(logo), listOf(blacks)))
        val byLogo = AdBreaks.guessedBreak(at(19, 3, 0), start, live, listOf(logo), listOf(blacks))!!
        assertEquals(at(19, 2, 58), byLogo.startMs)
        assertEquals(at(19, 7, 20), byLogo.endMs)
        val byBlacks = AdBreaks.guessedBreak(at(19, 4, 0), start, live, listOf(logo), listOf(blacks))!!
        assertTrue(byBlacks.separated && byBlacks.skippable)
        assertEquals(at(19, 6, 36), byBlacks.endMs)
        assertEquals(at(19, 7, 20), byBlacks.askedEndMs)
        // The logo's end not yet two seconds behind the live point: no further than the blacks.
        val near = AdBreaks.guessedBreak(at(19, 4, 0), start, at(19, 7, 21), listOf(logo), listOf(blacks))!!
        assertEquals(at(19, 6, 36), near.askedEndMs)
    }

    @Test
    fun `in a recording, a break by the blacks is skipped unasked to their end, asked as far as the logo's`() {
        // Très très bon, S16 E30, as phone B found it on 27 November: the pictures and captions give
        // 1388.3 to 1736.3 s (sure) and 3152.3 to 3716.3 s, the blacks 1443.1 to 1706.9 s from a
        // first black at 1412.6 s and 3251.6 to 3679.5 s from one at 3231.1 s.
        val guesses = listOf(Guess(1_388_300L..1_736_300L, sure = true), Guess(3_152_300L..3_716_300L))
        val separated = listOf(SeparatedBreak(1_443_100L..1_706_900L, 1_412_600L), SeparatedBreak(3_251_600L..3_679_500L, 3_231_100L))
        assertNull(AdBreaks.recordingBreak(3_200_000L, guesses, separated))
        val byLogo = AdBreaks.recordingBreak(3_240_000L, guesses, separated)!!
        assertEquals(3_231_100L, byLogo.startMs)
        assertEquals(3_684_300L, byLogo.endMs)
        val byBlacks = AdBreaks.recordingBreak(3_300_000L, guesses, separated)!!
        assertTrue(byBlacks.separated && byBlacks.sure)
        assertEquals(3_679_500L, byBlacks.endMs)
        assertEquals(3_684_300L, byBlacks.askedEndMs)
        // Where the logo's break is taken to end before the blacks do, both stop at the blacks' end,
        // and so does one pressed in the logo's break before them.
        val first = AdBreaks.recordingBreak(1_500_000L, guesses, separated)!!
        assertEquals(1_706_900L, first.endMs)
        assertEquals(1_706_900L, first.askedEndMs)
        assertEquals(1_706_900L, AdBreaks.recordingBreak(1_420_000L, guesses, separated)!!.endMs)
        // Blacks with no break by the pictures about them make none.
        assertNull(AdBreaks.recordingBreak(2_100_000L, guesses, listOf(SeparatedBreak(2_000_000L..2_200_000L, 1_980_000L))))
    }

    @Test
    fun `a recording's break by its blacks counts only within one by its pictures or captions`() {
        // Très très bon, S16 E30: the blacks give 1443 to 1698 s and 3252 to 3464 s, the pictures
        // and captions 1388 to 1736 s and 3152 to 3716 s.
        val first = SeparatedBreak(1_443_000L..1_698_000L, 1_420_000L)
        val second = SeparatedBreak(3_252_000L..3_464_000L, 3_230_000L)
        val guesses = listOf(Guess(1_388_000L..1_736_000L), Guess(3_152_000L..3_716_000L))
        assertEquals(listOf(first, second), AdBreaks.confirmed(listOf(first, second), guesses))
        // Blacks in a programme, where the logo is on, make no break; nor do they across its return.
        val programme = SeparatedBreak(2_000_000L..2_200_000L, 1_980_000L)
        val across = SeparatedBreak(3_600_000L..3_800_000L, 3_580_000L)
        assertEquals(listOf(first), AdBreaks.confirmed(listOf(first, programme, across), guesses))
        assertEquals(emptyList<SeparatedBreak>(), AdBreaks.confirmed(listOf(first), emptyList()))
    }
}
