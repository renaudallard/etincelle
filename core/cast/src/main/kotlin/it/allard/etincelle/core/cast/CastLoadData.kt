// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.model.coerceWholeNumbers
import org.json.JSONObject

internal const val DRM_HEADER_TOKEN = "x-dt-auth-token"

// The spacing of a live channel's pictures the receiver's rule counts in, as this app's does.
private const val LIVE_PICTURE_EVERY_S = 4

private const val HLS = "application/x-mpegurl"
private const val VAPI_URL = "https://api-eu.fubo.tv/"

/**
 * What a LOAD carries to the receiver, shared by the Play Services path ([FuboCastMediaItemConverter])
 * and the direct one ([DirectCastSession]) so the receiver contract has a single definition. Two
 * receivers are supported (see [CastReceiver]):
 *
 * - Custom `9527437F` (default): packs the DASH stream + DRMtoday Widevine license URL +
 *   `x-dt-auth-token` into `customData` (`stream_url`, `content_type`, `license_url`, `drm_token`);
 *   the receiver injects the header on the license request.
 * - Official `D4E9D842` (experimental): the receiver fetches HLS itself, so we hand off a
 *   session-handoff `customData` (tokens + Fubo ids + endpoints), reverse-engineered from Molotov 5.51.
 */
internal object CastLoadData {

    /** The media fields of a LOAD for [mediaItem]. */
    class Media(val contentId: String, val contentType: String, val isLive: Boolean, val title: String?, val customData: JSONObject)

    fun media(context: Context, mediaItem: MediaItem, session: UserSession?): Media {
        val source = mediaItem.localConfiguration?.tag as? PlaybackSource
        val title = source?.title ?: mediaItem.mediaMetadata.title?.toString()
        val isLive = source?.isLive ?: false
        val official = CastReceiver.isOfficial(context)
        val customData = if (official) officialCustomData(context, source, isLive, session) else customReceiverData(mediaItem, source, session)
        // The official receiver resolves the stream from customData, so it gets no contentId.
        val contentId = if (official) "" else customData.optString("stream_url")
        val contentType = if (official) HLS else MimeTypes.APPLICATION_MPD
        return Media(contentId, contentType, isLive, title?.takeIf { it.isNotEmpty() }, customData)
    }

    /** The `media` object of a LOAD request on the Cast media namespace, as the direct path sends it. */
    fun mediaJson(media: Media): JSONObject = JSONObject().apply {
        put("contentId", media.contentId)
        put("contentType", media.contentType)
        put("streamType", if (media.isLive) "LIVE" else "BUFFERED")
        put("metadata", JSONObject().apply {
            put("metadataType", 0)
            media.title?.let { put("title", it) }
        })
        put("customData", media.customData)
    }

    private fun customReceiverData(mediaItem: MediaItem, source: PlaybackSource?, s: UserSession?): JSONObject {
        val drmConfig = mediaItem.localConfiguration?.drmConfiguration
        val widevine = source?.drm as? DrmSpec.Widevine
        val streamUrl = source?.manifestUrl ?: mediaItem.localConfiguration?.uri?.toString().orEmpty()
        val licenseUrl = widevine?.licenseUrl ?: drmConfig?.licenseUri?.toString()
        val token = widevine?.licenseHeaders?.get(DRM_HEADER_TOKEN)
            ?: drmConfig?.licenseRequestHeaders?.get(DRM_HEADER_TOKEN)
        return JSONObject().apply {
            put("stream_url", streamUrl)
            put("content_type", MimeTypes.APPLICATION_MPD)
            // A rewound live cast: ask the receiver to start this many seconds behind the live edge
            // (it seeks there once the window is known) instead of clamping to the edge. Round to the
            // nearest second so the resume does not creep toward the edge on each cast.
            if (source?.isLive == true && source.liveRewindOffsetMs > 0) {
                put("live_rewind_sec", (source.liveRewindOffsetMs + 500) / 1000)
            }
            // The on-screen programme's air-window (epoch ms), so the receiver can mark the current
            // show on its seek bar like the phone does. Live only; absent when no times are known.
            source?.programWindow?.takeIf { source.isLive }?.let {
                put("program_start_ms", it.startMs)
                put("program_end_ms", it.endMs)
            }
            // Where the service keeps pictures of the channel, one every four seconds: the receiver
            // looks in them for the ad breaks the stream does not mark, as this app does (LiveBreaks).
            source?.liveThumbnails?.takeIf { source.isLive && it.intervalSec == LIVE_PICTURE_EVERY_S }?.let {
                put("live_thumbnails_url", it.baseUrl)
            }
            if (!licenseUrl.isNullOrEmpty() && !token.isNullOrEmpty()) {
                put("license_url", licenseUrl)
                put("drm_token", token)
            }
            // Let the receiver report continue-watching progress to the playhead itself: the phone is
            // not reliably present during a cast session, so it cannot be relied on to do it.
            val payload = source?.progressPayload
            if (s != null && source?.progressUrl != null && payload != null && s.accessToken.isNotEmpty()) {
                put("playhead_url", source.progressUrl)
                put(
                    "playhead_payload",
                    JSONObject().apply {
                        // Moshi decoded JSON integers as Double; send whole numbers back as integers.
                        payload.forEach { (key, value) -> put(key, coerceWholeNumbers(value)) }
                    },
                )
                put("access_token", s.accessToken)
                // Where the credits start: left from there on, the episode counts as watched, as when
                // this phone reports it itself.
                source.upNext?.let { put("credits_sec", it.atSec) }
                s.profileId.takeIf { it.isNotEmpty() }?.let { put("profile_id", it) }
                s.userId.takeIf { it.isNotEmpty() }?.let { put("user_id", it) }
            }
        }
    }

    // The official receiver's session-handoff customData (reverse-engineered from Molotov 5.51).
    private fun officialCustomData(context: Context, source: PlaybackSource?, isLive: Boolean, s: UserSession?): JSONObject {
        val recording = source?.originRecordingAssetId != null
        return JSONObject().apply {
            put("user_info", JSONObject().apply {
                s?.profileId?.takeIf { it.isNotEmpty() }?.let { put("profile_id", it) }
                s?.userId?.takeIf { it.isNotEmpty() }?.let { put("user_id", it) }
            })
            put("access_data", JSONObject().apply {
                s?.accessToken?.let { put("access_token", it) }
                s?.refreshToken?.let { put("refresh_token", it) }
            })
            put("headers", JSONObject().apply {
                put("device_id", CastReceiver.stableId(context, "cast_device_id"))
                put("sender_app_session_id", CastReceiver.stableId(context, "cast_session_id"))
            })
            put("stream_data", JSONObject().apply {
                if (isLive) {
                    source?.originChannelId?.let { put("station_id", it) }
                } else {
                    (source?.originVodId ?: source?.originRecordingAssetId)?.let { put("airing_id", it) }
                    source?.manifestUrl?.let { put("content_url", it) }
                }
                put("vapi_url", VAPI_URL)
                put("pdp_url", VAPI_URL)
                put("stream_entered_time", System.currentTimeMillis())
            })
            put("env", "production")
            put("playback_type", if (isLive) "LIVE" else if (recording) "DVR" else "VOD")
            put("casting_source", "etincelle")
            put("contract_version", "2.3")
            put("sender_player_version", "1.129.4")
            put("language", "fr")
        }
    }
}
