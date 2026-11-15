// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device guide feed (/device-integrations/epg, shape seen live) mapped for the system's TV guide. */
class LiveChannelsTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(EpgResponse::class.java)

    private val json = """
        {"response":[
          {"type":"channelWithProgramAssets","data":{
            "channel":{"id":"600046","name":"TF1","displayName":"TF1","logoOnDarkUrl":"https://logo/tf1.png"},
            "programsWithAssets":[
              {"program":{"programId":"1_1","title":"Le journal","heading":"Le journal","shortDescription":"Info",
                "horizontalImage":"https://img/j.jpg"},
               "assets":[{"assetId":"LIVE_1","accessRights":{"startTime":"2026-11-15T19:00:00Z","endTime":"2026-11-15T19:45:00Z"}}]},
              {"program":{"programId":"1_2","title":"Épisode 3","heading":"Une série"},
               "assets":[{"assetId":"LIVE_2","accessRights":{"startTime":"2026-11-15T19:45:00Z","endTime":"2026-11-15T20:40:00Z"}}]},
              {"program":{"programId":"1_3","title":"Sans horaire"},"assets":[{"assetId":"LIVE_3","accessRights":{}}]}
            ]}},
          {"type":"channelWithProgramAssets","data":{
            "channel":{"id":"602399","name":"KITCHEN MANIA"},"programsWithAssets":[]}}
        ],"metadata":null}
    """.trimIndent()

    @Test
    fun channelsCarryTheirTimedProgrammes() {
        val (tf1, km) = adapter.fromJson(json)!!.toLiveChannels()
        assertEquals("600046", tf1.id)
        assertEquals("TF1", tf1.name)
        assertEquals("https://logo/tf1.png", tf1.logoUrl)
        // The programme without air times is left out.
        assertEquals(listOf("LIVE_1", "LIVE_2"), tf1.programs.map { it.id })
        assertEquals("Le journal", tf1.programs[0].title)
        assertNull(tf1.programs[0].episodeTitle)
        assertEquals("Une série", tf1.programs[1].title)
        assertEquals("Épisode 3", tf1.programs[1].episodeTitle)
        assertEquals(45 * 60 * 1000L, tf1.programs[0].endMs - tf1.programs[0].startMs)
        // A channel with nothing scheduled is still a channel.
        assertEquals("KITCHEN MANIA", km.name)
        assertTrue(km.programs.isEmpty())
    }
}
