// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record CTA shapes the backend serves, captured from the live Molotov backend: a live-channel
 * page nests the episode + whole-series options in a "record options" dropdown, while a programme or
 * series page carries a single record action-item directly. All must yield the right [RecordAction]s.
 */
class RecordActionMapperTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(PageResponse::class.java)

    private fun detail(json: String) = adapter.fromJson(json)!!.toProgramDetail(null, null, false)

    // A live-channel page: "record options" dropdown holding the episode and the whole series, plus a
    // sibling tracking action (must be ignored) and an unrelated "details" action-item.
    private val channelJson = """
        {"content":{"metadata":{"ctas":[
          {"type":"action-item-group","id":"id-pdp-action-item-group","action_items":[
            {"type":"action-item","id":"id-record-options-600021","text":{"text":"Options d'enregistrement"},
             "actions":{"on_click":[
               {"type":"open_dropdown","content":{"type":"menu","menu_items":[
                 {"type":"menu-item","id":"id-record-LIVE_3639158","text":{"text":"Enregistrer l'épisode"},
                  "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST",
                    "url":"https://api-eu.fubo.tv/action/v1/add-recording",
                    "payload":{"action_name":"add-recording","params":{"asset_id":"LIVE_3639158","is_upcoming":"false"}}}}]}},
                 {"type":"menu-item","id":"id-record-series-new-100015090","text":{"text":"Enregistrer la série"},
                  "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST",
                    "url":"https://api-eu.fubo.tv/action/v1/record-new-episodes",
                    "payload":{"action_name":"record-new-episodes","params":{"channel_id":"600021","series_id":"100015090","title":"Cosmos 1999"}}}}]}}
               ]}},
               {"type":"tracking","endpoint":{"method":"POST","url":"https://api-eu.fubo.tv/papi/v1/tracking","payload":{"event":"x"}}}
             ]}},
            {"type":"action-item","id":"id-view-details-mobile","text":{"text":"Détails du programme"},
             "actions":{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://x/program-details/series/100015090"}}]}}
          ]}
        ]}}}
    """.trimIndent()

    @Test
    fun `live channel exposes the episode and whole-series record options`() {
        val actions = detail(channelJson).recordActions
        assertEquals(2, actions.size)
        assertEquals("Enregistrer l'épisode", actions[0].label)
        assertTrue(actions[0].url.endsWith("/action/v1/add-recording"))
        assertEquals("add-recording", (actions[0].payload["action_name"]))
        assertEquals("Enregistrer la série", actions[1].label)
        assertTrue(actions[1].url.endsWith("/action/v1/record-new-episodes"))
        @Suppress("UNCHECKED_CAST")
        val params = actions[1].payload["params"] as Map<String, Any?>
        assertEquals("100015090", params["series_id"])
    }

    @Test
    fun `a series page exposes only the whole-series record`() {
        val json = """
            {"content":{"metadata":{"ctas":[
              {"type":"action-item-group","id":"id-pdp-action-item-group","action_items":[
                {"type":"action-item","id":"id-record-series-new-100015090","text":{"text":"Enregistrer la série"},
                 "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST",
                   "url":"https://api-eu.fubo.tv/action/v1/record-new-episodes",
                   "payload":{"action_name":"record-new-episodes","params":{"series_id":"100015090"}}}}]}},
                {"type":"action-item","id":"id-add-serie-to-library-100015090","text":{"text":"Favoris"},
                 "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST","url":"https://x/library","payload":{}}}]}}
              ]}
            ]}}}
        """.trimIndent()
        val actions = detail(json).recordActions
        assertEquals(1, actions.size)
        assertEquals("Enregistrer la série", actions[0].label)
        assertTrue(actions[0].url.endsWith("/action/v1/record-new-episodes"))
    }

    @Test
    fun `a live programme page exposes a single direct episode record`() {
        val json = """
            {"content":{"metadata":{"ctas":[
              {"type":"action-item-group","id":"id-pdp-action-item-group","action_items":[
                {"type":"action-item","id":"id-record-LIVE_3639118","text":{"text":"Enregistrer"},
                 "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST",
                   "url":"https://api-eu.fubo.tv/action/v1/add-recording",
                   "payload":{"action_name":"add-recording","params":{"asset_id":"LIVE_3639118","is_upcoming":"false"}}}}]}}
              ]}
            ]}}}
        """.trimIndent()
        val actions = detail(json).recordActions
        assertEquals(1, actions.size)
        assertEquals("Enregistrer", actions[0].label)
    }

    @Test
    fun `an already-recording series exposes the stop-recording toggle`() {
        val json = """
            {"content":{"metadata":{"ctas":[
              {"type":"action-item-group","id":"id-pdp-action-item-group","action_items":[
                {"type":"action-item","id":"id-stop-record-series-100015090","text":{"text":"Arrêter d'enregistrer la série"},
                 "actions":{"on_click":[{"type":"api_call","endpoint":{"method":"POST",
                   "url":"https://api-eu.fubo.tv/papi/v1/actions",
                   "payload":{"action_name":"stop-recording-series","params":{"series_id":"100015090"}}}}]}}
              ]}
            ]}}}
        """.trimIndent()
        val actions = detail(json).recordActions
        assertEquals(1, actions.size)
        assertEquals("Arrêter d'enregistrer la série", actions[0].label)
        assertTrue(actions[0].url.endsWith("/papi/v1/actions"))
    }

    @Test
    fun `a page without a record CTA yields no record actions`() {
        val json = """{"content":{"metadata":{"ctas":[
          {"type":"button","id":"id-play-mre-button","text":{"text":"Regarder"},
           "actions":{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://x/playback"}}]}}
        ]}}}"""
        assertTrue(detail(json).recordActions.isEmpty())
    }
}
