// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context

/**
 * The chosen Cast receiver, persisted so both [CastOptionsProvider] (the receiver app id, read once
 * at Cast init) and [FuboCastMediaItemConverter] (the customData shape) agree.
 *
 * - [CUSTOM] `9527437F`: the user's receiver, plays our DASH+Widevine via an `x-dt-auth-token` header.
 * - [OFFICIAL] `D4E9D842`: Fubo's published receiver; takes a session-handoff customData and fetches
 *   HLS itself. Experimental, and a change only takes effect after the app is restarted.
 */
object CastReceiver {
    const val CUSTOM = "9527437F"
    const val OFFICIAL = "D4E9D842"
    private const val PREFS = "etincelle_cast"
    private const val KEY = "receiver_app_id"

    fun appId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, CUSTOM) ?: CUSTOM

    fun isOfficial(context: Context): Boolean = appId(context) == OFFICIAL

    fun setOfficial(context: Context, official: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (official) OFFICIAL else CUSTOM).apply()
    }

    /** A stable per-install id stored under [key], created on first use (for cast device/session ids). */
    fun stableId(context: Context, key: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(key, null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(key, it).apply() }
    }
}
