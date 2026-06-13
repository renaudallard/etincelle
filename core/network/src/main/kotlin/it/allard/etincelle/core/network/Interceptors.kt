// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Adds the static Molotov client + device headers to every request. */
class FuboHeadersInterceptor(private val device: DeviceInfo) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("user-agent", EnvConfig.USER_AGENT)
            .header("x-application-id", EnvConfig.APPLICATION_ID)
            .header("x-client-version", EnvConfig.CLIENT_VERSION)
            .header("x-os", "android")
            .header("x-os-version", device.osVersion)
            .header("x-device-app", "android")
            .header("x-device-platform", "android_phone")
            .header("x-device-type", "phone")
            .header("x-device-group", "mobile")
            .header("x-device-brand", device.brand)
            .header("x-device-model", device.model)
            .header("x-device-id", device.deviceId)
            .header("x-preferred-language", "fr-FR")
            .header("x-supported-streaming-protocols", "hls,dash")
            .header("x-drm-scheme", "widevine")
            .header("x-supported-features", EnvConfig.SUPPORTED_FEATURES)
            .build()
        return chain.proceed(request)
    }
}

/**
 * Adds the bearer token and user/profile ids once authenticated. Requests that already carry an
 * Authorization header (the `/refresh` call, which sends the refresh token) are left untouched.
 */
class AuthInterceptor(private val session: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("authorization") != null) return chain.proceed(request)

        val builder = request.newBuilder()
        session.accessToken?.let { builder.header("authorization", "Bearer $it") }
        session.userId?.takeIf { it.isNotBlank() }?.let { builder.header("x-user-id", it) }
        session.profileId?.takeIf { it.isNotBlank() }?.let { builder.header("x-profile-id", it) }
        return chain.proceed(builder.build())
    }
}

/**
 * Retries transient failures: network errors (e.g. the device's Wi-Fi briefly sleeping) a few
 * times, and a transient 5xx/404 on an idempotent GET once (the papi/vapi backend returns those
 * intermittently for endpoints that work moments later).
 */
class RetryInterceptor(
    private val maxNetworkRetries: Int = 2,
    private val maxStatusRetries: Int = 1,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var networkAttempt = 0
        var statusAttempt = 0
        while (true) {
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                if (networkAttempt >= maxNetworkRetries) throw e
                networkAttempt++
                backoff(networkAttempt)
                continue
            }
            if (request.method == "GET" && statusAttempt < maxStatusRetries && response.code.isTransientStatus()) {
                response.close()
                statusAttempt++
                backoff(statusAttempt)
                continue
            }
            return response
        }
    }

    private fun Int.isTransientStatus(): Boolean = this == 404 || this in 500..599

    private fun backoff(attempt: Int) {
        try {
            Thread.sleep(400L * attempt)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted during retry backoff", ie)
        }
    }
}
