// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.allard.etincelle.core.designsystem.CardTagPill
import it.allard.etincelle.core.designsystem.categoryIconRes
import it.allard.etincelle.core.designsystem.R as DesignR
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel2
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.Avatar
import it.allard.etincelle.core.model.CardShape
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.expandable
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.groupBySeason
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.ui.PROFILE_NAME_MAX
import it.allard.etincelle.core.ui.ProfileEditor
import it.allard.etincelle.core.ui.ProfileScreen
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
            TvButton(onClick = onUseEmail, modifier = Modifier.focusRequester(emailBtn)) {
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
                    TvButton(onClick = onStart) { Text("Réessayer") }
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
    val passwordField = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(OVERSCAN),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("etincelle", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(24.dp))
        // While the on-screen keyboard is up it takes the D-pad, so its action key is the way on:
        // from the email to the password, then from the password to signing in.
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { runCatching { passwordField.requestFocus() } }),
            modifier = Modifier.width(480.dp).focusRequester(first).dpadLeavesField(focusManager),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Mot de passe") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy) onLogin(email, password) }),
            modifier = Modifier.width(480.dp).focusRequester(passwordField).dpadLeavesField(focusManager),
        )
        Spacer(Modifier.height(20.dp))
        TvButton(onClick = { onLogin(email, password) }, enabled = !busy) { Text("Se connecter") }
        Spacer(Modifier.height(12.dp))
        TvButton(onClick = onUseCode, highlighted = false) { Text("Utiliser un code") }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/** Two-factor login with the remote: asks for the code the backend sent, with the code page's texts. */
@Composable
fun TvCodeScreen(
    challenge: MfaChallenge,
    busy: Boolean,
    error: String?,
    info: String?,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(challenge.verifyUrl) { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(OVERSCAN), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(challenge.title, style = MaterialTheme.typography.displaySmall)
        challenge.message?.let { Text(it, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(24.dp))
        // While the on-screen keyboard is up it takes the D-pad, so its action key sends the code.
        OutlinedTextField(
            code, { v -> code = challenge.codeLength?.let { v.take(it) } ?: v }, label = { Text(challenge.inputLabel) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy) onVerify(code) }),
            modifier = Modifier.width(480.dp).focusRequester(field).dpadLeavesField(focusManager),
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = { onVerify(code) }, enabled = !busy && code.isNotBlank()) { Text(challenge.submitLabel) }
            challenge.resendLabel?.let { TvButton(onClick = onResend, enabled = !busy, highlighted = false) { Text(it) } }
            TvButton(onClick = onCancel, highlighted = false) { Text("Annuler") }
        }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (info != null && error == null) Text(info, color = Color.White)
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
    onFavoriteOrder: () -> Unit = {},
) {
    val tabFocus = remember { Tab.entries.associateWith { FocusRequester() } }
    val railsFocus = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    // This screen is rebuilt whenever the page changes (another tab, or back from a detail), so on
    // entry put the focus back on the card that opened the detail, else on the current tab. The
    // card is saved with the page's state, which outlives the detail.
    val returnKey = rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember(returnKey) { ReturnFocus(returnKey) }
    // Without a card to come back to, the search page starts on its field.
    LaunchedEffect(Unit) {
        if (!returnFocus.restore()) {
            runCatching { (if (state.tab == Tab.SEARCH) searchField else tabFocus.getValue(state.tab)).requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = OVERSCAN, end = OVERSCAN, top = 24.dp)) {
        val tabs = Tab.entries
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                // Coming up from the page lands on the current tab, not on the one nearest on screen.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection == FocusDirection.Up) runCatching { tabFocus.getValue(state.tab).requestFocus() }
                    }
                }
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        // The search page has its field on top of the results; elsewhere go to the rails.
                        runCatching { (if (state.tab == Tab.SEARCH) searchField else railsFocus).requestFocus() }.isSuccess
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tabs) { tab ->
                TvTab(
                    label = tab.label,
                    // Stays marked on the tab's sub-pages, so the bar always says where you are.
                    selected = state.tab == tab,
                    onClick = { onSelectTab(tab) },
                    modifier = Modifier.focusRequester(tabFocus.getValue(tab)),
                )
            }
            item {
                TvTab(label = "Paramètres", selected = false, onClick = onSettings)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.tab == Tab.SEARCH) {
            TvSearchContent(state, onSearch, onCardClick, onSeeAll, returnFocus, searchField, railsFocus, gridColumns)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.current?.title ?: state.tab.label, style = MaterialTheme.typography.headlineMedium)
                if (state.tab == Tab.CHANNELS && !state.canGoBack) {
                    Spacer(Modifier.width(24.dp))
                    TvButton(onClick = onFavoriteOrder, highlighted = false) { Text("Réorganiser les favoris") }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Appui long sur OK : ajouter une chaîne aux favoris ou l'en retirer",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                val page = state.current
                if (page?.isGrid == true) {
                    TvGridContent(page.rails, onCardClick, railsFocus, gridColumns, returnFocus)
                } else {
                    LazyColumn(
                        modifier = Modifier.focusGroup().focusRequester(railsFocus),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(page?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick, onSeeAll, returnFocus = returnFocus) }
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
    returnFocus: ReturnFocus,
    field: FocusRequester,
    gridFocus: FocusRequester,
    gridColumns: Int,
) {
    // A TextFieldValue so the remote can tell when the cursor is at the end of the text (see below).
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // A text field opens the keyboard as soon as it gets focus, which catches a remote that is only
    // passing through. It stays read-only, with no keyboard, until OK is pressed on it.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) keyboard?.show() }
    // "Tout voir" and Back swap the rows and the grid under the focused item, which would hand the
    // focus to the first tab: carry it into the grid, or back to the item that opened it.
    val isGrid = state.current?.isGrid == true
    var shownGrid by remember { mutableStateOf(isGrid) }
    LaunchedEffect(isGrid) {
        if (isGrid == shownGrid) return@LaunchedEffect
        shownGrid = isGrid
        // Let the new page lay out its items first.
        withFrameNanos { }
        if (isGrid) {
            runCatching { gridFocus.requestFocus() }
        } else if (!returnFocus.restore()) {
            runCatching { field.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher") },
                singleLine = true,
                readOnly = !editing,
                // The on-screen keyboard takes the D-pad while it is up, so its action key searches.
                // It then closes, since it would otherwise cover the results.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.text.isNotBlank()) {
                        editing = false
                        keyboard?.hide()
                        onSubmit(query.text)
                    }
                }),
                // Right leaves the field for the Rechercher button once the cursor is at the end.
                modifier = Modifier.weight(1f).focusRequester(field)
                    .onFocusChanged { if (!it.isFocused) editing = false }
                    .onPreviewKeyEvent { event ->
                        val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (event.type != KeyEventType.KeyDown || !ok) return@onPreviewKeyEvent false
                        // Once editing, OK brings back a keyboard that Back has closed.
                        if (editing) keyboard?.show() else editing = true
                        true
                    }
                    .dpadLeavesField(focusManager) {
                        query.selection.collapsed && query.selection.end == query.text.length
                    },
            )
            TvButton(onClick = { onSubmit(query.text) }, enabled = query.text.isNotBlank()) { Text("Rechercher") }
        }
        Spacer(Modifier.height(16.dp))
        val page = state.current
        // A rail opened with "Tout voir" lists all its results in a grid, under its own title.
        if (page?.isGrid == true) {
            page.title?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
            Spacer(Modifier.height(12.dp))
        }
        Box(Modifier.fillMaxSize()) {
            if (page?.isGrid == true) {
                TvGridContent(page.rails, onCardClick, gridFocus, gridColumns, returnFocus)
            } else if (page != null && page.rails.isEmpty() && !state.busy && state.error == null) {
                Text("Aucun résultat trouvé", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 32.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(page?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick, onSeeAll, returnFocus = returnFocus) }
                }
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
        Text(
            label,
            color = if (focused) BrandBlack else if (selected) BrandYellow else Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** Settings page on TV. For now its only entry is Déconnexion, with an inline confirmation. */
@Composable
fun TvSettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    gridColumns: Int,
    onGridColumns: (Int) -> Unit,
    onProfiles: () -> Unit = {},
) {
    var confirm by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    LaunchedEffect(confirm) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(OVERSCAN), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Paramètres", style = MaterialTheme.typography.displaySmall)
        if (!confirm) {
            TvButton(onClick = { confirm = true }, modifier = Modifier.focusRequester(focus)) { Text("Déconnexion") }
        } else {
            Text("Voulez-vous vraiment vous déconnecter ?", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(onClick = { confirm = false }, modifier = Modifier.focusRequester(focus)) { Text("Annuler") }
                TvButton(onClick = onLogout) { Text("Se déconnecter") }
            }
        }
        TvButton(onClick = onProfiles, highlighted = false) { Text("Profils") }
        Text("Vignettes par ligne : $gridColumns", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in TvPrefs.MIN_GRID_COLUMNS..TvPrefs.MAX_GRID_COLUMNS) {
                TvButton(
                    onClick = { TvPrefs.setGridColumns(context, n); onGridColumns(n) },
                    highlighted = n == gridColumns,
                ) { Text("$n") }
            }
        }
    }
}

/**
 * The profiles with the remote: "Qui regarde ?" when the app asks, or the list opened from the settings.
 * OK on a profile watches as it; with Modifier les profils on, it opens the profile's editor instead.
 */
@Composable
fun TvProfilesScreen(
    screen: ProfileScreen,
    busy: Boolean,
    error: String?,
    onPick: (Profile) -> Unit,
    onEdit: (Profile?) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val list = screen.list
    Column(Modifier.fillMaxSize().padding(OVERSCAN), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(if (screen.asking) "Qui regarde ?" else "Profils", style = MaterialTheme.typography.displaySmall)
        error?.let { Text(it, color = BrandYellow) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(list.profiles, key = { _, p -> p.id }) { i, profile ->
                TvProfileTile(
                    profile.name, profile.avatarUrl, profile.id == screen.currentId, if (editing) "✎" else null,
                    onClick = { if (!busy) (if (editing) onEdit(profile) else onPick(profile)) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            if (list.profiles.size < list.maxAllowed) {
                item(key = "add") { TvProfileTile("Ajouter un profil", null, false, "+", onClick = { if (!busy) onEdit(null) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = { editing = !editing }, highlighted = false) {
                Text(if (editing) "Terminé" else "Modifier les profils")
            }
            TvButton(onClick = onBack, highlighted = false) { Text("Retour") }
        }
    }
}

@Composable
private fun TvProfileTile(name: String, avatarUrl: String?, current: Boolean, badge: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier.size(128.dp)
                .onFocusChanged { focused = it.isFocused }
                .clip(CircleShape)
                .background(BackgroundLevel2)
                .border(
                    if (focused || current) 4.dp else 0.dp,
                    if (focused) Color.White else if (current) BrandYellow else Color.Transparent,
                    CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            badge?.let {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (avatarUrl != null) 0.5f else 0f)), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.displaySmall, color = Color.White)
                }
            }
        }
        Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Creates a profile or edits one with the remote: its name, its avatar, and deleting it. */
@Composable
fun TvProfileEditorScreen(
    editor: ProfileEditor,
    avatars: List<Avatar>,
    canDelete: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (String, Int?) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val profile = editor.profile
    var name by remember(editor) { mutableStateOf(profile?.name.orEmpty()) }
    var avatarId by remember(editor) { mutableStateOf(profile?.avatarId ?: avatars.firstOrNull()?.id) }
    var confirm by remember(editor) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(confirm) {
        withFrameNanos { }
        runCatching { saveFocus.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(OVERSCAN), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (profile == null) "Ajouter un profil" else "Modifier le profil", style = MaterialTheme.typography.displaySmall)
        if (confirm) {
            Text("Supprimer le profil ?", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(
                "Tous les enregistrements et les données de ce profil seront définitivement supprimés.",
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(onClick = { confirm = false }, modifier = Modifier.focusRequester(saveFocus)) { Text("Annuler") }
                TvButton(onClick = { confirm = false; onDelete() }, highlighted = false, enabled = !busy) { Text("Supprimer") }
            }
            return@Column
        }
        OutlinedTextField(
            name, { name = it.take(PROFILE_NAME_MAX) }, label = { Text("Nom du profil") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { runCatching { saveFocus.requestFocus() } }),
            modifier = Modifier.width(480.dp).dpadLeavesField(focusManager),
        )
        error?.let { Text(it, color = BrandYellow) }
        Text("Avatar", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(avatars, key = { it.id }) { avatar ->
                var focused by remember { mutableStateOf(false) }
                val selected = avatar.id == avatarId
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    modifier = Modifier.size(88.dp)
                        // Down goes on to Enregistrer, not to the button nearest on screen.
                        .focusProperties { down = saveFocus }
                        .onFocusChanged { focused = it.isFocused }
                        .clip(CircleShape)
                        .border(
                            if (focused || selected) 4.dp else 0.dp,
                            if (focused) Color.White else if (selected) BrandYellow else Color.Transparent,
                            CircleShape,
                        )
                        .clickable { avatarId = avatar.id },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = { onSave(name, avatarId) }, enabled = !busy && name.isNotBlank(), modifier = Modifier.focusRequester(saveFocus)) {
                Text("Enregistrer")
            }
            TvButton(onClick = onBack, highlighted = false) { Text("Annuler") }
            if (profile != null && canDelete) {
                TvButton(onClick = { confirm = true }, highlighted = false, enabled = !busy) { Text("Supprimer le profil") }
            }
        }
    }
}

/** Toggles a channel card's favourite; provided by the activity, absent where cards cannot toggle. */
val LocalFavoriteToggle = staticCompositionLocalOf<((ContentCard) -> Unit)?> { null }

/**
 * Reorders the favourite channels with the remote: OK picks a channel up, Up and Down move it, OK sets
 * it down. Enregistrer saves the new order.
 */
@Composable
fun TvFavoriteOrderScreen(
    order: FavoriteOrder,
    busy: Boolean,
    error: String?,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    var picked by remember { mutableStateOf<String?>(null) }
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    // Start on the first channel, the one most likely to move; the screen it replaces may still hold
    // the focus during the first frame, hence the wait.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { (if (order.channels.isEmpty()) cancelFocus else firstFocus).requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(start = OVERSCAN, end = OVERSCAN, top = 32.dp)) {
        Text(order.title, style = MaterialTheme.typography.headlineMedium)
        order.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(onClick = onSave, enabled = !busy && order.channels.isNotEmpty(), modifier = Modifier.focusRequester(saveFocus)) {
                Text(order.save.label)
            }
            TvButton(onClick = onBack, highlighted = false, modifier = Modifier.focusRequester(cancelFocus)) { Text("Annuler") }
        }
        error?.let { Text(it, color = BrandYellow, modifier = Modifier.padding(top = 12.dp)) }
        if (order.channels.isEmpty()) {
            Text(
                "Aucune chaîne favorite. Appui long sur OK sur une chaîne pour l'ajouter.",
                color = Color.White,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.focusGroup(),
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(order.channels, key = { _, c -> c.id }) { i, channel ->
                var focused by remember { mutableStateOf(false) }
                val moving = picked == channel.id
                Row(
                    Modifier.width(560.dp)
                        // Up from the first channel goes to Enregistrer, not to the button nearest on screen.
                        .then(if (i == 0) Modifier.focusRequester(firstFocus).focusProperties { up = saveFocus } else Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (moving) BrandYellow else BackgroundLevel1)
                        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                        .onPreviewKeyEvent { event ->
                            if (!moving || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> { if (i > 0) onMove(i, i - 1); true }
                                Key.DirectionDown -> { if (i < order.channels.size - 1) onMove(i, i + 1); true }
                                else -> false
                            }
                        }
                        .clickable(enabled = !busy) { picked = if (moving) null else channel.id }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = if (moving) BrandBlack else Color.White, modifier = Modifier.width(36.dp))
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 72.dp, height = 40.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(
                        channel.name,
                        color = if (moving) BrandBlack else Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Prompt offering the newer GitHub release: focusable card over the current screen. Installer
 * downloads it, showing the progress, and hands it to the system installer; a download in progress is
 * only stopped by Plus tard.
 */
@Composable
fun TvUpdateDialog(info: UpdateInfo, progress: Int?, error: String?, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // A real Dialog traps D-pad focus and routes Back to onDismissRequest, unlike a plain overlay.
    Dialog(onDismissRequest = { if (progress == null) onDismiss() }) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(BackgroundLevel1).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Mise à jour disponible", style = MaterialTheme.typography.headlineSmall)
            Text(
                "La version ${info.version} est disponible. Voulez-vous l'installer ?",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            if (progress != null) Text("Téléchargement… $progress %", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            error?.let { Text(it, color = BrandYellow, style = MaterialTheme.typography.bodyLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(onClick = onInstall, modifier = Modifier.focusRequester(focus)) { Text("Installer") }
                TvButton(onClick = onDismiss) { Text("Plus tard") }
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
    onStartOver: () -> Unit,
    onRecord: (RecordAction) -> Unit,
    onWatchRecording: (String) -> Unit,
    onEpisode: (ContentCard) -> Unit,
    isRecording: Boolean = false,
) {
    val watchFocus = remember { FocusRequester() }
    val showWatch = !(detail.isSeries && !isRecording) && detail.upcomingMessage == null
    // A page with no playable element at all (a series with no catch-up episodes and no recordings)
    // would otherwise leave the D-pad nothing to focus; make the column itself focusable as a fallback.
    val nothingFocusable = !showWatch && detail.recordActions.isEmpty() &&
        detail.episodes.isEmpty() && detail.recordings.isEmpty()
    // Re-key on the shown detail so focus is re-established when navigating detail -> detail, not only
    // for the first detail page.
    LaunchedEffect(detail.vodId, detail.channelId, detail.title) { runCatching { watchFocus.requestFocus() } }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        // The programme's artwork (16:9, like the screen) is the page's background, as in the official
        // app: faded into the page on the left, under the text, and at the bottom. It fades out as the
        // page scrolls down to the episodes and rails.
        detail.posterUrl?.let { url ->
            val page = MaterialTheme.colorScheme.surface
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - (scroll.value / (size.height / 2)).coerceIn(0f, 1f) }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(0f to page, 0.4f to page.copy(alpha = 0.95f), 0.75f to Color.Transparent))
                        drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to page))
                    },
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(OVERSCAN)
                .then(if (nothingFocusable) Modifier.focusRequester(watchFocus).focusable() else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The text stays on the left, over the faded side of the artwork.
            Column(Modifier.fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // No artwork (e.g. a FAST channel): the channel's logo heads the page instead.
                if (detail.posterUrl == null) {
                    detail.channelLogoUrl?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            modifier = Modifier.height(56.dp).width(160.dp),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                        )
                    }
                }
                detail.timeLabel?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f))
                }
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
                if (showWatch || detail.recordActions.isNotEmpty()) {
                    // Wrap so every action stays reachable when a live channel shows several buttons
                    // (Regarder en direct + depuis le début + Enregistrer l'épisode + la série).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (showWatch) {
                            TvButton(onClick = onWatch, enabled = !busy, modifier = Modifier.focusRequester(watchFocus)) {
                                Text(if (detail.isLive) "Regarder en direct" else "Regarder")
                            }
                        }
                        if (showWatch && detail.isLive) {
                            TvButton(onClick = onStartOver, enabled = !busy) { Text("Regarder depuis le début") }
                        }
                        // Each record option (the episode and/or the whole series) is its own D-pad button,
                        // using the backend's own label. When there is no Regarder and no recording/episode to
                        // take initial focus, the first record button is the only focusable widget, so it gets
                        // watchFocus.
                        val recordTakesFocus = !showWatch && detail.recordings.isEmpty() && detail.episodes.isEmpty()
                        detail.recordActions.forEachIndexed { i, action ->
                            TvButton(
                                onClick = { onRecord(action) }, enabled = !busy,
                                modifier = if (recordTakesFocus && i == 0) Modifier.focusRequester(watchFocus) else Modifier,
                            ) { Text(action.label) }
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
            }
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
        // Scrolls so that every season stays reachable; the focused one is brought into view. The
        // scroll clips at its edges, so the padding leaves room for a focused button to grow and the
        // offset keeps the first one in line with the heading.
        Row(
            Modifier.offset(x = (-6).dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            seasons.forEachIndexed { i, (label, _) ->
                TvButton(onClick = { selected = i }, highlighted = i == idx) { Text(label) }
            }
        }
    }
    seasons[idx].second.forEachIndexed { i, ep ->
        TvEpisodeRow(ep, enabled = !busy, onClick = { onEpisode(ep) }, focus = firstFocus.takeIf { i == 0 })
    }
}

/** One catch-up episode on the TV detail: a thumbnail and label, focusable and clickable. */
@Composable
private fun TvEpisodeRow(
    card: ContentCard,
    enabled: Boolean,
    onClick: () -> Unit,
    focus: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    if (focus != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) BackgroundLevel2 else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(6.dp)).background(BackgroundLevel1)) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            card.tag?.let { CardTagPill(it) }
            card.channelLogoUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).width(36.dp).height(14.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(card.title ?: "Épisode", color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // When it aired, as the official app lists it.
            card.caption?.let {
                Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

/** One DVR recording on the TV detail: its subtitle/channel plus a "Regarder l'enregistrement" action. */
@Composable
private fun TvRecordingRow(recording: Recording, enabled: Boolean, onWatch: () -> Unit, focus: FocusRequester? = null) {
    if (focus != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The episode and the day it was recorded ("S16 E6 Agde • Enregistré: 30 sept.").
    val label = listOfNotNull(recording.title, recording.subtitle).joinToString(" • ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.ifBlank { "Enregistrement" },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TvButton(
            onClick = onWatch,
            enabled = enabled,
            highlighted = false,
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
    returnFocus: ReturnFocus? = null,
) {
    Column {
        rail.title?.takeIf { it.isNotBlank() }?.let { title ->
            if (showSeeAll && rail.expandable()) {
                var focused by remember { mutableStateOf(false) }
                val key = "seeall|${rail.id}"
                Text(
                    "$title   ›  Tout voir",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (focused) BrandYellow else Color.White,
                    modifier = Modifier
                        .then(returnFocus?.target(key) ?: Modifier)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { returnFocus?.save(key); onSeeAll(rail) }
                        .padding(bottom = 6.dp),
                )
            } else {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Index in the key: a card id can repeat within a rail, and a duplicate lazy key crashes.
            itemsIndexed(rail.cards, key = { i, c -> "${c.id}#$i" }) { i, card ->
                val key = "${rail.id}|$i|${card.id}"
                TvCard(card, { returnFocus?.save(key); onCardClick(it) }, returnFocus?.target(key) ?: Modifier)
            }
        }
    }
}

/** A "see all" / Direct page on TV as a D-pad grid (5 per row), keeping section headers. */
@Composable
private fun TvGridContent(
    rails: List<ContentRail>,
    onCardClick: (ContentCard) -> Unit,
    focus: FocusRequester,
    columns: Int,
    returnFocus: ReturnFocus,
) {
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
            // Index in the key too: a card id can repeat within a rail, and a duplicate key crashes the grid.
            // Rows of a list (a show's recorded episodes) take the whole width, as in the official app.
            rail.cards.forEachIndexed { i, card ->
                val key = "${rail.id}|${card.id}"
                if (card.shape == CardShape.ROW) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "${rail.id}-${card.id}#$i") {
                        TvEpisodeRow(card, enabled = true, onClick = { returnFocus.save(key); onCardClick(card) }, modifier = returnFocus.target(key))
                    }
                } else {
                    item(key = "${rail.id}-${card.id}#$i") { TvCard(card, { returnFocus.save(key); onCardClick(it) }, returnFocus.target(key)) }
                }
            }
        }
    }
}

@Composable
private fun TvCard(card: ContentCard, onCardClick: (ContentCard) -> Unit, focusModifier: Modifier = Modifier) {
    val isChannel = card.shape == CardShape.SQUARE
    // A genre/category tile carries no artwork; draw the brand icon instead of a blank box.
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    var focused by remember { mutableStateOf(false) }
    val toggleFavorite = LocalFavoriteToggle.current
    // Artwork keeps its own shape: landscape cards are wider, posters as tall as the official app's.
    val w = when (card.shape) {
        CardShape.SQUARE -> 112.dp
        CardShape.WIDE, CardShape.ROW -> 208.dp
        CardShape.POSTER, CardShape.POSTER_THIN -> 120.dp
    }
    val h = w / card.shape.ratio
    Column(Modifier.width(w)) {
        Box(
            Modifier.size(w, h)
                .then(focusModifier)
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(10.dp))
                .background(BackgroundLevel1)
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                // A long press of OK on a channel adds it to the favourites or removes it.
                .combinedClickable(
                    onLongClick = toggleFavorite?.takeIf { card.favorite != null }?.let { { it(card) } },
                    onClick = { onCardClick(card) },
                ),
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
            if (card.isFavorite) {
                Icon(
                    painterResource(DesignR.drawable.ic_favoris_filled),
                    contentDescription = "Favori",
                    tint = BrandYellow,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp),
                )
            }
            card.tag?.let { CardTagPill(it) }
            // The logo of the channel offering the card, as the official app shows it.
            card.channelLogoUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 2.dp).width(48.dp).height(18.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        card.title?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(w).padding(top = 4.dp))
        }
        card.subtitle?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BrandYellow, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(w))
        }
        card.caption?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(w))
        }
    }
}

/**
 * A text field keeps the arrow keys to move its cursor, so once the on-screen keyboard is closed a
 * remote user is stuck in it. Let Up and Down leave the field like any other control, and Right too
 * when [cursorAtEnd] says there is nothing left to move over.
 */
private fun Modifier.dpadLeavesField(focusManager: FocusManager, cursorAtEnd: () -> Boolean = { false }): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionRight -> cursorAtEnd() && focusManager.moveFocus(FocusDirection.Right)
            else -> false
        }
    }

/**
 * Brings the focus back to the item (a card, or a rail's "Tout voir") that opened a detail or a
 * sub-page. [saved] holds that item's key; it must live in the page's saveable state so it survives
 * the detail covering the page.
 */
@Stable
private class ReturnFocus(private val saved: MutableState<String?>) {
    private val requester = FocusRequester()

    fun save(key: String) {
        saved.value = key
    }

    /** The modifier to put on the item with [key]: the restore target when it is the saved one. */
    fun target(key: String): Modifier = if (saved.value == key) Modifier.focusRequester(requester) else Modifier

    /** Focuses the saved item if it is on screen, and forgets it either way. */
    fun restore(): Boolean {
        if (saved.value == null) return false
        // A requester whose item is gone does not throw, it only reports that nothing took the focus.
        val ok = runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        saved.value = null
        return ok
    }
}
