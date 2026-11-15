// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.LoginResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Two-factor sign-in. The envelope and the code page follow the official app's models (no account
 * with two-factor login was available to capture them live), so the parsing stays lenient.
 */
class MfaTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: FuboRepository
    private val session = SessionManager()
    private val store = MemoryStore()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
            .build()
            .create(FuboApi::class.java)
        repo = FuboRepository(api, session, store, NoProgress)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun page() = MockResponse().setBody(
        """{"content":{"template":"mfa",
          "metadata":{"title":{"text":"Vérifiez votre identité"},"subtitle":{"text":"Un code a été envoyé à m***@x.fr"}},
          "form":{"inputs":[{"name":"code","label":{"text":"Code à 6 chiffres"},"length":6,"type":"text"}],
            "submit_button":{"text":{"text":"Valider"},"actions":{"on_click":[{"type":"form_submit","template":"mfa-verify",
              "endpoint":{"method":"PUT","url":"${server.url("/mfa/verify")}","payload":{"flow":"signin"}}}]}}},
          "resend_code_label":{"text":"Pas reçu ?"},
          "resend_code_button":{"text":{"text":"Renvoyer le code"},"actions":{"on_click":[{"type":"form_submit","template":"resend-code",
            "endpoint":{"method":"PUT","url":"${server.url("/mfa/resend")}"}}]}}}}""",
    )

    private suspend fun challenge(): it.allard.etincelle.core.model.MfaChallenge {
        server.enqueue(MockResponse().setBody("""{"type":"MFA","payload":{"type":"email","token":"MFA1"}}"""))
        server.enqueue(page())
        return (repo.login("e@x", "pw") as LoginResult.CodeRequired).challenge
    }

    @Test
    fun signInWithTwoFactorAsksForTheCode() = runTest {
        val c = challenge()
        server.takeRequest()
        val pageRequest = server.takeRequest()
        assertEquals("/papi/v1/signin/mfa", pageRequest.path)
        assertEquals("MFA1", pageRequest.getHeader("X-MFA-Token"))
        assertEquals("Vérifiez votre identité", c.title)
        assertEquals("Un code a été envoyé à m***@x.fr", c.message)
        assertEquals("Code à 6 chiffres", c.inputLabel)
        assertEquals(6, c.codeLength)
        assertEquals("Valider", c.submitLabel)
        assertEquals("Renvoyer le code", c.resendLabel)
        // Nothing is signed in until the code is checked.
        assertEquals(null, session.session)
    }

    @Test
    fun theCodeGoesToTheVerifyUrlAndSignsIn() = runTest {
        val c = challenge()
        server.enqueue(MockResponse().setBody("""{"access_token":"AT","refresh_token":"RT","id_token":"IT"}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"id":"u1","profiles":[{"id":"p1"}]}}"""))
        val signed = repo.verifyCode(c, " 123456 ")
        server.takeRequest(); server.takeRequest()
        val verify = server.takeRequest()
        assertEquals("PUT", verify.method)
        assertEquals("/mfa/verify", verify.path)
        assertEquals("MFA1", verify.getHeader("X-MFA-Token"))
        assertEquals("""{"flow":"signin","code":"123456"}""", verify.body.readUtf8())
        assertEquals("p1", signed.profileId)
        assertEquals("AT", store.saved?.accessToken)
    }

    @Test
    fun resendingReplacesTheToken() = runTest {
        val c = challenge()
        server.enqueue(MockResponse().setBody("""{"type":"MFA","payload":{"token":"MFA2"}}"""))
        val next = repo.resendCode(c)
        assertEquals("MFA2", next.token)
        server.takeRequest(); server.takeRequest()
        val resend = server.takeRequest()
        assertEquals("PUT", resend.method)
        assertEquals("/mfa/resend", resend.path)
        assertEquals("MFA1", resend.getHeader("X-MFA-Token"))
    }

    @Test
    fun aWrongCodeSaysSoAndAnExpiredTokenSignsOut() = runTest {
        val c = challenge()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"INVALID_CODE","message":"bad"}}"""))
        assertEquals("Code incorrect", runCatching { repo.verifyCode(c, "1") }.exceptionOrNull()?.message)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"MFA_TOKEN_EXPIRED","message":"x"}}"""))
        assertTrue(runCatching { repo.verifyCode(c, "1") }.exceptionOrNull() is AppError.Unauthorized)
    }

    @Test
    fun anAccountWithoutTwoFactorSignsInAsBefore() = runTest {
        server.enqueue(MockResponse().setBody("""{"type":"TOKEN","payload":{"access_token":"AT","refresh_token":"RT"}}"""))
        server.enqueue(MockResponse().setBody("""{"data":{"id":"u1","profiles":[{"id":"p1"}]}}"""))
        assertTrue(repo.login("e@x", "pw") is LoginResult.SignedIn)
    }

    private class MemoryStore : SessionStore {
        var saved: UserSession? = null
        override suspend fun read() = saved
        override suspend fun save(session: UserSession) { saved = session }
        override suspend fun clear() { saved = null }
        override suspend fun readPick(): ProfilePick? = null
        override suspend fun savePick(pick: ProfilePick) = Unit
    }

    private object NoProgress : ProgressStore {
        override suspend fun read(key: String) = 0L
        override suspend fun save(key: String, positionMs: Long) = Unit
        override suspend fun clear(key: String) = Unit
        override suspend fun clearAll() = Unit
    }
}
