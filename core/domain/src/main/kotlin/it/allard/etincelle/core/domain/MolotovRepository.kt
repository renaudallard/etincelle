// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.domain

import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession

/**
 * What the app needs from a Molotov backend, expressed in domain terms. The presentation layer
 * depends only on this interface, so the backend (Fubo today) stays swappable.
 */
interface MolotovRepository {
    suspend fun login(email: String, password: String): UserSession
    suspend fun restoreSession(): Boolean
    suspend fun logout()

    suspend fun loadHome(): ContentPage
    suspend fun loadPage(url: String): ContentPage
    /** The live guide (EPG): channels, each with its current and upcoming programs. */
    suspend fun loadGuide(): ContentPage
    suspend fun search(query: String): ContentPage

    suspend fun resolveLiveChannel(channelId: String): PlaybackSource
    suspend fun resolveVod(vodId: String): PlaybackSource

    /** Remembers (or, when [positionMs] is 0, forgets) the resume position for a VOD/replay. */
    suspend fun savePlaybackPosition(key: String, positionMs: Long)
}
