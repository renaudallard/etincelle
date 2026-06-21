// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import it.allard.etincelle.core.cast.CastUiState
import it.allard.etincelle.core.designsystem.R as DesignR
import it.allard.etincelle.core.designsystem.theme.BrandYellow

/** Cast control for the top bar: shows a picker of discovered Chromecasts, or the connected one. */
@Composable
fun CastButton(state: CastUiState, onConnect: (String) -> Unit, onDisconnect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Hide the button when there is nothing to cast to, but keep an already-open picker on screen so it
    // does not vanish mid-interaction if the last discovered device drops off the network.
    if (!state.available && !open) return
    if (state.available) {
        TextButton(onClick = { open = true }) {
            // A filled glyph signals an active Cast connection; the outline one means "not casting".
            val icon = if (state.isCasting) DesignR.drawable.ic_cast_connected else DesignR.drawable.ic_cast
            Icon(painterResource(icon), contentDescription = "Caster", modifier = Modifier.size(30.dp))
            val device = state.connectedDeviceName
            if (device != null) {
                Spacer(Modifier.width(6.dp))
                Text(device)
            }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Fermer") } },
            title = { Text("Lire sur") },
            text = {
                Column {
                    // This phone — selecting it while casting transfers playback back here.
                    TextButton(onClick = { if (state.isCasting) onDisconnect(); open = false }) {
                        Text((if (!state.isCasting) "• " else "") + "Cet appareil")
                    }
                    state.devices.forEach { device ->
                        // Prefer the route id (unambiguous across same-named devices); fall back to the
                        // name when no route id is known yet, e.g. a session resumed after an app kill.
                        val selected = state.isCasting && when {
                            state.connectedRouteId != null -> state.connectedRouteId == device.routeId
                            else -> state.connectedDeviceName == device.name
                        }
                        TextButton(onClick = { if (!selected) onConnect(device.routeId); open = false }) {
                            Text((if (selected) "• " else "") + device.name)
                        }
                    }
                }
            },
        )
    }
}

/**
 * The persistent cast bar shown at the bottom while a stream plays (or is being sent) on a
 * Chromecast. A Chromecast glyph fills from the bottom while connecting and snaps full once the
 * receiver actually plays, next to a "Connexion à …" / "Lecture sur …" label.
 */
@Composable
fun CastStatusBar(state: CastUiState, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val name = state.statusDeviceName ?: return
    // The connecting flag is the authority for "a connect is in flight" (it is cleared the moment the
    // receiver reports playback, and bounded by a watchdog), so the label follows it directly even
    // when the old device of a device-to-device switch is still reporting playback.
    val connecting = state.connecting
    val label = if (connecting) "Connexion à $name…" else "Lecture sur $name"
    // Tapping the bar opens the full playback controls (the expand glyph hints it). When [onClick] is
    // null those controls cannot open (no playing source, e.g. a cast resumed after an app kill), so
    // the bar is plain, non-clickable status with no affordance.
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connecting) PulsingCastGlyph() else CastFillGlyph(1f)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            if (onClick != null) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    painterResource(DesignR.drawable.ic_pleinecran),
                    contentDescription = "Commandes de lecture",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// The connect animation: the filled glyph rises and falls until the receiver starts playing. Kept in
// its own composable so the infinite transition only schedules frames while actually connecting.
@Composable
private fun PulsingCastGlyph() {
    val pulse by rememberInfiniteTransition(label = "cast").animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "castFill",
    )
    CastFillGlyph(pulse)
}

// The cast glyph with the filled variant revealed from the bottom up to [fill] (0..1).
@Composable
private fun CastFillGlyph(fill: Float) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            painterResource(DesignR.drawable.ic_cast),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        )
        Icon(
            painterResource(DesignR.drawable.ic_cast_connected),
            contentDescription = null,
            tint = BrandYellow,
            modifier = Modifier.size(28.dp).drawWithContent {
                val visible = size.height * fill.coerceIn(0f, 1f)
                clipRect(top = size.height - visible) { this@drawWithContent.drawContent() }
            },
        )
    }
}

/**
 * A brief centered overlay flashed when the volume keys change the cast device volume, since the
 * phone's own volume bar is suppressed while casting.
 */
@Composable
fun CastVolumeOverlay(level: Float, deviceName: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (level <= 0f) "🔇" else "🔊", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { level.coerceIn(0f, 1f) },
                modifier = Modifier.width(160.dp),
                color = BrandYellow,
            )
            if (deviceName != null) {
                Spacer(Modifier.height(8.dp))
                Text("Volume $deviceName", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
