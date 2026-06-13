// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.app.Application
import it.allard.etincelle.data.fubo.AppContainer

/** Application-scoped manual DI (Hilt comes in a later milestone). */
class EtincelleApp : Application() {
    val container: AppContainer by lazy { AppContainer(this, debug = true) }
}
