// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recordings pages ("Mes vidéos"), shaped as captured in November 2026. */
class MyStuffTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PageResponse::class.java)

    private fun nav(path: String) =
        """{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://api-eu.fubo.tv/papi/v1/$path?trkOp=x"}}]}"""

    @Test
    fun theRecordingsPageIsAGridOfShowsAndRecordings() {
        val page = adapter.fromJson(
            """{"title":{"text":"Mes vidéos"},"content":{"template":"my-stuff","sections":[
              {"component_type":"chip-filter","components":[{"type":"chip-filter","id":"recordings-all","title":{"text":"Tout"},
                "actions":{"on_click":[{"type":"refresh_page","endpoint":{"url":"https://api-eu.fubo.tv/papi/v1/my-stuff/status/recordings/all"}}]}}]},
              {"component_type":"card-poster","components":[
                {"type":"card-poster","id":"f1","picture":{"url":"https://img/f.jpg"},"actions":${nav("my-stuff/folder/seriesId/100001536")},
                 "footer":{"title":{"text":"Cauchemar en cuisine"},"subtitle":{"text":"14 épisodes"}}},
                {"type":"card-poster","id":"p1","picture":{"url":"https://img/p.jpg"},"actions":${nav("program-details/program/LIVE_4442427")},
                 "footer":{"title":{"text":"Grignote-moi"},"subtitle":{"text":"52m • 2026"},"time_label":{"text":"Diffusé: 29 sept."}}}]}]}}""",
        )!!.toPage()

        assertTrue(page.isGrid)
        val cards = page.rails.single().cards
        assertEquals(listOf("Cauchemar en cuisine", "Grignote-moi"), cards.map { it.title })
        assertEquals("14 épisodes", cards[0].badge)
        assertNull(cards[0].recordingAssetId)
        assertEquals("LIVE_4442427", cards[1].recordingAssetId)
        assertNull(cards[1].badge)
    }

    @Test
    fun aFolderListsItsRecordedEpisodes() {
        val page = adapter.fromJson(
            """{"content":{"template":"my-stuff-details","sections":[{"component_type":"list-item-wide","components":[
              {"type":"list-item-wide","id":"e1","title":{"text":"S16 E6 Agde"},"info":{"text":"Enregistré: 30 sept."},
               "picture":{"url":"https://img/e.jpg"},"actions":${nav("program-details/program/LIVE_4445433")}}]}]}}""",
        )!!.toPage()

        val episode = page.rails.single().cards.single()
        assertEquals("LIVE_4445433", episode.recordingAssetId)
        assertEquals("Enregistré: 30 sept.", episode.timeLabel)
    }

    @Test
    fun aProgrammeLinkElsewhereStaysCatchUp() {
        val page = adapter.fromJson(
            """{"content":{"template":"catalog","sections":[{"component_type":"card-wide","components":[
              {"id":"c","picture":{"url":"https://img/c.jpg"},"actions":${nav("program-details/program/LIVE_1")}}]}]}}""",
        )!!.toPage()
        assertNull(page.rails.single().cards.single().recordingAssetId)
    }
}
