// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import it.allard.etincelle.core.model.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// A file that no longer reads, as after a power cut while it was written, starts over empty: every
// read and write would fail on it otherwise, and nobody could sign in again.
private val Context.dataStore by preferencesDataStore(
    name = "etincelle_session",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Persists the authenticated session across app launches. */
class TokenStore(private val context: Context) : SessionStore {

    // The Keystore is slow enough to be kept off the thread of the caller, which is the main one.
    override suspend fun read(): UserSession? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        // The tokens are stored encrypted; a decrypt failure (e.g. a legacy plaintext token from
        // before encryption was added) is treated as "no session", so the user signs in again.
        // That holds for the refresh token too: a session read without it would be saved back
        // without it, wiping a stored refresh token over what may be a passing Keystore error.
        val access = prefs[ACCESS]?.let { TokenCrypto.decrypt(it) } ?: return@withContext null
        val refresh = prefs[REFRESH]?.let { TokenCrypto.decrypt(it) ?: return@withContext null }
        val userId = prefs[USER_ID] ?: return@withContext null
        val profileId = prefs[PROFILE_ID] ?: return@withContext null
        UserSession(access, refresh, userId, profileId)
    }

    override suspend fun save(session: UserSession) {
        withContext(Dispatchers.IO) {
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
    }

    override suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    override suspend fun readPick(): ProfilePick? {
        val prefs = context.dataStore.data.first()
        return ProfilePick(prefs[PICKED_AT] ?: return null, prefs[PICKED_IDS] ?: return null)
    }

    override suspend fun savePick(pick: ProfilePick) {
        context.dataStore.edit { prefs ->
            prefs[PICKED_AT] = pick.atMs
            prefs[PICKED_IDS] = pick.ids
        }
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val PROFILE_ID = stringPreferencesKey("profile_id")
        val PICKED_AT = longPreferencesKey("profile_picked_at")
        val PICKED_IDS = stringPreferencesKey("profile_picked_ids")
    }
}
