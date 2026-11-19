// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Json
import it.allard.etincelle.core.model.Avatar
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.Profiles

// --- Auth ---

/** The backend takes the account email in a `username` field. */
data class SigninRequest(
    @Json(name = "username") val email: String,
    val password: String,
)

/**
 * Tokens from a sign-in or a refresh. `v2/signin` wraps them in a `{type, payload}` envelope while
 * `refresh` and the pairing flow return them flat, so both shapes parse and [tokens] returns
 * whichever of the two actually carries them.
 */
data class SigninResponse(
    @Json(name = "access_token") val accessToken: String?,
    @Json(name = "refresh_token") val refreshToken: String?,
    @Json(name = "id_token") val idToken: String?,
    val payload: SigninResponse? = null,
    // The v2 envelope's kind: tokens, or "MFA" when the account asks for a code, with [token] (the MFA
    // token) in the payload.
    val type: String? = null,
    val token: String? = null,
) {
    val tokens: SigninResponse get() = payload ?: this

    /** The MFA token when the backend asks for a code instead of signing in, else null. */
    val mfaToken: String? get() = payload?.token?.takeIf { type.equals("MFA", ignoreCase = true) && it.isNotBlank() }
}

// --- Two-factor sign-in code page (/papi/v1/signin/mfa, template "mfa") ---
// Labels are texts ({"text": …}) in the page API, read leniently in case a plain string comes instead.

data class MfaPageResponse(val content: MfaContentDto?)
data class MfaContentDto(
    val metadata: MfaMetadataDto?,
    val form: MfaFormDto?,
    @Json(name = "resend_code_label") val resendCodeLabel: Any?,
    @Json(name = "resend_code_button") val resendCodeButton: MfaButtonDto?,
)
data class MfaMetadataDto(val title: Any?, val subtitle: Any?, val description: Any?)
data class MfaFormDto(val inputs: List<MfaInputDto>?, @Json(name = "submit_button") val submitButton: MfaButtonDto?)
data class MfaInputDto(val name: String?, val label: Any?, val placeholder: Any?, val length: Int?)
data class MfaButtonDto(val text: Any?, val actions: MfaActionsDto?)
data class MfaActionsDto(@Json(name = "on_click") val onClick: List<MfaActionDto>?)
data class MfaActionDto(val type: String?, val template: String?, val endpoint: EndpointDto?)

private fun textOf(value: Any?): String? = when (value) {
    is String -> value
    is Map<*, *> -> value["text"] as? String
    else -> null
}?.takeIf { it.isNotBlank() }

private fun MfaButtonDto.endpoint(template: String): EndpointDto? =
    actions?.onClick.orEmpty().firstOrNull { it.template == template }?.endpoint

/** The code challenge for [token] from its code page, or null when the page cannot verify a code. */
fun MfaPageResponse.toChallenge(token: String): MfaChallenge? {
    val c = content ?: return null
    val input = c.form?.inputs.orEmpty().firstOrNull { !it.name.isNullOrBlank() } ?: return null
    val inputName = input.name ?: return null
    val submit = c.form?.submitButton ?: return null
    val verify = submit.endpoint("mfa-verify") ?: return null
    val resend = c.resendCodeButton?.endpoint("resend-code")?.url
    val meta = c.metadata
    return MfaChallenge(
        token = token,
        title = textOf(meta?.title) ?: "Validation en deux étapes",
        message = listOfNotNull(textOf(meta?.subtitle), textOf(meta?.description)).joinToString("\n").ifBlank { null },
        inputLabel = textOf(input.label) ?: textOf(input.placeholder) ?: "Code",
        inputName = inputName,
        codeLength = input.length?.takeIf { it > 0 },
        submitLabel = textOf(submit.text) ?: "Valider",
        verifyUrl = verify.url ?: return null,
        verifyPayload = verify.payload.orEmpty(),
        resendLabel = resend?.let { textOf(c.resendCodeButton?.text) ?: textOf(c.resendCodeLabel) ?: "Renvoyer le code" },
        resendUrl = resend,
    )
}

// --- TV code/QR pairing (signin/code) ---

/** Response of `GET signin/code`: a short code the user confirms on their phone, with its lifetime. */
data class SignInCodeResponse(
    val code: String?,
    @Json(name = "issued_at") val issuedAt: Long?,
    @Json(name = "expires_at") val expiresAt: Long?,
)

data class SignInCodePollRequest(val code: String)

/**
 * Response of `POST signin/code` while the TV polls: [status] is "CODE_VALIDATING" until the user
 * confirms, then [data] carries the tokens (same shape as a normal sign-in).
 */
data class SignInCodeValidationResponse(
    val status: String?,
    val data: SigninResponse?,
)

data class UserResponse(val data: UserData?)

data class UserData(val id: String?, val profiles: List<ProfileDto>?)

data class ProfileDto(val id: String?, val name: String?)

// --- Profiles (/profile) ---

data class ProfilesResponse(
    val profiles: List<ProfileFullDto>?,
    val avatars: List<AvatarDto>?,
    @Json(name = "max_allowed") val maxAllowed: Int?,
)
data class ProfileFullDto(val id: String?, val name: String?, val avatar: AvatarDto?)
data class AvatarDto(val id: Int?, val url: String?)
data class ProfileRequest(val name: String, @Json(name = "avatar_id") val avatarId: Int?)
data class ProfileResponse(val profile: ProfileFullDto?)

fun ProfileFullDto.toProfile(): Profile? =
    Profile(id ?: return null, name?.takeIf { it.isNotBlank() } ?: "Profil", avatar?.id, avatar?.url)

// The official app allows 6 profiles when the backend gives no limit.
fun ProfilesResponse.toProfiles() = Profiles(
    profiles = profiles.orEmpty().mapNotNull { it.toProfile() },
    avatars = avatars.orEmpty().mapNotNull { a -> Avatar(a.id ?: return@mapNotNull null, a.url ?: return@mapNotNull null) },
    maxAllowed = maxAllowed ?: 6,
)

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
    @Json(name = "accessRightsV2") val accessRightsV2: AccessRightsV2Dto? = null,
    @Json(name = "nextProgramURL") val nextProgramUrl: String? = null,
    /**
     * Where the pictures of a live channel are: `{type: "timestamp", base_url, interval}`, one every
     * `interval` seconds, each at `base_url` followed by its time. Taken as it comes: its shape on
     * anything but live was not seen, and no answer is to be refused over it.
     */
    val thumbnails: Any? = null,
)

/** Per-mode air-windows and scrubbing rights on a live playback response; only [live] is used today. */
data class AccessRightsV2Dto(val live: AccessRightsV2WindowDto?)

/**
 * The current live programme's air-window. The backend has been seen to express the bounds either as
 * RFC3339-UTC strings ([startTime]/[endTime]) or as epoch-ms longs ([startTimeMs]/[endTimeMs]), so
 * both are parsed and the ms form is preferred.
 */
data class AccessRightsV2WindowDto(
    val startTime: String? = null,
    val endTime: String? = null,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
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

data class ProgramDto(val title: String?, val heading: String? = null)
