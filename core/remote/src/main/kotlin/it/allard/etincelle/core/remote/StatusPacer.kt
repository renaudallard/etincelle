// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.remote

import kotlin.math.abs

/**
 * Says which of the statuses the TV works out are worth sending to the phone. Each one received keeps
 * the phone from sleeping, and between two of them the phone works the position out by itself, so one
 * is only sent when it tells what the phone cannot foresee: another state, title or programme, a
 * position that is not where playback would have taken it (a seek), or after [KEEP_ALIVE_MS] without
 * any, as a phone left without news takes the TV for gone. Not thread safe.
 */
class StatusPacer {
    private var sent = false
    private var what = ""
    private var clockMs = 0L
    private var advancing = false
    private var atMs = 0L

    /**
     * Whether the status worked out at [nowMs] (a monotonic clock) is to be sent, which is then taken
     * as done. [what] is all it says but its times. [clockMs] is where playback is, as a time that
     * stands still while paused and follows the clock while playing: the position on demand, the
     * wall-clock time of what plays on a live stream. [advancing] is whether it plays.
     */
    fun due(what: String, clockMs: Long, advancing: Boolean, nowMs: Long): Boolean {
        val since = nowMs - atMs
        val foreseen = this.clockMs + if (this.advancing) since else 0L
        if (sent && what == this.what && since < KEEP_ALIVE_MS && abs(clockMs - foreseen) < JUMP_MS) return false
        sent = true
        this.what = what
        this.clockMs = clockMs
        this.advancing = advancing
        atMs = nowMs
        return true
    }

    /** The next status is sent whatever it says: the phone is new, asked for something, or missed the last one. */
    fun reset() {
        sent = false
    }

    companion object {
        /** A status is sent at least this often. */
        const val KEEP_ALIVE_MS = 10_000L

        // A position this far from where playback would have taken it was sought.
        private const val JUMP_MS = 2_000L
    }
}
