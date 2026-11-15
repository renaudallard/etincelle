// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import it.allard.etincelle.core.model.CardTag

/**
 * A card's tag in its image's bottom-left corner, drawn as the official app does: a dark rounded pill
 * with the backend's icon before the label (the check of "REC", the dot of "DIRECT").
 */
@Composable
fun BoxScope.CardTagPill(tag: CardTag) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.align(Alignment.BottomStart).padding(6.dp)
            .clip(shape).background(Color.Black.copy(alpha = 0.75f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tag.iconUrl?.let {
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(tag.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
