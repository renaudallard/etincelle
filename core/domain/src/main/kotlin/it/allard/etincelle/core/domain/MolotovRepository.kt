// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.domain

import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.PairingCode
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.model.UserSession

/** Which `program-details/{kind}/{id}` endpoint a detail page comes from. */
enum class DetailKind { PROGRAM, SERIES, CHANNEL }

/**
 * What the app needs from a Molotov backend, expressed in domain terms. The presentation layer
 * depends only on this interface, so the backend (Fubo today) stays swappable.
 */
interface MolotovRepository {
    suspend fun login(email: String, password: String): UserSession
    suspend fun restoreSession(): Boolean
    suspend fun logout()

    /** Starts the TV "connect my TV" flow: returns a code to display while the user confirms it. */
    suspend fun startCodeLogin(): PairingCode

    /** Polls a pairing code (with the device id that generated it); returns the signed-in session once
     * confirmed, or null while pending. */
    suspend fun pollCodeLogin(code: String, deviceId: String): UserSession?

    /** Confirms a TV's pairing code from this signed-in account (authorizes the waiting TV). */
    suspend fun confirmTvCode(code: String)

    /** The current in-memory session (tokens + ids), used to build the official Cast session handoff. */
    fun currentSession(): UserSession?

    suspend fun loadHome(): ContentPage
    suspend fun loadPage(url: String): ContentPage
    /** The live guide (EPG): channels, each with its current and upcoming programs. */
    suspend fun loadGuide(): ContentPage
    suspend fun search(query: String): ContentPage

    /**
     * A show's detail page (info, cast, year, …) shown before playing, keyed by an id and its [kind]
     * (program, series, or channel). A channel detail is marked live so the watch button plays it directly.
     */
    suspend fun fetchProgramDetail(id: String, kind: DetailKind): ProgramDetail

    /** Records the live airing identified by [assetId]. */
    suspend fun recordEpisode(assetId: String)

    /** The user's DVR recordings (recorded and scheduled), each with a playable dvr asset. */
    suspend fun loadRecordings(): List<Recording>

    suspend fun resolveLiveChannel(channelId: String): PlaybackSource
    suspend fun resolveVod(vodId: String): PlaybackSource

    /** Resolves a DVR recording [assetId] to a playable stream. */
    suspend fun resolveRecording(assetId: String): PlaybackSource

    /** Remembers (or, when [positionMs] is 0, forgets) the resume position for a VOD/replay. */
    suspend fun savePlaybackPosition(key: String, positionMs: Long)

    /** Reports playback progress to the server playhead (continue-watching). No-op for live and for
     * sources without a playhead; best-effort, so failures must not disrupt playback. */
    suspend fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long)
}
