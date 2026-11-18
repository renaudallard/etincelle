// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import it.allard.etincelle.core.model.UserSession

/** Persistent storage for the authenticated session (implemented by [TokenStore]). */
interface SessionStore {
    suspend fun read(): UserSession?

    /** A session is saved, whether or not it can be read just now ([read] answers null for both). */
    suspend fun holds(): Boolean = read() != null
    suspend fun save(session: UserSession)
    suspend fun clear()

    /** When the current profile was picked, and the account's profile ids at the time (see [ProfilePick]). */
    suspend fun readPick(): ProfilePick?
    suspend fun savePick(pick: ProfilePick)
}

/** A profile choice: when it was made ([atMs]) and the account's profile ids then ([ids]). */
data class ProfilePick(val atMs: Long, val ids: String)
