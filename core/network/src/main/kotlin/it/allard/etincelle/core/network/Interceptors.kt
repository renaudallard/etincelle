// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import okhttp3.Interceptor
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import okio.BufferedSink

/** Adds the static Molotov client + device headers to every request. */
class FuboHeadersInterceptor(private val device: DeviceInfo) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
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
            .header("x-preferred-language", "fr-FR")
            .header("x-supported-streaming-protocols", "hls,dash")
            .header("x-drm-scheme", "widevine")
            .header("x-supported-features", EnvConfig.SUPPORTED_FEATURES)
        // A call may pin its own x-device-id (TV pairing uses a fresh one per attempt so each generated
        // code is distinct); otherwise use this install's stable id.
        if (original.header("x-device-id") == null) builder.header("x-device-id", device.deviceId)
        return chain.proceed(builder.build())
    }
}

/**
 * Adds the bearer token and user/profile ids once authenticated. Requests that already carry an
 * Authorization header (the `/refresh` call, which sends the refresh token) are left untouched.
 */
class AuthInterceptor(
    private val session: SessionManager,
    private val isTrustedHost: (String) -> Boolean = String::isTrustedApiHost,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("authorization") != null) return chain.proceed(request)
        // pageByUrl/pingProgress follow backend-supplied absolute URLs, so only attach the account
        // credentials to Fubo's own hosts; a tampered or hostile URL must never receive the bearer token.
        if (!isTrustedHost(request.url.host)) {
            // Nor the two-factor token, which the sign-in calls carry to urls the backend names.
            return chain.proceed(request.newBuilder().removeHeader(MFA_TOKEN_HEADER).build())
        }

        val builder = request.newBuilder()
        session.accessToken?.let { builder.header("authorization", "Bearer $it") }
        session.userId?.takeIf { it.isNotBlank() }?.let { builder.header("x-user-id", it) }
        session.profileId?.takeIf { it.isNotBlank() }?.let { builder.header("x-profile-id", it) }
        return chain.proceed(builder.build())
    }
}

/**
 * Keeps the account's headers from following a redirect off the app's own hosts. [AuthInterceptor] sees
 * a call once, before any redirect: each hop after it is sent the same headers, of which OkHttp only
 * drops Authorization when the host changes. Runs on the network, so on every hop.
 */
class RedirectGuardInterceptor(
    private val isTrustedHost: (String) -> Boolean = String::isTrustedApiHost,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (isTrustedHost(request.url.host)) return chain.proceed(request)
        val bare = request.newBuilder()
        for (header in ACCOUNT_HEADERS) bare.removeHeader(header)
        return chain.proceed(bare.build())
    }
}

private const val MFA_TOKEN_HEADER = "X-MFA-Token"
private val ACCOUNT_HEADERS = listOf("authorization", MFA_TOKEN_HEADER, "x-user-id", "x-profile-id")

/**
 * Whether this host is one of the app's own backend domains (Fubo's api-eu.fubo.tv plus the molotov.tv
 * tenant) or a subdomain of them, as the backend hands back in page/playhead/heartbeat URLs.
 * Credentials are attached only to these.
 */
fun String.isTrustedApiHost(): Boolean {
    val h = lowercase()
    return h == "fubo.tv" || h.endsWith(".fubo.tv") || h == "molotov.tv" || h.endsWith(".molotov.tv")
}

/**
 * Marks the body of anything but a GET as to be sent once. On a connection that fails OkHttp sends a
 * request again by itself, also one the server had already received in full when its answer was lost:
 * a recording is then scheduled twice, a profile created twice, a sign-in code spent.
 */
class SendOnceInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (request.method == "GET" || body == null || body.isOneShot()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().method(request.method, Once(body)).build())
    }

    private class Once(private val body: RequestBody) : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
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
                // Only replay idempotent GETs; replaying a POST/PUT could duplicate a recording or
                // a sign-in if the request actually reached the server before the failure.
                if (request.method != "GET" || networkAttempt >= maxNetworkRetries) throw e
                networkAttempt++
                backoff(chain, networkAttempt)
                continue
            }
            if (request.method == "GET" && statusAttempt < maxStatusRetries && response.code.isTransientStatus()) {
                response.close()
                statusAttempt++
                backoff(chain, statusAttempt)
                continue
            }
            return response
        }
    }

    private fun Int.isTransientStatus(): Boolean = this == 404 || this in 500..599

    // Sleep in short slices so a cancelled call aborts the backoff promptly instead of blocking the
    // OkHttp thread for the full delay.
    private fun backoff(chain: Interceptor.Chain, attempt: Int) {
        val start = System.nanoTime()
        val delayNanos = 400L * attempt * 1_000_000
        // Compare elapsed time, not an absolute deadline: nanoTime is only meaningful as a difference,
        // and (now - start) stays correct across the counter's wraparound where (now < deadline) can not.
        while (System.nanoTime() - start < delayNanos) {
            if (chain.call().isCanceled()) throw IOException("Cancelled during retry backoff")
            try {
                Thread.sleep(50)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted during retry backoff", ie)
            }
        }
    }
}
