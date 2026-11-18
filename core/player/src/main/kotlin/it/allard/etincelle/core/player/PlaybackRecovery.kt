// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import it.allard.etincelle.core.model.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// How many times in a row playback is brought back before giving up.
private const val MAX_RECOVERIES = 5

// The wait before the second attempt, doubled before each one after it: 2, 4, 8 then 16 s, half a
// minute in all, so a Wi-Fi access point restarting or a TV set waking does not close the player.
private const val RETRY_BACKOFF_MS = 2_000L

// Playback that held this long before failing again is a new incident, with all its attempts.
private const val RETRY_RESET_MS = 60_000L

// How often a reloaded live stream is checked for being ready to seek.
private const val SEEK_POLL_MS = 250L

// How often an attempt that waits looks whether it may go on.
private const val HELD_POLL_MS = 1_000L

/**
 * Brings back the playback of [source] on [player] when it fails: a player left on its error shows a
 * frozen picture and nothing else. The stream is asked again with [resolve] (null when there is none to
 * be had), as its address or its licence may be what went stale, and played from where it was: the
 * position of a replay, the time of day of a live programme watched behind the live point. After
 * [MAX_RECOVERIES] failures in a row [onGiveUp] is called. While [held] says so nothing is loaded: the
 * attempt waits, as for an app that was left and keeps no stream loaded behind what the TV shows.
 * Main thread only; [stop] ends it.
 */
class PlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    private val source: PlaybackSource,
    private val resolve: suspend (PlaybackSource) -> PlaybackSource?,
    private val held: () -> Boolean = { false },
    private val onGiveUp: () -> Unit,
) : Player.Listener {
    private var attempts = 0

    // When playback last got going (SystemClock.elapsedRealtime), 0 if it has not since it last failed.
    private var readyAt = 0L
    private var job: Job? = null
    private val window = Timeline.Window()

    // The time of day (epoch ms) a live programme is to resume at, kept until it has: an attempt that
    // fails before its stream is ready no longer knows it.
    private var liveAt: Long? = null

    /**
     * The duration of what plays, as last known: a reload that fails before its stream has loaded
     * leaves the player without one, and with it no way to tell where to resume from.
     */
    var durationMs: Long = C.TIME_UNSET
        private set

    // The reload under way goes to the live point whatever the position says (see recover).
    private var toLivePoint = false

    override fun onPlayerError(error: PlaybackException) = recover(error.errorCode)

    // Sets [liveAt] from where a live programme is being watched. A player between two streams, its
    // reload not loaded yet, says nothing of it: what was noted before stands.
    private fun noteLiveTime() {
        liveAt = if (toLivePoint) null else LivePlayback.rewoundAt(player, window, liveAt)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // The first time since the last failure: a seek or a rebuffer later on is not a new start, and
        // would make a failure an hour after the last one count as following it.
        if (playbackState == Player.STATE_READY && readyAt == 0L) readyAt = SystemClock.elapsedRealtime()
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun recover(errorCode: Int) {
        // Not the time since the last failure: an attempt may itself take that long to fail.
        if (readyAt != 0L && SystemClock.elapsedRealtime() - readyAt > RETRY_RESET_MS) attempts = 0
        readyAt = 0L
        if (attempts >= MAX_RECOVERIES) {
            stop()
            onGiveUp()
            return
        }
        attempts++
        player.duration.takeIf { it > 0 }?.let { durationMs = it }
        // What was playing left the stream's rewind window: only the live point is sure to play.
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) toLivePoint = true
        noteLiveTime()
        val backoff = if (attempts == 1) 0L else RETRY_BACKOFF_MS shl (attempts - 2)
        job?.cancel()
        job = scope.launch {
            if (backoff > 0) delay(backoff)
            while (held()) delay(HELD_POLL_MS)
            // No stream to be had is one more failure.
            val fresh = resolve(source) ?: return@launch recover(PlaybackException.ERROR_CODE_UNSPECIFIED)
            // The app may have been left while the stream was asked for.
            while (held()) delay(HELD_POLL_MS)
            // Where playback is now, not where it failed: a seek made during the wait counts.
            noteLiveTime()
            val position = player.currentPosition
            val item = MediaItemFactory.create(fresh)
            if (!fresh.isLive && position > 0) player.setMediaItem(item, position) else player.setMediaItem(item)
            // Playing or paused, as it was.
            player.prepare()
            toLivePoint = false
            val at = liveAt ?: return@launch
            while (!LivePlayback.seekToTimeIfReady(player, window, at, liveIfGone = true)) delay(SEEK_POLL_MS)
            liveAt = null
        }
    }
}
