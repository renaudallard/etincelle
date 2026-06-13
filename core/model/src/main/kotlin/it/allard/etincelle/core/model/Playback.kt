// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** DRM requirement for a stream, expressed in player-agnostic terms. */
sealed interface DrmSpec {
    data object None : DrmSpec

    /**
     * Widevine with an HTTP license server. [licenseHeaders] are sent verbatim on the license
     * request (Fubo/DRMtoday puts the auth token in the `x-dt-auth-token` header here).
     */
    data class Widevine(
        val licenseUrl: String,
        val licenseHeaders: Map<String, String> = emptyMap(),
    ) : DrmSpec
}

/** Everything the player needs to start a stream, mapped from the backend playback response. */
data class PlaybackSource(
    val manifestUrl: String,
    val drm: DrmSpec,
    val isLive: Boolean,
    val title: String? = null,
    /** Non-null for VOD/replay: the key under which to remember the resume position. */
    val resumeKey: String? = null,
    /** Where to start playback (ms); 0 starts at the beginning / live edge. */
    val startPositionMs: Long = 0L,
    /** Server session keep-alive endpoints to ping while playing (may be null). */
    val heartbeatUrl: String? = null,
    val concurrencyHeartbeatUrl: String? = null,
)
