// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import it.allard.etincelle.core.designsystem.RaisedButton
import it.allard.etincelle.core.designsystem.CardTagPill
import it.allard.etincelle.core.cast.CastReceiver
import it.allard.etincelle.core.designsystem.categoryIconRes
import it.allard.etincelle.core.designsystem.R as DesignR
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.Avatar
import it.allard.etincelle.core.model.CardShape
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.expandable
import it.allard.etincelle.core.model.groupBySeason
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.Recording
import it.allard.etincelle.core.player.SkipAds
import it.allard.etincelle.core.ui.PROFILE_NAME_MAX
import it.allard.etincelle.core.ui.ProfileEditor
import it.allard.etincelle.core.ui.ProfileScreen
import it.allard.etincelle.core.ui.UpdateInfo
import it.allard.etincelle.core.ui.VoiceSearch
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
            visualTransformation = PasswordVisualTransformation(),
            // Hiding the characters is only on screen; the keyboard has to be told it is a password,
            // or it learns it and offers it back as a suggestion.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        RaisedButton(onClick = { onLogin(email, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Se connecter")
        }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

/** Two-factor login: asks for the code the backend sent, with the code page's own texts. */
@Composable
fun CodeScreen(
    challenge: MfaChallenge,
    busy: Boolean,
    error: String?,
    info: String?,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(challenge.verifyUrl) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(challenge.title, style = MaterialTheme.typography.headlineSmall)
        challenge.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            code,
            { v -> code = challenge.codeLength?.let { v.take(it) } ?: v },
            label = { Text(challenge.inputLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy) onVerify(code) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        RaisedButton(onClick = { onVerify(code) }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(challenge.submitLabel)
        }
        challenge.resendLabel?.let { TextButton(onClick = onResend, enabled = !busy) { Text(it) } }
        TextButton(onClick = onCancel) { Text("Annuler") }
        Spacer(Modifier.height(16.dp))
        if (busy) CircularProgressIndicator()
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (info != null && error == null) Text(info)
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
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h-${rail.id}", contentType = "header") {
                        Text(header, style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Index in the key too: a card id falls back to its label/image, which can repeat
                // within a rail, and a duplicate lazy-list key crashes the list. Rows of a list (a
                // show's recorded episodes) take the whole width, as in the official app.
                rail.cards.forEachIndexed { i, card ->
                    val key = "${rail.id}-${card.id}#$i"
                    if (card.shape == CardShape.ROW) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = key, contentType = "row") { EpisodeRow(card, enabled = true) { onCardClick(card) } }
                    } else {
                        item(key = key, contentType = "card") { GridCard(card, onCardClick) }
                    }
                }
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

/** Toggles a channel card's favourite; provided by the activity, absent where cards cannot toggle. */
val LocalFavoriteToggle = staticCompositionLocalOf<((ContentCard) -> Unit)?> { null }

/** A channel card's favourite star, in its image's top-left corner: filled for a favourite; tapping it
 * adds or removes the channel. */
@Composable
private fun BoxScope.FavoriteStar(card: ContentCard) {
    val action = card.favorite ?: return
    val toggle = LocalFavoriteToggle.current ?: return
    Icon(
        painterResource(if (card.isFavorite) DesignR.drawable.ic_favoris_filled else DesignR.drawable.ic_favoris),
        contentDescription = action.label,
        tint = if (card.isFavorite) BrandYellow else Color.White,
        modifier = Modifier.align(Alignment.TopStart).size(32.dp).clickable { toggle(card) }.padding(6.dp),
    )
}

/** The logo of the channel offering a card, in its image's bottom-right corner. */
@Composable
private fun BoxScope.ChannelLogo(url: String) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp).width(36.dp).height(14.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun GridCard(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    val logo = card.shape == CardShape.SQUARE
    Column(
        modifier = Modifier.fillMaxWidth().clickable { onCardClick(card) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Artwork takes its own shape (posters are portrait); a logo sits on a landscape tile.
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(if (logo) CardShape.WIDE.ratio else card.shape.ratio)
                .clip(RoundedCornerShape(10.dp)).background(BackgroundLevel1),
            contentAlignment = Alignment.Center,
        ) {
            if (categoryIcon != null) {
                Icon(painterResource(categoryIcon), contentDescription = card.title, tint = BrandYellow, modifier = Modifier.size(28.dp))
            } else {
                // A channel logo is fitted whole, as on the rails; cropping it to the 16:9 tile cut
                // the logo off. Programme artwork still fills the tile.
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.title,
                    modifier = Modifier.fillMaxSize().padding(if (logo) 12.dp else 0.dp),
                    contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
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
            FavoriteStar(card)
            card.tag?.let { CardTagPill(it) }
            card.channelLogoUrl?.let { ChannelLogo(it) }
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
        card.caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Prompt offering the newer GitHub release. Installer downloads it, showing the progress, and hands it
 * to the system installer; a download in progress is only stopped by Plus tard.
 */
@Composable
fun UpdateDialog(info: UpdateInfo, progress: Int?, error: String?, onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Mise à jour disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("La version ${info.version} est disponible. Voulez-vous l'installer ?")
                if (progress != null) {
                    Text("Téléchargement… $progress %", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = BrandYellow)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onInstall, enabled = progress == null) { Text("Installer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Plus tard") } },
    )
}

/**
 * The profiles: "Qui regarde ?" when the app asks, or the list opened from the settings. A tile watches
 * as that profile; with Modifier on, it opens the profile's editor instead. Ajouter un profil appears
 * while the account has room for one more.
 */
@Composable
fun ProfilesScreen(
    screen: ProfileScreen,
    busy: Boolean,
    error: String?,
    onPick: (Profile) -> Unit,
    onEdit: (Profile?) -> Unit,
    onEditing: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = screen.editing
    val list = screen.list
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onEditing(!editing) }, enabled = !busy) { Text(if (editing) "Terminé" else "Modifier") }
        }
        Text(
            if (screen.asking) "Qui regarde ?" else "Profils",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(list.profiles, key = { it.id }) { profile ->
                ProfileTile(
                    name = profile.name,
                    avatarUrl = profile.avatarUrl,
                    current = profile.id == screen.currentId,
                    badge = if (editing) "✎" else null,
                    onClick = { if (!busy) (if (editing) onEdit(profile) else onPick(profile)) },
                )
            }
            if (list.profiles.size < list.maxAllowed) {
                item(key = "add") { ProfileTile(name = "Ajouter un profil", avatarUrl = null, current = false, badge = "+", onClick = { if (!busy) onEdit(null) }) }
            }
        }
    }
}

@Composable
private fun ProfileTile(name: String, avatarUrl: String?, current: Boolean, badge: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(BackgroundLevel1)
                .border(if (current) 3.dp else 0.dp, if (current) BrandYellow else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(model = avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            badge?.let {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (avatarUrl != null) 0.5f else 0f)), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                }
            }
        }
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Creates a profile or edits one: its name, its avatar, and (for an existing profile) deleting it. */
@Composable
fun ProfileEditorScreen(
    editor: ProfileEditor,
    avatars: List<Avatar>,
    canDelete: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (String, Int?) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = editor.profile
    var name by remember(editor) { mutableStateOf(profile?.name.orEmpty()) }
    var avatarId by remember(editor) { mutableStateOf(profile?.avatarId ?: avatars.firstOrNull()?.id) }
    var confirm by remember(editor) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSave(name, avatarId) }, enabled = !busy && name.isNotBlank()) { Text("Enregistrer") }
        }
        Text(
            if (profile == null) "Ajouter un profil" else "Modifier le profil",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(PROFILE_NAME_MAX) },
            label = { Text("Nom du profil") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        Text("Avatar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(72.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(avatars, key = { it.id }) { avatar ->
                val selected = avatar.id == avatarId
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    // The grid gives the cell's width; a fixed size would only set the height and
                    // turn the circle into an oval.
                    modifier = Modifier.aspectRatio(1f).clip(CircleShape)
                        .border(if (selected) 3.dp else 0.dp, if (selected) BrandYellow else Color.Transparent, CircleShape)
                        .clickable { avatarId = avatar.id },
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (profile != null && canDelete) {
            TextButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.padding(16.dp)) {
                Text("Supprimer le profil", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Supprimer le profil ?") },
            text = { Text("Tous les enregistrements et les données de ce profil seront définitivement supprimés.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler") } },
        )
    }
}

/** Reorders the favourite channels: ▲ and ▼ move a channel, Enregistrer saves the new order. */
@Composable
fun FavoriteOrderScreen(
    order: FavoriteOrder,
    busy: Boolean,
    error: String?,
    onMove: (Int, Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSave, enabled = !busy && order.channels.isNotEmpty()) { Text(order.save.label) }
        }
        Text(order.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        order.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (order.channels.isEmpty()) {
            Text(
                "Aucune chaîne favorite. Touchez l'étoile d'une chaîne pour l'ajouter.",
                modifier = Modifier.padding(16.dp),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val listState = rememberLazyListState()
        // The list holds on to its first row shown and follows it when it moves, which takes the row
        // just moved up to the top out of sight, or the list along with the one sent down: it is told
        // to stay where it is.
        val move = { from: Int, to: Int ->
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            onMove(from, to)
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(order.channels, key = { _, c -> c.id }) { i, channel ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", modifier = Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(width = 56.dp, height = 32.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(channel.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    TextButton(onClick = { move(i, i - 1) }, enabled = !busy && i > 0) { Text("▲") }
                    TextButton(onClick = { move(i, i + 1) }, enabled = !busy && i < order.channels.size - 1) { Text("▼") }
                }
            }
        }
    }
}

// The privacy policy, kept with the source.
private const val PRIVACY_URL = "https://github.com/renaudallard/etincelle/blob/master/PRIVACY.md"

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
    // False in a build that Google Play updates: only the version is shown, with nothing to check.
    selfUpdate: Boolean = true,
    // Null where Android cannot install an update without asking: the setting is not shown.
    autoUpdate: Boolean? = null,
    onAutoUpdate: (Boolean) -> Unit = {},
    onConnectTv: (String) -> Unit = {},
    onClearTvConnect: () -> Unit = {},
    connectInfo: String? = null,
    connectError: String? = null,
    // Something asked from this screen is under way: the TV's code being checked, the profiles loading.
    busy: Boolean = false,
    onProfiles: () -> Unit = {},
    // What went wrong with something asked from this screen, such as the profiles that did not load.
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    var connectTv by remember { mutableStateOf(false) }
    var tvCode by remember { mutableStateOf("") }
    val context = LocalContext.current
    var mono by remember { mutableStateOf(AppIcon.isMono(context)) }
    var skipAds by remember { mutableStateOf(LocalPrefs.skipAds(context)) }
    var officialReceiver by remember { mutableStateOf(CastReceiver.isOfficial(context)) }
    // statusBarsPadding keeps the Retour button below the status bar so it stays tappable. The page
    // scrolls: it is longer than a small screen.
    Column(modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Spacer(Modifier.width(8.dp))
            Text("Paramètres", style = MaterialTheme.typography.titleLarge)
        }
        if (busy && !connectTv) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrandYellow)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onProfiles).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Profils", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Changer de profil, en ajouter ou les modifier",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        SettingToggle(
            title = "Masquer les contenus verrouillés",
            subtitle = "Cache les programmes non inclus dans votre abonnement",
            checked = hideLocked,
            onCheckedChange = { LocalPrefs.setHideLocked(context, it); onHideLocked(it) },
        )
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        SettingChoice(
            title = "Passer les publicités automatiquement",
            subtitle = "En différé. Une coupure qui n'est pas passée est proposée par un bouton.",
            choices = listOf(
                Triple(SkipAds.OFF, "Non", "Toujours le bouton"),
                Triple(SkipAds.MARKED, "Coupures signalées", "Celles que la chaîne signale : groupes TF1, France Télévisions, RMC"),
                Triple(
                    SkipAds.SURE,
                    "Signalées et presque sûres",
                    "Aussi celles trouvées d'après le logo de la chaîne quand rien n'en laisse douter. " +
                        "Peut couper la fin d'un générique ou quelques secondes d'émission.",
                ),
            ),
            selected = skipAds,
            onSelect = {
                LocalPrefs.setSkipAds(context, it)
                skipAds = it
            },
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
            Modifier.fillMaxWidth().clickable(enabled = selfUpdate && !checkingUpdate) { onCheckUpdate() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (selfUpdate) "Vérifier les mises à jour" else "Version", style = MaterialTheme.typography.bodyLarge)
                Text(
                    updateStatus ?: if (selfUpdate) "Version actuelle : $appVersion" else appVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (checkingUpdate) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        if (autoUpdate != null) {
            SettingToggle(
                title = "Mises à jour automatiques",
                subtitle = "Installées quand vous quittez l'application, sans rien lire ni diffuser",
                checked = autoUpdate,
                onCheckedChange = onAutoUpdate,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
        Column(
            Modifier.fillMaxWidth()
                .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) } }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("Politique de confidentialité", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Ce que l'application envoie, et à qui",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        enabled = !busy,
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
                    enabled = tvCode.length >= 4 && !busy && connectInfo == null,
                ) { Text("Connecter") }
            },
            dismissButton = { TextButton(onClick = { connectTv = false; tvCode = ""; onClearTvConnect() }) { Text("Fermer") } },
        )
    }
}

/** A setting with a few values: a title + subtitle, then one row with a radio button per value. */
@Composable
private fun <T> SettingChoice(title: String, subtitle: String, choices: List<Triple<T, String, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        for ((value, label, detail) in choices) {
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
    isGrid: Boolean = false,
    columns: Int = LocalPrefs.DEFAULT_GRID_COLUMNS,
    pageTitle: String? = null,
    searched: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Saved, so it is still there on coming back from a result opened over the search.
    var query by rememberSaveable { mutableStateOf("") }
    // The results keep their scroll under the search that gave them and the page they are, the rails or
    // one of them opened with "Tout voir": another search starts at its first results, not where the
    // last ones were left, and Back from "Tout voir" finds the rails as they were.
    var searchedFor by rememberSaveable { mutableStateOf("") }
    val results = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    // The search may be said rather than typed, where the device has something to listen with.
    val context = LocalContext.current
    val canListen = remember { VoiceSearch.request(context) != null }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        VoiceSearch.heard(result.resultCode, result.data)?.let { heard ->
            query = heard
            searchedFor = heard
            onSubmit(heard)
        }
    }
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Rechercher une chaîne, un film, une série…") },
            singleLine = true,
            trailingIcon = if (!canListen) null else {
                {
                    IconButton(onClick = {
                        keyboard?.hide()
                        // The recogniser may have gone since it was looked for.
                        runCatching { VoiceSearch.request(context)?.let(listen::launch) }
                    }) {
                        Icon(painterResource(DesignR.drawable.ic_micro), contentDescription = "Recherche vocale")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The keyboard would hide the results: close it once the search is sent.
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                searchedFor = query
                onSubmit(query)
            }),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        results.SaveableStateProvider("$searchedFor#${pageTitle.takeIf { isGrid }}") {
            // A rail opened with "Tout voir" lists all its results in a grid, as elsewhere.
            if (isGrid) {
                GridContent(
                    rails = rails,
                    busy = busy,
                    error = error,
                    onCardClick = onCardClick,
                    refreshing = refreshing,
                    onRefresh = onRefresh,
                    columns = columns,
                    pageTitle = pageTitle,
                    modifier = Modifier.weight(1f),
                )
            } else if (searched && rails.isEmpty() && !busy && error == null) {
                Text(
                    "Aucun résultat trouvé",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            } else {
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
    onWatchReplay: () -> Unit,
    onRecord: (RecordAction) -> Unit,
    onWatchRecording: (String) -> Unit,
    onBack: () -> Unit,
    onEpisode: (ContentCard) -> Unit = {},
    isRecording: Boolean = false,
    castButton: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Kept with the page (see where it is shown): a page pushed over this one starts at its own top.
    val listState = rememberLazyListState()
    // A series may have a hundred episodes and more: only those on screen are laid out.
    val seasons = remember(detail.episodes) { detail.episodes.groupBySeason() }
    var season by rememberSaveable { mutableIntStateOf(0) }
    val shown = seasons.getOrNull(season.coerceIn(0, (seasons.size - 1).coerceAtLeast(0)))?.second.orEmpty()
    LazyColumn(modifier.fillMaxSize().statusBarsPadding(), state = listState) {
        item(key = "header", contentType = "header") {
            DetailHeader(
                detail, busy, error, info, onWatch, onStartOver, onWatchReplay, onRecord, onWatchRecording, onBack,
                isRecording, castButton, seasons, season, onSeason = { season = it },
            )
        }
        // By place as well: a page may list one id twice, which a key alone would not take.
        itemsIndexed(shown, key = { i, ep -> "ep#$i#${ep.id}" }, contentType = { _, _ -> "episode" }) { _, ep ->
            EpisodeRow(ep, enabled = !busy, onClick = { onEpisode(ep) }, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
        }
        if (shown.isNotEmpty()) item(key = "after-episodes", contentType = "spacer") { Spacer(Modifier.height(16.dp)) }
        // A channel's catalogue carousels (replays, most-viewed, live & upcoming) full width below the
        // live header. onEpisode is the shared card click (vm.onCardClick), so a card opens normally.
        itemsIndexed(detail.sections, key = { i, rail -> "rail#$i#${rail.id}" }, contentType = { _, _ -> "rail" }) { _, rail ->
            Spacer(Modifier.height(20.dp))
            // No "Tout voir" here: a see-all would navigate out of the open detail. The carousels carry
            // plenty of cards, and each card opens normally via onEpisode (vm.onCardClick).
            Rail(rail, onCardClick = onEpisode, onSeeAll = {}, showSeeAll = false)
        }
        if (detail.sections.isNotEmpty()) item(key = "end", contentType = "spacer") { Spacer(Modifier.height(16.dp)) }
    }
}

/** What heads a detail page, down to the title of its episodes: its artwork, its actions and its text. */
@Composable
private fun DetailHeader(
    detail: ProgramDetail,
    busy: Boolean,
    error: String?,
    info: String?,
    onWatch: () -> Unit,
    onStartOver: () -> Unit,
    onWatchReplay: () -> Unit,
    onRecord: (RecordAction) -> Unit,
    onWatchRecording: (String) -> Unit,
    onBack: () -> Unit,
    isRecording: Boolean,
    castButton: @Composable () -> Unit,
    seasons: List<Pair<String, List<ContentCard>>>,
    season: Int,
    onSeason: (Int) -> Unit,
) {
    Column {
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
        // The episodes under it go on in line with its text.
        val bottom = if (seasons.isEmpty()) 16.dp else 0.dp
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            detail.timeLabel?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                        RaisedButton(onClick = onWatch, enabled = !busy) {
                            Text(if (detail.isLive) "Regarder en direct" else "Regarder")
                        }
                    }
                    if (showWatch && detail.isLive) {
                        RaisedButton(onClick = onStartOver, enabled = !busy) { Text("Regarder depuis le début") }
                    }
                    // The same programme on demand: whole from its first minute, where the live stream
                    // only goes back as far as its rewind window.
                    if (showWatch && detail.replay != null) {
                        RaisedButton(onClick = onWatchReplay, enabled = !busy) { Text("Regarder le replay") }
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
            if (seasons.isNotEmpty()) EpisodesTitle(seasons, season, onSeason)
        }
    }
}

/** The title of the "Épisodes disponibles", with a season dropdown when there are several. */
@Composable
private fun EpisodesTitle(seasons: List<Pair<String, List<ContentCard>>>, selected: Int, onSelect: (Int) -> Unit) {
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
                    // Mark the season on screen, so the menu shows which one is current.
                    seasons.forEachIndexed { i, (label, _) ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (i == idx) BrandYellow else Color.Unspecified) },
                            onClick = { onSelect(i); expanded = false },
                            trailingIcon = if (i == idx) ({ Text("✓", color = BrandYellow) }) else null,
                        )
                    }
                }
            }
        }
    }
}

/** One catch-up episode row on a detail page: a thumbnail and label, tappable to open the episode. */
@Composable
private fun EpisodeRow(card: ContentCard, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).height(68.dp).clip(RoundedCornerShape(8.dp)).background(BackgroundLevel1)) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            card.tag?.let { CardTagPill(it) }
            card.channelLogoUrl?.let { ChannelLogo(it) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.title ?: "Épisode", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // When it aired, as the official app lists it.
            card.caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("›", style = MaterialTheme.typography.titleLarge, color = BrandYellow)
    }
}

/** The record CTA: a single button carrying the backend's own label when there is one option, or a
 * button that opens a menu when there are several (e.g. "Enregistrer l'épisode" / "Enregistrer la série"). */
@Composable
private fun RecordButton(actions: List<RecordAction>, enabled: Boolean, onRecord: (RecordAction) -> Unit) {
    if (actions.size == 1) {
        RaisedButton(onClick = { onRecord(actions[0]) }, enabled = enabled) { Text(actions[0].label) }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        RaisedButton(onClick = { expanded = true }, enabled = enabled) { Text("Enregistrer") }
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
    // The episode and the day it was recorded ("S16 E6 Agde • Enregistré: 30 sept.").
    val label = listOfNotNull(recording.title, recording.subtitle).joinToString(" • ")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.ifBlank { "Enregistrement" },
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
        // A card id can repeat within a rail (it falls back to label/image), and a duplicate lazy-list
        // key crashes the row: the index joins it then. Otherwise the id alone, so that a card keeps
        // its place as those before it come and go (the guide loaded again as programmes end).
        val unique = remember(rail.cards) { rail.cards.mapTo(HashSet()) { it.id }.size == rail.cards.size }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(rail.cards, key = { i, c -> if (unique) c.id else "${c.id}#$i" }) { _, card -> CardItem(card, onCardClick) }
        }
    }
}

@Composable
private fun CardItem(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    // Channel and app logos draw as small squares; artwork keeps its own shape at a common width.
    val isChannel = card.shape == CardShape.SQUARE
    // A genre tile is a navigation card (no channel/VOD id) whose title names a category; it carries
    // no artwork, so draw the brand icon instead of a blank box.
    val categoryIcon = if (card.channelId == null && card.vodId == null) categoryIconRes(card.title) else null
    val cardWidth = when (card.shape) {
        CardShape.SQUARE -> 96.dp
        CardShape.WIDE, CardShape.ROW -> 150.dp
        CardShape.POSTER, CardShape.POSTER_THIN -> 120.dp
    }
    Column(
        modifier = Modifier.width(cardWidth).clickable { onCardClick(card) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.width(cardWidth).aspectRatio(card.shape.ratio)
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
            FavoriteStar(card)
            card.tag?.let { CardTagPill(it) }
            card.channelLogoUrl?.let { ChannelLogo(it) }
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
        card.caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(cardWidth),
            )
        }
    }
}

// categoryIconRes now lives in core:designsystem so the TV app shares the same genre icons.
