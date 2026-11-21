// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.model.ProgramWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers how a programme known only by its planned start gets the real one from the guide. */
class LiveProgrammeTest {

    private val min = 60_000L

    // As seen on France 3: planned at 12:15, on air at 12:22:20, the guide put right at about 12:25.
    private val planned = ProgramWindow(735 * min, 755 * min)
    private val real = ProgramWindow(742 * min + 20_000L, 775 * min)
    private val before = ProgramWindow(700 * min + 9_000L, 742 * min + 20_000L)

    @Test
    fun `a start on the minute is the planned one for a while`() {
        assertTrue(LiveProgramme.planned(planned, 736 * min))
        assertFalse(LiveProgramme.planned(planned, 751 * min))
        assertFalse(LiveProgramme.planned(real, 744 * min))
    }

    @Test
    fun `the real start replaces the planned one once the guide has it`() = runBlocking {
        var guide = planned
        var asked = 0
        val programme = LiveProgramme(planned) { asked++; guide }
        assertEquals(planned, programme.atPlayhead(736 * min, 736 * min))
        // Not asked again within the minute.
        assertEquals(planned, programme.atPlayhead(736 * min + 30_000L, 736 * min + 30_000L))
        assertEquals(1, asked)
        guide = real
        assertEquals(real, programme.atPlayhead(745 * min, 745 * min))
        // No longer planned: left alone.
        assertEquals(real, programme.atPlayhead(747 * min, 747 * min))
        assertEquals(2, asked)
    }

    @Test
    fun `the show before, still on, replaces a planned start already passed`() = runBlocking {
        val programme = LiveProgramme(planned) { before }
        assertEquals(before, programme.atPlayhead(738 * min, 738 * min))
    }

    @Test
    fun `asking about a planned start does not hold back the next show`() = runBlocking {
        // A short programme, the weather say, planned from 12:30 to 12:35.
        val weather = ProgramWindow(750 * min, 755 * min)
        val next = ProgramWindow(755 * min, 800 * min)
        var asked = 0
        val programme = LiveProgramme(weather) { at -> asked++; if (at >= 755 * min) next else weather }
        assertEquals(weather, programme.atPlayhead(754 * min + 50_000L, 754 * min + 50_000L))
        // Ten seconds on, past the planned end: the guide is asked for what follows at once.
        assertEquals(next, programme.atPlayhead(755 * min, 755 * min))
        assertEquals(2, asked)
    }

    @Test
    fun `an exact start is not asked about`() = runBlocking {
        var asked = 0
        val programme = LiveProgramme(real) { asked++; null }
        assertEquals(real, programme.atPlayhead(744 * min, 744 * min))
        assertEquals(0, asked)
    }
}
