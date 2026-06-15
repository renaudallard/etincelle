// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the launcher icon between the default colour icon and the mono-white one by enabling the
 * matching launcher activity-alias. The choice persists; the home-screen icon updates shortly after.
 */
object AppIcon {
    private const val PREFS = "etincelle"
    private const val KEY_MONO = "launcher_mono"
    private const val DEFAULT_ALIAS = "it.allard.etincelle.mobile.DefaultLauncher"
    private const val MONO_ALIAS = "it.allard.etincelle.mobile.MonoLauncher"

    fun isMono(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MONO, false)

    fun setMono(context: Context, mono: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MONO, mono).apply()
        enable(context, MONO_ALIAS, mono)
        enable(context, DEFAULT_ALIAS, !mono)
    }

    private fun enable(context: Context, alias: String, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, alias),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
