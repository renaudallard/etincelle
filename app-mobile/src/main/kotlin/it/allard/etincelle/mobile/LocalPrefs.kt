// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.content.Context

/** Small persisted UI preferences (the ViewModel is Context-free, so the app layer stores these). */
object LocalPrefs {
    private const val PREFS = "etincelle"
    private const val HIDE_LOCKED = "hide_locked"

    fun hideLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HIDE_LOCKED, false)

    fun setHideLocked(context: Context, hide: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HIDE_LOCKED, hide).apply()
}
