// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Application
import it.allard.etincelle.data.fubo.AppContainer

/** TV application-scoped manual DI, sharing the same data layer as the phone app. */
class EtincelleTvApp : Application() {
    val container: AppContainer by lazy { AppContainer(this, debug = BuildConfig.DEBUG) }
}
