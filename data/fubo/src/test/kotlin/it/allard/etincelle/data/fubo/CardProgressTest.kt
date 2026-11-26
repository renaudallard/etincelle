// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import it.allard.etincelle.core.model.CardProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bar the backend puts under a card's picture: how far a live programme is, or a watched one. */
class CardProgressTest {
    private val adapter = Moshi.Builder().build().adapter(PageResponse::class.java)

    private fun page(json: String) = adapter.fromJson(json)!!

    // 2026-11-15T13:04:11Z and 14:50:00Z, as a live card of that day gave them.
    private val start = 1794747851000L
    private val end = 1794754200000L

    @Test
    fun aLiveCardRunsBetweenItsTimesAndAWatchedOneIsAtItsPercentage() {
        val cards = page(
            """{"content":{"sections":[{"component_type":"card-wide","components":[
              {"id":"l","type":"card-wide","channel_id":600045,"picture":{"url":"https://img/l.jpg"},
               "body":{"progress_bar":{"background_color":"#0F1010","progress_color":"#FF5423","kind":"live",
                 "start_time":"2026-11-15T13:04:11Z","end_time":"2026-11-15T14:50:00Z","type":"progress_bar"}},
               "footer":{"title":{"text":"Meurtres à Aix-en-Provence"}}},
              {"id":"v","type":"card-wide","picture":{"url":"https://img/v.jpg"},
               "body":{"progress_bar":{"percentage":32,"background_color":"#0F1010","progress_color":"#FFFFFF","kind":"vod","type":"progress_bar"}},
               "footer":{"title":{"text":"Cauchemar en cuisine"}}},
              {"id":"n","type":"card-wide","picture":{"url":"https://img/n.jpg"},"footer":{"title":{"text":"Sans barre"}}}]}]}}""",
        ).toRails().single().cards
        assertEquals(CardProgress(startMs = start, endMs = end, color = 0xFFFF5423, track = 0xFF0F1010), cards[0].progress)
        assertEquals(CardProgress(percent = 32, color = 0xFFFFFFFF, track = 0xFF0F1010), cards[1].progress)
        assertNull(cards[2].progress)
    }

    @Test
    fun anEpisodeRowCarriesItsBarItself() {
        val episodes = page(
            """{"content":{"sections":[{"component_type":"list-item-wide","components":[
              {"id":"e","type":"list-item-wide","title":{"text":"S4 E246 Plantes"},"picture":{"url":"https://img/e.jpg"},
               "progress_bar":{"percentage":75,"progress_color":"#FFFFFF","kind":"vod","type":"progress_bar"}}]}]}}""",
        ).toEpisodes()
        assertEquals(CardProgress(percent = 75, color = 0xFFFFFFFF), episodes.single().progress)
    }

    @Test
    fun whatIsNoCardsBarIsLeftOut() {
        val cards = page(
            """{"content":{"sections":[{"component_type":"card-wide","components":[
              {"id":"s","picture":{"url":"https://img/s.jpg"},"body":{"progress_bar":{"percentage":23,"kind":"dvr_space"}}},
              {"id":"t","picture":{"url":"https://img/t.jpg"},"body":{"progress_bar":{"kind":"live","start_time":"soon","end_time":"later"}}},
              {"id":"r","picture":{"url":"https://img/r.jpg"},"body":{"progress_bar":{"kind":"live","start_time":"2026-11-15T14:50:00Z","end_time":"2026-11-15T13:04:11Z"}}},
              {"id":"c","picture":{"url":"https://img/c.jpg"},"body":{"progress_bar":{"percentage":140,"progress_color":"red","kind":"vod"}}}]}]}}""",
        ).toRails().single().cards
        assertNull(cards[0].progress)
        assertNull(cards[1].progress)
        assertNull(cards[2].progress)
        // Out of range is held to the whole bar; a colour that is not one is the app's own.
        assertEquals(CardProgress(percent = 100), cards[3].progress)
    }

    @Test
    fun aLiveBarFillsWithTheClockAndAWatchedOneStays() {
        val live = CardProgress(startMs = start, endMs = end)
        assertTrue(live.live)
        assertEquals(0f, live.filledAt(start - 60_000), 0f)
        assertEquals(0.5f, live.filledAt((start + end) / 2), 0.0001f)
        assertEquals(1f, live.filledAt(end + 60_000), 0f)
        val watched = CardProgress(percent = 32)
        assertEquals(false, watched.live)
        assertEquals(0.32f, watched.filledAt(start), 0.0001f)
        assertEquals(0.32f, watched.filledAt(end), 0.0001f)
    }
}
