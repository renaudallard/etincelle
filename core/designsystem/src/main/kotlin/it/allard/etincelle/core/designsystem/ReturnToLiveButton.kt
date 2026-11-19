// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A "back to the live edge" pill, shown over a live player while the viewer is watching behind the
 * edge (after rewinding into the DVR window). A red dot recalls the broadcast-live convention; the
 * tap jumps playback to the live edge. Hidden by the caller when already at the edge.
 */
@Composable
fun ReturnToLiveButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    PlayerPill("Revenir au direct", Color(0xFFE53935), onClick, modifier)

/**
 * A "skip the ads" pill, shown over a live player while what plays, behind the live point, is an ad
 * break whose end is known (see AdBreaks in core:player); the tap jumps to that end. [label] lets a
 * TV say which key does it.
 */
@Composable
fun SkipAdsButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Passer la pub") =
    PlayerPill(label, Color(0xFFFFC107), onClick, modifier)

@Composable
private fun PlayerPill(label: String, dot: Color, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = Color(0xCC000000),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(label)
        }
    }
}
