// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Favourite channels, from shapes captured on the live backend (/page/channels, /favorite/channels). */
class FavoriteChannelsTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun cards(json: String) = moshi.adapter(PageResponse::class.java).fromJson(json)!!.toRails().single().cards

    private fun order(json: String) = moshi.adapter(FavoriteOrderResponse::class.java).fromJson(json)!!.toFavoriteOrder()

    private val channelsJson = """
        {"content":{"sections":[{"type":"carousel","component_type":"square","title":{"text":"Chaînes favorites"},"components":[
          {"type":"square","id":"fav-600021","is_favorite":true,"title":{"text":"Paris Première"},"picture":{"url":"https://x/pp.png"},
           "actions":{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://api-eu.fubo.tv/papi/v1/channel-details/600021"}}],
            "on_fav_icon_click":[{"type":"api_call","endpoint":{"method":"POST","url":"https://api-eu.fubo.tv/action/v1/unfavorite-channel",
              "payload":{"action_name":"unfavorite-channel","params":{"channel_id":"600021","channel_name":"Paris Première"}}}}]}},
          {"type":"square","id":"tnt-600046","is_favorite":false,"title":{"text":"TF1"},"picture":{"url":"https://x/tf1.png"},
           "actions":{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://api-eu.fubo.tv/papi/v1/channel-details/600046"}}],
            "on_fav_icon_click":[{"type":"api_call","endpoint":{"method":"POST","url":"https://api-eu.fubo.tv/action/v1/favorite-channel",
              "payload":{"action_name":"favorite-channel","params":{"channel_id":"600046","channel_name":"TF1"}}}}]}},
          {"type":"square","id":"app-1","title":{"text":"france.tv"},"picture":{"url":"https://x/ftv.png"},
           "actions":{"on_click":[{"type":"navigation","endpoint":{"method":"GET","url":"https://api-eu.fubo.tv/papi/v1/broadcaster-details/1"}}]}}
        ]}]}}
    """.trimIndent()

    @Test
    fun channelCardsCarryTheirFavouriteToggle() {
        val (pp, tf1, app) = cards(channelsJson)
        assertTrue(pp.isFavorite)
        assertEquals("Retirer des favoris", pp.favorite!!.label)
        assertTrue(pp.favorite!!.url.endsWith("/action/v1/unfavorite-channel"))
        assertFalse(tf1.isFavorite)
        assertEquals("Ajouter aux favoris", tf1.favorite!!.label)
        assertEquals("favorite-channel", tf1.favorite!!.payload["action_name"])
        assertNull(app.favorite)
        assertFalse(app.isFavorite)
    }

    private val orderJson = """
        {"content":{"template":"reorder-favorites","title":{"text":"Réorganiser les favoris"},
         "subtitle":{"text":"Sélectionnez une chaîne et déplacez-la vers l'emplacement souhaité."},
         "items":[
           {"type":"reorder-channel-list-item","id":"600021","name":{"text":"Paris Première"},"logo_dark":{"url":"https://x/pp.png"}},
           {"type":"reorder-channel-list-item","id":"602399","name":{"text":"KITCHEN MANIA"},"logo_dark":{"url":"https://x/km.png"}}],
         "submit_button":{"type":"button","text":{"text":"Enregistrer"},"actions":{"on_click":[
           {"type":"save_favorite_channel_reorder","endpoint":{"method":"POST","url":"https://api-eu.fubo.tv/action/v1/save-favorite-channel-reorder",
            "payload":{"action_name":"save-favorite-channel-reorder","callback_actions":[{"type":"close_page"}],
             "custom_action_data":{"channel_ids":[]},"metadatas":null}}},
           {"type":"tracking","endpoint":{"method":"POST","url":"https://api-eu.fubo.tv/papi/v1/tracking","payload":{"event":"x"}}}]}}}}
    """.trimIndent()

    @Test
    fun reorderPageGivesTheChannelsAndTheSaveAction() {
        val order = order(orderJson)!!
        assertEquals("Réorganiser les favoris", order.title)
        assertEquals(listOf("600021", "602399"), order.channels.map { it.id })
        assertEquals("KITCHEN MANIA", order.channels[1].name)
        assertEquals("Enregistrer", order.save.label)
        assertTrue(order.save.url.endsWith("/action/v1/save-favorite-channel-reorder"))
    }

    @Test
    fun savedOrderGoesInTheCustomActionData() {
        val save = order(orderJson)!!.save
        val body = favoriteOrderPayload(save, listOf("602399", "600021"))
        assertEquals("save-favorite-channel-reorder", body["action_name"])
        assertEquals(mapOf("channel_ids" to listOf("602399", "600021")), body["custom_action_data"])
        // The rest of the backend's payload goes back untouched.
        assertEquals(save.payload["callback_actions"], body["callback_actions"])
        assertTrue(body.containsKey("metadatas"))
    }

    @Test
    fun reorderPageWithoutSaveActionIsUnusable() {
        assertNull(order("""{"content":{"title":{"text":"x"},"items":[]}}"""))
    }
}
