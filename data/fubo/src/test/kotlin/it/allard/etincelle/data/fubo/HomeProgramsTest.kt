// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.allard.etincelle.core.model.HomeProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Android TV home feeds (shapes seen live on /continue-watching/topshelf/v1 and the recommendations). */
class HomeProgramsTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(TopshelfResponse::class.java)

    private val json = """
        {"response":[
          {"type":"programWithAssets","data":{
            "program":{"programId":"8120878_627","title":"Comme un oiseau sans aile","heading":"Alex Hugo","subheading":"S1, EP1",
              "shortDescription":"Des coups de fusil…","horizontalImage":"https://img/h.jpg","verticalImage":"https://img/v.jpg","type":"episode"},
            "assets":[{"assetId":"VOD_641571","type":"vod","playbackDurationSec":5304,
              "userProperties":{"lastOffset":2668,"lastOffsetTimeStamp":"2026-11-14T17:35:14Z"}}]}},
          {"type":"programWithAssets","data":{
            "program":{"programId":"12122055_136","title":"Journal","heading":"Journal","type":"news"},
            "assets":[{"assetId":"LIVE_4833527","type":"stream","playbackDurationSec":10800,
              "accessRights":{"startTime":"2026-11-15T05:00:00Z","endTime":"2026-11-15T08:00:00Z"}}]}},
          {"type":"programWithAssets","data":{"program":{"title":"Sans asset"},"assets":[]}}
        ],"metadata":null}
    """.trimIndent()

    @Test
    fun feedEntriesBecomeHomePrograms() {
        val (episode, live) = adapter.fromJson(json)!!.toHomePrograms()
        assertEquals("VOD_641571", episode.assetId)
        assertEquals("Alex Hugo", episode.title)
        assertEquals("Comme un oiseau sans aile", episode.episodeTitle)
        assertEquals(HomeProgram.Kind.EPISODE, episode.kind)
        assertEquals(5304L, episode.durationSec)
        assertEquals(2668L, episode.positionSec)
        assertEquals(1_794_677_714_000L, episode.watchedAtMs)
        assertNull(episode.liveStartMs)

        assertEquals("LIVE_4833527", live.assetId)
        // A heading equal to the title is not repeated as an episode title.
        assertNull(live.episodeTitle)
        assertEquals(HomeProgram.Kind.OTHER, live.kind)
        assertNull(live.positionSec)
        assertEquals(1_794_718_800_000L, live.liveStartMs)
    }

    @Test
    fun entryWithoutAssetIsDropped() {
        assertEquals(2, adapter.fromJson(json)!!.toHomePrograms().size)
    }
}
