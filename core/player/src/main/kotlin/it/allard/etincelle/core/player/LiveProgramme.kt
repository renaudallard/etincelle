// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import it.allard.etincelle.core.model.ProgramWindow

// How often the guide is asked for the programme playback is in when none known is it, and how long
// it is left alone after it had none to give: what was on an hour ago will not come later.
private const val ASK_EVERY_MS = 15_000L
private const val ASK_AGAIN_MS = 60_000L

// How long past its end a programme goes on being shown while the one after it is not known yet: the
// guide lags a boundary by some seconds, and its hours leave gaps of a few minutes between two shows.
private const val LINGER_MS = 5 * 60_000L

// A start on the minute is the planned one, as much as seven minutes off the real start, which the
// guide gives two to four minutes into the programme: it is asked again, this often, for that long.
private const val PLANNED_ASK_MS = 60_000L
private const val PLANNED_FOR_MS = 15 * 60_000L

/**
 * The programme a live stream's bar is scaled to: the one playback is in, which is not the one on air
 * for a stream watched behind the live point. Scaled to the one on air, the bar of a programme started
 * over, paused across the end of a show, or handed over rewound, shows the hours of the next show with
 * its mark stuck at the start.
 *
 * It starts from the programme the stream was resolved with, [first], and learns the others from
 * [ask], which is given a time of day (epoch ms) and answers with what the guide had on then, if it
 * still knows. Where it does not, as for what ended a while ago, there is none: the bar then spans
 * the bare rewind window. Main thread only.
 */
class LiveProgramme(first: ProgramWindow?, private val ask: suspend (Long) -> ProgramWindow?) {
    private val known = listOfNotNull(first).toMutableList()
    private var shown = first
    private var nextAskMs = 0L

    /**
     * The programme to scale the bar to now, or null for none; asks the guide when it is not known.
     * To be called as the bar is redrawn. [window] is a caller-owned scratch instance to fill.
     */
    suspend fun current(player: Player, window: Timeline.Window, nowMs: Long = System.currentTimeMillis()): ProgramWindow? {
        // Not anchored to the clock yet, or not at all: what it was resolved with.
        val at = LivePlayback.playheadEpochMs(player, window) ?: return shown
        return atPlayhead(at, nowMs)
    }

    /** As [current], for playback at [at] (epoch ms). */
    internal suspend fun atPlayhead(at: Long, nowMs: Long): ProgramWindow? {
        val found = pick(known, shown, at)
        shown = found
        if (found != null && found.holds(at)) {
            // Still planned: whatever the guide now has on then replaces it, a later start, or the
            // show before that is not over yet.
            if (planned(found, at) && nowMs >= nextAskMs) {
                nextAskMs = nowMs + PLANNED_ASK_MS
                val answer = ask(at)
                if (answer != null && answer != found && answer.holds(at)) {
                    known -= found
                    if (answer !in known) known += answer
                    shown = answer
                }
            }
            return shown
        }
        // None known holds it, though the one before may still be shown for a moment: ask.
        if (nowMs >= nextAskMs) {
            nextAskMs = nowMs + ASK_EVERY_MS
            val answer = ask(at)
            if (answer == null) {
                nextAskMs = nowMs + ASK_AGAIN_MS
            } else if (answer !in known) {
                known += answer
                if (answer.holds(at)) shown = answer
            }
        }
        return shown
    }

    internal companion object {
        // A couple of seconds short of its start still counts as in it: a seek to the programme's
        // beginning may land there.
        private const val START_SLACK_MS = 2_000L

        fun ProgramWindow.holds(atMs: Long) = atMs >= startMs - START_SLACK_MS && atMs < endMs

        /** Whether [window]'s start, on the minute, may be the planned one still, at [atMs]. */
        fun planned(window: ProgramWindow, atMs: Long) =
            window.startMs % 60_000L == 0L && atMs - window.startMs < PLANNED_FOR_MS

        /**
         * Among [known], the programme playback at [atMs] is in: the latest to have started, as the
         * guide's hours overlap by a minute or two where a show began early. Failing that, [shown]
         * for as long as it has only just ended. Null when playback is in none of them.
         */
        fun pick(known: List<ProgramWindow>, shown: ProgramWindow?, atMs: Long): ProgramWindow? =
            known.filter { it.holds(atMs) }.maxByOrNull { it.startMs }
                ?: shown?.takeIf { atMs >= it.endMs && atMs < it.endMs + LINGER_MS }
    }
}
