// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import android.content.Context
import android.os.Build
import java.io.File

/** What automatic updates keep between launches; see [AutoUpdate]. */
interface AutoUpdateStore {
    /** The setting. */
    var enabled: Boolean

    /** The version whose automatic install needed someone after all: it is proposed like any other. */
    var byHand: String?

    /** Android would install an update of this app without asking. */
    val possible: Boolean

    /** Where a downloaded update waits for its install. */
    val file: File
}

/**
 * Automatic updates, a setting that is off by default: a newer release is downloaded without a word and
 * installed once the app is left with nothing playing, since installing closes the app. Android lets an
 * app update itself without asking from version 12. Whatever still needs someone (an older Android, a
 * check by Google Play Protect, an install that fails) is proposed as before when the app is next opened.
 */
class AutoUpdate(context: Context) : AutoUpdateStore {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("etincelle_update", Context.MODE_PRIVATE)

    override val file = File(app.cacheDir, "update-auto.apk")

    override var enabled: Boolean
        get() = prefs.getBoolean(ENABLED, false)
        set(on) = prefs.edit().putBoolean(ENABLED, on).apply()

    override var byHand: String?
        get() = prefs.getString(BY_HAND, null)
        set(version) = prefs.edit().putString(BY_HAND, version).apply()

    // The app must also be allowed to install apps, which the first update by hand asks for.
    override val possible: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && app.packageManager.canRequestPackageInstalls()

    companion object {
        private const val ENABLED = "enabled"
        private const val BY_HAND = "by_hand"

        /** This Android can install an update without asking, so the setting is worth showing. */
        val SUPPORTED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
