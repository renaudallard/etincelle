// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.player.AdBreaks.Mark
import it.allard.etincelle.core.player.AdBreaks.Stretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        // Short periods alone are no break: a 90 s one every ten minutes, as on RMC Mécanic.
        val unshaped = listOf(plain(0, 10 * minute), plain(10 * minute, 11 * minute + 30_000), plain(11 * minute + 30_000, null))
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(unshaped))

        // One unbroken period, as on the M6 channels and in every recording.
        assertEquals(emptyList<LongRange>(), AdBreaks.breaks(listOf(plain(0, null))))
    }

    @Test
    fun `a live guess ends before the logo is back, a sure one less early`() {
        assertEquals(268_000L, AdBreaks.liveEnd(Guess(0L..300_000L)))
        assertEquals(276_000L, AdBreaks.liveEnd(Guess(0L..300_000L, sure = true)))
    }
}
