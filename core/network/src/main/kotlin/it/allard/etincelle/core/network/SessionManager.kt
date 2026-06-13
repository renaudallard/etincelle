// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import it.allard.etincelle.core.model.UserSession

/** In-memory holder for the authenticated session, read synchronously by the auth interceptor. */
class SessionManager {
    @Volatile
    var session: UserSession? = null

    val accessToken: String? get() = session?.accessToken
    val userId: String? get() = session?.userId
    val profileId: String? get() = session?.profileId
}
