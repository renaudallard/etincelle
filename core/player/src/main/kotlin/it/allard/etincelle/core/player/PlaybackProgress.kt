// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import it.allard.etincelle.core.model.PlaybackSource

/** Decides which playback position is worth remembering as a resume point. */
object PlaybackProgress {
    private const val MIN_MS = 10_000L
    private const val END_MS = 15_000L

    /**
     * The position to persist for [positionMs] in a stream of [durationMs], or 0 to start fresh
     * next time. Returns 0 when barely started, near the end, or the duration is unknown
     * (durationMs <= 0). Live playback is filtered out upstream by a null resume key, not here.
     */
    fun positionToSave(positionMs: Long, durationMs: Long): Long =
        if (positionMs >= MIN_MS && durationMs > 0 && positionMs < durationMs - END_MS) positionMs else 0L

    /**
     * As above for [source], where an episode is over once its next-episode slate is due: the credits
     * are rolling, so it starts fresh next time instead of jumping straight to the next one.
     */
    fun positionToSave(source: PlaybackSource, positionMs: Long, durationMs: Long): Long =
        if (source.upNext?.let { positionMs >= it.atSec * 1000 } == true) 0L else positionToSave(positionMs, durationMs)
}
