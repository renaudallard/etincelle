// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.network.ProgressStore
import it.allard.etincelle.core.network.SessionManager
import it.allard.etincelle.core.network.SessionStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun `login loads the profile and persists the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"AT","refresh_token":"RT","id_token":"IT"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"id":"u1","profiles":[{"id":"p1"}]}}"""))

        val result = repo.login("e@x", "pw")

        assertEquals("u1", result.userId)
        assertEquals("p1", result.profileId)
        assertEquals("AT", session.accessToken)
        assertEquals("AT", store.saved?.accessToken)
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
        server.enqueue(MockResponse().setBody("""{"response":[]}""")) // loadHome also fetches recorded DVR
        server.enqueue(MockResponse().setBody("""{"response":[]}""")) // and scheduled DVR

        repo.loadHome()

        assertEquals("NEW", session.accessToken)
        assertEquals("NEW", store.saved?.accessToken)
        assertEquals(5, server.requestCount)
        server.takeRequest() // the 401'd home
        val refresh = server.takeRequest()
        assertEquals("/refresh", refresh.path)
        assertEquals("Bearer RT", refresh.getHeader("Authorization"))
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
    fun `savePlaybackPosition stores a position and clears it at zero`() = runTest {
        repo.savePlaybackPosition("VOD_7", 30_000L)
        assertEquals(30_000L, progress.positions["VOD_7"])

        repo.savePlaybackPosition("VOD_7", 0L)
        assertEquals(null, progress.positions["VOD_7"])
    }

    @Test
    fun `a server error surfaces a friendly French message`() = runTest {
        session.session = UserSession("AT", "RT", "u1", "p1")
        server.enqueue(MockResponse().setResponseCode(500))

        val error = runCatching { repo.loadHome() }.exceptionOrNull()

        assertTrue(error is AppError.Network)
        assertEquals("Service indisponible, réessayez", error?.message)
    }

    private class FakeSessionStore : SessionStore {
        var saved: UserSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: UserSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private class FakeProgressStore : ProgressStore {
        val positions = mutableMapOf<String, Long>()
        override suspend fun read(key: String) = positions[key] ?: 0L
        override suspend fun save(key: String, positionMs: Long) { positions[key] = positionMs }
        override suspend fun clear(key: String) { positions.remove(key) }
    }
}
