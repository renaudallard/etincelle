// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

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
import org.json.JSONObject

private const val DRM_HEADER_TOKEN = "x-dt-auth-token"

/**
 * Converts a Media3 [MediaItem] to a Cast [MediaQueueItem], packing the DRMtoday Widevine license
 * URL + `x-dt-auth-token` into `customData` (`stream_url`, `content_type`, `license_url`,
 * `drm_token`) for the custom receiver, which injects the header on the license request. The title
 * travels via [MediaMetadata] and liveness via the Cast stream type. The default converter drops
 * DRM, so this is required to cast protected content.
 */
@UnstableApi
class FuboCastMediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val source = mediaItem.localConfiguration?.tag as? PlaybackSource
        val drmConfig = mediaItem.localConfiguration?.drmConfiguration
        val widevine = source?.drm as? DrmSpec.Widevine

        val streamUrl = source?.manifestUrl ?: mediaItem.localConfiguration?.uri?.toString().orEmpty()
        val licenseUrl = widevine?.licenseUrl ?: drmConfig?.licenseUri?.toString()
        val token = widevine?.licenseHeaders?.get(DRM_HEADER_TOKEN)
            ?: drmConfig?.licenseRequestHeaders?.get(DRM_HEADER_TOKEN)
        val title = source?.title ?: mediaItem.mediaMetadata.title?.toString()
        val isLive = source?.isLive ?: false

        val customData = JSONObject().apply {
            put("stream_url", streamUrl)
            put("content_type", MimeTypes.APPLICATION_MPD)
            if (!licenseUrl.isNullOrEmpty() && !token.isNullOrEmpty()) {
                put("license_url", licenseUrl)
                put("drm_token", token)
            }
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            if (!title.isNullOrEmpty()) putString(MediaMetadata.KEY_TITLE, title)
        }

        val mediaInfo = MediaInfo.Builder(streamUrl)
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypes.APPLICATION_MPD)
            .setMetadata(metadata)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(mediaInfo).setCustomData(customData).build()
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
