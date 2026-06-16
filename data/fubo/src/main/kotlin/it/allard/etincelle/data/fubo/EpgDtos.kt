// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// --- /epg live guide ---

data class EpgResponse(val response: List<EpgEntryDto>?)

data class EpgEntryDto(val data: EpgChannelDataDto?)

data class EpgChannelDataDto(
    val channel: EpgChannelDto?,
    val programsWithAssets: List<EpgProgramWithAssetsDto>?,
)

data class EpgChannelDto(
    val id: String?,
    val name: String?,
    val displayName: String?,
    val logoOnDarkUrl: String?,
)

data class EpgProgramWithAssetsDto(
    val program: EpgProgramDto?,
    val assets: List<EpgAssetDto>?,
)

data class EpgProgramDto(
    val title: String?,
    val heading: String?,
    val horizontalImage: String?,
    val subheading: String? = null,
    val verticalImage: String? = null,
    val programId: String? = null,
    val metadata: EpgProgramMetadataDto? = null,
)

data class EpgProgramMetadataDto(val seriesId: String?)

data class EpgAssetDto(
    val accessRights: EpgAccessRightsDto?,
    val assetId: String? = null,
    val type: String? = null,
    val channel: EpgChannelDto? = null,
)

data class EpgAccessRightsDto(val startTime: String?, val endTime: String?)

// --- time helpers (SimpleDateFormat is not thread-safe, so build one per call) ---

private const val RFC3339 = "yyyy-MM-dd'T'HH:mm:ss'Z'"

private fun utcFormatter() =
    SimpleDateFormat(RFC3339, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Formats an epoch instant as an RFC3339 UTC timestamp, the format /epg expects. */
internal fun rfc3339Utc(epochMillis: Long): String = utcFormatter().format(Date(epochMillis))

/** Parses an RFC3339 UTC timestamp and renders it as device-local "HH:mm", or null if unparseable. */
private fun localHourMinute(rfc3339: String?): String? {
    val parsed = rfc3339?.let { runCatching { utcFormatter().parse(it) }.getOrNull() } ?: return null
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsed)
}

// --- mapping to domain ---

/** Maps the /epg guide into a page: one rail per channel, one card per program (tap plays live). */
fun EpgResponse.toGuidePage(): ContentPage {
    val seen = HashSet<String>()
    val rails = response.orEmpty().mapNotNull { entry ->
        val data = entry.data ?: return@mapNotNull null
        val channel = data.channel ?: return@mapNotNull null
        val channelId = channel.id ?: return@mapNotNull null
        // A repeated channel id would make two rails with the same "guide-<id>" key (a LazyColumn crash).
        if (!seen.add(channelId)) return@mapNotNull null
        val cards = data.programsWithAssets.orEmpty()
            .mapIndexedNotNull { index, p -> p.toCard(channelId, channel.logoOnDarkUrl, index) }
        if (cards.isEmpty()) null else ContentRail("guide-$channelId", channel.displayName ?: channel.name, cards)
    }
    return ContentPage("Guide", rails)
}

private fun EpgProgramWithAssetsDto.toCard(channelId: String, channelLogo: String?, index: Int): ContentCard? {
    val name = program?.title ?: program?.heading ?: return null
    val start = assets?.firstOrNull()?.accessRights?.startTime
    val time = localHourMinute(start)
    return ContentCard(
        // Index-based id: two same-titled programs with missing/equal start times must not collide,
        // or the guide's LazyRow throws on a duplicate key.
        id = "$channelId-$index",
        title = if (time != null) "$time · $name" else name,
        imageUrl = program?.horizontalImage ?: channelLogo,
        isLocked = false,
        channelId = channelId,
        vodId = null,
        seriesId = null,
        actionUrl = null,
        recordingAssetId = null,
    )
}
