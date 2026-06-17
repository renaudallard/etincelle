// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Json

// --- Auth ---

data class SigninRequest(val email: String, val password: String)

// --- Recording ---

data class AddRecordingRequest(
    @Json(name = "action_name") val actionName: String = "add-recording",
    @Json(name = "params") val params: AddRecordingParams,
    @Json(name = "metadatas") val metadatas: Map<String, String>,
)

data class AddRecordingParams(
    @Json(name = "asset_id") val assetId: String,
    @Json(name = "is_upcoming") val isUpcoming: String = "false",
)

data class SigninResponse(
    @Json(name = "access_token") val accessToken: String?,
    @Json(name = "refresh_token") val refreshToken: String?,
    @Json(name = "id_token") val idToken: String?,
)

data class UserResponse(val data: UserData?)

data class UserData(val id: String?, val profiles: List<ProfileDto>?)

data class ProfileDto(val id: String?, val name: String?)

// --- Playback (/vapi/asset/v1) ---

data class PlaybackResponse(
    val stream: StreamDto?,
    @Json(name = "drm_v2") val drmV2: DrmV2Dto?,
    val drm: DrmV1Dto?,
    val heartbeat: UrlHolderDto?,
    val concurrency: ConcurrencyDto?,
    val playhead: PlayHeadDto?,
    val type: String?,
    val program: ProgramDto?,
)

/**
 * Server playhead (continue-watching) progress write. Present for VOD/DVR, absent for live. The
 * [payload] is a template echoed back verbatim on the POST with its "lastOffset" entry replaced by
 * the position in seconds.
 */
data class PlayHeadDto(
    val method: String?,
    val url: String?,
    val payload: Map<String, Any?>?,
)

data class StreamDto(
    val url: String?,
    val packagingProtocol: String?,
    val drmProtected: Boolean?,
    val live: Boolean?,
)

data class DrmV2Dto(val scheme: String?, val license: LicenseDto?)

data class LicenseDto(val url: String?, val headers: Map<String, String>?)

data class DrmV1Dto(
    val scheme: String?,
    val licenseUrl: String?,
    val licenseUrlHeaders: Map<String, String>?,
    val token: String?,
)

data class UrlHolderDto(val url: String?)

data class ConcurrencyDto(val heartbeatUrl: String?)

data class ProgramDto(val title: String?)
