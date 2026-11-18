// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.model.CardShape
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
            drm = null, heartbeat = null, concurrency = null, playhead = null, type = "live", program = ProgramDto("Title"),
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
            drmV2 = null, drm = null, heartbeat = null, concurrency = null, playhead = null, type = "dvr", program = null,
        )
        assertFalse(response.toPlaybackSource().isLive)
    }

    @Test
    fun `unprotected stream maps to no drm`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = false, live = false),
            drmV2 = null, drm = null, heartbeat = null, concurrency = null, playhead = null, type = "vod", program = null,
        )
        assertEquals(DrmSpec.None, response.toPlaybackSource().drm)
    }

    @Test
    fun `falls back to v1 drm when drm_v2 absent`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = true, live = false),
            drmV2 = null,
            drm = DrmV1Dto("widevine", "https://lic-v1", mapOf("h" to "v"), "token"),
            heartbeat = null, concurrency = null, playhead = null, type = "vod", program = null,
        )
        val drm = response.toPlaybackSource().drm as DrmSpec.Widevine
        assertEquals("https://lic-v1", drm.licenseUrl)
        assertEquals("v", drm.licenseHeaders["h"])
    }

    @Test
    fun `program window parses rfc3339 utc bounds`() {
        val window = AccessRightsV2WindowDto(startTime = "2026-06-23T19:50:00Z", endTime = "2026-06-23T20:15:00Z")
            .toProgramWindow()!!
        assertEquals(1_782_244_200_000L, window.startMs)
        assertEquals(1_782_245_700_000L, window.endMs)
    }

    @Test
    fun `program window prefers the epoch-ms bounds over the string form`() {
        val window = AccessRightsV2WindowDto(
            startTime = "2026-06-23T19:50:00Z", endTime = "2026-06-23T20:15:00Z",
            startTimeMs = 1_000L, endTimeMs = 2_000L,
        ).toProgramWindow()!!
        assertEquals(1_000L, window.startMs)
        assertEquals(2_000L, window.endMs)
    }

    @Test
    fun `program window is null when bounds are missing or not ordered`() {
        assertNull(AccessRightsV2WindowDto().toProgramWindow())
        assertNull(AccessRightsV2WindowDto(startTimeMs = 5_000L, endTimeMs = 5_000L).toProgramWindow())
        assertNull(AccessRightsV2WindowDto(startTime = "not-a-date", endTime = "2026-06-23T20:15:00Z").toProgramWindow())
    }

    @Test
    fun `playhead maps to the progress url and payload`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = false, live = false),
            drmV2 = null, drm = null, heartbeat = null, concurrency = null,
            playhead = PlayHeadDto("POST", "https://api-eu.fubo.tv/playhead/v2", mapOf("assetId" to "175982", "lastOffset" to "@")),
            type = "vod", program = null,
        )
        val source = response.toPlaybackSource()
        assertEquals("https://api-eu.fubo.tv/playhead/v2", source.progressUrl)
        assertEquals("175982", source.progressPayload?.get("assetId"))
    }

    @Test
    fun `a playhead on another host is not kept`() {
        val response = PlaybackResponse(
            stream = StreamDto("https://cdn/m.mpd", "dash", drmProtected = false, live = false),
            drmV2 = null, drm = null, heartbeat = null, concurrency = null,
            playhead = PlayHeadDto("POST", "https://fubo.tv.example.net/playhead/v2", mapOf("assetId" to "175982")),
            type = "vod", program = null,
        )
        assertNull(response.toPlaybackSource().progressUrl)
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
    fun `a card takes its tile shape from its row`() {
        val url = "https://api-eu.fubo.tv/papi/v1/program-details/program/VOD_1"
        fun shape(type: String) = page(componentType = type, title = "X", locked = false, actionUrl = url).toRails()[0].cards[0].shape
        assertEquals(CardShape.SQUARE, shape("square"))
        assertEquals(CardShape.POSTER, shape("card-poster"))
        assertEquals(CardShape.POSTER_THIN, shape("card-poster-thin"))
        assertEquals(CardShape.WIDE, shape("card-wide"))
        assertEquals(CardShape.ROW, shape("list-item-wide"))
    }

    @Test
    fun `a channels-grid card parses its channel-details id`() {
        val card = page(
            componentType = "square",
            title = "KITCHEN MANIA",
            locked = false,
            actionUrl = "https://api-eu.fubo.tv/papi/v1/channel-details/700123",
        ).toRails()[0].cards[0]
        assertEquals("700123", card.channelId)
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
