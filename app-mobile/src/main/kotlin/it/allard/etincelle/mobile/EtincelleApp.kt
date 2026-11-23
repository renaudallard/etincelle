// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import it.allard.etincelle.core.designsystem.artworkImageLoader
import it.allard.etincelle.data.fubo.AppContainer

/** Application-scoped manual DI (Hilt comes in a later milestone). */
class EtincelleApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this, debug = BuildConfig.DEBUG) }

    /**
     * The process-scoped Cast context, or null on devices without Google Play Services (the app
     * stays fully usable for local playback there, it just will not offer casting).
     */
    val castContext: CastContext? by lazy {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            runCatching { CastContext.getSharedInstance(this) }.getOrNull()
        } else {
            null
        }
    }

    override fun newImageLoader(): ImageLoader = artworkImageLoader(this)
}
