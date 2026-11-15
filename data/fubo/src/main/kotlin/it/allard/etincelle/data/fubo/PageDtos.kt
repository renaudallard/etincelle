// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.data.fubo

import com.squareup.moshi.Json
import it.allard.etincelle.core.model.CardShape
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.FavoriteChannel
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.UpNext

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
    // Sent only with the auto_play_up_next feature: the slate offering the next episode.
    @Json(name = "end_slate") val endSlate: EndSlateDto? = null,
)

// The end slate: at [position] seconds a show_video_slate action opens an "up-next" slate.
data class EndSlateDto(val position: Long?, val actions: List<SlateActionDto>?)
data class SlateActionDto(val type: String?, val content: UpNextDto?)
data class UpNextDto(
    val type: String?,
    val title: TextDto?,
    val subtitle: TextDto?,
    @Json(name = "count_down_text") val countDownText: TextDto?,
    @Json(name = "display_duration") val displayDuration: Long?,
    // Run when the countdown ends: a refresh_page to the next episode's page (whose player then plays).
    @Json(name = "auto_dismiss_actions") val autoDismissActions: List<ActionItemDto>?,
    // The buttons: play the next episode now, or watch the credits.
    val components: List<SlateButtonDto>?,
)
data class SlateButtonDto(val text: TextDto?, val style: String?)

/** The next episode offered by the end slate, or null when nothing follows or the slate is unusable. */
fun PlayerDto.toUpNext(): UpNext? {
    val slate = endSlate ?: return null
    val at = slate.position ?: return null
    val upNext = slate.actions.orEmpty().firstOrNull { it.type == "show_video_slate" }?.content
        ?.takeIf { it.type == "up-next" } ?: return null
    val nextVodId = upNext.autoDismissActions.orEmpty()
        .firstNotNullOfOrNull { a -> a.endpoint?.url?.substringBefore('?')?.let { VOD_REGEX.find(it)?.groupValues?.get(1) } }
        ?: return null
    val buttons = upNext.components.orEmpty()
    return UpNext(
        atSec = at,
        showSec = upNext.displayDuration ?: DEFAULT_SLATE_SEC,
        title = upNext.title?.text,
        subtitle = upNext.subtitle?.text,
        countdown = upNext.countDownText?.text?.takeIf { "%s" in it } ?: "Commence dans %s",
        playLabel = buttons.firstOrNull { it.style == "primary" }?.text?.text ?: "À suivre",
        creditsLabel = buttons.firstOrNull { it.style == "secondary" }?.text?.text ?: "Regarder le générique",
        nextVodId = nextVodId,
    )
}

private const val DEFAULT_SLATE_SEC = 10L

data class MetadataDto(
    val id: String?,
    val title: TextDto?,
    val subtitle: TextDto?,
    val description: TextDto?,
    val artwork: PictureDto?,
    val tags: List<TagDto>?,
    val ctas: List<CtaDto>?,
)

// The record actions live in the metadata CTAs. The backend delivers them two ways: as a direct
// action-item (id "id-record-LIVE_…" or "id-record-series-…") on a programme/series page, or as the
// items of a "record options" dropdown (id "id-record-options-…") on a live-channel page. Either way,
// each carries the api_call (endpoint url + payload) that schedules the recording.
data class CtaDto(@Json(name = "action_items") val actionItems: List<CtaActionItemDto>?)
data class CtaActionItemDto(val text: TextDto?, val actions: CtaActionsDto?)
data class CtaActionsDto(@Json(name = "on_click") val onClick: List<CtaOnClickDto>?)
data class CtaOnClickDto(val type: String? = null, val content: CtaContentDto? = null, val endpoint: EndpointDto? = null)
data class CtaContentDto(@Json(name = "menu_items") val menuItems: List<MenuItemDto>?)
data class MenuItemDto(val text: TextDto? = null, val actions: CtaActionsDto? = null)

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
    val header: HeaderDto? = null,
    @Json(name = "is_favorite") val isFavorite: Boolean? = null,
)

// Live ("En direct à la TV") cards carry their show title in a footer rather than in title/heading.
data class FooterDto(val title: TextDto?, val subtitle: TextDto?)

// A poster card names the channel offering it with a logo, in its body or its header.
data class BodyDto(val picture: PictureDto?, @Json(name = "station_logo") val stationLogo: PictureDto? = null)
data class HeaderDto(@Json(name = "station_logo") val stationLogo: PictureDto? = null)
data class StateDto(@Json(name = "is_locked") val isLocked: Boolean?)
data class ActionsDto(
    @Json(name = "on_click") val onClick: List<ActionItemDto>?,
    // A channel card's favourite toggle: an api_call to favorite-channel or unfavorite-channel.
    @Json(name = "on_fav_icon_click") val onFavIconClick: List<ActionItemDto>? = null,
)
data class ActionItemDto(val endpoint: EndpointDto?, val type: String? = null)
data class EndpointDto(
    val url: String?,
    val method: String?,
    // The request body for an api_call endpoint (e.g. a record action), replayed verbatim. Null for
    // plain navigation endpoints.
    val payload: Map<String, @JvmSuppressWildcards Any?>? = null,
)
data class PictureDto(val url: String?)
data class TextDto(val text: String?)

// --- mapping to domain ---

private val CHANNEL_REGEX = Regex("""program-details/channel/(\d+)""")
private val CHANNEL_DETAILS_REGEX = Regex("""channel-details/(\d+)""")
private val VOD_REGEX = Regex("""program-details/program/([\w-]+)""")
private val SERIES_REGEX = Regex("""program-details/series/([\w-]+)""")
// metadata.id looks like "id-metadata-program-details-2768066_48"; the trailing "{digits}_{digits}"
// is the program id used to match this show's DVR recordings.
private val PROGRAM_ID_REGEX = Regex("""(\d+_\d+)$""")

// A record action-item/menu-item, identified by its api_call's action_name (add-recording,
// record-new-episodes, stop-recording-series, …) rather than a fragile element-id convention, so the
// button toggles correctly once a recording exists. The "record options" dropdown container carries no
// api_call of its own (only its nested items do), so it maps to null.
private fun recordActionFrom(text: TextDto?, actions: CtaActionsDto?): RecordAction? {
    val endpoint = actions?.onClick.orEmpty().firstOrNull { it.type == "api_call" }?.endpoint ?: return null
    val payload = endpoint.payload ?: return null
    if ((payload["action_name"] as? String)?.contains("record", ignoreCase = true) != true) return null
    val url = endpoint.url ?: return null
    val label = text?.text?.takeIf { it.isNotBlank() } ?: return null
    return RecordAction(label = label, url = url, payload = payload)
}

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
    // Record actions ("Enregistrer l'épisode" / "Enregistrer la série") are server-driven and appear
    // as a direct action-item or as the items of a "record options" dropdown, so gather from both.
    val recordActions = meta?.ctas.orEmpty().flatMap { it.actionItems.orEmpty() }.flatMap { ai ->
        val nested = ai.actions?.onClick.orEmpty()
            .flatMap { it.content?.menuItems.orEmpty() }
            .mapNotNull { recordActionFrom(it.text, it.actions) }
        listOfNotNull(recordActionFrom(ai.text, ai.actions)) + nested
    }
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
        recordActions = recordActions,
        programId = programId,
        upcomingMessage = upcomingMessage,
        upNext = content?.player?.toUpNext(),
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
        val shape = cardShape(section.componentType)
        val cards = section.components.orEmpty().mapNotNull { it.toCard(shape) }
        if (cards.isEmpty()) null else ContentRail("rail-$index", section.title?.text, cards, section.seeAllUrl())
    }
}

// The row's component type says how its artwork is cut: posters are portrait images (1000x1314,
// thin ones 720x1080), channel and app logos are squares, the rest is 16:9.
private fun cardShape(componentType: String?): CardShape = when (componentType) {
    "square" -> CardShape.SQUARE
    "card-poster" -> CardShape.POSTER
    "card-poster-thin" -> CardShape.POSTER_THIN
    else -> CardShape.WIDE
}

// The "see all" target lives in the carousel's aux button as a navigation action.
private fun SectionDto.seeAllUrl(): String? = auxButton?.actions?.onClick.orEmpty()
    .firstOrNull { it.type == "navigation" }?.endpoint?.url

// Poster cards carry no title field; their display name rides in the action's trkOriginElement param.
private fun trkTitle(url: String): String? =
    Regex("""[?&]trkOriginElement=([^&]+)""").find(url)?.groupValues?.get(1)
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

private fun ComponentDto.toCard(shape: CardShape = CardShape.WIDE): ContentCard? {
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
    // A live card links to its channel via `program-details/channel/{id}`; a card on the /channels grid
    // uses `channel-details/{id}` (same channel id). Accept either so both open the channel detail
    // (with "Regarder en direct") rather than loading the link as a generic page.
    val channelId = path?.let {
        CHANNEL_REGEX.find(it)?.groupValues?.get(1) ?: CHANNEL_DETAILS_REGEX.find(it)?.groupValues?.get(1)
    }
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
        shape = shape,
        channelLogoUrl = body?.stationLogo?.url ?: header?.stationLogo?.url,
        favorite = actions?.onFavIconClick?.let { apiCall(it, if (isFavorite == true) "Retirer des favoris" else "Ajouter aux favoris") },
        isFavorite = isFavorite == true,
    )
}

// The first api_call among [items] as a replayable action, or null when there is none.
private fun apiCall(items: List<ActionItemDto>, label: String): RecordAction? {
    val endpoint = items.firstOrNull { it.type == "api_call" }?.endpoint ?: return null
    return RecordAction(label, endpoint.url ?: return null, endpoint.payload ?: return null)
}

// --- favourite channels order ---

data class FavoriteOrderResponse(val content: FavoriteOrderContentDto?)
data class FavoriteOrderContentDto(
    val title: TextDto?,
    val subtitle: TextDto?,
    val items: List<FavoriteItemDto>?,
    @Json(name = "submit_button") val submitButton: SubmitButtonDto?,
)
data class FavoriteItemDto(val id: String?, val name: TextDto?, @Json(name = "logo_dark") val logoDark: PictureDto?)
data class SubmitButtonDto(val text: TextDto?, val actions: ActionsDto?)

/** The favourite channels in their order, or null when the page carries no save action. */
fun FavoriteOrderResponse.toFavoriteOrder(): FavoriteOrder? {
    val c = content ?: return null
    val endpoint = c.submitButton?.actions?.onClick.orEmpty()
        .firstOrNull { it.type == "save_favorite_channel_reorder" }?.endpoint ?: return null
    val save = RecordAction(c.submitButton?.text?.text ?: "Enregistrer", endpoint.url ?: return null, endpoint.payload ?: return null)
    val channels = c.items.orEmpty().mapNotNull { item ->
        FavoriteChannel(item.id ?: return@mapNotNull null, item.name?.text ?: return@mapNotNull null, item.logoDark?.url)
    }
    return FavoriteOrder(c.title?.text ?: "Réorganiser les favoris", c.subtitle?.text, channels, save)
}

/**
 * The body that saves [channelIds] as the favourites order: the backend's own payload with the new
 * order in its custom_action_data, as the official app sends it.
 */
fun favoriteOrderPayload(save: RecordAction, channelIds: List<String>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val custom = (save.payload["custom_action_data"] as? Map<String, Any?>).orEmpty()
    return save.payload + ("custom_action_data" to custom + ("channel_ids" to channelIds))
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
    val cards = section.components.orEmpty().mapNotNull { it.toCard(CardShape.SQUARE) }
    return if (cards.isEmpty()) null else ContentRail("apps", "Applications", cards)
}
