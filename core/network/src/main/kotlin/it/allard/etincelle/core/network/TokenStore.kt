// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.allard.etincelle.core.model.UserSession
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "etincelle_session")

/** Persists the authenticated session across app launches. */
class TokenStore(private val context: Context) : SessionStore {

    override suspend fun read(): UserSession? {
        val prefs = context.dataStore.data.first()
        // The tokens are stored encrypted; a decrypt failure (e.g. a legacy plaintext token from
        // before encryption was added) is treated as "no session", so the user signs in again.
        val access = prefs[ACCESS]?.let { TokenCrypto.decrypt(it) } ?: return null
        val userId = prefs[USER_ID] ?: return null
        val profileId = prefs[PROFILE_ID] ?: return null
        return UserSession(access, prefs[REFRESH]?.let { TokenCrypto.decrypt(it) }, userId, profileId)
    }

    override suspend fun save(session: UserSession) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS] = TokenCrypto.encrypt(session.accessToken)
            // Mirror the session exactly: a null refresh token must clear any previously stored one,
            // not leave a stale value behind.
            val refresh = session.refreshToken
            if (refresh != null) prefs[REFRESH] = TokenCrypto.encrypt(refresh) else prefs.remove(REFRESH)
            prefs[USER_ID] = session.userId
            prefs[PROFILE_ID] = session.profileId
        }
    }

    override suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val PROFILE_ID = stringPreferencesKey("profile_id")
    }
}
