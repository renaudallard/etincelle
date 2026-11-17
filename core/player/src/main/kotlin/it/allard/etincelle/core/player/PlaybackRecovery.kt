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

// The wait before each attempt after the first, which grows up to a cap.
private const val RETRY_BACKOFF_MS = 1_500L
private const val RETRY_MAX_BACKOFF_MS = 2_000L

// Playback that held this long before failing again is a new incident, with all its attempts.
private const val RETRY_RESET_MS = 60_000L

// How often a reloaded live stream is checked for being ready to seek.
private const val SEEK_POLL_MS = 250L

/**
 * Brings back the playback of [source] on [player] when it fails: a player left on its error shows a
 * frozen picture and nothing else. The stream is asked again with [resolve] (null when there is none to
 * be had), as its address or its licence may be what went stale, and played from where it was: the
 * position of a replay, the time of day of a live programme watched behind the live point. After
 * [MAX_RECOVERIES] failures in a row [onGiveUp] is called. Main thread only; [stop] ends it.
 */
class PlaybackRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    private val source: PlaybackSource,
    private val resolve: suspend (PlaybackSource) -> PlaybackSource?,
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

    override fun onPlayerError(error: PlaybackException) = recover(error.errorCode)

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) readyAt = SystemClock.elapsedRealtime()
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
        val position = player.currentPosition
        // Fills [window] when a live stream is loaded.
        val behind = LivePlayback.behindLiveEdgeMs(player, window)
        when {
            // What was playing left the stream's rewind window: only the live point is sure to play.
            errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> liveAt = null
            behind > LivePlayback.LIVE_REWIND_THRESHOLD_MS && window.windowStartTimeMs != C.TIME_UNSET ->
                liveAt = window.windowStartTimeMs + position
        }
        val backoff = ((attempts - 1) * RETRY_BACKOFF_MS).coerceAtMost(RETRY_MAX_BACKOFF_MS)
        job?.cancel()
        job = scope.launch {
            if (backoff > 0) delay(backoff)
            // No stream to be had is one more failure.
            val fresh = resolve(source) ?: return@launch recover(PlaybackException.ERROR_CODE_UNSPECIFIED)
            val item = MediaItemFactory.create(fresh)
            if (!fresh.isLive && position > 0) player.setMediaItem(item, position) else player.setMediaItem(item)
            // Playing or paused, as it was.
            player.prepare()
            val at = liveAt ?: return@launch
            while (!LivePlayback.seekToTimeIfReady(player, window, at)) delay(SEEK_POLL_MS)
            liveAt = null
        }
    }
}
