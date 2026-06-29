// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.allard.etincelle.core.designsystem.categoryIconRes
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel2
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.expandable
import it.allard.etincelle.core.model.groupBySeason
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState
import it.allard.etincelle.core.ui.UpdateInfo

private val OVERSCAN = 48.dp

// The web page where the user confirms a TV: scanning the QR opens it; etincelle on a phone can
// confirm too (Paramètres > Connecter une TV).
private const val TV_CONNECT_URL = "https://www.molotov.tv/tv"

@Composable
fun TvLoginScreen(
    pairingCode: String?,
    busy: Boolean,
    error: String?,
    onStartPairing: () -> Unit,
    onStopPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onEmailLogin: (String, String) -> Unit,
) {
    var emailMode by remember { mutableStateOf(false) }
    if (emailMode) {
        TvEmailLoginScreen(busy, error, onEmailLogin, onUseCode = { emailMode = false })
    } else {
        TvCodeLoginScreen(pairingCode, busy, error, onStartPairing, onStopPairing, onUseEmail = {
            onCancelPairing()
            emailMode = true
        })
    }
}

@Composable
private fun TvCodeLoginScreen(code: String?, busy: Boolean, error: String?, onStart: () -> Unit, onStop: () -> Unit, onUseEmail: () -> Unit) {
    LaunchedEffect(Unit) { onStart() }
    // Stop polling when the code screen leaves (email toggle / sign-in), so the loop does not linger.
    DisposableEffect(Unit) { onDispose { onStop() } }
    val emailBtn = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { emailBtn.requestFocus() } }
    val qr = remember { qrImageBitmap(TV_CONNECT_URL, 480) }
    Row(
        modifier = Modifier.fillMaxSize().padding(OVERSCAN),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
    ) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = "Code QR",
                modifier = Modifier.size(300.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
            Text("etincelle", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(16.dp))
            Text("Connectez votre téléviseur", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sur votre téléphone, dans etincelle : Paramètres > Connecter une TV (ou sur " +
                    "molotov.tv/tv), connectez-vous et saisissez ce code :",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(560.dp),
            )
            Spacer(Modifier.height(20.dp))
            if (code != null) {
                Text(code, style = MaterialTheme.typography.displayMedium, color = BrandYellow)
            } else if (error == null) {
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUseEmail, modifier = Modifier.focusRequester(emailBtn)) {
                Text("Se connecter par email")
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
                // The code loop stops after a while so it does not poll an idle TV forever; once it has
                // stopped (not busy) let the user restart it instead of dead-ending with no code and no
                // way back. While it is still auto-retrying (busy) no button is needed.
                if (!busy) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onStart) { Text("Réessayer") }
                }
            }
        }
    }
}

@Composable
private fun TvEmailLoginScreen(busy: Boolean, error: String?, onLogin: (String, String) -> Unit, onUseCode: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(OVERSCAN),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("etincelle", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.width(480.dp).focusRequester(first))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.width(480.dp),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = { onLogin(email, password) }, enabled = !busy) { Text("Se connecter") }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onUseCode) { Text("Utiliser un code") }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun TvBrowseScreen(
    state: UiState,
    onSelectTab: (Tab) -> Unit,
    onCardClick: (ContentCard) -> Unit,
    onSearch: (String) -> Unit,
    onSettings: () -> Unit,
    onSeeAll: (ContentRail) -> Unit,
    gridColumns: Int,
) {
    val firstTab = remember { FocusRequester() }
    val railsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstTab.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = OVERSCAN, end = OVERSCAN, top = 24.dp)) {
        val tabs = Tab.entries
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        runCatching { railsFocus.requestFocus() }.isSuccess
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(tabs) { i, tab ->
                TvTab(
                    label = tab.label,
                    selected = state.tab == tab && !state.canGoBack,
                    onClick = { onSelectTab(tab) },
                    modifier = if (i == 0) Modifier.focusRequester(firstTab) else Modifier,
                )
            }
            item {
                TvTab(label = "Paramètres", selected = false, onClick = onSettings)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.tab == Tab.SEARCH) {
            TvSearchContent(state, onSearch, onCardClick, onSeeAll)
        } else {
            Text(state.current?.title ?: state.tab.label, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                val page = state.current
                if (page?.isGrid == true) {
                    TvGridContent(page.rails, onCardClick, railsFocus, gridColumns)
                } else {
                    LazyColumn(
                        modifier = Modifier.focusGroup().focusRequester(railsFocus),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(page?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick, onSeeAll) }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TvSearchContent(
    state: UiState,
    onSubmit: (String) -> Unit,
    onCardClick: (ContentCard) -> Unit,
    onSeeAll: (ContentRail) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher") },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(field),
            )
            Button(onClick = { onSubmit(query) }, enabled = query.isNotBlank()) { Text("Rechercher") }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.current?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick, onSeeAll) }
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
    }
}

@Composable
private fun TvTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) BrandYellow else if (selected) BackgroundLevel2 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (focused) BrandBlack else Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** Settings page on TV. For now its only entry is Déconnexion, with an inline confirmation. */
@Composable
fun TvSettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(confirm) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(OVERSCAN), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Paramètres", style = MaterialTheme.typography.displaySmall)
        if (!confirm) {
            Button(onClick = { confirm = true }, modifier = Modifier.focusRequester(focus)) { Text("Déconnexion") }
        } else {
            Text("Voulez-vous vraiment vous déconnecter ?", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirm = false }, modifier = Modifier.focusRequester(focus)) { Text("Annuler") }
                Button(onClick = onLogout) { Text("Se déconnecter") }
            }
        }
        Text("Vignettes par ligne : $gridColumns", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in TvPrefs.MIN_GRID_COLUMNS..TvPrefs.MAX_GRID_COLUMNS) {
                Button(
                    onClick = { TvPrefs.setGridColumns(context, n); onGridColumns(n) },
                    colors = if (n == gridColumns) {
                        ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = BrandBlack)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text("$n") }
            }
        }
    }
}

/** Startup prompt offering the newer GitHub release: focusable card over the current screen. */
@Composable
fun TvUpdateDialog(info: UpdateInfo, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A real Dialog traps D-pad focus and routes Back to onDismissRequest, unlike a plain overlay.
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(BackgroundLevel1).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Mise à jour disponible", style = MaterialTheme.typography.headlineSmall)
            Text(
                "La version ${info.version} est disponible. Voulez-vous la télécharger ?",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDownload, modifier = Modifier.focusRequester(focus)) { Text("Télécharger") }
                Button(onClick = onDismiss) { Text("Plus tard") }
            }
        }
    }
}

/** A show's detail page on TV: poster beside the info (year, genre, cast), synopsis, and Regarder. */
@Composable
fun TvProgramDetailScreen(
    detail: ProgramDetail,
    busy: Boolean,
    error: String?,
    info: String?,
    onWatch: () -> Unit,
    onRecord: () -> Unit,
    onWatchRecording: (String) -> Unit,
    onEpisode: (ContentCard) -> Unit,
    isRecording: Boolean = false,
) {
    val watchFocus = remember { FocusRequester() }
    val showWatch = !(detail.isSeries && !isRecording) && detail.upcomingMessage == null
    // A page with no playable element at all (a series with no catch-up episodes and no recordings)
    // would otherwise leave the D-pad nothing to focus; make the column itself focusable as a fallback.
    val nothingFocusable = !showWatch && detail.recordAssetId == null &&
        detail.episodes.isEmpty() && detail.recordings.isEmpty()
    // Re-key on the shown detail so focus is re-established when navigating detail -> detail, not only
    // for the first detail page.
    LaunchedEffect(detail.vodId, detail.channelId, detail.title) { runCatching { watchFocus.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(OVERSCAN)) {
        if (detail.posterUrl != null) {
            AsyncImage(
                model = detail.posterUrl,
                contentDescription = detail.title,
                modifier = Modifier.width(300.dp).height(440.dp).clip(RoundedCornerShape(12.dp)).background(BackgroundLevel1),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(32.dp))
        } else if (detail.channelLogoUrl != null) {
            // No programme poster (e.g. a FAST channel): show the channel logo centred instead of nothing.
            Box(
                Modifier.width(300.dp).height(440.dp).clip(RoundedCornerShape(12.dp)).background(BackgroundLevel1),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = detail.channelLogoUrl,
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxSize().padding(40.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(32.dp))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .then(if (nothingFocusable) Modifier.focusRequester(watchFocus).focusable() else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(detail.title ?: "", style = MaterialTheme.typography.displaySmall)
            val sub = listOfNotNull(detail.subtitle, detail.genre).joinToString(" • ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.titleMedium, color = Color.White)
            if (detail.tags.isNotEmpty()) {
                Text(detail.tags.joinToString("   "), style = MaterialTheme.typography.titleSmall, color = BrandYellow)
            }
            // An upcoming (not-yet-aired) programme is not playable: show the backend's reason instead of
            // a "Regarder" that 5xx's.
            detail.upcomingMessage?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = BrandYellow)
            }
            // A multi-episode series has no directly playable asset (its id is not a VOD), so it has
            // no "Regarder"; the user picks an episode. A recorded series keeps it (plays the recording).
            if (showWatch || detail.recordAssetId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (showWatch) {
                        Button(onClick = onWatch, enabled = !busy, modifier = Modifier.focusRequester(watchFocus)) {
                            Text(if (detail.isLive) "Regarder en direct" else "Regarder")
                        }
                    }
                    if (detail.recordAssetId != null) {
                        // When there is no Regarder and no recording/episode to take initial focus,
                        // the record button is the only focusable widget, so hand it watchFocus.
                        val recordButtonFocus = !showWatch &&
                            detail.recordings.isEmpty() && detail.episodes.isEmpty()
                        Button(
                            onClick = onRecord, enabled = !busy,
                            modifier = if (recordButtonFocus) Modifier.focusRequester(watchFocus) else Modifier,
                        ) { Text("Enregistrer") }
                    }
                }
            }
            if (busy) CircularProgressIndicator()
            if (info != null) Text(info, color = BrandYellow)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            detail.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White) }
            detail.credits?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            listOfNotNull(
                detail.year?.let { "Année de sortie : $it" },
                detail.classification?.let { "Classification : $it" },
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White) }
            // Your own recordings first (what you came for); the available episodes follow.
            if (detail.recordings.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Vos enregistrements", style = MaterialTheme.typography.titleMedium, color = Color.White)
                // With no "Regarder" button the first recording takes initial focus, so the page is not
                // a D-pad dead-end.
                val recordingsOwnFocus = !showWatch
                detail.recordings.forEachIndexed { i, recording ->
                    TvRecordingRow(
                        recording,
                        enabled = !busy,
                        onWatch = { onWatchRecording(recording.assetId) },
                        focus = watchFocus.takeIf { recordingsOwnFocus && i == 0 },
                    )
                }
            }
            if (detail.episodes.isNotEmpty()) {
                // With no "Regarder" and no recordings above, hand initial focus to the first episode.
                TvEpisodesSection(
                    detail.episodes, busy, onEpisode,
                    firstFocus = if (showWatch || detail.recordings.isNotEmpty()) null else watchFocus,
                )
            }
            // A channel's catalogue carousels (replays, most-viewed, live & upcoming) below the header.
            // No "Tout voir" (it would navigate out of the open detail); each card opens via onEpisode.
            detail.sections.forEach { rail ->
                Spacer(Modifier.height(20.dp))
                TvRail(rail, onCardClick = onEpisode, onSeeAll = {}, showSeeAll = false)
            }
        }
    }
}

/** The "Épisodes disponibles" section on TV: a season selector (when several) over the episode list. */
@Composable
private fun TvEpisodesSection(
    episodes: List<ContentCard>,
    busy: Boolean,
    onEpisode: (ContentCard) -> Unit,
    firstFocus: FocusRequester? = null,
) {
    val seasons = remember(episodes) { episodes.groupBySeason() }
    var selected by remember(episodes) { mutableStateOf(0) }
    val idx = selected.coerceIn(0, seasons.size - 1)
    Spacer(Modifier.height(8.dp))
    Text("Épisodes disponibles", style = MaterialTheme.typography.titleMedium, color = Color.White)
    if (seasons.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEachIndexed { i, (label, _) ->
                TextButton(onClick = { selected = i }) {
                    Text(label, color = if (i == idx) BrandYellow else Color.White)
                }
            }
        }
    }
    seasons[idx].second.forEachIndexed { i, ep ->
        TvEpisodeRow(ep, enabled = !busy, onClick = { onEpisode(ep) }, focus = firstFocus.takeIf { i == 0 })
    }
}

/** One catch-up episode on the TV detail: a thumbnail and label, focusable and clickable. */
@Composable
private fun TvEpisodeRow(card: ContentCard, enabled: Boolean, onClick: () -> Unit, focus: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    if (focus != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) BackgroundLevel2 else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.title,
            modifier = Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(6.dp)).background(BackgroundLevel1),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Text(card.title ?: "Épisode", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One DVR recording on the TV detail: its subtitle/channel plus a "Regarder l'enregistrement" action. */
@Composable
private fun TvRecordingRow(recording: Recording, enabled: Boolean, onWatch: () -> Unit, focus: FocusRequester? = null) {
    if (focus != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val label = listOfNotNull(recording.subtitle, recording.channelName).joinToString(" • ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.ifBlank { recording.title ?: "Enregistrement" },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = onWatch,
            enabled = enabled,
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        ) { Text("Regarder l'enregistrement") }
    }
}

@Composable
private fun TvRail(
    rail: ContentRail,
    onCardClick: (ContentCard) -> Unit,
    onSeeAll: (ContentRail) -> Unit,
    showSeeAll: Boolean = true,
) {
    Column {
        rail.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (showSeeAll && rail.expandable()) {
                var focused by remember { mutableStateOf(false) }
                Text(
                    "$title   ›  Tout voir",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) BrandYellow else Color.White,
                    modifier = Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { onSeeAll(rail) }
                        .padding(bottom = 6.dp),
                )
            } else {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Index in the key: a card id can repeat within a rail, and a duplicate lazy key crashes.
            itemsIndexed(rail.cards, key = { i, c -> "${c.id}#$i" }) { _, card -> TvCard(card, onCardClick) }
        }
    }
}

/** A "see all" / Direct page on TV as a D-pad grid (5 per row), keeping section headers. */
@Composable
private fun TvGridContent(rails: List<ContentRail>, onCardClick: (ContentCard) -> Unit, focus: FocusRequester, columns: Int) {
    val multiSection = rails.size > 1
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().focusGroup().focusRequester(focus),
        contentPadding = PaddingValues(bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        rails.forEach { rail ->
            val header = rail.title?.takeIf { multiSection && it.isNotBlank() }
            if (header != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-${rail.id}") {
                    Text(header, style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
            items(rail.cards, key = { "${rail.id}-${it.id}" }) { card -> TvCard(card, onCardClick) }
        }
    }
}

@Composable
private fun TvCard(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    val isChannel = card.square
    // A genre/category tile carries no artwork; draw the brand icon instead of a blank box.
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    var focused by remember { mutableStateOf(false) }
    val w = if (isChannel) 112.dp else 208.dp
    val h = if (isChannel) 112.dp else 117.dp
    Column(Modifier.width(w)) {
        Box(
            Modifier.size(w, h)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(10.dp))
                .background(BackgroundLevel1)
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                .clickable { onCardClick(card) },
            contentAlignment = Alignment.Center,
        ) {
            if (categoryIcon != null) {
                Icon(painterResource(categoryIcon), contentDescription = card.title, tint = BrandYellow, modifier = Modifier.size(48.dp))
            } else {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize().padding(if (isChannel) 14.dp else 0.dp),
                    contentScale = if (isChannel) ContentScale.Fit else ContentScale.Crop,
                )
            }
            if (card.isLocked) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp)) {
                    Text("€", color = BrandBlack, style = MaterialTheme.typography.labelSmall)
                }
            }
            card.badge?.let {
                Box(Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp)) {
                    Text(it, color = BrandBlack, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        card.title?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(w).padding(top = 4.dp))
        }
        card.subtitle?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BrandYellow, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(w))
        }
    }
}
