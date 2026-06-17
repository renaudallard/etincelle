// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.Recording

// --- /dvr/v2/list (recordings) ---
//
// A recording is a "programWithAssets", the same shape the EPG parses, so the program/asset DTOs from
// EpgDtos.kt are reused here. The "response" array holds the recordings; "metadata" (pagination) is
// ignored.

data class DvrListResponse(val response: List<DvrEntryDto>?)

data class DvrEntryDto(val data: EpgProgramWithAssetsDto?)

// --- mapping to domain ---

/** Maps the recordings in a /dvr/v2/list page to domain [Recording]s, skipping any without a dvr asset. */
fun DvrListResponse.toRecordings(): List<Recording> =
    response.orEmpty().mapNotNull { it.data?.toRecording() }

private fun EpgProgramWithAssetsDto.toRecording(): Recording? {
    val program = program ?: return null
    val dvrAsset = assets.orEmpty().firstOrNull { it.type == "dvr" } ?: return null
    val assetId = dvrAsset.assetId ?: return null
    return Recording(
        assetId = assetId,
        title = program.title ?: program.heading,
        subtitle = program.subheading,
        imageUrl = program.horizontalImage ?: program.verticalImage,
        channelName = dvrAsset.channel?.name,
        programId = program.programId,
        seriesId = program.metadata?.seriesId,
        seriesName = program.heading,
    )
}

/**
 * A home-rail card for a recording. Tapping it opens the show's detail page, where this recording
 * appears as a playable occurrence: by [Recording.seriesId] for an episode, otherwise by
 * [Recording.programId] as a programme detail. [ContentCard.recordingAssetId] is kept so playback
 * still works as a fallback when no detail page can be opened.
 */
fun Recording.toCard(): ContentCard = ContentCard(
    id = "rec-$assetId",
    title = title,
    imageUrl = imageUrl,
    isLocked = false,
    channelId = null,
    vodId = programId?.takeIf { seriesId == null },
    seriesId = seriesId,
    actionUrl = null,
    recordingAssetId = assetId,
)

/**
 * Collapses recordings so each show appears once, paired with how many episodes were recorded. Groups
 * by series (else programme, else the asset), keeping the first in list order as the representative.
 */
fun List<Recording>.collapsedByShow(): List<Pair<Recording, Int>> =
    groupBy { it.seriesId ?: it.programId ?: it.assetId }
        .map { (_, recs) -> recs.first() to recs.size }

/**
 * A collapsed show card showing one episode's image: it taps through to the show's detail (the
 * episode list, since [Recording.seriesId]/[Recording.programId] are kept), and badges the episode
 * count when more than one was recorded.
 */
fun Recording.toCollapsedCard(episodeCount: Int): ContentCard =
    toCard().copy(
        // Show the show name, not the representative episode's title.
        title = seriesName ?: title,
        badge = if (episodeCount > 1) "$episodeCount épisodes" else null,
    )
