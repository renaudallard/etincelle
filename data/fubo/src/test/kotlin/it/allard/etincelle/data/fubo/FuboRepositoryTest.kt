// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.LoginResult
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.network.ProfilePick
import it.allard.etincelle.core.network.ProgressStore
import it.allard.etincelle.core.network.SessionManager
import it.allard.etincelle.core.network.SessionStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class FuboRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: FuboRepository
    private val session = SessionManager()
    private val store = FakeSessionStore()
    private val progress = FakeProgressStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build()
            .create(FuboApi::class.java)
        repo = FuboRepository(api, session, store, progress)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the guide follows the pages to the last channel`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        fun page(id: String, next: String) = MockResponse().setBody(
            """{"response":[{"data":{"channel":{"id":"$id","name":"C$id"},"programsWithAssets":[
              {"program":{"title":"P$id"},"assets":[{"assetId":"LIVE_$id","accessRights":
                {"startTime":"2026-11-15T20:00:00Z","endTime":"2026-11-15T21:00:00Z"}}]}]}}],
              "metadata":[{"type":"paginationState","data":{"totalResults":2,"currentOffset":0,"nextOffset":$next}}]}""",
        )
        server.enqueue(page("1", "100"))
        server.enqueue(page("2", "null"))

        val guide = repo.loadGuide()

        assertEquals(listOf("C1", "C2"), guide.rails.map { it.title })
        assertFalse(server.takeRequest().path!!.contains("offset="))
        assertTrue(server.takeRequest().path!!.contains("offset=100"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `login loads the profile and persists the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"AT","refresh_token":"RT","id_token":"IT"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"id":"u1","profiles":[{"id":"p1"}]}}"""))

        val result = (repo.login("e@x", "pw") as LoginResult.SignedIn).session

        assertEquals("u1", result.userId)
        assertEquals("p1", result.profileId)
        assertEquals("AT", session.accessToken)
        assertEquals("AT", store.saved?.accessToken)
    }

    @Test
    fun `login unwraps the v2 signin envelope and sends the email as username`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"signin","payload":{"access_token":"AT","refresh_token":"RT","id_token":"IT"}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"data":{"id":"u1","profiles":[{"id":"p1"}]}}"""))

        val result = (repo.login("e@x", "pw") as LoginResult.SignedIn).session

        val request = server.takeRequest()
        assertEquals("/v2/signin", request.path)
        assertEquals("""{"username":"e@x","password":"pw"}""", request.body.readUtf8())
        assertEquals("u1", result.userId)
        assertEquals("AT", session.accessToken)
        assertEquals("RT", store.saved?.refreshToken)
    }

    @Test
    fun `restoreSession reloads a saved session`() = runTest {
        store.saved = UserSession("AT", "RT", "u1", "p1")
        assertTrue(repo.restoreSession())
        assertEquals("AT", session.accessToken)
    }

    @Test
    fun `a 401 triggers a refresh and retries the call`() = runTest {
        session.session = UserSession("OLD", "RT", "u1", "p1")
        server.enqueue(MockResponse().setResponseCode(401)) // loadHome
        server.enqueue(MockResponse().setBody("""{"access_token":"NEW","refresh_token":"RT2"}""")) // refresh
        server.enqueue(MockResponse().setBody("""{"title":{"text":"Accueil"},"content":{"template":"catalog","sections":[]}}""")) // retry
        server.enqueue(MockResponse().setBody("""{"content":{"sections":[]}}""")) // loadHome also fetches the channels page (apps)
        server.enqueue(MockResponse().setBody("""{"response":[]}""")) // loadHome also fetches recorded DVR
        server.enqueue(MockResponse().setBody("""{"response":[]}""")) // and scheduled DVR

        repo.loadHome()

        assertEquals("NEW", session.accessToken)
        assertEquals("NEW", store.saved?.accessToken)
        assertEquals(6, server.requestCount)
        server.takeRequest() // the 401'd home
        val refresh = server.takeRequest()
        assertEquals("/refresh", refresh.path)
        assertEquals("Bearer RT", refresh.getHeader("Authorization"))
    }

    @Test
    fun `the home still loads for an account without recordings`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(MockResponse().setBody("""{"title":{"text":"Accueil"},"content":{"template":"catalog","sections":[]}}"""))
        server.enqueue(MockResponse().setBody("""{"content":{"sections":[]}}""")) // channels page (apps)
        server.enqueue(MockResponse().setResponseCode(404).setBody("404 page not found")) // no recording option

        val home = repo.loadHome()

        assertEquals("Accueil", home.title)
        assertTrue(home.rails.none { it.id == "recordings" })
    }

    @Test
    fun `a series detail still opens for an account without recordings`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(MockResponse().setBody("""{"title":{"text":"Une série"},"content":{"sections":[]}}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("404 page not found"))

        val detail = repo.fetchProgramDetail("100001", DetailKind.SERIES)

        assertEquals(2, server.requestCount)
        assertTrue(detail.recordings.isEmpty())
    }

    @Test
    fun `resolveVod applies the saved resume position`() = runTest {
        progress.positions["VOD_7"] = 42_000L
        server.enqueue(
            MockResponse().setBody(
                """{"stream":{"url":"https://cdn/m.mpd","packagingProtocol":"dash","drmProtected":false,"live":false},"type":"vod"}""",
            ),
        )

        val source = repo.resolveVod("VOD_7")

        assertEquals("VOD_7", source.resumeKey)
        assertEquals(42_000L, source.startPositionMs)
    }

    @Test
    fun `resolveRecording requests the dvr asset and keeps its origin for re-resolving`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"stream":{"url":"https://cdn/r.mpd","packagingProtocol":"dash","drmProtected":false,"live":false},"type":"dvr"}""",
            ),
        )

        val source = repo.resolveRecording("LIVE_42")

        val request = server.takeRequest()
        assertEquals("dvr", request.requestUrl?.queryParameter("type"))
        assertEquals("LIVE_42", request.requestUrl?.queryParameter("id"))
        assertEquals("LIVE_42", source.originRecordingAssetId)
    }

    @Test
    fun `savePlaybackPosition stores a position and clears it at zero`() = runTest {
        repo.savePlaybackPosition("VOD_7", 30_000L)
        assertEquals(30_000L, progress.positions["VOD_7"])

        repo.savePlaybackPosition("VOD_7", 0L)
        assertEquals(null, progress.positions["VOD_7"])
    }

    private fun playheadSource(payload: Map<String, Any?>) = PlaybackSource(
        manifestUrl = "https://cdn/m.mpd",
        drm = DrmSpec.None,
        isLive = false,
        progressUrl = server.url("/playhead/v2").toString(),
        progressPayload = payload,
    )

    @Test
    fun `reportProgress posts lastOffset in seconds and echoes the payload as integers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"playhead":{"ping_interval":10}}"""))

        // Moshi decodes JSON numbers as Double; the body must send them back as integers.
        repo.reportProgress(
            playheadSource(mapOf("assetId" to "175982", "duration" to 6054.0, "lastOffset" to "@")),
            positionMs = 120_000L,
            durationMs = 6_054_000L,
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/playhead/v2", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"lastOffset\":120"))
        assertTrue(body, body.contains("\"duration\":6054"))
        assertTrue(body, body.contains("\"assetId\":\"175982\""))
    }

    @Test
    fun `reportProgress marks watched with full duration near the end`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        repo.reportProgress(
            playheadSource(mapOf("lastOffset" to "@", "duration" to 6054.0)),
            positionMs = 6_050_000L,
            durationMs = 6_054_000L,
        )

        assertTrue(server.takeRequest().body.readUtf8().contains("\"lastOffset\":6054"))
    }

    @Test
    fun `reportProgress is a no-op for live, barely-started, or unknown-duration playback`() = runTest {
        // live source carries no playhead
        repo.reportProgress(PlaybackSource("https://cdn/m.mpd", DrmSpec.None, isLive = true), 30_000L, 60_000L)
        // barely started (< 10s): skipped, matching the local resume policy
        repo.reportProgress(playheadSource(mapOf("lastOffset" to "@")), positionMs = 5_000L, durationMs = 60_000L)
        // unknown duration: skipped (cannot mirror the local store)
        repo.reportProgress(playheadSource(mapOf("lastOffset" to "@")), positionMs = 30_000L, durationMs = -1L)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server error surfaces a friendly French message`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { repo.loadHome() }.exceptionOrNull()

        assertTrue(error is AppError.Network)
        assertEquals("Service indisponible, réessayez", error?.message)
    }

    @Test
    fun `a 451 sign-in is a region block and a 403 is a blocked account`() = runTest {
        server.enqueue(MockResponse().setResponseCode(451))
        val geo = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertTrue(geo is AppError.GeoBlocked)

        server.enqueue(MockResponse().setResponseCode(403))
        val blocked = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Ce compte est bloqué", blocked?.message)
    }

    @Test
    fun `a 451 names which of the three blocks it is`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(451)
                .setBody("""{"error":{"code":"VPN_NETWORK","message":"vpn"}}""")
        )
        val vpn = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Désactivez votre VPN pour accéder au service", vpn?.message)

        server.enqueue(
            MockResponse().setResponseCode(451)
                .setBody("""{"error":{"code":"NO_SERVICE_FOR_SUBSCRIPTION","message":"sub"}}""")
        )
        val subscription = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertTrue(subscription is AppError.NotEntitled)

        server.enqueue(
            MockResponse().setResponseCode(451)
                .setBody("""{"error":{"code":"NO_SERVICE_IN_COUNTRY","message":"country"}}""")
        )
        val country = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertTrue(country is AppError.GeoBlocked)
    }

    @Test
    fun `a 451 outside sign-in is mapped the same way`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(
            MockResponse().setResponseCode(451)
                .setBody("""{"error":{"code":"VPN_NETWORK","message":"vpn"}}""")
        )

        val vpn = runCatching { repo.loadHome() }.exceptionOrNull()

        assertEquals("Désactivez votre VPN pour accéder au service", vpn?.message)
    }

    @Test
    fun `a 401 that is not a wrong password says what it actually is`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"MFA_TOKEN_EXPIRED","message":"mfa"}}""")
        )
        val mfa = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Délai de validation dépassé, reconnectez-vous", mfa?.message)

        // A wrong password keeps the curated message.
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"INVALID_USERNAME_PASSWORD","message":"nope"}}""")
        )
        val wrong = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Email ou mot de passe incorrect", wrong?.message)

        // An unrecognised code surfaces the backend's localised message instead of blaming the password.
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"SOMETHING_NEW","message":"en","lc_message":"message localisé"}}""")
        )
        val unknown = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("message localisé", unknown?.message)

        // No body at all still falls back cleanly.
        server.enqueue(MockResponse().setResponseCode(401))
        val empty = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Email ou mot de passe incorrect", empty?.message)
    }

    @Test
    fun `a sign-in error with a message but no code keeps the message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"lc_message":"Votre compte doit être réactivé"}}""")
        )
        val codeless = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Votre compte doit être réactivé", codeless?.message)

        // An error object carrying neither a code nor a message still blames nothing but the password.
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{}}"""))
        val bare = runCatching { repo.login("e@x", "pw") }.exceptionOrNull()
        assertEquals("Email ou mot de passe incorrect", bare?.message)
    }

    @Test
    fun `search keeps one card per title, preferring an included copy, and recounts the rail`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        fun card(id: String, title: String, locked: Boolean = false) =
            """{"id":"$id","title":{"text":"$title"},"is_locked":$locked,"actions":{"on_click":[{"type":"navigation","endpoint":{"url":"program-details/program/$id"}}]}}"""
        server.enqueue(
            MockResponse().setBody(
                """{"content":{"sections":[
                {"title":{"text":"Programmes (3)"},"component_type":"poster","components":[${card("a", "Show", locked = true)},${card("c", "Other")},${card("b", "show ")}]},
                {"title":{"text":"Chaînes (40)"},"component_type":"square","components":[${card("d", "Same")},${card("e", "Same")}]}
                ]}}"""
            )
        )

        val page = repo.search("show")

        // The copy the subscription includes stands in for the locked one, where the first copy was.
        assertEquals(listOf("b", "c"), page.rails[0].cards.map { it.id })
        assertEquals("Programmes (2)", page.rails[0].title)
        // A count above the cards received is a total the page does not hold: leave it.
        assertEquals("Chaînes (40)", page.rails[1].title)
    }

    @Test
    fun `a search result carries the logo of its channel`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(
            MockResponse().setBody(
                """{"content":{"sections":[{"title":{"text":"Programmes (2)"},"component_type":"poster","components":[
                {"id":"a","title":{"text":"Show"},"body":{"picture":{"url":"https://img/a"},"station_logo":{"url":"https://logo/f2"}}},
                {"id":"b","title":{"text":"Other"},"header":{"station_logo":{"url":"https://logo/tf1"}},"body":{"picture":{"url":"https://img/b"}}}
                ]}]}}"""
            )
        )

        val cards = repo.search("show").rails[0].cards

        assertEquals("https://logo/f2", cards[0].channelLogoUrl)
        assertEquals("https://img/a", cards[0].imageUrl)
        assertEquals("https://logo/tf1", cards[1].channelLogoUrl)
    }

    private class FakeSessionStore : SessionStore {
        var saved: UserSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: UserSession) { saved = session }
        override suspend fun clear() { saved = null; pick = null }
        var pick: ProfilePick? = null
        override suspend fun readPick() = pick
        override suspend fun savePick(pick: ProfilePick) { this.pick = pick }
    }

    private class FakeProgressStore : ProgressStore {
        val positions = mutableMapOf<String, Long>()
        override suspend fun read(key: String) = positions[key] ?: 0L
        override suspend fun save(key: String, positionMs: Long) { positions[key] = positionMs }
        override suspend fun clear(key: String) { positions.remove(key) }
        override suspend fun clearAll() { positions.clear() }
    }
}
