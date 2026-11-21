// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.JsonClass
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.HomeProgram
import it.allard.etincelle.core.model.LiveChannel
import it.allard.etincelle.core.model.LiveProgram
import it.allard.etincelle.core.model.ProgramWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// --- /epg live guide ---

@JsonClass(generateAdapter = true)
data class EpgResponse(val response: List<EpgEntryDto>?, val metadata: List<EpgMetadataDto>? = null) {
    /** Where the next page of channels starts, or null on the last page. */
    val nextOffset: Int? get() = metadata?.firstOrNull { it.type == "paginationState" }?.data?.nextOffset
}

@JsonClass(generateAdapter = true)
data class EpgMetadataDto(val type: String?, val data: EpgPaginationDto?)
@JsonClass(generateAdapter = true)
data class EpgPaginationDto(val nextOffset: Int?)

@JsonClass(generateAdapter = true)
data class EpgEntryDto(val data: EpgChannelDataDto?)

@JsonClass(generateAdapter = true)
data class EpgChannelDataDto(
    val channel: EpgChannelDto?,
    val programsWithAssets: List<EpgProgramWithAssetsDto>?,
)

@JsonClass(generateAdapter = true)
data class EpgChannelDto(
    val id: String?,
    val name: String?,
    val displayName: String?,
    val logoOnDarkUrl: String?,
)

@JsonClass(generateAdapter = true)
data class EpgProgramWithAssetsDto(
    val program: EpgProgramDto?,
    val assets: List<EpgAssetDto>?,
)

@JsonClass(generateAdapter = true)
data class EpgProgramDto(
    val title: String?,
    val heading: String?,
    val horizontalImage: String?,
    val subheading: String? = null,
    val verticalImage: String? = null,
    val programId: String? = null,
    val metadata: EpgProgramMetadataDto? = null,
    val shortDescription: String? = null,
    // "movie", "episode", "match"…
    val type: String? = null,
)

@JsonClass(generateAdapter = true)
data class EpgProgramMetadataDto(val seriesId: String?)

@JsonClass(generateAdapter = true)
data class EpgAssetDto(
    val accessRights: EpgAccessRightsDto?,
    val assetId: String? = null,
    val type: String? = null,
    val channel: EpgChannelDto? = null,
    val playbackDurationSec: Long? = null,
    // Where the user stopped watching (continue watching), in seconds.
    val userProperties: EpgUserPropertiesDto? = null,
)

@JsonClass(generateAdapter = true)
data class EpgUserPropertiesDto(val lastOffset: Long?, val lastOffsetTimeStamp: String?)

// The Android TV home feeds (/recommendations/topshelf/v1, /continue-watching/topshelf/v1): the
// same programme-with-assets entries as the guide, one per programme.
@JsonClass(generateAdapter = true)
data class TopshelfResponse(val response: List<TopshelfEntryDto>?)
@JsonClass(generateAdapter = true)
data class TopshelfEntryDto(val type: String?, val data: EpgProgramWithAssetsDto?)

@JsonClass(generateAdapter = true)
data class EpgAccessRightsDto(val startTime: String?, val endTime: String?)

// --- time helpers (SimpleDateFormat is not thread-safe, so build one per call) ---

private const val RFC3339 = "yyyy-MM-dd'T'HH:mm:ss'Z'"

private fun utcFormatter() =
    SimpleDateFormat(RFC3339, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

/** Formats an epoch instant as an RFC3339 UTC timestamp, the format /epg expects. */
internal fun rfc3339Utc(epochMillis: Long): String = utcFormatter().format(Date(epochMillis))

private val FRACTION_OF_SECOND = Regex("\\.\\d+")

// The backend writes "2026-06-13T19:10:00Z". A fraction of a second, or an offset in place of the Z,
// would be as valid a timestamp, and must not read as no time at all: every programme would lose its.
private fun parseRfc3339(rfc3339: String?, parser: SimpleDateFormat = rfc3339Parser()): Date? = rfc3339?.let {
    runCatching { parser.parse(if ('.' in it) it.replace(FRACTION_OF_SECOND, "") else it) }.getOrNull()
}

private fun rfc3339Parser() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

/** Parses an RFC3339 timestamp to epoch millis, or null if absent/unparseable. */
internal fun rfc3339UtcToEpochMillis(rfc3339: String?): Long? = parseRfc3339(rfc3339)?.time

/**
 * Renders RFC3339 timestamps as device-local "HH:mm", or null when unparseable. One for the thousands
 * of programmes of a guide, on the one thread that maps it.
 */
private class LocalHourMinute {
    private val parser = rfc3339Parser()
    private val format = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun of(rfc3339: String?): String? = parseRfc3339(rfc3339, parser)?.let { format.format(it) }
}

// --- mapping to domain ---

/** Maps the /epg guide into a page: one rail per channel, one card per program (tap plays live). */
fun EpgResponse.toGuidePage(): ContentPage {
    val seen = HashSet<String>()
    val hours = LocalHourMinute()
    val rails = response.orEmpty().mapNotNull { entry ->
        val data = entry.data ?: return@mapNotNull null
        val channel = data.channel ?: return@mapNotNull null
        val channelId = channel.id ?: return@mapNotNull null
        // A repeated channel id would make two rails with the same "guide-<id>" key (a LazyColumn crash).
        if (!seen.add(channelId)) return@mapNotNull null
        val cards = data.programsWithAssets.orEmpty()
            .mapIndexedNotNull { index, p -> p.toCard(channelId, channel.logoOnDarkUrl, index, hours) }
        if (cards.isEmpty()) null else ContentRail("guide-$channelId", channel.displayName ?: channel.name, cards)
    }
    return ContentPage("Guide", rails)
}

private fun EpgProgramWithAssetsDto.toCard(channelId: String, channelLogo: String?, index: Int, hours: LocalHourMinute): ContentCard? {
    // The show's name: on an episode the title is the episode's own ("Episode 38").
    val name = program?.heading ?: program?.title ?: return null
    val start = assets?.firstOrNull()?.accessRights?.startTime
    val time = hours.of(start)
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
        caption = program?.title?.takeIf { it != name },
    )
}

/**
 * The air-window of the programme the guide has on [channelId] at [nowMs], or null when it has none.
 * It starts when the programme really did, to the second, where a live stream's own window
 * ([AccessRightsV2Dto]) starts with the planned slot.
 */
internal fun EpgResponse.onAirWindow(channelId: String, nowMs: Long): ProgramWindow? =
    response.orEmpty().firstOrNull { it.data?.channel?.id == channelId }?.data?.programsWithAssets.orEmpty()
        .mapNotNull { it.assets?.firstOrNull()?.accessRights }
        .mapNotNull { AccessRightsV2WindowDto(it.startTime, it.endTime).toProgramWindow() }
        .firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }

/** The programmes of a home feed, each with its first playable asset. */
fun TopshelfResponse.toHomePrograms(): List<HomeProgram> = response.orEmpty().mapNotNull { entry ->
    val program = entry.data?.program ?: return@mapNotNull null
    val asset = entry.data.assets.orEmpty().firstOrNull { !it.assetId.isNullOrBlank() } ?: return@mapNotNull null
    val start = rfc3339UtcToEpochMillis(asset.accessRights?.startTime)
    val end = rfc3339UtcToEpochMillis(asset.accessRights?.endTime)
    HomeProgram(
        assetId = asset.assetId ?: return@mapNotNull null,
        title = program.heading ?: program.title ?: return@mapNotNull null,
        episodeTitle = program.title?.takeIf { program.heading != null && it != program.heading },
        subtitle = program.subheading,
        description = program.shortDescription,
        imageUrl = program.horizontalImage,
        posterUrl = program.verticalImage,
        kind = when (program.type) {
            "movie" -> HomeProgram.Kind.MOVIE
            "episode" -> HomeProgram.Kind.EPISODE
            else -> HomeProgram.Kind.OTHER
        },
        durationSec = asset.playbackDurationSec,
        positionSec = asset.userProperties?.lastOffset?.takeIf { it > 0 },
        watchedAtMs = rfc3339UtcToEpochMillis(asset.userProperties?.lastOffsetTimeStamp),
        // A live airing carries its air times; a replay or VOD carries its availability window.
        liveStartMs = start.takeIf { asset.assetId.startsWith("LIVE_") },
        liveEndMs = end.takeIf { asset.assetId.startsWith("LIVE_") },
    )
}

/** The channels of a device guide feed (/device-integrations/epg), each with its timed programmes. */
fun EpgResponse.toLiveChannels(): List<LiveChannel> = response.orEmpty().mapNotNull { entry ->
    val channel = entry.data?.channel ?: return@mapNotNull null
    val programs = entry.data.programsWithAssets.orEmpty().mapNotNull { pwa ->
        val program = pwa.program ?: return@mapNotNull null
        val asset = pwa.assets.orEmpty().firstOrNull() ?: return@mapNotNull null
        val start = rfc3339UtcToEpochMillis(asset.accessRights?.startTime) ?: return@mapNotNull null
        val end = rfc3339UtcToEpochMillis(asset.accessRights?.endTime)?.takeIf { it > start } ?: return@mapNotNull null
        LiveProgram(
            id = asset.assetId ?: program.programId ?: "$start",
            title = program.heading ?: program.title ?: return@mapNotNull null,
            episodeTitle = program.title?.takeIf { program.heading != null && it != program.heading },
            description = program.shortDescription,
            imageUrl = program.horizontalImage,
            startMs = start,
            endMs = end,
        )
    }
    LiveChannel(
        id = channel.id ?: return@mapNotNull null,
        name = channel.displayName ?: channel.name ?: return@mapNotNull null,
        logoUrl = channel.logoOnDarkUrl,
        programs = programs,
    )
}
