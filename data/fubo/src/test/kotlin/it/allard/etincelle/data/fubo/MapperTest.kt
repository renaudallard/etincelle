// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.model.DrmSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapperTest {

    @Test
    fun `drm_v2 maps to widevine with license url and headers`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/manifest.mpd", "dash", drmProtected = true, live = true),
            drmV2 = DrmV2Dto("widevine", LicenseDto("https://lic", mapOf("x-dt-auth-token" to "tok"))),
            drm = null, heartbeat = null, concurrency = null, type = "live", program = ProgramDto("Title"),
        )
        val source = response.toPlaybackSource()
        assertEquals("https://cdn/manifest.mpd", source.manifestUrl)
        assertTrue(source.isLive)
        val drm = source.drm as DrmSpec.Widevine
        assertEquals("https://lic", drm.licenseUrl)
        assertEquals("tok", drm.licenseHeaders["x-dt-auth-token"])
    }

    @Test
    fun `a dvr recording is not live even when its manifest reports live`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/dvr.mpd", "dash", drmProtected = false, live = true),
            drmV2 = null, drm = null, heartbeat = null, concurrency = null, type = "dvr", program = null,
        )
        assertFalse(response.toPlaybackSource().isLive)
    }

    @Test
    fun `unprotected stream maps to no drm`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = false, live = false),
            drmV2 = null, drm = null, heartbeat = null, concurrency = null, type = "vod", program = null,
        )
        assertEquals(DrmSpec.None, response.toPlaybackSource().drm)
    }

    @Test
    fun `falls back to v1 drm when drm_v2 absent`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = true, live = false),
            drmV2 = null,
            drm = DrmV1Dto("widevine", "https://lic-v1", mapOf("h" to "v"), "token"),
            heartbeat = null, concurrency = null, type = "vod", program = null,
        )
        val drm = response.toPlaybackSource().drm as DrmSpec.Widevine
        assertEquals("https://lic-v1", drm.licenseUrl)
        assertEquals("v", drm.licenseHeaders["h"])
    }

    @Test
    fun `a channel card parses its channel id`() {
        val card = page(
            componentType = "square",
            title = "France 2",
            locked = false,
            actionUrl = "https://api-eu.fubo.tv/papi/v1/program-details/channel/600019",
        ).toRails()[0].cards[0]
        assertEquals("600019", card.channelId)
        assertNull(card.vodId)
        assertEquals("France 2", card.title)
        assertFalse(card.isLocked)
    }

    @Test
    fun `a program card parses its vod id and lock`() {
        val card = page(
            componentType = "card",
            title = "Un film",
            locked = true,
            actionUrl = "https://api-eu.fubo.tv/papi/v1/program-details/program/VOD_42",
        ).toRails()[0].cards[0]
        assertEquals("VOD_42", card.vodId)
        assertNull(card.channelId)
        assertTrue(card.isLocked)
    }

    private fun page(componentType: String, title: String, locked: Boolean, actionUrl: String) = PageResponse(
        title = TextDto("Accueil"),
        content = PageContentDto(
            template = "catalog",
            sections = listOf(
                SectionDto(
                    title = TextDto("Rail"),
                    componentType = componentType,
                    components = listOf(
                        ComponentDto(
                            id = "card-1", type = componentType, title = TextDto(title), heading = null,
                            picture = PictureDto("https://logo"), body = null, image = null, imageCompact = null,
                            isLocked = locked, state = null,
                            actions = ActionsDto(listOf(ActionItemDto(EndpointDto(actionUrl, "GET")))),
                        ),
                    ),
                ),
            ),
        ),
    )
}
