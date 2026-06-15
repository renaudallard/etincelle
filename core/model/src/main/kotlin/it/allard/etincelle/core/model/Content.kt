// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** A loaded page: a title plus its rails (carousels). */
data class ContentPage(
    val title: String?,
    val rails: List<ContentRail>,
    /** A "see all" page: render its cards as a full-screen scrollable grid, not rails of carousels. */
    val isGrid: Boolean = false,
)

/** A horizontal carousel ("rail") on a page. */
data class ContentRail(
    val id: String,
    val title: String?,
    val cards: List<ContentCard>,
    /** A "see all" page to open from the rail header (the carousel's aux button), if any. */
    val seeAllUrl: String? = null,
)

/**
 * A single card in a rail. Tap behaviour, in priority order:
 * - [recordingAssetId] non-null -> plays that DVR recording directly (no detail page).
 * - [channelId] non-null -> opens that live channel's detail page.
 * - [vodId] non-null     -> opens that VOD/program detail page.
 * - [seriesId] non-null  -> opens that series detail page.
 * - else [actionUrl]     -> navigates to that page (sub-page or detail).
 */
data class ContentCard(
    val id: String,
    val title: String?,
    val imageUrl: String?,
    val isLocked: Boolean,
    val channelId: String?,
    val vodId: String?,
    val seriesId: String?,
    val actionUrl: String?,
    val recordingAssetId: String? = null,
    /** A live card's raw channel id ("channel_id"), used to look up its channel name. */
    val liveChannelId: String? = null,
    /** A secondary label under the title, e.g. the channel name on a live card. */
    val subtitle: String? = null,
    /** A channel-logo card (drawn as a small square); program/guide cards are landscape. */
    val square: Boolean = false,
)

/**
 * A DVR recording: a recorded (or scheduled) airing the user can play back. [assetId] is the dvr asset
 * passed to playback. [programId]/[seriesId] let a detail page keep only the recordings of its show.
 */
data class Recording(
    val assetId: String,
    val title: String?,
    val subtitle: String?,
    val imageUrl: String?,
    val channelName: String?,
    val programId: String?,
    val seriesId: String?,
)

/**
 * A show's detail page (Molotov-4.x style): the program metadata plus how to watch it. Shown when a
 * show card is tapped, instead of playing immediately. The play target is carried in
 * [channelId]/[vodId] (whichever the tapped card had), so watching reuses the normal resolve path.
 *
 * [isLive] marks a live-channel detail: the watch button reads "Regarder en direct" and plays via
 * [channelId] rather than [vodId]. [recordAssetId] is the live airing asset id to record, present
 * only when the airing is recordable; null hides the "Enregistrer" button.
 */
data class ProgramDetail(
    val title: String?,
    val subtitle: String?,
    val synopsis: String?,
    val posterUrl: String?,
    val genre: String?,
    val year: String?,
    val classification: String?,
    val credits: String?,
    val tags: List<String>,
    val channelId: String?,
    val vodId: String?,
    val isLive: Boolean,
    val recordAssetId: String?,
    /** This program's id ("{digits}_{digits}"), used to match its DVR recordings; null on series/channel. */
    val programId: String? = null,
    /** DVR recordings of this very show, shown as a "Vos enregistrements" section. */
    val recordings: List<Recording> = emptyList(),
)
