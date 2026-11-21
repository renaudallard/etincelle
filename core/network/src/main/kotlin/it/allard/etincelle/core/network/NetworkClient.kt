// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** Builds the configured OkHttp/Retrofit/Moshi stack. Manual DI for now (Hilt comes later). */
class NetworkClient(
    val session: SessionManager = SessionManager(),
    device: DeviceInfo = DeviceInfo(),
    debug: Boolean = false,
) {
    // The response types carry adapters generated at build time (@JsonClass): no reflection.
    val moshi: Moshi = Moshi.Builder().build()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RetryInterceptor())
        .addInterceptor(FuboHeadersInterceptor(device))
        .addInterceptor(AuthInterceptor(session))
        .addInterceptor(SendOnceInterceptor())
        .addNetworkInterceptor(RedirectGuardInterceptor())
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC),
                )
            }
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(EnvConfig.BASE_URL)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
}
