// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import it.allard.etincelle.core.model.UserSession
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InterceptorTest {

    private lateinit var server: MockWebServer
    private val session = SessionManager()
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val device = DeviceInfo(deviceId = "dev-1", brand = "b", model = "m", osVersion = "16")
        client = OkHttpClient.Builder()
            .addInterceptor(FuboHeadersInterceptor(device))
            // MockWebServer runs on localhost, so trust it here; host-allowlist behavior is tested below.
            .addInterceptor(AuthInterceptor(session) { true })
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun call(builder: Request.Builder.() -> Unit = {}) {
        server.enqueue(MockResponse().setBody("{}"))
        val request = Request.Builder().url(server.url("/x")).apply(builder).build()
        client.newCall(request).execute().close()
    }

    @Test
    fun `a device named outside ASCII still makes its requests`() {
        val device = DeviceInfo(deviceId = "dev-1", brand = "小米", model = "Redmi Note 13 青春版", osVersion = "١٤")
        server.enqueue(MockResponse().setBody("{}"))
        OkHttpClient.Builder().addInterceptor(FuboHeadersInterceptor(device)).build()
            .newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        val req = server.takeRequest()
        assertEquals("android", req.getHeader("x-device-brand"))
        assertEquals("Redmi Note 13", req.getHeader("x-device-model"))
        assertEquals("", req.getHeader("x-os-version"))
    }

    @Test
    fun `adds the molotov application id and device headers`() {
        call()
        val req = server.takeRequest()
        assertEquals("molotov", req.getHeader("x-application-id"))
        assertEquals("android_phone", req.getHeader("x-device-platform"))
        assertEquals("dev-1", req.getHeader("x-device-id"))
        assertEquals("widevine", req.getHeader("x-drm-scheme"))
    }

    @Test
    fun `adds bearer and user ids once authenticated`() {
        session.session = UserSession("AT", "RT", "user1", "profile1")
        call()
        val req = server.takeRequest()
        assertEquals("Bearer AT", req.getHeader("authorization"))
        assertEquals("user1", req.getHeader("x-user-id"))
        assertEquals("profile1", req.getHeader("x-profile-id"))
    }

    @Test
    fun `omits the bearer token for an untrusted host`() {
        session.session = UserSession("AT", "RT", "user1", "profile1")
        // Default host allowlist: MockWebServer's localhost is not a Fubo host, so no credentials.
        val guarded = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        server.enqueue(MockResponse().setBody("{}"))
        guarded.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
        val req = server.takeRequest()
        assertEquals(null, req.getHeader("authorization"))
        assertEquals(null, req.getHeader("x-user-id"))
    }

    @Test
    fun `a redirect to an untrusted host carries no account header`() {
        session.session = UserSession("AT", "RT", "user1", "profile1")
        // The same server under two names: only the first is trusted.
        val trusted = server.hostName
        val elsewhere = server.url("/there").newBuilder().host(if (trusted == "127.0.0.1") "localhost" else "127.0.0.1").build()
        val guarded = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(session) { it == trusted })
            .addNetworkInterceptor(RedirectGuardInterceptor { it == trusted })
            .build()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.toString()))
        server.enqueue(MockResponse().setBody("{}"))

        guarded.newCall(Request.Builder().url(server.url("/here")).header("X-MFA-Token", "MT").build()).execute().close()

        val first = server.takeRequest()
        assertEquals("Bearer AT", first.getHeader("authorization"))
        assertEquals("user1", first.getHeader("x-user-id"))
        val hop = server.takeRequest()
        assertEquals("/there", hop.path)
        assertEquals(null, hop.getHeader("authorization"))
        assertEquals(null, hop.getHeader("x-user-id"))
        assertEquals(null, hop.getHeader("x-profile-id"))
        assertEquals(null, hop.getHeader("X-MFA-Token"))
    }

    @Test
    fun `omits the two-factor token for an untrusted host`() {
        val guarded = OkHttpClient.Builder().addInterceptor(AuthInterceptor(session)).build()
        server.enqueue(MockResponse().setBody("{}"))
        guarded.newCall(Request.Builder().url(server.url("/x")).header("X-MFA-Token", "MT").build()).execute().close()
        assertEquals(null, server.takeRequest().getHeader("X-MFA-Token"))
        // A trusted host still gets it.
        call { header("X-MFA-Token", "MT") }
        assertEquals("MT", server.takeRequest().getHeader("X-MFA-Token"))
    }

    @Test
    fun `leaves an explicit authorization header alone for refresh`() {
        session.session = UserSession("AT", "RT", "user1", "profile1")
        call { header("authorization", "Bearer RT") }
        val req = server.takeRequest()
        assertEquals("Bearer RT", req.getHeader("authorization"))
    }

    @Test
    fun `retries a transient 404 on a GET once then returns the good response`() {
        val retryClient = OkHttpClient.Builder().addInterceptor(RetryInterceptor()).build()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val request = Request.Builder().url(server.url("/x")).build()
        val response = retryClient.newCall(request).execute()
        assertEquals(200, response.code)
        response.close()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a POST the server received is not sent again when its answer is lost`() {
        val once = OkHttpClient.Builder().addInterceptor(SendOnceInterceptor()).build()
        fun post() = once.newCall(Request.Builder().url(server.url("/x")).post("{}".toRequestBody()).build()).execute()
        // The first leaves a connection open, which the second goes out on and loses.
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("{}"))
        post().close()

        val error = runCatching { post().close() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(2, server.requestCount)
    }
}
