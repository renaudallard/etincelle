// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UpNext
import it.allard.etincelle.core.model.withUpNext
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    private val upNext = UpNext(167, 9, null, null, "Commence dans %s", "À suivre", "Regarder le générique", "VOD_2")
    private val episode = PlaybackSource("https://x/m.mpd", DrmSpec.None, isLive = false, resumeKey = "VOD_1")

    @Test
    fun episodeIsOverOnceItsSlateIsDue() {
        val withNext = episode.copy(upNext = upNext)
        assertEquals(120_000L, PlaybackProgress.positionToSave(withNext, 120_000, 200_000))
        assertEquals(0L, PlaybackProgress.positionToSave(withNext, 167_000, 200_000))
        assertEquals(0L, PlaybackProgress.positionToSave(withNext, 176_000, 200_000))
        // Without a slate the usual rule applies: 176 s of 200 s is still worth resuming.
        assertEquals(176_000L, PlaybackProgress.positionToSave(episode, 176_000, 200_000))
    }

    @Test
    fun sampledPositionKeepsTheResumePointWhileLoading() {
        val withNext = episode.copy(upNext = upNext)
        // A load under way (position 0 before its seek) leaves the saved point alone.
        assertEquals(null, PlaybackProgress.sampledToSave(withNext, 0, 200_000))
        assertEquals(null, PlaybackProgress.sampledToSave(withNext, 9_999, 200_000))
        assertEquals(120_000L, PlaybackProgress.sampledToSave(withNext, 120_000, 200_000))
        // Into the credits or near the end, the episode starts over next time.
        assertEquals(0L, PlaybackProgress.sampledToSave(withNext, 170_000, 200_000))
        assertEquals(0L, PlaybackProgress.sampledToSave(episode, 190_000, 200_000))
        // Without a duration yet, nothing is known about the end: keep the saved point.
        assertEquals(null, PlaybackProgress.sampledToSave(episode, 120_000, -1))
    }

    @Test
    fun aPlayerClosedBeforeItsStreamLoadedKeepsTheResumePoint() {
        // The position is the one it was to resume at, and the duration is not known yet.
        assertEquals(null, PlaybackProgress.closingToSave(episode, 120_000, -1))
        assertEquals(null, PlaybackProgress.closingToSave(episode, 120_000, 0))
        // Loaded, it follows the usual rule.
        assertEquals(120_000L, PlaybackProgress.closingToSave(episode, 120_000, 200_000))
        assertEquals(0L, PlaybackProgress.closingToSave(episode, 190_000, 200_000))
        assertEquals(0L, PlaybackProgress.closingToSave(episode, 5_000, 200_000))
    }

    @Test
    fun resumePointPastTheSlateStartsOver() {
        assertEquals(0L, episode.copy(startPositionMs = 176_000).withUpNext(upNext).startPositionMs)
        assertEquals(60_000L, episode.copy(startPositionMs = 60_000).withUpNext(upNext).startPositionMs)
        assertEquals(176_000L, episode.copy(startPositionMs = 176_000).withUpNext(null).startPositionMs)
    }
}
