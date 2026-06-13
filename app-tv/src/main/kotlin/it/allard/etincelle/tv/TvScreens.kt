// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel2
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState

private val OVERSCAN = 48.dp

@Composable
fun TvLoginScreen(busy: Boolean, error: String?, onLogin: (String, String) -> Unit) {
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
    onLogout: () -> Unit,
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
                TvTab(label = "Déconnexion", selected = false, onClick = onLogout)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.tab == Tab.SEARCH) {
            TvSearchContent(state, onSearch, onCardClick)
        } else {
            Text(state.current?.title ?: state.tab.label, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.focusGroup().focusRequester(railsFocus),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(state.current?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick) }
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
private fun TvSearchContent(state: UiState, onSubmit: (String) -> Unit, onCardClick: (ContentCard) -> Unit) {
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
                items(state.current?.rails.orEmpty(), key = { it.id }) { rail -> TvRail(rail, onCardClick) }
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

@Composable
private fun TvRail(rail: ContentRail, onCardClick: (ContentCard) -> Unit) {
    Column {
        rail.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rail.cards, key = { it.id }) { card -> TvCard(card, onCardClick) }
        }
    }
}

@Composable
private fun TvCard(card: ContentCard, onCardClick: (ContentCard) -> Unit) {
    val isChannel = card.channelId != null
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
        ) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize().padding(if (isChannel) 14.dp else 0.dp),
                contentScale = if (isChannel) ContentScale.Fit else ContentScale.Crop,
            )
            if (card.isLocked) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(BrandYellow).padding(horizontal = 4.dp)) {
                    Text("€", color = BrandBlack, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        card.title?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(w).padding(top = 4.dp))
        }
    }
}
