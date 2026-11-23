// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.media3.common.Timeline
import it.allard.etincelle.core.cast.AdBreakOffer
import it.allard.etincelle.core.designsystem.RaisedButton
import it.allard.etincelle.core.designsystem.RaisedButtonDefaults
import it.allard.etincelle.core.designsystem.SkipAdsButton
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.LiveProgramme
import it.allard.etincelle.core.remote.RemoteMessages
import kotlinx.coroutines.delay

/**
 * The remote control of an etincelle TV app driven from this phone: what plays there with its play/pause,
 * skips, a way back to its start (on a live channel, the programme's, which [fetchProgramWindow] asks the
 * guide for) and one to the live point when behind it (through [player], which drives the TV), and the TV
 * remote's arrows, OK and Retour to move around the TV app ([onKey]). The phone's volume keys set the
 * TV's volume meanwhile.
 */
@Composable
fun TvRemoteScreen(
    player: Player,
    tvName: String,
    fetchProgramWindow: suspend (String, Long) -> ProgramWindow?,
    onKey: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }
    // The TV is in an ad break it offers to skip.
    var adBreak by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var behindLive by remember { mutableStateOf(false) }
    // What this phone sent the TV, while the TV plays it: nothing for what was started on the TV itself.
    var source by remember { mutableStateOf<PlaybackSource?>(null) }
    // The live programme the TV is in, whose start "Revenir au début" goes back to.
    var programWindow by remember { mutableStateOf<ProgramWindow?>(null) }
    // Only while the app is on screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val window = Timeline.Window()
            while (true) {
                hasMedia = player.playbackState != Player.STATE_IDLE
                title = player.mediaMetadata.title?.toString().orEmpty()
                playing = player.playWhenReady
                adBreak = (player as? AdBreakOffer)?.adBreakOffered == true
                live = player.isCurrentMediaItemLive
                behindLive = LivePlayback.behindLiveEdgeMs(player, window) > LivePlayback.LIVE_REWIND_THRESHOLD_MS
                source = player.currentMediaItem?.localConfiguration?.tag as? PlaybackSource
                delay(REFRESH_MS)
            }
        }
    }
    // The programme follows playback past its end or back into the one before (see LiveProgramme),
    // the guide being asked beside the loop above.
    val channel = source?.takeIf { it.isLive }
    LaunchedEffect(player, channel, lifecycle) {
        programWindow = channel?.programWindow
        val id = channel?.originChannelId ?: return@LaunchedEffect
        val programme = LiveProgramme(channel.programWindow) { at -> fetchProgramWindow(id, at) }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val window = Timeline.Window()
            while (true) {
                programWindow = programme.current(player, window)
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
            // A live channel goes back to the start of its programme, when known.
            val toStart = !live || programWindow != null
            if (toStart || behindLive) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (toStart) {
                        RaisedButton(onClick = {
                            if (live) LivePlayback.seekToProgramStartIfReady(player, Timeline.Window(), programWindow) else player.seekTo(0)
                        }, color = RaisedButtonDefaults.Neutral) { Text("Revenir au début") }
                    }
                    if (behindLive) {
                        RaisedButton(onClick = { player.seekToDefaultPosition() }, color = RaisedButtonDefaults.Neutral) { Text("Revenir au direct") }
                    }
                }
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
