// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** Profiles: the /profile list (shape seen live) and when to ask who is watching. */
class ProfilesTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: FuboRepository
    private val session = SessionManager()
    private val store = MemoryStore()
    private var now = 10L * DAY

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build()
            .create(FuboApi::class.java)
        repo = FuboRepository(api, session, store, NoProgress, clock = { now })
        session.session = UserSession("AT", "RT", "u1", "p1")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun profiles(vararg ids: String) = MockResponse().setBody(
        """{"profiles":[${ids.joinToString(",") { """{"id":"$it","name":"N$it","avatar":{"id":0,"url":"https://images-eu.fubo.tv/profile_avatars/01.png"}}""" }}],
           "avatars":[{"id":0,"url":"https://images-eu.fubo.tv/profile_avatars/01.png"},{"id":1,"url":"https://images-eu.fubo.tv/profile_avatars/02.png"}],
           "max_allowed":6}""",
    )

    @Test
    fun profileListMapsProfilesAvatarsAndLimit() = runTest {
        server.enqueue(profiles("p1", "p2"))
        val list = repo.profiles()
        assertEquals(listOf("p1", "p2"), list.profiles.map { it.id })
        assertEquals("Np2", list.profiles[1].name)
        assertEquals(0, list.profiles[0].avatarId)
        assertEquals(2, list.avatars.size)
        assertEquals(6, list.maxAllowed)
    }

    @Test
    fun oneProfileNeverAsks() = runTest {
        server.enqueue(profiles("p1"))
        assertNull(repo.profilesToPick())
    }

    @Test
    fun severalProfilesAskUntilOneIsPicked() = runTest {
        server.enqueue(profiles("p1", "p2"))
        assertNotNull(repo.profilesToPick())
        server.enqueue(profiles("p1", "p2"))
        repo.selectProfile("p2")
        assertEquals("p2", session.profileId)
        assertEquals("p2", store.saved?.profileId)
        server.enqueue(profiles("p1", "p2"))
        assertNull(repo.profilesToPick())
    }

    @Test
    fun aPickOlderThanTwoDaysAsksAgain() = runTest {
        store.pick = ProfilePick(now - 2 * DAY - 1, "p1,p2")
        server.enqueue(profiles("p1", "p2"))
        assertNotNull(repo.profilesToPick())
        store.pick = ProfilePick(now - 2 * DAY + 1000, "p1,p2")
        server.enqueue(profiles("p1", "p2"))
        assertNull(repo.profilesToPick())
    }

    @Test
    fun changedProfilesAskAgain() = runTest {
        store.pick = ProfilePick(now, "p1,p2")
        server.enqueue(profiles("p1", "p2", "p3"))
        assertNotNull(repo.profilesToPick())
    }

    @Test
    fun aDeletedCurrentProfileAsksAgain() = runTest {
        store.pick = ProfilePick(now, "p2,p3")
        server.enqueue(profiles("p2", "p3"))
        assertNotNull(repo.profilesToPick())
    }

    @Test
    fun createSendsNameAndAvatar() = runTest {
        server.enqueue(MockResponse().setBody("""{"profile":{"id":"p9","name":"Test","avatar":{"id":3,"url":"https://x/04.png"}}}"""))
        val created = repo.createProfile("Test", 3)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/profile", request.path)
        assertEquals("""{"name":"Test","avatar_id":3}""", request.body.readUtf8())
        assertEquals("p9", created.id)
    }

    @Test
    fun choosingAProfileKeepsTokensRenewedMeanwhile() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"access_token":"NEW","refresh_token":"RT2"}"""))
        server.enqueue(profiles("p1", "p2"))
        repo.selectProfile("p2")
        val now = session.session
        assertEquals("NEW", now?.accessToken)
        assertEquals("RT2", now?.refreshToken)
        assertEquals("p2", now?.profileId)
        assertEquals(now, store.saved)
    }

    private class MemoryStore : SessionStore {
        var saved: UserSession? = null
        var pick: ProfilePick? = null
        override suspend fun read() = saved
        override suspend fun save(session: UserSession) { saved = session }
        override suspend fun clear() { saved = null; pick = null }
        override suspend fun readPick() = pick
        override suspend fun savePick(pick: ProfilePick) { this.pick = pick }
    }

    private object NoProgress : ProgressStore {
        override suspend fun read(key: String) = 0L
        override suspend fun save(key: String, positionMs: Long) = Unit
        override suspend fun clear(key: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
