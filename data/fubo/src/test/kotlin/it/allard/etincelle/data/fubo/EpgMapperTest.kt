// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.model.ProgramWindow
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
    fun `a guide card keeps its id as the programmes before it end, and no two share one`() {
        fun programme(title: String, start: String?) =
            EpgProgramWithAssetsDto(EpgProgramDto(title, null, null), start?.let { listOf(EpgAssetDto(EpgAccessRightsDto(it, null))) })
        fun guide(vararg programmes: EpgProgramWithAssetsDto) = EpgResponse(
            listOf(EpgEntryDto(EpgChannelDataDto(EpgChannelDto("600019", "France 2", "France 2", null), programmes.toList()))),
        ).toGuidePage().rails.single().cards.map { it.id }

        val before = guide(programme("A", "2026-11-23T18:00:00Z"), programme("B", "2026-11-23T19:00:00Z"))
        val after = guide(programme("B", "2026-11-23T19:00:00Z"))
        assertEquals(before[1], after[0])
        val shared = guide(programme("A", "2026-11-23T18:00:00Z"), programme("B", "2026-11-23T18:00:00Z"), programme("C", null))
        assertEquals(3, shared.toSet().size)
    }

    @Test
    fun `an episode is listed under its show's name, its own title below`() {
        val response = EpgResponse(
            listOf(
                EpgEntryDto(
                    EpgChannelDataDto(
                        EpgChannelDto("600019", "France 2", "France 2", null),
                        listOf(
                            EpgProgramWithAssetsDto(EpgProgramDto("Episode 38", "Un si grand soleil", null), null),
                            EpgProgramWithAssetsDto(EpgProgramDto("Le 20h", "Le 20h", null), null),
                        ),
                    ),
                ),
            ),
        )

        val cards = response.toGuidePage().rails.single().cards
        assertEquals("Un si grand soleil", cards[0].title)
        assertEquals("Episode 38", cards[0].caption)
        assertEquals("Le 20h", cards[1].title)
        assertNull(cards[1].caption)
    }

    @Test
    fun `the programme on air is the one the guide has running on that channel`() {
        fun programme(start: String, end: String) =
            EpgProgramWithAssetsDto(EpgProgramDto("P", null, null), listOf(EpgAssetDto(EpgAccessRightsDto(start, end))))
        val response = EpgResponse(
            response = listOf(
                EpgEntryDto(EpgChannelDataDto(EpgChannelDto("1", "x", "X", null), listOf(programme("2026-11-17T00:00:00Z", "2026-11-17T06:00:00Z")))),
                EpgEntryDto(
                    EpgChannelDataDto(
                        EpgChannelDto("600048", "rmc", "RMC Story", null),
                        listOf(
                            programme("2026-11-16T22:30:00Z", "2026-11-17T00:04:10Z"),
                            programme("2026-11-17T00:04:10Z", "2026-11-17T05:00:00Z"),
                        ),
                    ),
                ),
            ),
        )
        val start = rfc3339UtcToEpochMillis("2026-11-17T00:04:10Z")!!
        val end = rfc3339UtcToEpochMillis("2026-11-17T05:00:00Z")!!

        assertEquals(ProgramWindow(start, end), response.onAirWindow("600048", start))
        assertEquals(ProgramWindow(start, end), response.onAirWindow("600048", end - 1))
        // Past what the guide holds, and a channel it does not have.
        assertNull(response.onAirWindow("600048", end))
        assertNull(response.onAirWindow("2", start))
    }

    @Test
    fun `a timestamp reads the same with a fraction of a second or an offset`() {
        val expected = rfc3339UtcToEpochMillis("2026-06-13T19:10:00Z")
        assertEquals(1_781_377_800_000L, expected)
        assertEquals(expected, rfc3339UtcToEpochMillis("2026-06-13T19:10:00.000Z"))
        assertEquals(expected, rfc3339UtcToEpochMillis("2026-06-13T19:10:00.123456Z"))
        assertEquals(expected, rfc3339UtcToEpochMillis("2026-06-13T21:10:00+02:00"))
        assertEquals(expected, rfc3339UtcToEpochMillis("2026-06-13T19:10:00+00:00"))
        assertNull(rfc3339UtcToEpochMillis("13/06/2026"))
        assertNull(rfc3339UtcToEpochMillis(null))
        // And back, in the one shape the guide is asked with.
        assertEquals("2026-06-13T19:10:00Z", rfc3339Utc(expected!!))
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
