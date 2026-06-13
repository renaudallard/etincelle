// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

/** Fubo (Molotov) production EU endpoint and the client identity headers. */
object EnvConfig {
    const val BASE_URL = "https://api-eu.fubo.tv/"

    /** Required: identifies the client as the Molotov tenant, else the backend returns no content. */
    const val APPLICATION_ID = "molotov"
    const val CLIENT_VERSION = "5.51.0"
    const val USER_AGENT = "MolotovTV/5.51.0 (Linux; U; ANDROID; fr-FR; etincelle)"

    /** `use_drm_v2_response` makes /vapi/asset include the drm_v2 (license url + headers) we use. */
    const val SUPPORTED_FEATURES =
        "use_drm_v2_response,playback_template_v2,play_start_from_offset,load_channels_in_guide"
}
