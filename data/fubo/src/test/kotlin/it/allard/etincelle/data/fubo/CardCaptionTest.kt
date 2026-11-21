// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import it.allard.etincelle.core.model.CardTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lines the backend puts under a card's title or above a programme's, and the tag on its image. */
class CardCaptionTest {
    private val adapter = Moshi.Builder().build().adapter(PageResponse::class.java)

    private fun page(json: String) = adapter.fromJson(json)!!

    @Test
    fun aCardShowsItsFooterSubtitleAndTag() {
        // As the official app: the footer's subtitle, not its time label, and the body's tag.
        val cards = page(
            """{"content":{"sections":[{"component_type":"card-wide","components":[
              {"id":"c1","picture":{"url":"https://img/1.jpg"},
               "body":{"tags":[{"label":"REC","leading_icon":{"url":"https://img/newly_recorded.png"},"style":"quaternary"}]},
               "footer":{"title":{"text":"Cauchemar en cuisine"},"subtitle":{"text":"S16 E6 Agde"},"time_label":{"text":"Diffusé: 30 sept."}}},
              {"id":"c2","picture":{"url":"https://img/2.jpg"},"footer":{"title":{"text":"Sans rien"}}}]}]}}""",
        ).toRails().single().cards
        assertEquals("S16 E6 Agde", cards[0].caption)
        assertEquals(CardTag("REC", "https://img/newly_recorded.png"), cards[0].tag)
        assertNull(cards[1].caption)
        assertNull(cards[1].tag)
    }

    @Test
    fun anEpisodeRowSaysWhenItAired() {
        val episodes = page(
            """{"content":{"sections":[{"component_type":"list-item-wide","components":[
              {"id":"11741646_45-0","type":"list-item-wide","title":{"text":"S4 E246 Plantes"},"subtitle":{"text":"32m"},
               "info":{"text":"07 juin à 13h25"},"picture":{"url":"https://img/e.jpg"}}]}]}}""",
        ).toEpisodes()
        assertEquals("07 juin à 13h25", episodes.single().caption)
    }

    @Test
    fun aProgrammePageShowsItsHatTitle() {
        val detail = page(
            """{"content":{"metadata":{"id":"m","title":{"text":"Jack Hunter"},"subtitle":{"text":"2h • 2008"},
              "hat_title":{"text":"Diffusé: 12 févr."}}}}""",
        ).toProgramDetail(channelId = null, vodId = "LIVE_257252", isLive = false)
        assertEquals("Diffusé: 12 févr.", detail.timeLabel)
    }
}
