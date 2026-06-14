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
    val metadata: MetadataDto?,
    val sections: List<SectionDto>?,
)

data class MetadataDto(
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
data class CtaOnClickDto(val content: CtaContentDto?)
data class CtaContentDto(@Json(name = "menu_items") val menuItems: List<MenuItemDto>?)
data class MenuItemDto(val id: String?)

data class TagDto(val label: String?)
data class AboutFieldDto(val label: TextDto?, val value: TextDto?)

data class SectionDto(
    val title: TextDto?,
    @Json(name = "component_type") val componentType: String?,
    val components: List<ComponentDto>?,
)

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
    val description: TextDto?,
    @Json(name = "about_fields") val aboutFields: List<AboutFieldDto>?,
)

data class BodyDto(val picture: PictureDto?)
data class StateDto(@Json(name = "is_locked") val isLocked: Boolean?)
data class ActionsDto(@Json(name = "on_click") val onClick: List<ActionItemDto>?)
data class ActionItemDto(val endpoint: EndpointDto?)
data class EndpointDto(val url: String?, val method: String?)
data class PictureDto(val url: String?)
data class TextDto(val text: String?)

// --- mapping to domain ---

private val CHANNEL_REGEX = Regex("""program-details/channel/(\d+)""")
private val VOD_REGEX = Regex("""program-details/program/([\w-]+)""")
private val SERIES_REGEX = Regex("""program-details/series/([\w-]+)""")
private val RECORD_REGEX = Regex("""id-record-(LIVE_[0-9]+)""")

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
    val credits = about?.description?.text
    // Some descriptions already end with the "Réalisé par … avec …" credits; drop that tail so the
    // cast is not shown twice (once in the synopsis, once as the credits line).
    val rawSynopsis = meta?.description?.text
    val synopsis = if (credits != null && rawSynopsis != null && rawSynopsis.contains(credits)) {
        rawSynopsis.substringBefore(credits).trim().ifEmpty { null }
    } else {
        rawSynopsis
    }
    // The live airing to record sits in a CTA menu-item id like "id-record-LIVE_3479644".
    val recordAssetId = meta?.ctas.orEmpty()
        .flatMap { it.actionItems.orEmpty() }
        .flatMap { it.actions?.onClick.orEmpty() }
        .flatMap { it.content?.menuItems.orEmpty() }
        .firstNotNullOfOrNull { item -> item.id?.let { RECORD_REGEX.find(it)?.groupValues?.get(1) } }
    return ProgramDetail(
        title = meta?.title?.text,
        subtitle = meta?.subtitle?.text,
        synopsis = synopsis,
        posterUrl = meta?.artwork?.url,
        genre = fields["Genre"],
        year = fields["Année de sortie"],
        classification = fields["Classification"],
        credits = credits,
        tags = meta?.tags.orEmpty().mapNotNull { it.label },
        channelId = channelId,
        vodId = vodId,
        isLive = isLive,
        recordAssetId = recordAssetId,
    )
}

fun PageResponse.toRails(): List<ContentRail> {
    val sections = content?.sections ?: return emptyList()
    return sections.mapIndexedNotNull { index, section ->
        val cards = section.components.orEmpty().mapNotNull { it.toCard() }
        if (cards.isEmpty()) null else ContentRail("rail-$index", section.title?.text, cards)
    }
}

private fun ComponentDto.toCard(): ContentCard? {
    val img = picture?.url ?: body?.picture?.url ?: image?.url ?: imageCompact?.url
    val label = title?.text ?: heading?.text
    if (img == null && label == null) return null

    val actionUrl = actions?.onClick?.firstOrNull()?.endpoint?.url
    val channelId = actionUrl?.let { CHANNEL_REGEX.find(it)?.groupValues?.get(1) }
    val vodId = actionUrl?.let { VOD_REGEX.find(it)?.groupValues?.get(1) }
    val seriesId = actionUrl?.let { SERIES_REGEX.find(it)?.groupValues?.get(1) }

    return ContentCard(
        id = id ?: label ?: img ?: "card",
        title = label,
        imageUrl = img,
        isLocked = isLocked ?: state?.isLocked ?: false,
        channelId = channelId,
        vodId = vodId,
        seriesId = seriesId,
        actionUrl = actionUrl,
    )
}
