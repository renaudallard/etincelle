// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource

/** Maps a domain [PlaybackSource] onto a Media3 [MediaItem] with Widevine DRM when required. */
object MediaItemFactory {

    fun create(source: PlaybackSource): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(source.manifestUrl)
            .setMimeType(MimeTypes.APPLICATION_MPD)

        when (val drm = source.drm) {
            is DrmSpec.Widevine -> builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drm.licenseUrl)
                    .setLicenseRequestHeaders(drm.licenseHeaders)
                    .setMultiSession(true)
                    .build(),
            )

            DrmSpec.None -> Unit
        }

        return builder.build()
    }
}
