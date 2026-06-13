// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import it.allard.etincelle.core.model.UserSession
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
            .addInterceptor(AuthInterceptor(session))
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
}
