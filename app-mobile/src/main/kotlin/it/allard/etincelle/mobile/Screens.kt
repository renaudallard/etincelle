// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import it.allard.etincelle.core.designsystem.theme.BackgroundLevel1
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.ProgramDetail

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

@Composable
fun PageContent(
    rails: List<ContentRail>,
    busy: Boolean,
    error: String?,
    onCardClick: (ContentCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(rails, key = { it.id }) { rail -> Rail(rail, onCardClick) }
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
fun SearchScreen(
    rails: List<ContentRail>,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCardClick: (ContentCard) -> Unit,
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
        PageContent(rails, busy, error, onCardClick, Modifier.weight(1f))
    }
}

/** A show's detail page: poster, info (year, genre, cast), synopsis, and a Regarder button. */
@Composable
fun ProgramDetailScreen(
    detail: ProgramDetail,
    busy: Boolean,
    error: String?,
    onWatch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(BackgroundLevel1)) {
            AsyncImage(
                model = detail.posterUrl,
                contentDescription = detail.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            TextButton(onClick = onBack) { Text("← Retour") }
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
            Button(onClick = onWatch, enabled = !busy) { Text("Regarder") }
            if (busy) CircularProgressIndicator()
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
        }
    }
}

@Composable
private fun Rail(rail: ContentRail, onCardClick: (ContentCard) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        rail.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
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
    val isChannel = card.channelId != null
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

/** Brand icon for a genre tile, matched by its title; null for any non-category card. */
@DrawableRes
private fun categoryIconRes(title: String?): Int? = when (title?.trim()?.lowercase()) {
    "films", "cinéma", "cinema" -> R.drawable.ic_films
    "séries", "series" -> R.drawable.ic_series
    "divertissement" -> R.drawable.ic_divertissement
    "sport", "sports" -> R.drawable.ic_sport
    "informations", "information", "info", "actualités", "actualites", "actu" -> R.drawable.ic_informations
    "documentaires", "documentaire", "découverte", "decouverte", "docs" -> R.drawable.ic_documentaires
    "enfants", "enfant", "jeunesse" -> R.drawable.ic_enfants
    "culture" -> R.drawable.ic_culture
    else -> null
}
