// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

// How long after a rewind stopped at a programme's start a fresh press of Left goes on past it.
private const val GRACE_MS = 15_000L

/**
 * Where a rewind with the remote's arrows may go on a live channel: it stops at the start of the
 * programme playback is in, as on the Chromecast, and a fresh press of Left soon after it stopped
 * there goes on into the one before; anywhere else in the programme it stops at the start again.
 * Positions are the live window's; the stop is kept as the programme's start time of day, which
 * stays put as the window slides. Main thread only.
 */
class RewindHold {
    private var holding = true
    private var parkedAt: Long? = null

    /** Whether the last press stopped at a programme's start. */
    val parked: Boolean get() = parkedAt != null

    /**
     * Where a press moving [base] by [step] lands, within [0, end]: not before [start], the
     * programme's start, when coming from within the programme. [startOfDay] is that start as a time
     * of day; both null when the programme is not known. [letGo] says the press may go past a stop:
     * a fresh press of Left (not a repeat) once the last seek was made.
     */
    fun press(base: Long, step: Long, end: Long, start: Long?, startOfDay: Long?, letGo: Boolean): Long {
        // A start past the live point read with it (playback may run a little beyond until the window
        // is read again) is not one to stop at, and would leave no room between the two.
        val s = start?.takeIf { startOfDay != null && it in 1 until end }
        if (s != null) {
            val atStart = parkedAt == startOfDay && base <= s + GRACE_MS
            if (letGo && atStart) {
                holding = false
            } else if (base >= s && !atStart) {
                holding = true
            }
        }
        val floor = if (s != null && holding && base >= s) s else 0L
        val target = (base + step).coerceIn(floor, end)
        parkedAt = startOfDay?.takeIf { s != null && floor == s && target == s }
        return target
    }
}
