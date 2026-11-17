// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.view.Window
import android.view.WindowManager
import androidx.media3.common.Player

/**
 * Keeps the screen of [window] on while [player] is meant to play, buffering or being brought back
 * after an error included, and lets it go off as usual once playback is paused or over: a player screen
 * left open must not keep a phone lit or a TV awake for ever. The call returned ends it. Main thread
 * only.
 */
fun keepScreenOnWhilePlaying(window: Window?, player: Player): () -> Unit {
    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    val listener = object : Player.Listener {
        // Not isPlaying: it is false through a stall, long enough for a phone's screen to go off.
        fun update() {
            if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) window?.addFlags(flag) else window?.clearFlags(flag)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = update()

        override fun onPlaybackStateChanged(playbackState: Int) = update()
    }
    listener.update()
    player.addListener(listener)
    return {
        player.removeListener(listener)
        window?.clearFlags(flag)
    }
}
