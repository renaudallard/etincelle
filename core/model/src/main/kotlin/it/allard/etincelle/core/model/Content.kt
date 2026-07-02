// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** A loaded page: a title plus its rails (carousels). */
data class ContentPage(
    val title: String?,
    val rails: List<ContentRail>,
    /** A "see all" page: render its cards as a full-screen scrollable grid, not rails of carousels. */
    val isGrid: Boolean = false,
    /** The papi url this page was fetched from, so it can be reloaded (pull to refresh); null for roots. */
    val reloadUrl: String? = null,
)

/** A horizontal carousel ("rail") on a page. */
data class ContentRail(
    val id: String,
    val title: String?,
    val cards: List<ContentCard>,
    /** A "see all" page to open from the rail header (the carousel's aux button), if any. */
    val seeAllUrl: String? = null,
)

/** Groups episode cards by season (parsed from their "Sx Ey ..." title), ordered by season number. */
fun List<ContentCard>.groupBySeason(): List<Pair<String, List<ContentCard>>> {
    val regex = Regex("""^S(\d+)""")
    return groupBy { card -> card.title?.let { regex.find(it)?.groupValues?.get(1)?.toIntOrNull() } }
        .toList()
        .sortedBy { it.first ?: Int.MAX_VALUE }
        .map { (n, eps) -> (if (n != null) "Saison $n" else "Épisodes") to eps }
}

/** A content rail (one whose cards lead somewhere) can be opened as a full grid from its header. */
fun ContentRail.expandable(): Boolean = seeAllUrl != null || cards.any {
    it.vodId != null || it.seriesId != null || it.channelId != null ||
        it.liveChannelId != null || it.recordingAssetId != null
}

/**
 * A single card in a rail. Tap behaviour, in priority order:
 * - [recordingAssetId] non-null -> opens the show's detail ([seriesId]) or the programme detail
 *   ([vodId]) to pick an episode, or plays the recording directly when neither is set.
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
    /** A small overlay badge on the image, e.g. the episode count on a collapsed recordings card. */
    val badge: String? = null,
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
    /** The show name (the program heading), distinct from [title] which is the episode name. */
    val seriesName: String? = null,
)

/**
 * A record action the backend offers on a detail page (e.g. "Enregistrer l'épisode" or "Enregistrer
 * la série"). Server-driven: [label] is the backend's own localized button text and [url]/[payload]
 * are the api_call it attached to the action, replayed verbatim to schedule the recording. The backend
 * delivers these as either a direct action-item or the items of a "record options" dropdown.
 */
data class RecordAction(
    val label: String,
    val url: String,
    val payload: Map<String, Any?>,
)

/**
 * A show's detail page (Molotov-4.x style): the program metadata plus how to watch it. Shown when a
 * show card is tapped, instead of playing immediately. The play target is carried in
 * [channelId]/[vodId] (whichever the tapped card had), so watching reuses the normal resolve path.
 *
 * [isLive] marks a live-channel detail: the watch button reads "Regarder en direct" and plays via
 * [channelId] rather than [vodId]. [recordActions] are the record options the backend exposes (record
 * the episode and/or the whole series); empty hides the record button.
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
    val recordActions: List<RecordAction> = emptyList(),
    /** This program's id ("{digits}_{digits}"), used to match its DVR recordings; null on series/channel. */
    val programId: String? = null,
    /** DVR recordings of this very show, shown as a "Vos enregistrements" section. */
    val recordings: List<Recording> = emptyList(),
    /** Catch-up episodes from the series' "Regarder maintenant" tab; empty when there is no catch-up. */
    val episodes: List<ContentCard> = emptyList(),
    /** A series page (vodId is the series id, not a playable asset), so it has no direct "Regarder". */
    val isSeries: Boolean = false,
    /** Set when the programme is not yet aired: the backend's own localized reason (e.g. "… en direct
     * dans 4 jours."). Non-null means it is not playable, so no "Regarder" is offered. */
    val upcomingMessage: String? = null,
    /** The channel's own logo, shown as the header when a channel detail has no programme poster (a FAST
     * channel with no "now playing" metadata would otherwise render bare). */
    val channelLogoUrl: String? = null,
    /** A channel's catalogue carousels (replays, most-viewed, live & upcoming), shown below the live
     * header so a channel detail offers more than just "Regarder en direct". Empty for non-channels. */
    val sections: List<ContentRail> = emptyList(),
)
