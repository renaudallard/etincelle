// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import it.allard.etincelle.core.cast.AdBreakOffer
import it.allard.etincelle.core.designsystem.RaisedButton
import it.allard.etincelle.core.designsystem.RaisedButtonDefaults
import it.allard.etincelle.core.designsystem.SkipAdsButton
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.remote.RemoteMessages
import kotlinx.coroutines.delay

/**
 * The remote control of an etincelle TV app driven from this phone: what plays there with its play/pause
 * and skips (through [player], which drives the TV), and the TV remote's arrows, OK and Retour to move
 * around the TV app ([onKey]). The phone's volume keys set the TV's volume meanwhile.
 */
@Composable
fun TvRemoteScreen(player: Player, tvName: String, onKey: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }
    // The TV is in an ad break it offers to skip.
    var adBreak by remember { mutableStateOf(false) }
    // Only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                hasMedia = player.playbackState != Player.STATE_IDLE
                title = player.mediaMetadata.title?.toString().orEmpty()
                playing = player.playWhenReady
                adBreak = (player as? AdBreakOffer)?.adBreakOffered == true
                delay(REFRESH_MS)
            }
        }
    }
    Column(
        modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Fermer") }
            Spacer(Modifier.weight(1f))
            Text(tvName, style = MaterialTheme.typography.titleMedium, color = BrandYellow)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (hasMedia && title.isNotEmpty()) title else "Télécommande",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasMedia) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton("−10", onClick = { player.seekBack() })
                PadButton(if (playing) "❚❚" else "▶", primary = true, onClick = { player.playWhenReady = !player.playWhenReady })
                PadButton("+30", onClick = { player.seekForward() })
            }
        }
        if (hasMedia && adBreak) {
            Spacer(Modifier.height(16.dp))
            SkipAdsButton(onClick = { onKey(RemoteMessages.KEY_SKIP) })
        }
        Spacer(Modifier.weight(1f))
        // The arrows and OK of the TV remote, laid out as on it.
        PadButton("▲", onClick = { onKey("up") })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PadButton("◀", onClick = { onKey("left") })
            PadButton("OK", primary = true, onClick = { onKey("ok") })
            PadButton("▶", onClick = { onKey("right") })
        }
        PadButton("▼", onClick = { onKey("down") })
        Spacer(Modifier.height(24.dp))
        RaisedButton(onClick = { onKey("back") }, color = RaisedButtonDefaults.Neutral) { Text("Retour") }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PadButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    RaisedButton(
        onClick = onClick,
        color = if (primary) BrandYellow else RaisedButtonDefaults.Neutral,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.padding(6.dp).size(76.dp),
    ) {
        Text(label, fontSize = 20.sp, maxLines = 1)
    }
}

private const val REFRESH_MS = 500L
