// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.view.Window
import android.view.WindowManager
import androidx.media3.common.Player

/**
 * Keeps the screen of [window] on while [player] plays, and lets it go off as usual once playback is
 * paused, over or failed: a player screen left open must not keep a phone lit or a TV awake for ever.
 * The call returned ends it. Main thread only.
 */
fun keepScreenOnWhilePlaying(window: Window?, player: Player): () -> Unit {
    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window?.addFlags(flag) else window?.clearFlags(flag)
        }
    }
    listener.onIsPlayingChanged(player.isPlaying)
    player.addListener(listener)
    return {
        player.removeListener(listener)
        window?.clearFlags(flag)
    }
}
