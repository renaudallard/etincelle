// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata as ItemMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import it.allard.etincelle.core.model.UserSession

/**
 * Converts a Media3 [MediaItem] to a Cast [MediaQueueItem] for the Play Services path; the content
 * of the LOAD is built by [CastLoadData].
 */
@UnstableApi
class FuboCastMediaItemConverter(
    private val context: Context,
    private val session: () -> UserSession? = { null },
) : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val media = CastLoadData.media(context, mediaItem, session())
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            media.title?.let { putString(MediaMetadata.KEY_TITLE, it) }
        }
        val mediaInfo = MediaInfo.Builder(media.contentId)
            .setStreamType(if (media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(media.contentType)
            .setMetadata(metadata)
            .setCustomData(media.customData)
            .build()
        return MediaQueueItem.Builder(mediaInfo).setCustomData(media.customData).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val customData = info?.customData
        val uri = customData?.optString("stream_url").orEmpty().ifEmpty { info?.contentId.orEmpty() }
        val builder = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD)
        // An item the receiver loaded by itself (reloading a stalled stream) comes back only from here:
        // keep its title for the notification and the cast controls.
        info?.metadata?.getString(MediaMetadata.KEY_TITLE)?.let {
            builder.setMediaMetadata(ItemMetadata.Builder().setTitle(it).build())
        }
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
