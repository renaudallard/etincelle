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
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.Recording
import kotlin.math.roundToInt

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
    columns: Int = LocalPrefs.DEFAULT_GRID_COLUMNS,
    pageTitle: String? = null,
    modifier: Modifier = Modifier,
) {
    // Show each rail's section header (genre, "Apps", ...) unless it just repeats the page's top-bar
    // title, as a plain see-all of a single rail does.
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
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            rails.forEach { rail ->
                val header = rail.title?.takeIf { it.isNotBlank() && it != pageTitle }
                if (header != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-${rail.id}") {
                        Text(header, style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Index in the key too: a card id falls back to its label/image, which can repeat
                // within a rail, and a duplicate lazy-list key crashes the list.
                itemsIndexed(rail.cards, key = { i, c -> "${rail.id}-${c.id}#$i" }) { _, card -> GridCard(card, onCardClick) }
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

/** A small badge over a card's image, e.g. "4 épisodes" on a collapsed recordings card. */
@Composable
private fun BoxScope.EpisodeCountBadge(text: String) {
    Box(
        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
            .clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = BrandBlack)
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
            card.badge?.let { EpisodeCountBadge(it) }
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
    gridColumns: Int = LocalPrefs.DEFAULT_GRID_COLUMNS,
    onGridColumns: (Int) -> Unit = {},
    appVersion: String = "",
    checkingUpdate: Boolean = false,
    updateStatus: String? = null,
    onCheckUpdate: () -> Unit = {},
    onConnectTv: (String) -> Unit = {},
    onClearTvConnect: () -> Unit = {},
    connectInfo: String? = null,
    connectError: String? = null,
    connecting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    var connectTv by remember { mutableStateOf(false) }
    var tvCode by remember { mutableStateOf("") }
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Vignettes par ligne : $gridColumns", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Densité des grilles (films, séries, enregistrements)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = gridColumns.toFloat(),
                onValueChange = {
                    val n = it.roundToInt().coerceIn(LocalPrefs.MIN_GRID_COLUMNS, LocalPrefs.MAX_GRID_COLUMNS)
                    if (n != gridColumns) {
                        LocalPrefs.setGridColumns(context, n)
                        onGridColumns(n)
                    }
                },
                valueRange = LocalPrefs.MIN_GRID_COLUMNS.toFloat()..LocalPrefs.MAX_GRID_COLUMNS.toFloat(),
                steps = LocalPrefs.MAX_GRID_COLUMNS - LocalPrefs.MIN_GRID_COLUMNS - 1,
            )
        }
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
        Column(
            Modifier.fillMaxWidth().clickable { onClearTvConnect(); tvCode = ""; connectTv = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Connecter une TV", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Saisissez le code affiché sur votre téléviseur",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    if (connectTv) {
        AlertDialog(
            onDismissRequest = { connectTv = false; tvCode = ""; onClearTvConnect() },
            title = { Text("Connecter une TV") },
            text = {
                Column {
                    Text("Saisissez le code affiché sur l'écran de connexion de votre téléviseur.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tvCode,
                        onValueChange = {
                            tvCode = it.filter(Char::isDigit).take(8)
                            // Editing the code clears a previous result, so a follow-up pairing (e.g. a
                            // second TV) can be submitted in the same dialog instead of staying locked
                            // out by the success message.
                            if (connectInfo != null || connectError != null) onClearTvConnect()
                        },
                        label = { Text("Code") },
                        singleLine = true,
                        enabled = !connecting,
                    )
                    if (connectError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(connectError, color = MaterialTheme.colorScheme.error)
                    } else if (connectInfo != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(connectInfo, color = BrandYellow)
                    }
                }
            },
            confirmButton = {
                // Disabled once a connect succeeded so a second tap cannot re-submit the now consumed
                // code and replace the success message with a spurious error.
                TextButton(
                    onClick = { onConnectTv(tvCode) },
                    enabled = tvCode.length >= 4 && !connecting && connectInfo == null,
                ) { Text("Connecter") }
            },
            dismissButton = { TextButton(onClick = { connectTv = false; tvCode = ""; onClearTvConnect() }) { Text("Fermer") } },
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
    onStartOver: () -> Unit,
    onRecord: (RecordAction) -> Unit,
    onWatchRecording: (String) -> Unit,
    onBack: () -> Unit,
    onEpisode: (ContentCard) -> Unit = {},
    isRecording: Boolean = false,
    castButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Reset to the top whenever a different detail opens, so a pushed episode page shows its title and
    // "Regarder" rather than inheriting the previous page's scroll position.
    val scrollState = rememberScrollState()
    LaunchedEffect(detail) { scrollState.scrollTo(0) }
    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(scrollState)) {
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
        } else if (detail.channelLogoUrl != null) {
            // No programme poster (e.g. a FAST channel with no "now playing"): show the channel logo
            // centred so the page has a header instead of opening bare.
            Box(
                Modifier.fillMaxWidth().height(160.dp).background(BackgroundLevel1),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = detail.channelLogoUrl,
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentScale = ContentScale.Fit,
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
            // An upcoming (not-yet-aired) programme is not playable either: show the backend's reason.
            val showWatch = !(detail.isSeries && !isRecording) && detail.upcomingMessage == null
            detail.upcomingMessage?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = BrandYellow)
            }
            if (showWatch || detail.recordActions.isNotEmpty()) {
                // Wrap so the record button stays reachable when a live channel shows several actions
                // (Regarder en direct + depuis le début + Enregistrer) that overflow one row.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (showWatch) {
                        Button(onClick = onWatch, enabled = !busy) {
                            Text(if (detail.isLive) "Regarder en direct" else "Regarder")
                        }
                    }
                    if (showWatch && detail.isLive) {
                        Button(onClick = onStartOver, enabled = !busy) { Text("Regarder depuis le début") }
                    }
                    if (detail.recordActions.isNotEmpty()) {
                        RecordButton(detail.recordActions, enabled = !busy, onRecord = onRecord)
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
            // Your own recordings first (what you came for); the full list of available episodes follows.
            if (detail.recordings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Vos enregistrements", style = MaterialTheme.typography.titleMedium)
                detail.recordings.forEach { recording ->
                    RecordingRow(recording, enabled = !busy, onWatch = { onWatchRecording(recording.assetId) })
                }
            }
            if (detail.episodes.isNotEmpty()) {
                EpisodesSection(detail.episodes, busy, onEpisode)
            }
        }
        // A channel's catalogue carousels (replays, most-viewed, live & upcoming) full width below the
        // live header. onEpisode is the shared card click (vm.onCardClick), so a card opens normally.
        detail.sections.forEach { rail ->
            Spacer(Modifier.height(20.dp))
            // No "Tout voir" here: a see-all would navigate out of the open detail. The carousels carry
            // plenty of cards, and each card opens normally via onEpisode (vm.onCardClick).
            Rail(rail, onCardClick = onEpisode, onSeeAll = {}, showSeeAll = false)
        }
        if (detail.sections.isNotEmpty()) Spacer(Modifier.height(16.dp))
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

/** The record CTA: a single button carrying the backend's own label when there is one option, or a
 * button that opens a menu when there are several (e.g. "Enregistrer l'épisode" / "Enregistrer la série"). */
@Composable
private fun RecordButton(actions: List<RecordAction>, enabled: Boolean, onRecord: (RecordAction) -> Unit) {
    if (actions.size == 1) {
        Button(onClick = { onRecord(actions[0]) }, enabled = enabled) { Text(actions[0].label) }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { expanded = true }, enabled = enabled) { Text("Enregistrer") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(text = { Text(action.label) }, onClick = { expanded = false; onRecord(action) })
            }
        }
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
private fun Rail(
    rail: ContentRail,
    onCardClick: (ContentCard) -> Unit,
    onSeeAll: (ContentRail) -> Unit,
    showSeeAll: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        rail.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (showSeeAll && rail.expandable()) {
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
            // Index in the key: a card id can repeat within a rail (it falls back to label/image), and
            // a duplicate lazy-list key crashes the row.
            itemsIndexed(rail.cards, key = { i, c -> "${c.id}#$i" }) { _, card -> CardItem(card, onCardClick) }
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
            card.badge?.let { EpisodeCountBadge(it) }
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
