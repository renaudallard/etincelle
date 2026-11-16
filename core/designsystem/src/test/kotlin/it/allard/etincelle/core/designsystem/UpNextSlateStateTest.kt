// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import it.allard.etincelle.core.model.UpNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextSlateStateTest {
    // The slate is due at 167 s and counts down 9 s; the episode lasts 200 s.
    private val upNext = UpNext(167, 9, null, null, "Commence dans %s", "À suivre", "Regarder le générique", "VOD_2")

    @Test
    fun countdownFiresOnceWhenItRunsOut() {
        val state = UpNextSlateState()
        assertFalse(state.tick(upNext, 100_000, 200_000))
        assertNull(state.left)
        assertFalse(state.tick(upNext, 170_000, 200_000))
        assertEquals(6L, state.left)
        assertTrue(state.tick(upNext, 176_000, 200_000))
        assertFalse(state.tick(upNext, 177_000, 200_000))
    }

    @Test
    fun previousEpisodePositionDoesNotSkipThisOne() {
        val state = UpNextSlateState()
        // Just after the switch, the player still reports the end of the previous episode.
        assertFalse(state.tick(upNext, 198_000, 200_000))
        assertNull(state.left)
        // Then this episode starts, and its countdown runs as usual.
        assertFalse(state.tick(upNext, 1_000, 200_000))
        assertFalse(state.tick(upNext, 170_000, 200_000))
        assertTrue(state.tick(upNext, 176_000, 200_000))
    }

    @Test
    fun creditsLetTheEpisodeEnd() {
        val state = UpNextSlateState()
        state.tick(upNext, 1_000, 200_000)
        state.tick(upNext, 172_000, 200_000)
        state.hidden = true
        assertFalse(state.tick(upNext, 176_000, 200_000))
        assertFalse(state.tick(upNext, 199_000, 200_000))
        // A seek back before the slate brings it back.
        state.tick(upNext, 100_000, 200_000)
        assertFalse(state.hidden)
    }

    @Test
    fun slatesFollowTheEpisode() {
        val slates = UpNextSlates()
        assertNull(slates.shown(upNext))
        val first = slates.of(upNext)
        assertSame(first, slates.of(upNext.copy()))
        assertSame(first, slates.shown(upNext))
        val next = upNext.copy(nextVodId = "VOD_3")
        assertNull(slates.shown(next))
        assertNotSame(first, slates.of(next))
        assertNull(slates.shown(upNext))
    }
}
