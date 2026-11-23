// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.content.SharedPreferences

/**
 * Which ad breaks are skipped without asking (see [AdSkipper]); every other is offered with a button.
 * [MARKED]: those the stream marks. [SURE]: those too, and the guessed ones that are sure (see
 * [Guess]).
 */
enum class SkipAds {
    OFF,
    MARKED,
    SURE,
    ;

    companion object {
        private const val KEY = "skip_ads"

        // Where the setting was a switch, on for MARKED.
        private const val OLD_KEY = "auto_skip_ads"

        fun read(prefs: SharedPreferences): SkipAds =
            prefs.getString(KEY, null)?.let { stored -> entries.firstOrNull { it.name == stored } }
                ?: if (prefs.getBoolean(OLD_KEY, false)) MARKED else OFF

        fun write(prefs: SharedPreferences, skip: SkipAds) {
            prefs.edit().putString(KEY, skip.name).remove(OLD_KEY).apply()
        }
    }
}
