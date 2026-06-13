// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Url

/** Subset of the Fubo (Molotov) REST API used by the app. */
interface FuboApi {

    @PUT("signin")
    suspend fun signin(@Body body: SigninRequest): SigninResponse

    /** Exchanges the refresh token (passed as the Authorization bearer) for fresh tokens. */
    @POST("refresh")
    suspend fun refresh(@Header("Authorization") bearer: String): SigninResponse

    @GET("user")
    suspend fun user(): UserResponse

    /** Server-driven home page (carousels of cards). */
    @GET("papi/v1/page/home")
    suspend fun homePage(): PageResponse

    /** Follow an absolute page endpoint URL from a card action. */
    @GET
    suspend fun pageByUrl(@Url url: String): PageResponse

    /** Search; results come back as a normal page of rails. */
    @GET("papi/v1/search")
    suspend fun search(@Query("query") query: String): PageResponse

    /** Live guide (EPG): channels with their programs over an RFC3339-UTC time window. */
    @GET("epg")
    suspend fun epg(
        @Query("startTime") startTime: String,
        @Query("endTime") endTime: String,
        @Query("limit") limit: Int,
        @Query("ignoreEmpty") ignoreEmpty: Boolean = true,
    ): EpgResponse

    /** Live: pass [channelId]+type=live. VOD/replay: pass [id]+type=vod. */
    @GET("vapi/asset/v1")
    suspend fun playbackAsset(
        @Query("channelId") channelId: String? = null,
        @Query("id") id: String? = null,
        @Query("type") type: String,
        @Query("wants_trackers") wantsTrackers: Boolean = true,
    ): PlaybackResponse
}
