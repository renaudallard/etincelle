// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.model.UpNext
import kotlinx.coroutines.delay

/**
 * What the viewer did with the slate of one episode, and its countdown: watching its credits
 * ([hidden]), past the countdown ([fired]), and the seconds [left]. Kept by the caller when the slate
 * may move between screens (the show page and the cast controller), or when the countdown runs where
 * the slate is not drawn, so neither choice is forgotten on the way.
 */
@Stable
class UpNextSlateState {
    var hidden by mutableStateOf(false)
    var fired = false
    var left by mutableStateOf<Long?>(null)
        private set

    // The episode was seen before its slate: only then is the player known to be on it.
    private var armed = false

    /**
     * Moves the countdown of [upNext] to [positionMs] of [durationMs]; true once, when it runs out and
     * the next episode should start.
     */
    fun tick(upNext: UpNext, positionMs: Long, durationMs: Long): Boolean {
        val now = upNext.secondsLeft(positionMs, durationMs)
        if (now == null) {
            hidden = false
            armed = true
        }
        // Just after the switch the player still reports where the previous episode ended, past this
        // one's slate: going on from there would skip this episode. An episode always starts before
        // its slate (a resume point past it starts over), so wait to see it there.
        if (!armed) {
            left = null
            return false
        }
        left = now
        // Watching the credits (a hidden slate) lets the episode end without going on.
        if (now != 0L || fired || hidden) return false
        fired = true
        return true
    }
}

/**
 * One [UpNextSlateState] per episode, started by what runs the countdown ([of]) and read by what shows
 * the slate ([shown]), which follows the episode it is on.
 */
class UpNextSlates {
    private var key by mutableStateOf<UpNext?>(null)
    private var state = UpNextSlateState()

    /** The countdown of [upNext], started anew for another episode. */
    fun of(upNext: UpNext): UpNextSlateState {
        if (upNext != key) {
            state = UpNextSlateState()
            key = upNext
        }
        return state
    }

    /** The countdown of [upNext] if it is the one running; it never starts one. */
    fun shown(upNext: UpNext?): UpNextSlateState? = if (upNext != null && upNext == key) state else null
}

/**
 * The slate offering the next episode near the end of the current one: its title, a countdown, then
 * [UpNext.playLabel] (play it now) and [UpNext.creditsLabel] (hide the slate and watch the end). When
 * the countdown reaches zero, [onPlayNext] runs once. The countdown follows [position] (pausing holds
 * it); a hidden slate comes back only after a seek before it. With [drive] false the countdown runs
 * elsewhere, through [UpNextSlateState.tick], and the slate only shows [state]. [button] draws a button
 * in the app's own style; with [takeFocus] (TV) the play button gets the focus when the slate appears,
 * and [onLeave] runs when the slate goes away, so the caller can hand the focus back.
 */
@Composable
fun UpNextSlate(
    upNext: UpNext,
    position: () -> Long,
    duration: () -> Long,
    onPlayNext: () -> Unit,
    button: @Composable RowScope.(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = false,
    onLeave: () -> Unit = {},
    drive: Boolean = true,
    state: UpNextSlateState = remember(upNext) { UpNextSlateState() },
) {
    val playNext by rememberUpdatedState(onPlayNext)
    // The player can change under a running countdown (the cast moving to another device).
    val positionNow by rememberUpdatedState(position)
    val durationNow by rememberUpdatedState(duration)
    if (drive) {
        LaunchedEffect(upNext, state) {
            while (true) {
                if (state.tick(upNext, positionNow(), durationNow())) playNext()
                delay(TICK_MS)
            }
        }
    }
    val seconds = state.left
    if (seconds == null || state.hidden) return
    val focus = remember { FocusRequester() }
    if (takeFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The focused button leaves with the slate (credits chosen, a seek back, the next episode), and
    // the focus would otherwise stay on nothing.
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(Unit) { onDispose { leave() } }
    Column(
        modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE6000000)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        upNext.title?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White) }
        upNext.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)) }
        Text(upNext.countdown.replace("%s", "$seconds s"), style = MaterialTheme.typography.bodyMedium, color = BrandYellow)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            button(upNext.playLabel, true, playNext, if (takeFocus) Modifier.focusRequester(focus) else Modifier)
            button(upNext.creditsLabel, false, { state.hidden = true }, Modifier)
        }
    }
}

private const val TICK_MS = 250L
