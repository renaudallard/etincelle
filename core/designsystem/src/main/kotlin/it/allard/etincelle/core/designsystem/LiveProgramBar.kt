// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// Dark-theme palette, in step with the rest of designsystem (see ReturnToLiveButton).
private val Played = Color(0xFFFFC107) // brand yellow: the show watched so far
private val ShowTrack = Color(0x4DFFFFFF) // the aired show, seekable but not yet watched
private val Upcoming = Color(0x26FFFFFF) // the show not yet aired (past the live edge): dimmer
private val Earlier = Color(0xFF5B6478) // the stretch of the show older than the rewind buffer: a distinct colour
private val LiveTick = Color(0xFFE53935) // the live edge, echoing the back-to-live dot
private val Thumb = Color(0xFFFFFFFF)

/**
 * The bottom padding that puts a LiveProgramBar where the player controller draws its own time bar:
 * above the controller's 60dp bottom row (time and settings), centred in the 48dp band media3 keeps
 * 52dp up for it. Measured in the player view, which media3 lays out without system bar insets.
 */
val LiveProgramBarBottomOffset = 62.dp

/**
 * A live seek bar scaled to the current programme: the whole bar is the on-screen show, the watched
 * part is [Played] yellow, the stretch older than the rewind buffer is the distinct [Earlier] colour,
 * and a [LiveTick] marks the live edge part-way along (the not-yet-aired remainder is the tail past
 * it). [playedFraction]/[liveFraction]/[seekFloorFraction] are fractions [0,1] of the programme (see
 * LivePlayback.liveBarGeometry).
 *
 * [onSeekToFraction] makes the bar draggable (phone, touch); pass null for a position-only indicator
 * (TV, where the remote scrubs with the rewind/forward buttons), and [onScrubbing] tells when a drag
 * starts and ends. [startLabel]/[endLabel], when given, are drawn at either end of the bar: the
 * programme's start and end times.
 */
@Composable
fun LiveProgramBar(
    playedFraction: Float,
    liveFraction: Float,
    seekFloorFraction: Float,
    modifier: Modifier = Modifier,
    onSeekToFraction: ((Float) -> Unit)? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    onScrubbing: (Boolean) -> Unit = {},
) {
    // While dragging, follow the finger locally and commit the seek on release, so the thumb tracks
    // smoothly instead of fighting the 1s position poll.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val played = scrub ?: playedFraction
    // The gestures outlive a recomposition: they call what the bar was last given.
    val seekTo by rememberUpdatedState(onSeekToFraction)
    val scrubbing by rememberUpdatedState(onScrubbing)

    val gestures = if (onSeekToFraction == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        scrub = (it.x / size.width).coerceIn(0f, 1f)
                        scrubbing(true)
                    },
                    onHorizontalDrag = { change, _ ->
                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                        change.consume()
                    },
                    onDragEnd = {
                        scrub?.let { seekTo?.invoke(it) }
                        scrub = null
                        scrubbing(false)
                    },
                    onDragCancel = {
                        scrub = null
                        scrubbing(false)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { seekTo?.invoke((it.x / size.width).coerceIn(0f, 1f)) }
            }
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        startLabel?.let { Text(it, color = Thumb, style = MaterialTheme.typography.labelMedium) }
        Canvas(Modifier.weight(1f).height(28.dp).then(gestures)) {
            drawLiveBar(played, liveFraction, seekFloorFraction)
        }
        endLabel?.let { Text(it, color = Thumb, style = MaterialTheme.typography.labelMedium) }
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

    // The aired part of the show is the base track up to the live edge; the not-yet-aired tail past it
    // is dimmer; the stretch too old to rewind to gets its own colour; the watched part is yellow.
    segment(seekFloor, live, ShowTrack)
    segment(live, 1f, Upcoming)
    segment(0f, seekFloor, Earlier)
    segment(seekFloor.coerceAtMost(played), played, Played)

    // Live edge marker, then the thumb on top.
    drawLine(LiveTick, Offset(x(live), y - tickH / 2f), Offset(x(live), y + tickH / 2f), 2.dp.toPx())
    drawCircle(Thumb, thumbR, Offset(x(played), y))
}
