// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Registers the Cast receiver application id with the Cast framework. The app declares this class
 * via the `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME` manifest meta-data.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(context.getString(R.string.cast_receiver_app_id))
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
