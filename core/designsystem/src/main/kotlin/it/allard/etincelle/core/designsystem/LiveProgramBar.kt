// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// Dark-theme palette, in step with the rest of designsystem (see ReturnToLiveButton).
private val Played = Color(0xFFFFC107) // brand yellow: the show watched so far
private val ShowTrack = Color(0x4DFFFFFF) // the show, not yet reached
private val Earlier = Color(0xFF5B6478) // the stretch of the show older than the rewind buffer: a distinct colour
private val LiveTick = Color(0xFFE53935) // the live edge, echoing the back-to-live dot
private val Thumb = Color(0xFFFFFFFF)

/**
 * A live seek bar scaled to the current programme: the whole bar is the on-screen show, the watched
 * part is [Played] yellow, the stretch older than the rewind buffer is the distinct [Earlier] colour,
 * and a [LiveTick] marks the live edge part-way along (the not-yet-aired remainder is the tail past
 * it). [playedFraction]/[liveFraction]/[seekFloorFraction] are fractions [0,1] of the programme (see
 * LivePlayback.liveBarGeometry).
 *
 * [onSeekToFraction] makes the bar draggable (phone, touch); pass null for a position-only indicator
 * (TV, where the remote scrubs with the rewind/forward buttons).
 */
@Composable
fun LiveProgramBar(
    playedFraction: Float,
    liveFraction: Float,
    seekFloorFraction: Float,
    modifier: Modifier = Modifier,
    onSeekToFraction: ((Float) -> Unit)? = null,
) {
    // While dragging, follow the finger locally and commit the seek on release, so the thumb tracks
    // smoothly instead of fighting the 1s position poll.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val played = scrub ?: playedFraction

    val gestures = if (onSeekToFraction == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    },
                    onDragEnd = { scrub?.let(onSeekToFraction); scrub = null },
                    onDragCancel = { scrub = null },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { onSeekToFraction((it.x / size.width).coerceIn(0f, 1f)) }
            }
    }

    Canvas(modifier.fillMaxWidth().height(28.dp).then(gestures)) {
        drawLiveBar(played, liveFraction, seekFloorFraction)
    }
}

private fun DrawScope.drawLiveBar(played: Float, live: Float, seekFloor: Float) {
    val trackH = 4.dp.toPx()
    val thumbR = 7.dp.toPx()
    val tickH = 12.dp.toPx()
    val y = size.height / 2f
    val left = thumbR
    val right = size.width - thumbR
    val span = (right - left).coerceAtLeast(1f)
    fun x(f: Float) = left + span * f.coerceIn(0f, 1f)

    fun segment(from: Float, to: Float, color: Color) {
        if (to <= from) return
        drawLine(color, Offset(x(from), y), Offset(x(to), y), trackH, StrokeCap.Round)
    }

    // The show runs as the base track; the stretch older than the rewind buffer gets its own colour;
    // the watched part of the show is painted over in yellow.
    segment(seekFloor, 1f, ShowTrack)
    segment(0f, seekFloor, Earlier)
    segment(seekFloor.coerceAtMost(played), played, Played)

    // Live edge marker, then the thumb on top.
    drawLine(LiveTick, Offset(x(live), y - tickH / 2f), Offset(x(live), y + tickH / 2f), 2.dp.toPx())
    drawCircle(Thumb, thumbR, Offset(x(played), y))
}
