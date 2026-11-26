// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.allard.etincelle.core.model.CardProgress
import kotlinx.coroutines.delay

// A live programme's bar is drawn again this often: a pixel of a card is some tens of seconds of it.
private const val TICK_MS = 30_000L

// The official app's colours, for a bar the backend gives none.
private const val TRACK = 0xFF0F1010
private const val LIVE = 0xFFFF5423
private const val WATCHED = 0xFFFFFFFF

/**
 * A card's progress along the bottom of its picture, as the official app draws it: a live programme
 * as far as the clock, moving with it, or one watched before as far as it was watched.
 */
@Composable
fun BoxScope.CardProgressBar(progress: CardProgress, height: Dp = 3.dp) {
    val now by produceState(System.currentTimeMillis(), progress) {
        while (progress.live) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height).background(Color(progress.track ?: TRACK))) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(progress.filledAt(now))
                .background(Color(progress.color ?: if (progress.live) LIVE else WATCHED)),
        )
    }
}
