// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

/** Persistent per-item playback positions (resume points), implemented by [ProgressDataStore]. */
interface ProgressStore {
    /** Saved position in ms for [key], or 0 if none. */
    suspend fun read(key: String): Long
    suspend fun save(key: String, positionMs: Long)
    suspend fun clear(key: String)

    /** Wipes every saved position (on logout, so the next account starts clean). */
    suspend fun clearAll()
}
