// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.content.Context

/** Small persisted UI preferences (the ViewModel is Context-free, so the app layer stores these). */
object LocalPrefs {
    private const val PREFS = "etincelle"
    private const val HIDE_LOCKED = "hide_locked"
    private const val GRID_COLUMNS = "grid_columns"
    private const val AUTO_SKIP_ADS = "auto_skip_ads"
    const val MIN_GRID_COLUMNS = 1
    const val MAX_GRID_COLUMNS = 5
    const val DEFAULT_GRID_COLUMNS = 3

    fun hideLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HIDE_LOCKED, false)

    fun setHideLocked(context: Context, hide: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HIDE_LOCKED, hide).apply()

    /** Ad breaks whose end is known are skipped without asking, rather than offered with a button. */
    fun autoSkipAds(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_SKIP_ADS, false)

    fun setAutoSkipAds(context: Context, skip: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AUTO_SKIP_ADS, skip).apply()

    /** Number of cards per row on the grid ("tout voir") pages, 1 to 5. */
    fun gridColumns(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

    fun setGridColumns(context: Context, columns: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(GRID_COLUMNS, columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)).apply()
}
