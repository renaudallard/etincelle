// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Json
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.ProgramDetail

// --- /papi/v1/page/* server-driven UI ---

data class PageResponse(val title: TextDto?, val content: PageContentDto?)

data class PageContentDto(
    val template: String?,
    val metadata: MetadataDto? = null,
    val sections: List<SectionDto>?,
    val player: PlayerDto? = null,
)

// For an upcoming (not-yet-aired) programme the detail's player block is not playable: is_upcoming is
// true and title/subtitle carry the backend's own localized reason (e.g. "… en direct dans 4 jours.").
data class PlayerDto(
    @Json(name = "is_upcoming") val isUpcoming: Boolean? = null,
    val title: TextDto? = null,
    val subtitle: TextDto? = null,
)

data class MetadataDto(
    val id: String?,
    val title: TextDto?,
    val subtitle: TextDto?,
    val description: TextDto?,
    val artwork: PictureDto?,
    val tags: List<TagDto>?,
    val ctas: List<CtaDto>?,
)

// The record action hides inside the metadata CTAs: a menu-item whose id is "id-record-{LIVE_xxxxx}".
data class CtaDto(@Json(name = "action_items") val actionItems: List<CtaActionItemDto>?)
data class CtaActionItemDto(val actions: CtaActionsDto?)
data class CtaActionsDto(@Json(name = "on_click") val onClick: List<CtaOnClickDto>?)
data class CtaOnClickDto(val content: CtaContentDto?, val endpoint: EndpointDto? = null)
data class CtaContentDto(@Json(name = "menu_items") val menuItems: List<MenuItemDto>?)
data class MenuItemDto(val id: String?)

data class TagDto(val label: String?)
data class AboutFieldDto(val label: TextDto?, val value: TextDto?)

data class SectionDto(
    val title: TextDto?,
    @Json(name = "component_type") val componentType: String?,
    val components: List<ComponentDto>?,
    @Json(name = "aux_button") val auxButton: AuxButtonDto? = null,
)

// A carousel's "see more" button; its navigation action points to the rail's full "see all" page.
data class AuxButtonDto(val actions: ActionsDto?)

data class ComponentDto(
    val id: String?,
    val type: String?,
    val title: TextDto?,
    val heading: TextDto?,
    val picture: PictureDto?,
    val body: BodyDto?,
    val image: PictureDto?,
    @Json(name = "image_compact") val imageCompact: PictureDto?,
    @Json(name = "is_locked") val isLocked: Boolean?,
    val state: StateDto?,
    val actions: ActionsDto?,
    val description: TextDto? = null,
    @Json(name = "about_fields") val aboutFields: List<AboutFieldDto>? = null,
    val footer: FooterDto? = null,
    @Json(name = "channel_id") val channelIdRaw: Any? = null,
    val slug: String? = null,
)

// Live ("En direct à la TV") cards carry their show title in a footer rather than in title/heading.
data class FooterDto(val title: TextDto?, val subtitle: TextDto?)

data class BodyDto(val picture: PictureDto?)
data class StateDto(@Json(name = "is_locked") val isLocked: Boolean?)
data class ActionsDto(@Json(name = "on_click") val onClick: List<ActionItemDto>?)
data class ActionItemDto(val endpoint: EndpointDto?, val type: String? = null)
data class EndpointDto(val url: String?, val method: String?)
data class PictureDto(val url: String?)
data class TextDto(val text: String?)

// --- mapping to domain ---

private val CHANNEL_REGEX = Regex("""program-details/channel/(\d+)""")
private val CHANNEL_DETAILS_REGEX = Regex("""channel-details/(\d+)""")
private val VOD_REGEX = Regex("""program-details/program/([\w-]+)""")
private val SERIES_REGEX = Regex("""program-details/series/([\w-]+)""")
private val RECORD_REGEX = Regex("""id-record-(LIVE_[0-9]+)""")
// metadata.id looks like "id-metadata-program-details-2768066_48"; the trailing "{digits}_{digits}"
// is the program id used to match this show's DVR recordings.
private val PROGRAM_ID_REGEX = Regex("""(\d+_\d+)$""")

fun PageResponse.toPage(): ContentPage = ContentPage(title?.text, toRails())

/** Maps a `program-details/...` page (its `metadata` + the "À propos" `about` section) to a detail. */
fun PageResponse.toProgramDetail(channelId: String?, vodId: String?, isLive: Boolean): ProgramDetail {
    val meta = content?.metadata
    val about = content?.sections.orEmpty().firstOrNull { it.componentType == "about" }?.components?.firstOrNull()
    val fields = about?.aboutFields.orEmpty().mapNotNull { f ->
        val key = f.label?.text ?: return@mapNotNull null
        val value = f.value?.text ?: return@mapNotNull null
        key to value
    }.toMap()
    // Blank -> null, so an empty credits string neither wipes the synopsis (endsWith("") is true) nor
    // surfaces an empty credits line.
    val credits = about?.description?.text?.takeIf { it.isNotBlank() }
    // Some descriptions already end with the "Réalisé par … avec …" credits; drop that trailing tail so
    // the cast is not shown twice. Match the suffix only, so a credits phrase that happens to appear
    // mid-synopsis does not truncate the real body.
    val rawSynopsis = meta?.description?.text
    // Match the suffix ignoring trailing whitespace, so a stray newline or space after the credits
    // block does not defeat the match and leave the cast shown twice.
    val trimmedSynopsis = rawSynopsis?.trimEnd()
    val synopsis = if (credits != null && trimmedSynopsis != null && trimmedSynopsis.endsWith(credits)) {
        trimmedSynopsis.removeSuffix(credits).trim().ifEmpty { null }
    } else {
        rawSynopsis
    }
    // The live airing to record sits in a CTA menu-item id like "id-record-LIVE_3479644".
    val recordAssetId = meta?.ctas.orEmpty()
        .flatMap { it.actionItems.orEmpty() }
        .flatMap { it.actions?.onClick.orEmpty() }
        .flatMap { it.content?.menuItems.orEmpty() }
        .firstNotNullOfOrNull { item -> item.id?.let { RECORD_REGEX.find(it)?.groupValues?.get(1) } }
    val programId = meta?.id?.let { PROGRAM_ID_REGEX.find(it)?.groupValues?.get(1) }
    // An upcoming (not-yet-aired) programme is not playable; surface the backend's own localized reason
    // (e.g. "Ce programme sera en direct dans 4 jours.") instead of letting Regarder 5xx.
    val upcomingMessage = content?.player?.takeIf { it.isUpcoming == true }?.let { p ->
        p.title?.text?.takeIf { it.isNotBlank() } ?: p.subtitle?.text?.takeIf { it.isNotBlank() }
    }
    return ProgramDetail(
        title = meta?.title?.text,
        subtitle = meta?.subtitle?.text,
        synopsis = synopsis,
        // Titles without a real poster come back with a generic "/arts/up/default-…" placeholder;
        // treat that as no poster so a better image can be used instead.
        posterUrl = meta?.artwork?.url?.takeUnless { it.contains("/arts/up/default-") },
        genre = fields["Genre"],
        year = fields["Année de sortie"],
        classification = fields["Classification"],
        credits = credits,
        tags = meta?.tags.orEmpty().mapNotNull { it.label },
        channelId = channelId,
        vodId = vodId,
        isLive = isLive,
        recordAssetId = recordAssetId,
        programId = programId,
        upcomingMessage = upcomingMessage,
    )
}

/** True if the series detail offers a "Regarder maintenant" (catch-up) tab carrying its episodes. */
fun PageResponse.hasWatchNowTab(): Boolean = content?.sections.orEmpty()
    .filter { it.componentType == "tab" }
    .flatMap { it.components.orEmpty() }
    .any { it.slug == "tab-watch-now" }

/** The episodes listed on a series' "Regarder maintenant" tab (a list-item-wide section). */
fun PageResponse.toEpisodes(): List<ContentCard> = content?.sections.orEmpty()
    .filter { it.componentType == "list-item-wide" }
    .flatMap { it.components.orEmpty() }
    .mapNotNull { it.toCard() }

/**
 * The series this program/airing belongs to, taken from its "Détails du programme" navigation (a
 * `program-details/series/{id}` link in the CTAs or body), or null for a standalone title. A show
 * opens on a single episode/airing, so this is how its full episode list is reached.
 */
fun PageResponse.seriesLink(): String? {
    val ctaUrls = content?.metadata?.ctas.orEmpty()
        .flatMap { it.actionItems.orEmpty() }
        .flatMap { it.actions?.onClick.orEmpty() }
        .mapNotNull { it.endpoint?.url }
    val sectionUrls = content?.sections.orEmpty()
        .flatMap { it.components.orEmpty() }
        .flatMap { it.actions?.onClick.orEmpty() }
        .mapNotNull { it.endpoint?.url }
    // A show's own "Détails du programme" link is a primary CTA (trkOriginElement=view_program_details_cta);
    // the recommendation carousels on the page ("see also", "most watched", …) also carry
    // program-details/series/ links, so skip those section cards (tagged trkOriginComponent=section-…) to
    // avoid listing a different show's episodes.
    return (ctaUrls + sectionUrls)
        .filterNot { it.contains("trkOriginComponent=section-") }
        .firstNotNullOfOrNull { SERIES_REGEX.find(it)?.groupValues?.get(1) }
}

fun PageResponse.toRails(): List<ContentRail> {
    val sections = content?.sections ?: return emptyList()
    return sections.mapIndexedNotNull { index, section ->
        // Banners are single promo images, not browsable content; skip them (avoids a title-less 1-card row).
        if (section.componentType == "banner") return@mapIndexedNotNull null
        val square = section.componentType == "square"
        val cards = section.components.orEmpty().mapNotNull { it.toCard(square) }
        if (cards.isEmpty()) null else ContentRail("rail-$index", section.title?.text, cards, section.seeAllUrl())
    }
}

// The "see all" target lives in the carousel's aux button as a navigation action.
private fun SectionDto.seeAllUrl(): String? = auxButton?.actions?.onClick.orEmpty()
    .firstOrNull { it.type == "navigation" }?.endpoint?.url

// Poster cards carry no title field; their display name rides in the action's trkOriginElement param.
private fun trkTitle(url: String): String? =
    Regex("""[?&]trkOriginElement=([^&]+)""").find(url)?.groupValues?.get(1)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

private fun ComponentDto.toCard(square: Boolean = false): ContentCard? {
    val img = picture?.url ?: body?.picture?.url ?: image?.url ?: imageCompact?.url
    // Cards often list a tracking action before the navigation one; prefer the navigation target.
    val actionUrl = (actions?.onClick.orEmpty().firstOrNull { it.type == "navigation" }
        ?: actions?.onClick?.firstOrNull())?.endpoint?.url
    // Live cards put the show title in their footer; poster cards carry no title field at all, only
    // the display name in the action's trkOriginElement tracking parameter.
    val label = title?.text ?: heading?.text ?: footer?.title?.text ?: actionUrl?.let { trkTitle(it) }
    if (img == null && label == null) return null

    // Match only the path, not the query string: a nav url can carry an unrelated program-details/...
    // link in its tracking params, which would otherwise set the wrong id (or both vod and series).
    val path = actionUrl?.substringBefore('?')
    val channelId = path?.let { CHANNEL_REGEX.find(it)?.groupValues?.get(1) }
    val vodId = path?.let { VOD_REGEX.find(it)?.groupValues?.get(1) }
    val seriesId = path?.let { SERIES_REGEX.find(it)?.groupValues?.get(1) }

    return ContentCard(
        id = id ?: label ?: img ?: "card",
        title = label,
        imageUrl = img,
        isLocked = isLocked ?: state?.isLocked ?: false,
        channelId = channelId,
        vodId = vodId,
        seriesId = seriesId,
        actionUrl = actionUrl,
        recordingAssetId = null,
        // channel_id is a number on live cards but a string on poster cards; accept both.
        liveChannelId = (channelIdRaw as? String) ?: (channelIdRaw as? Number)?.toLong()?.toString(),
        square = square,
    )
}

/**
 * Builds a channel id -> name directory from a `/page/channels` response: each channel card's title
 * is the name, and a `channel-details/{id}` action carries its id.
 */
fun PageResponse.toChannelDirectory(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    content?.sections.orEmpty().forEach { section ->
        section.components.orEmpty().forEach { card ->
            val name = card.title?.text ?: card.heading?.text ?: card.footer?.title?.text ?: return@forEach
            val id = card.actions?.onClick.orEmpty()
                .firstNotNullOfOrNull { item -> item.endpoint?.url?.let { CHANNEL_DETAILS_REGEX.find(it)?.groupValues?.get(1) } }
                ?: return@forEach
            out.putIfAbsent(id, name)
        }
    }
    return out
}

/**
 * The channels page's "Apps" section (broadcaster apps: france.tv, TF1+, M6+, ...) as a rail for the
 * home page. An entitled app links to its `broadcaster-details/{id}` catalogue (a normal page); a
 * locked one only carries a tracking action and is marked `isLocked`.
 */
fun PageResponse.toAppsRail(): ContentRail? {
    val section = content?.sections.orEmpty()
        .firstOrNull { it.title?.text == "Apps" || it.title?.text == "Applications" } ?: return null
    val cards = section.components.orEmpty().mapNotNull { it.toCard(square = true) }
    return if (cards.isEmpty()) null else ContentRail("apps", "Applications", cards)
}
