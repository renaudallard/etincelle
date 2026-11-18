// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// A file that no longer reads starts over empty: every replay and recording would fail to start on it.
private val Context.progressDataStore by preferencesDataStore(
    name = "etincelle_progress",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists per-item resume positions (keyed by VOD id) across launches. */
class ProgressDataStore(private val context: Context) : ProgressStore {

    override suspend fun read(key: String): Long =
        context.progressDataStore.data.first()[longPreferencesKey(key)] ?: 0L

    override suspend fun save(key: String, positionMs: Long) {
        context.progressDataStore.edit { it[longPreferencesKey(key)] = positionMs }
    }

    override suspend fun clear(key: String) {
        context.progressDataStore.edit { it.remove(longPreferencesKey(key)) }
    }

    override suspend fun clearAll() {
        context.progressDataStore.edit { it.clear() }
    }
}
