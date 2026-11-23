// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.content.Context
import it.allard.etincelle.core.player.SkipAds

/** Small persisted UI preferences (the ViewModel is Context-free, so the app layer stores these). */
object LocalPrefs {
    private const val PREFS = "etincelle"
    private const val HIDE_LOCKED = "hide_locked"
    private const val GRID_COLUMNS = "grid_columns"
    const val MIN_GRID_COLUMNS = 1
    const val MAX_GRID_COLUMNS = 5
    const val DEFAULT_GRID_COLUMNS = 3

    fun hideLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HIDE_LOCKED, false)

    fun setHideLocked(context: Context, hide: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HIDE_LOCKED, hide).apply()

    /** Which ad breaks are skipped without asking, rather than offered with a button. */
    fun skipAds(context: Context): SkipAds = SkipAds.read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun setSkipAds(context: Context, skip: SkipAds) = SkipAds.write(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), skip)

    /** Number of cards per row on the grid ("tout voir") pages, 1 to 5. */
    fun gridColumns(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(GRID_COLUMNS, DEFAULT_GRID_COLUMNS)
            .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

    fun setGridColumns(context: Context, columns: Int) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(GRID_COLUMNS, columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)).apply()
}
