// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import it.allard.etincelle.core.model.UserSession

/** Persistent storage for the authenticated session (implemented by [TokenStore]). */
interface SessionStore {
    suspend fun read(): UserSession?
    suspend fun save(session: UserSession)
    suspend fun clear()
}
