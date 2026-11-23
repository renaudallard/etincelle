// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import it.allard.etincelle.core.designsystem.artworkImageLoader
import it.allard.etincelle.data.fubo.AppContainer

/** TV application-scoped manual DI, sharing the same data layer as the phone app. */
class EtincelleTvApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(this, debug = BuildConfig.DEBUG) }

    /** The phone's remote control, which may outlive the activity. */
    val remote: TvRemote by lazy { TvRemote(this) }

    override fun newImageLoader(): ImageLoader = artworkImageLoader(this)
}
