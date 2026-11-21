// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A programme page names the stream its player plays; for a recording that is the DVR one. */
class RecordingPlaybackTest {
    private val adapter = Moshi.Builder().build().adapter(PageResponse::class.java)

    private fun detail(playUrl: String) = adapter.fromJson(
        """{"content":{"metadata":{"id":"m","title":{"text":"Jack Hunter"}},
          "player":{"actions":{"on_display":[{"type":"play","is_blocking":true,
            "endpoint":{"method":"GET","url":"$playUrl"}}]}}}}""",
    )!!.toProgramDetail(channelId = null, vodId = "LIVE_257252", isLive = false)

    @Test
    fun aRecordedProgrammePlaysFromTheDvr() {
        assertEquals("LIVE_257252", detail("https://api-eu.fubo.tv/vapi/asset/v1?id=LIVE_257252&type=dvr").recordingAssetId)
    }

    @Test
    fun catchUpAndLivePagesAreNoRecordings() {
        assertNull(detail("https://api-eu.fubo.tv/vapi/asset/v1?id=VOD_698359&type=vod").recordingAssetId)
        assertNull(detail("https://api-eu.fubo.tv/vapi/asset/v1?channelId=600021&type=live").recordingAssetId)
    }

    @Test
    fun aPageWithoutAPlayerIsNoRecording() {
        val page = adapter.fromJson("""{"content":{"metadata":{"id":"m","title":{"text":"T"}}}}""")!!
        assertNull(page.toProgramDetail(channelId = null, vodId = "LIVE_1", isLive = false).recordingAssetId)
    }
}
