// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Json

// --- Auth ---

data class SigninRequest(val email: String, val password: String)

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
    val type: String?,
    val program: ProgramDto?,
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
