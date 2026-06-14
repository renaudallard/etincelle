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
        title = program.heading,
        subtitle = program.subheading,
        imageUrl = program.horizontalImage ?: program.verticalImage,
        channelName = dvrAsset.channel?.name,
        programId = program.programId,
        seriesId = program.metadata?.seriesId,
    )
}

/** A home-rail card that, when tapped, plays this recording (via [ContentCard.recordingAssetId]). */
fun Recording.toCard(): ContentCard = ContentCard(
    id = "rec-$assetId",
    title = title,
    imageUrl = imageUrl,
    isLocked = false,
    channelId = null,
    vodId = null,
    seriesId = null,
    actionUrl = null,
    recordingAssetId = assetId,
)
