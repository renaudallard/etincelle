// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/** Subset of the Fubo (Molotov) REST API used by the app. */
interface FuboApi {

    @PUT("signin")
    suspend fun signin(@Body body: SigninRequest): SigninResponse

    /** TV pairing: generates a short pairing code (no auth) tied to [deviceId] (a fresh id per attempt
     * so each generated code is distinct; the poll must reuse the same id). */
    @GET("signin/code")
    suspend fun signInCode(@Header("x-device-id") deviceId: String): SignInCodeResponse

    /** TV pairing: polls a code's status (with the same device id that generated it); `data` carries
     * the tokens once the user confirms it. */
    @POST("signin/code")
    suspend fun pollSignInCode(
        @Header("x-device-id") deviceId: String,
        @Body body: SignInCodePollRequest,
    ): SignInCodeValidationResponse

    /** TV pairing: confirms a code from a signed-in device (authenticated; authorizes the TV). */
    @PUT("signin/code")
    suspend fun confirmSignInCode(@Body body: SignInCodePollRequest): okhttp3.ResponseBody

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

    /** The full channel directory (used to label live cards with their channel name). */
    @GET("papi/v1/page/channels")
    suspend fun channelsPage(): PageResponse

    /** Search; results come back as a normal page of rails. */
    @GET("papi/v1/search")
    suspend fun search(@Query("query") query: String): PageResponse

    /** A program's detail page; the "À propos" tab carries both the metadata and the cast/credits. */
    @GET("papi/v1/program-details/program/{id}")
    suspend fun programDetail(
        @Path("id") id: String,
        @Query("tabID") tab: String = "id-tab-about",
    ): PageResponse

    /** A series' detail page; same shape as a program detail. */
    @GET("papi/v1/program-details/series/{id}")
    suspend fun seriesDetail(
        @Path("id") id: String,
        @Query("tabID") tab: String = "id-tab-about",
    ): PageResponse

    /** A live channel's detail page; same shape as a program detail, carries the record CTA. */
    @GET("papi/v1/program-details/channel/{id}")
    suspend fun channelDetail(
        @Path("id") id: String,
        @Query("tabID") tab: String = "id-tab-about",
    ): PageResponse

    /** Replays a server-driven record action (its api_call url + payload); the response body is unused.
     * The url is absolute, so it also carries the endpoint (add-recording, record-new-episodes, …). */
    @POST
    suspend fun postAction(
        @Url url: String,
        @Body payload: Map<String, @JvmSuppressWildcards Any?>,
    ): okhttp3.ResponseBody

    /** Posts a server playhead (continue-watching) progress update to the response-supplied url. */
    @POST
    suspend fun pingProgress(
        @Url url: String,
        @Body payload: Map<String, @JvmSuppressWildcards Any?>,
    ): okhttp3.ResponseBody

    /**
     * DVR recordings. [status] must be "recorded" or "scheduled": status=all returns an empty body,
     * so the two statuses are fetched separately and merged.
     */
    @GET("dvr/v2/list")
    suspend fun dvrList(
        @Query("sort") sort: String = "date",
        @Query("status") status: String,
    ): DvrListResponse

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
