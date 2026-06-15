// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import it.allard.etincelle.core.cast.CastReceiver
import it.allard.etincelle.core.designsystem.categoryIconRes
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.expandable
import it.allard.etincelle.core.model.groupBySeason
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Recording

@Composable
fun LoginScreen(busy: Boolean, error: String?, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("etincelle", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onLogin(email, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Se connecter")
        }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * A Box that triggers [onRefresh] when the user drags past either end of a scrollable child (pull
 * down at the top or push up past the bottom), and shows a thin progress bar while [refreshing].
 *
 * The drag is measured in onPreScroll at a boundary, before the child and its stretch-overscroll
 * effect consume it (measuring afterwards in onPostScroll reads ~0 and never fires).
 */
@Composable
fun RefreshableBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    atTop: () -> Boolean,
    atBottom: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val refreshingNow by rememberUpdatedState(refreshing)
    val topNow by rememberUpdatedState(atTop)
    val bottomNow by rememberUpdatedState(atBottom)
    val refresh by rememberUpdatedState(onRefresh)
    val connection = remember {
        object : NestedScrollConnection {
            var overscroll = 0f

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!refreshingNow && source == NestedScrollSource.UserInput) {
                    val dy = available.y
                    when {
                        dy > 0f && topNow() -> overscroll += dy
                        dy < 0f && bottomNow() -> overscroll += dy
                        else -> overscroll = 0f
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!refreshingNow && kotlin.math.abs(overscroll) > threshold) refresh()
                overscroll = 0f
                return Velocity.Zero
            }
        }
    }
    Box(modifier.nestedScroll(connection)) {
        content()
        if (refreshing) {
            LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = BrandYellow)
        }
    }
}

@Composable
fun PageContent(
    rails: List<ContentRail>,
    busy: Boolean,
    error: String?,
    onCardClick: (ContentCard) -> Unit,
    onSeeAll: (ContentRail) -> Unit,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    RefreshableBox(
        refreshing,
        onRefresh,
        atTop = { !listState.canScrollBackward },
        atBottom = { !listState.canScrollForward },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(rails, key = { it.id }) { rail -> Rail(rail, onCardClick, onSeeAll) }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (error != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** A "see all" page: every card in a full-screen scrollable grid, 3 per row. */
@Composable
fun GridContent(
    rails: List<ContentRail>,
    busy: Boolean,
    error: String?,
    onCardClick: (ContentCard) -> Unit,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // A multi-section page (e.g. the channels page, with an "Apps" group) keeps its section headers;
    // a single-section see-all just shows its cards (its title is already in the top bar).
    val multiSection = rails.size > 1
    val gridState = rememberLazyGridState()
    RefreshableBox(
        refreshing,
        onRefresh,
        atTop = { !gridState.canScrollBackward },
        atBottom = { !gridState.canScrollForward },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rails.forEach { rail ->
                val header = rail.title?.takeIf { multiSection && it.isNotBlank() }
                if (header != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-${rail.id}") {
                        Text(header, style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(rail.cards, key = { "${rail.id}-${it.id}" }) { card -> GridCard(card, onCardClick) }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (error != null) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun GridCard(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onCardClick(card) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)).background(BackgroundLevel1),
            contentAlignment = Alignment.Center,
        ) {
            if (categoryIcon != null) {
                Icon(painterResource(categoryIcon), contentDescription = card.title, tint = BrandYellow, modifier = Modifier.size(28.dp))
            } else {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (card.isLocked) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp),
                ) {
                    Text("€", style = MaterialTheme.typography.labelSmall, color = BrandBlack)
                }
            }
        }
        card.title?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        card.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = BrandYellow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Settings page: app-icon, hide-locked and Cast-receiver toggles, plus Déconnexion (with confirmation). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    hideLocked: Boolean = false,
    onHideLocked: (Boolean) -> Unit = {},
    appVersion: String = "",
    checkingUpdate: Boolean = false,
    updateStatus: String? = null,
    onCheckUpdate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var mono by remember { mutableStateOf(AppIcon.isMono(context)) }
    var officialReceiver by remember { mutableStateOf(CastReceiver.isOfficial(context)) }
    // statusBarsPadding keeps the Retour button below the status bar so it stays tappable.
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Spacer(Modifier.width(8.dp))
            Text("Paramètres", style = MaterialTheme.typography.titleLarge)
        }
        SettingToggle(
            title = "Masquer les contenus verrouillés",
            subtitle = "Cache les programmes non inclus dans votre abonnement",
            checked = hideLocked,
            onCheckedChange = { LocalPrefs.setHideLocked(context, it); onHideLocked(it) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        SettingToggle(
            title = "Icône monochrome",
            subtitle = "Logo blanc sur fond transparent",
            checked = mono,
            onCheckedChange = { mono = it; AppIcon.setMono(context, it) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        SettingToggle(
            title = "Récepteur Fubo officiel",
            subtitle = "Expérimental. Redémarrez l'application après le changement.",
            checked = officialReceiver,
            onCheckedChange = { officialReceiver = it; CastReceiver.setOfficial(context, it) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !checkingUpdate) { onCheckUpdate() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Vérifier les mises à jour", style = MaterialTheme.typography.bodyLarge)
                Text(
                    updateStatus ?: "Version actuelle : $appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (checkingUpdate) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Text(
            "Déconnexion",
            style = MaterialTheme.typography.bodyLarge,
            color = BrandYellow,
            modifier = Modifier.fillMaxWidth().clickable { confirm = true }.padding(horizontal = 16.dp, vertical = 18.dp),
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Déconnexion") },
            text = { Text("Voulez-vous vraiment vous déconnecter ?") },
            confirmButton = { TextButton(onClick = { confirm = false; onLogout() }) { Text("Se déconnecter") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

/** A settings row: a title + subtitle on the left and a Material switch on the right. */
@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SearchScreen(
    rails: List<ContentRail>,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCardClick: (ContentCard) -> Unit,
    onSeeAll: (ContentRail) -> Unit,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Rechercher une chaîne, un film, une série…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit(query) }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        PageContent(
            rails = rails,
            busy = busy,
            error = error,
            onCardClick = onCardClick,
            onSeeAll = onSeeAll,
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A show's detail page: poster, info (year, genre, cast), synopsis, and a Regarder button. */
@Composable
fun ProgramDetailScreen(
    detail: ProgramDetail,
    busy: Boolean,
    error: String?,
    info: String?,
    onWatch: () -> Unit,
    onRecord: () -> Unit,
    onWatchRecording: (String) -> Unit,
    onBack: () -> Unit,
    onEpisode: (ContentCard) -> Unit = {},
    isRecording: Boolean = false,
    castButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            castButton()
        }
        if (detail.posterUrl != null) {
            Box(Modifier.fillMaxWidth().height(220.dp).background(BackgroundLevel1)) {
                AsyncImage(
                    model = detail.posterUrl,
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(detail.title ?: "", style = MaterialTheme.typography.headlineSmall)
            val sub = listOfNotNull(detail.subtitle, detail.genre).joinToString(" • ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (detail.tags.isNotEmpty()) {
                Text(detail.tags.joinToString("   "), style = MaterialTheme.typography.labelMedium, color = BrandYellow)
            }
            // A multi-episode series has no directly playable asset (its id is not a VOD), so it has
            // no "Regarder"; the user picks an episode. A recorded series keeps it (plays the recording).
            val showWatch = !(detail.isSeries && !isRecording)
            if (showWatch || detail.recordAssetId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (showWatch) {
                        Button(onClick = onWatch, enabled = !busy) {
                            Text(if (detail.isLive) "Regarder en direct" else "Regarder")
                        }
                    }
                    if (detail.recordAssetId != null) {
                        Button(onClick = onRecord, enabled = !busy) { Text("Enregistrer") }
                    }
                }
            }
            if (busy) CircularProgressIndicator()
            if (info != null) Text(info, color = BrandYellow)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            detail.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            detail.credits?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOfNotNull(
                detail.year?.let { "Année de sortie : $it" },
                detail.classification?.let { "Classification : $it" },
            ).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // For a recording, hide the catch-up episodes (their VOD can 5xx for recorded content);
            // the user's recordings below are what plays.
            if (detail.episodes.isNotEmpty() && !isRecording) {
                EpisodesSection(detail.episodes, busy, onEpisode)
            }
            if (detail.recordings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Vos enregistrements", style = MaterialTheme.typography.titleMedium)
                detail.recordings.forEach { recording ->
                    RecordingRow(recording, enabled = !busy, onWatch = { onWatchRecording(recording.assetId) })
                }
            }
        }
    }
}

/** The "Épisodes disponibles" section: a season dropdown (when there are several) over the episode list. */
@Composable
private fun EpisodesSection(episodes: List<ContentCard>, busy: Boolean, onEpisode: (ContentCard) -> Unit) {
    val seasons = remember(episodes) { episodes.groupBySeason() }
    var selected by remember(episodes) { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val idx = selected.coerceIn(0, seasons.size - 1)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Épisodes disponibles", style = MaterialTheme.typography.titleMedium)
        if (seasons.size > 1) {
            Spacer(Modifier.width(12.dp))
            Box {
                TextButton(onClick = { expanded = true }) { Text("${seasons[idx].first} ▾", color = BrandYellow) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    seasons.forEachIndexed { i, (label, _) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { selected = i; expanded = false })
                    }
                }
            }
        }
    }
    seasons[idx].second.forEach { ep -> EpisodeRow(ep, enabled = !busy, onClick = { onEpisode(ep) }) }
}

/** One catch-up episode row on a detail page: a thumbnail and label, tappable to open the episode. */
@Composable
private fun EpisodeRow(card: ContentCard, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.title,
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(BackgroundLevel1),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            card.title ?: "Épisode",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge, color = BrandYellow)
    }
}

/** One DVR recording on a detail page: its subtitle/channel plus a "Regarder l'enregistrement" action. */
@Composable
private fun RecordingRow(recording: Recording, enabled: Boolean, onWatch: () -> Unit) {
    val label = listOfNotNull(recording.subtitle, recording.channelName).joinToString(" • ")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.ifBlank { recording.title ?: "Enregistrement" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onWatch, enabled = enabled) { Text("Regarder l'enregistrement") }
    }
}

@Composable
private fun Rail(rail: ContentRail, onCardClick: (ContentCard) -> Unit, onSeeAll: (ContentRail) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        rail.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (rail.expandable()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onSeeAll(rail) }
                        .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("Tout voir ›", style = MaterialTheme.typography.labelLarge, color = BrandYellow)
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rail.cards, key = { it.id }) { card -> CardItem(card, onCardClick) }
        }
    }
}

@Composable
private fun CardItem(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    // Only channel-logo cards draw as small squares; program/guide cards stay landscape.
    val isChannel = card.square
    // A genre tile is a navigation card (no channel/VOD id) whose title names a category; it carries
    // no artwork, so draw the brand icon instead of a blank box.
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    val cardWidth = if (isChannel) 96.dp else 150.dp
    Column(
        modifier = Modifier.width(cardWidth).clickable { onCardClick(card) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = (if (isChannel) Modifier.size(96.dp) else Modifier.width(150.dp).height(86.dp))
                .clip(RoundedCornerShape(if (isChannel) 16.dp else 10.dp))
                .background(BackgroundLevel1),
            contentAlignment = Alignment.Center,
        ) {
            if (categoryIcon != null) {
                Icon(
                    painterResource(categoryIcon),
                    contentDescription = card.title,
                    tint = BrandYellow,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize().padding(if (isChannel) 12.dp else 0.dp),
                    contentScale = if (isChannel) ContentScale.Fit else ContentScale.Crop,
                )
            }
            if (card.isLocked) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp),
                ) {
                    Text("€", style = MaterialTheme.typography.labelSmall, color = BrandBlack)
                }
            }
        }
        card.title?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(cardWidth),
            )
        }
    }
}

// categoryIconRes now lives in core:designsystem so the TV app shares the same genre icons.
