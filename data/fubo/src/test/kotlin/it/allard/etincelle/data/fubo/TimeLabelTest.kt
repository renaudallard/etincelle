// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a programme aired, as the backend words it on cards, episode rows and programme pages. */
class TimeLabelTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PageResponse::class.java)

    private fun page(json: String) = adapter.fromJson(json)!!

    @Test
    fun aCardTakesItsFooterTimeLabel() {
        val cards = page(
            """{"content":{"sections":[{"component_type":"card-wide","components":[
              {"id":"c1","picture":{"url":"https://img/1.jpg"},
               "footer":{"title":{"text":"Jack Hunter"},"subtitle":{"text":"1h 51m • 2008"},"time_label":{"text":"Diffusé: 12 févr."}}},
              {"id":"c2","picture":{"url":"https://img/2.jpg"},"footer":{"title":{"text":"Sans date"}}}]}]}}""",
        ).toRails().single().cards
        assertEquals("Diffusé: 12 févr.", cards[0].timeLabel)
        assertNull(cards[1].timeLabel)
    }

    @Test
    fun anEpisodeRowSaysWhenItAired() {
        val episodes = page(
            """{"content":{"sections":[{"component_type":"list-item-wide","components":[
              {"id":"11741646_45-0","type":"list-item-wide","title":{"text":"S4 E246 Plantes"},"subtitle":{"text":"32m"},
               "info":{"text":"07 juin à 13h25"},"picture":{"url":"https://img/e.jpg"}}]}]}}""",
        ).toEpisodes()
        assertEquals("07 juin à 13h25", episodes.single().timeLabel)
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
