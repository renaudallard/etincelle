// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import it.allard.etincelle.core.player.LivePlayback
import org.json.JSONObject

/**
 * A media3 [Player] for a cast driven by [DirectCastSession], so the player screen, its controls and
 * [LivePlayback] work as with media3's CastPlayer. State comes from the receiver's MEDIA_STATUS
 * messages; commands go back as PLAY, PAUSE and SEEK. Stopping is the controller's business (it owns
 * the session), so this player offers no stop command.
 *
 * On a live stream the receiver counts time from an arbitrary origin and reports the seekable window
 * as `liveSeekableRange`, whose end is the live edge. Positions here are relative to the start of that
 * window, and the window's wall-clock start is estimated from the phone's clock, the edge being now.
 * Main thread only.
 */
@UnstableApi
internal class DirectCastPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var session: DirectCastSession? = null
    private var item: MediaItem? = null

    // The receiver's last reported state, and when it arrived (elapsedRealtime ms).
    private var playerState = ""
    private var idleReason = ""
    private var times = ReceiverTimes(0.0, 0.0, 0.0)
    private var movingWindow = false
    private var live = false
    private var duration = 0.0
    private var updatedAt = 0L

    // How far behind the live edge (ms) a rewound live cast should resume. The receiver is told too
    // (live_rewind_sec), but a seek made before the player has joined the live edge is lost, so it is
    // done from here once the TV plays and has joined (see ReceiverTimes.joinedLive).
    private var pendingRewindMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    // Statuses only come on changes: ask for one regularly so the position does not drift, and refresh
    // the state every second so a live window keeps moving between them.
    private val ticker = object : Runnable {
        override fun run() {
            if (session == null) return
            if (++ticks % POLL_EVERY_TICKS == 0) session?.requestStatus()
            invalidateState()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private var ticks = 0

    fun attach(session: DirectCastSession) {
        this.session = session
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    /** The item now loaded on the receiver (the phone's [MediaItem], with its PlaybackSource tag). */
    fun setItem(item: MediaItem, liveRewindMs: Long = 0L) {
        this.item = item
        pendingRewindMs = liveRewindMs
        playerState = "BUFFERING"
        idleReason = ""
        live = false
        duration = 0.0
        times = ReceiverTimes(0.0, 0.0, 0.0)
        updatedAt = SystemClock.elapsedRealtime()
        invalidateState()
    }

    fun detach() {
        handler.removeCallbacks(ticker)
        session = null
        item = null
        pendingRewindMs = 0
        playerState = ""
        invalidateState()
    }

    /** A MEDIA_STATUS entry from the receiver; an empty object when nothing is loaded. */
    fun onStatus(status: JSONObject) {
        status.optJSONObject("media")?.let { media ->
            live = media.optString("streamType") == "LIVE"
            duration = media.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
        }
        // While it loads an item the receiver says IDLE (INTERRUPTED when another was playing) and
        // gives the loading away in its extended status only (seen on a Chromecast with Google TV).
        val loading = status.optJSONObject("extendedStatus")?.optString("playerState") == "LOADING"
        playerState = status.optString("playerState").let { if (it == "IDLE" && loading) "LOADING" else it }
        idleReason = status.optString("idleReason")
        var start = times.start
        var end = times.end
        status.optJSONObject("liveSeekableRange")?.let { range ->
            live = true
            start = range.optDouble("start", 0.0)
            end = range.optDouble("end", 0.0)
            movingWindow = range.optBoolean("isMovingWindow", true)
        }
        times = ReceiverTimes(status.optDouble("currentTime", times.time), start, end)
        updatedAt = SystemClock.elapsedRealtime()
        if (pendingRewindMs > 0 && live && playerState == "PLAYING" && times.windowMs > 0 && times.joinedLive) {
            val target = times.liveSeekTarget(times.windowMs - pendingRewindMs)
            pendingRewindMs = 0
            session?.seek(target)
            times = times.copy(time = target)
        }
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setSeekBackIncrementMs(LivePlayback.SEEK_BACK_MS)
            .setSeekForwardIncrementMs(LivePlayback.SEEK_FORWARD_MS)
        val item = item
        if (session == null || item == null || playerState.isEmpty()) {
            return builder.setPlaybackState(STATE_IDLE).build()
        }
        val now = advanced(SystemClock.elapsedRealtime())
        val data = MediaItemData.Builder(item).setMediaItem(item).setIsSeekable(true)
        val positionMs: Long
        if (live && now.windowMs > 0) {
            val windowMs = now.windowMs
            data.setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
                .setIsDynamic(true)
                .setDurationUs(windowMs * 1000)
                .setDefaultPositionUs(windowMs * 1000)
                .setWindowStartTimeMs(System.currentTimeMillis() - windowMs)
            positionMs = now.livePositionMs
        } else {
            if (duration > 0) data.setDurationUs((duration * 1_000_000).toLong())
            positionMs = (now.time * 1000).toLong().coerceAtLeast(0)
        }
        builder.setPlaylist(listOf(data.build())).setContentPositionMs(positionMs)

        when (playerState) {
            "PLAYING" -> builder.setPlaybackState(STATE_READY).setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            "PAUSED" -> builder.setPlaybackState(STATE_READY).setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            "BUFFERING", "LOADING" -> builder.setPlaybackState(STATE_BUFFERING).setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            else -> when (idleReason) {
                "FINISHED" -> builder.setPlaybackState(STATE_ENDED)
                "ERROR" -> builder.setPlaybackState(STATE_IDLE)
                    .setPlayerError(PlaybackException("cast receiver error", null, PlaybackException.ERROR_CODE_REMOTE_ERROR))
                else -> builder.setPlaybackState(STATE_IDLE)
            }
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val session = session ?: return Futures.immediateVoidFuture()
        // Show the change at once (the receiver confirms in its next status).
        freeze()
        if (playWhenReady) {
            session.play()
            playerState = "PLAYING"
        } else {
            session.pause()
            playerState = "PAUSED"
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val session = session ?: return Futures.immediateVoidFuture()
        freeze()
        // An unset position is the default one: the live edge, or the start of on-demand content.
        val target = when {
            live -> times.liveSeekTarget(positionMs)
            positionMs == C.TIME_UNSET -> 0.0
            else -> positionMs / 1000.0
        }
        session.seek(target)
        times = times.copy(time = target)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(ticker)
        session = null
        return Futures.immediateVoidFuture()
    }

    // The receiver's times at [now] (elapsedRealtime ms), from the last status.
    private fun advanced(now: Long) =
        times.advance((now - updatedAt) / 1000.0, playing = playerState == "PLAYING", moving = live && movingWindow)

    // Fold the time elapsed since the last status into the model, before changing it locally.
    private fun freeze() {
        val now = SystemClock.elapsedRealtime()
        times = advanced(now)
        updatedAt = now
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val POLL_EVERY_TICKS = 5

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SEEK_BACK,
            COMMAND_SEEK_FORWARD,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
        ).build()
    }
}

/**
 * The receiver's playhead [time] and live window [[start], [end]], in its own seconds (the end is the
 * live edge), with the arithmetic the player needs on them.
 */
internal data class ReceiverTimes(val time: Double, val start: Double, val end: Double) {
    /** The window length in ms. */
    val windowMs: Long get() = ((end - start) * 1000).toLong().coerceAtLeast(0)

    /** The playhead as a position (ms) in the live window. */
    val livePositionMs: Long get() = ((time - start) * 1000).toLong().coerceIn(0, windowMs)

    /**
     * The player has joined the live stream. Replacing a playing stream, the receiver reports PLAYING
     * while still parked at the start of the window, then jumps to the live edge, undoing any seek made
     * before that jump. Our receiver waits for the same sign before applying live_rewind_sec.
     */
    val joinedLive: Boolean get() = time > start + JOIN_MARGIN_S

    /** These times [elapsed] seconds later: the playhead moves when [playing], the window when [moving]. */
    fun advance(elapsed: Double, playing: Boolean, moving: Boolean) = ReceiverTimes(
        time + if (playing) elapsed else 0.0,
        start + if (moving) elapsed else 0.0,
        end + if (moving) elapsed else 0.0,
    )

    /** The receiver time to seek to for [positionMs] in the live window; unset means the live edge. */
    fun liveSeekTarget(positionMs: Long): Double =
        // A range the receiver gave the wrong way round, or only one end of, has no inside to seek in.
        if (positionMs == C.TIME_UNSET || end <= start) end else (start + positionMs / 1000.0).coerceIn(start, end)

    private companion object {
        // As REWIND_JOIN_MARGIN in the receiver.
        const val JOIN_MARGIN_S = 5.0
    }
}
