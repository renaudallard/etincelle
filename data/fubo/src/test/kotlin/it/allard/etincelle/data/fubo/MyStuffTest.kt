// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import it.allard.etincelle.core.model.CardShape
import it.allard.etincelle.core.model.CardTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recordings pages ("Mes vidéos"), shaped as captured in November 2026. */
class MyStuffTest {
    private val adapter = Moshi.Builder().build().adapter(PageResponse::class.java)

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
        assertEquals("14 épisodes", cards[0].caption)
        assertEquals("52m • 2026", cards[1].caption)
    }

    @Test
    fun aFolderPageIsAListOfRecordedRows() {
        val row = adapter.fromJson(
            """{"content":{"template":"my-stuff-details","sections":[{"component_type":"list-item-wide","components":[
              {"type":"list-item-wide","id":"12008536_45-0","title":{"text":"S16 E6 Agde"},"info":{"text":"Enregistré: 30 sept."},
               "tags":[{"label":"REC","leading_icon":{"url":"https://img/rec.png"}}],"channel_picture":{"url":"https://img/m6.png"},
               "picture":{"url":"https://img/e.jpg"},"actions":${nav("program-details/program/LIVE_4445433")}}]}]}}""",
        )!!.toPage().rails.single().cards.single()

        assertEquals(CardShape.ROW, row.shape)
        assertEquals("Enregistré: 30 sept.", row.caption)
        assertEquals(CardTag("REC", "https://img/rec.png"), row.tag)
        assertEquals("https://img/m6.png", row.channelLogoUrl)
    }

    @Test
    fun aFolderListsItsRecordedEpisodes() {
        val recordings = adapter.fromJson(
            """{"content":{"template":"my-stuff-details","sections":[{"component_type":"list-item-wide","components":[
              {"type":"list-item-wide","id":"12008536_45-0","title":{"text":"S16 E6 Agde"},"info":{"text":"Enregistré: 30 sept."},
               "picture":{"url":"https://img/e.jpg"},"actions":${nav("program-details/program/LIVE_4445433")}}]}]}}""",
        )!!.toRecordings("100001536")

        val episode = recordings.single()
        assertEquals("LIVE_4445433", episode.assetId)
        assertEquals("S16 E6 Agde", episode.title)
        assertEquals("Enregistré: 30 sept.", episode.subtitle)
        assertEquals("12008536_45", episode.programId)
        assertEquals("100001536", episode.seriesId)
    }
}
