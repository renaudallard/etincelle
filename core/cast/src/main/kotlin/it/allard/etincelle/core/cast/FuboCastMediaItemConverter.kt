// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.model.coerceWholeNumbers
import org.json.JSONObject

private const val DRM_HEADER_TOKEN = "x-dt-auth-token"
private const val HLS = "application/x-mpegurl"
private const val VAPI_URL = "https://api-eu.fubo.tv/"

/**
 * Converts a Media3 [MediaItem] to a Cast [MediaQueueItem]. Two receivers are supported (see
 * [CastReceiver]):
 *
 * - Custom `9527437F` (default): packs the DASH stream + DRMtoday Widevine license URL +
 *   `x-dt-auth-token` into `customData` (`stream_url`, `content_type`, `license_url`, `drm_token`);
 *   the receiver injects the header on the license request.
 * - Official `D4E9D842` (experimental): the receiver fetches HLS itself, so we hand off a
 *   session-handoff `customData` (tokens + Fubo ids + endpoints), reverse-engineered from Molotov 5.51.
 */
@UnstableApi
class FuboCastMediaItemConverter(
    private val context: Context,
    private val session: () -> UserSession? = { null },
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val source = mediaItem.localConfiguration?.tag as? PlaybackSource
        val title = source?.title ?: mediaItem.mediaMetadata.title?.toString()
        val isLive = source?.isLive ?: false

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            if (!title.isNullOrEmpty()) putString(MediaMetadata.KEY_TITLE, title)
        }

        val official = CastReceiver.isOfficial(context)
        val customData = if (official) officialCustomData(source, isLive) else customReceiverData(mediaItem, source)
        // The official receiver resolves the stream from customData, so it gets no contentId.
        val contentId = if (official) "" else customData.optString("stream_url")
        val contentType = if (official) HLS else MimeTypes.APPLICATION_MPD

        val mediaInfo = MediaInfo.Builder(contentId)
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(mediaInfo).setCustomData(customData).build()
    }

    private fun customReceiverData(mediaItem: MediaItem, source: PlaybackSource?): JSONObject {
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
            if (!licenseUrl.isNullOrEmpty() && !token.isNullOrEmpty()) {
                put("license_url", licenseUrl)
                put("drm_token", token)
            }
            // Let the receiver report continue-watching progress to the playhead itself: the phone is
            // not reliably present during a cast session, so it cannot be relied on to do it.
            val s = session()
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
                s.profileId.takeIf { it.isNotEmpty() }?.let { put("profile_id", it) }
                s.userId.takeIf { it.isNotEmpty() }?.let { put("user_id", it) }
            }
        }
    }

    // The official receiver's session-handoff customData (reverse-engineered from Molotov 5.51).
    private fun officialCustomData(source: PlaybackSource?, isLive: Boolean): JSONObject {
        val s = session()
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

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val customData = info?.customData
        val uri = customData?.optString("stream_url").orEmpty().ifEmpty { info?.contentId.orEmpty() }
        val builder = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD)
        val licenseUrl = customData?.optString("license_url")
        val token = customData?.optString("drm_token")
        if (!licenseUrl.isNullOrEmpty() && !token.isNullOrEmpty()) {
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl)
                    .setLicenseRequestHeaders(mapOf(DRM_HEADER_TOKEN to token))
                    .build(),
            )
        }
        return builder.build()
    }
}
