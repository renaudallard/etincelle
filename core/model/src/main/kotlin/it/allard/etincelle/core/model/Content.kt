// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.model

/** A loaded page: a title plus its rails (carousels). */
data class ContentPage(
    val title: String?,
    val rails: List<ContentRail>,
)

/** A horizontal carousel ("rail") on a page. */
data class ContentRail(
    val id: String,
    val title: String?,
    val cards: List<ContentCard>,
)

/**
 * A single card in a rail. Tap behaviour, in priority order:
 * - [channelId] non-null -> plays that live channel.
 * - [vodId] non-null     -> plays that VOD/replay.
 * - else [actionUrl]     -> navigates to that page (sub-page or detail).
 */
data class ContentCard(
    val id: String,
    val title: String?,
    val imageUrl: String?,
    val isLocked: Boolean,
    val channelId: String?,
    val vodId: String?,
    val actionUrl: String?,
)

/**
 * A show's detail page (Molotov-4.x style): the program metadata plus how to watch it. Shown when a
 * show card is tapped, instead of playing immediately. The play target is carried in
 * [channelId]/[vodId] (whichever the tapped card had), so watching reuses the normal resolve path.
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
)
