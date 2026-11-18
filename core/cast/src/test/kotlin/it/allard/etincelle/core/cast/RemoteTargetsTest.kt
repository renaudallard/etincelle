// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.remote.RemoteMessages
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTargetsTest {
    private val source = PlaybackSource(manifestUrl = "https://example.invalid/a.mpd", drm = DrmSpec.None, isLive = false)

    @Test
    fun aChannelAReplayAndARecordingGoByTheirOwnId() {
        assertEquals(listOf(RemoteMessages.KIND_LIVE to "12"), source.copy(isLive = true, originChannelId = "12").remoteTargets())
        assertEquals(listOf(RemoteMessages.KIND_VOD to "VOD_1"), source.copy(originVodId = "VOD_1").remoteTargets())
        assertEquals(listOf(RemoteMessages.KIND_RECORDING to "LIVE_1"), source.copy(originRecordingAssetId = "LIVE_1").remoteTargets())
    }

    @Test
    fun aRecordingPlayedFromItsPageIsAskedForByThatPage() {
        val targets = source.copy(originRecordingAssetId = "LIVE_1", originPageVodId = "VOD_9").remoteTargets()
        // Asked for as the page, and known again when the TV names the recording.
        assertEquals(RemoteMessages.KIND_VOD to "VOD_9", targets.first())
        assertEquals(true, (RemoteMessages.KIND_RECORDING to "LIVE_1") in targets)
    }

    @Test
    fun whatHasNoIdIsNothingToAskFor() {
        assertEquals(emptyList<Pair<String, String>>(), source.remoteTargets())
    }
}
