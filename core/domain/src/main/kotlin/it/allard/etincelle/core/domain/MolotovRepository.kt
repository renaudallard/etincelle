// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.domain

import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.HomeProgram
import it.allard.etincelle.core.model.LiveChannel
import it.allard.etincelle.core.model.LoginResult
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.PairingCode
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.UserSession

/** Which `program-details/{kind}/{id}` endpoint a detail page comes from. */
enum class DetailKind { PROGRAM, SERIES, CHANNEL }

/**
 * What the app needs from a Molotov backend, expressed in domain terms. The presentation layer
 * depends only on this interface, so the backend (Fubo today) stays swappable.
 */
interface MolotovRepository {
    /** Signs in with a password; an account with two-factor login answers with a code to ask for. */
    suspend fun login(email: String, password: String): LoginResult

    /** Finishes a two-factor sign-in with the [code] the user received. */
    suspend fun verifyCode(challenge: MfaChallenge, code: String): UserSession

    /** Asks for a new code; the returned challenge replaces [challenge]. */
    suspend fun resendCode(challenge: MfaChallenge): MfaChallenge
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

    /** Schedules a recording by replaying the backend's own record [action] (episode or whole series). */
    suspend fun record(action: RecordAction)

    /** Every channel with its programmes over the next [hours], for the system's TV guide. */
    suspend fun liveChannels(hours: Int): List<LiveChannel>

    /** Programmes recommended for the Android TV home screen. */
    suspend fun homeRecommendations(): List<HomeProgram>

    /** Programmes to continue watching, with where the user stopped. */
    suspend fun continueWatching(): List<HomeProgram>

    /** The account's profiles, with the avatars to choose from and the profile limit. */
    suspend fun profiles(): Profiles

    /** The key the phone and the TV app of this account share to control one another on the local
     * network, derived from the account (see core/remote); null when signed out. */
    suspend fun remoteKey(): ByteArray?

    /**
     * The profiles when the viewer should say who is watching, as the official app asks: with several
     * profiles, when the current one is gone, the profiles changed, or the choice is over two days old.
     * Null keeps the current profile.
     */
    suspend fun profilesToPick(): Profiles?

    /** Watches as profile [id] from now on (it sends the x-profile-id of every request). */
    suspend fun selectProfile(id: String)

    suspend fun createProfile(name: String, avatarId: Int?): Profile
    suspend fun updateProfile(id: String, name: String, avatarId: Int?)

    /** Deletes profile [id]; deleting the current one switches to another profile. */
    suspend fun deleteProfile(id: String)

    /** Adds a channel to the favourites or removes it, by replaying the card's own [action]. */
    suspend fun toggleFavorite(action: RecordAction)

    /** The favourite channels in their current order. */
    suspend fun favoriteOrder(): FavoriteOrder

    /** Saves [channelIds] as the new order of the favourite channels, with the order's [save] action. */
    suspend fun saveFavoriteOrder(save: RecordAction, channelIds: List<String>)

    suspend fun resolveLiveChannel(channelId: String): PlaybackSource
    suspend fun resolveVod(vodId: String): PlaybackSource

    /** The air-window of the programme on a live channel, so the live seek bar can mark the show on
     * screen and re-scope when playback moves into another: the one on now, or with [atMs] the one that
     * was on at that time (epoch ms), where a stream watched behind the live point is. Null when no
     * times are given. */
    suspend fun liveProgramWindow(channelId: String, atMs: Long? = null): ProgramWindow?

    /** Resolves a DVR recording [assetId] to a playable stream. */
    suspend fun resolveRecording(assetId: String): PlaybackSource

    /** Remembers (or, when [positionMs] is 0, forgets) the resume position for a VOD/replay. */
    suspend fun savePlaybackPosition(key: String, positionMs: Long)

    /** Reports playback progress to the server playhead (continue-watching). No-op for live and for
     * sources without a playhead; best-effort, so failures must not disrupt playback. */
    suspend fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long)
}
