// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class EpgMapperTest {

    @Test
    fun `maps each channel to a rail of programs that play the channel live`() {
        // Pin the timezone so the local "HH:mm" rendering is deterministic.
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val response = EpgResponse(
                response = listOf(
                    EpgEntryDto(
                        EpgChannelDataDto(
                            channel = EpgChannelDto("600019", "france 2", "France 2", "https://logo"),
                            programsWithAssets = listOf(
                                EpgProgramWithAssetsDto(
                                    program = EpgProgramDto("Le 20h", null, "https://img"),
                                    assets = listOf(
                                        EpgAssetDto(EpgAccessRightsDto("2026-06-13T19:10:00Z", "2026-06-13T21:25:00Z")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val page = response.toGuidePage()
            assertEquals("Guide", page.title)
            assertEquals(1, page.rails.size)

            val rail = page.rails.single()
            assertEquals("France 2", rail.title)

            val card = rail.cards.single()
            assertEquals("600019", card.channelId)
            assertNull(card.vodId)
            assertFalse(card.isLocked)
            assertEquals("19:10 · Le 20h", card.title)
            assertEquals("https://img", card.imageUrl)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `drops channels with no playable programs`() {
        val response = EpgResponse(
            response = listOf(
                EpgEntryDto(EpgChannelDataDto(EpgChannelDto("1", "x", "X", null), programsWithAssets = emptyList())),
            ),
        )
        assertEquals(0, response.toGuidePage().rails.size)
    }
}
