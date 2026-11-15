// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.allard.etincelle.core.model.UpNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The end slate of an episode page (shape captured on the live backend with auto_play_up_next). */
class UpNextTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(PageResponse::class.java)

    private fun detail(player: String) =
        adapter.fromJson("""{"content":{"metadata":{"id":"id-metadata-12160446_39"},"player":$player}}""")!!
            .toProgramDetail(null, "VOD_699927", false)

    private val slate = """
        {"type":"player","end_slate":{"type":"end_slate","position":208,"actions":[{"type":"show_video_slate","content":{
          "type":"up-next","title":{"text":"Sisbro"},"subtitle":{"text":"S1, EP2"},
          "count_down_text":{"text":"Commence dans %s"},"display_duration":9,
          "auto_dismiss_actions":[
            {"type":"refresh_page","endpoint":{"method":"GET","url":"https://api-eu.fubo.tv/papi/v1/program-details/program/VOD_699936?transient=false"}},
            {"type":"close_video_slate"}],
          "components":[
            {"type":"button","style":"primary","text":{"text":"À suivre"}},
            {"type":"button","style":"secondary","text":{"text":"Regarder le générique"}}]}}]}}
    """.trimIndent()

    @Test
    fun episodePageCarriesTheNextEpisode() {
        val upNext = detail(slate).upNext!!
        assertEquals(208L, upNext.atSec)
        assertEquals(9L, upNext.showSec)
        assertEquals("VOD_699936", upNext.nextVodId)
        assertEquals("Sisbro", upNext.title)
        assertEquals("S1, EP2", upNext.subtitle)
        assertEquals("Commence dans %s", upNext.countdown)
        assertEquals("À suivre", upNext.playLabel)
        assertEquals("Regarder le générique", upNext.creditsLabel)
    }

    @Test
    fun noSlateMeansNothingFollows() {
        assertNull(detail("""{"type":"player"}""").upNext)
        // A slate without a next episode to go to is unusable.
        assertNull(detail(slate.replace("program-details/program/VOD_699936", "page/home")).upNext)
    }

    private val upNext = UpNext(208, 9, null, null, "Commence dans %s", "À suivre", "Regarder le générique", "VOD_2")

    @Test
    fun countdownFollowsThePosition() {
        assertNull(upNext.secondsLeft(207_999, 600_000))
        assertEquals(9L, upNext.secondsLeft(208_000, 600_000))
        assertEquals(5L, upNext.secondsLeft(212_500, 600_000))
        assertEquals(0L, upNext.secondsLeft(217_000, 600_000))
        assertEquals(0L, upNext.secondsLeft(300_000, 600_000))
    }

    @Test
    fun countdownEndsBeforeTheContentDoes() {
        // 4 s of content left at the slate: the countdown keeps 2.5 s back, so 2 s are shown.
        assertEquals(2L, upNext.secondsLeft(208_000, 212_000))
        assertEquals(0L, upNext.secondsLeft(212_000, 212_000))
        // An unknown duration does not shorten it.
        assertEquals(9L, upNext.secondsLeft(208_000, -1))
    }
}
