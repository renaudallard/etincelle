// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import android.content.Context
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.network.DeviceInfo
import it.allard.etincelle.core.network.NetworkClient
import it.allard.etincelle.core.network.ProgressDataStore
import it.allard.etincelle.core.network.TokenStore

/** Application-scoped manual DI container shared by the phone and TV apps. */
class AppContainer(context: Context, debug: Boolean = false) {
    private val network = NetworkClient(device = DeviceInfo.persistent(context.applicationContext), debug = debug)
    private val tokenStore = TokenStore(context.applicationContext)
    private val progressStore = ProgressDataStore(context.applicationContext)
    val repository: MolotovRepository = FuboRepository(network, tokenStore, progressStore)
}
