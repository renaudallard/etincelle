// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.remote.RemoteMessages
import org.json.JSONObject

/**
 * A media3 [Player] for the etincelle TV app driven by [TvRemoteSession], so the player screen, its
 * controls and the media notification drive the TV as they drive a Chromecast. State comes from the
 * TV's status messages, commands go back as pause, resume and seek. On a live stream the TV reports
 * its position within the rewind window, which this player shows as a dynamic window. The TV only
 * sends a status when something changed or after a while (see StatusPacer): in between, this player
 * works out where playback has got to by itself. Main thread only.
 */
@UnstableApi
internal class TvRemotePlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    private var session: TvRemoteSession? = null
    private var item: MediaItem? = null

    // The TV's last status, and when it arrived (elapsedRealtime ms).
    private var state = ""
    private var title = ""
    private var positionMs = 0L
    private var durationMs = -1L
    private var live = false
    private var windowStartMs = -1L
    private var updatedAt = 0L

    // Statuses are seconds apart; refresh the state between them so the position keeps moving.
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (session == null) return
            invalidateState()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun attach(session: TvRemoteSession) {
        this.session = session
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    /** The item now sent to the TV (the phone's [MediaItem], with its PlaybackSource tag). */
    fun setItem(item: MediaItem) {
        this.item = item
        state = RemoteMessages.STATE_BUFFERING
        title = item.mediaMetadata.title?.toString().orEmpty()
        positionMs = 0
        durationMs = -1
        live = false
        windowStartMs = -1
        updatedAt = SystemClock.elapsedRealtime()
        invalidateState()
    }

    /** The TV plays something other than what this phone sent: show it by its title alone. */
    fun showTvContent() {
        item = MediaItem.Builder().setMediaId(TV_ITEM_ID).build()
        invalidateState()
    }

    fun detach() {
        handler.removeCallbacks(ticker)
        session = null
        item = null
        state = ""
        invalidateState()
    }

    /** A status message from the TV. */
    fun onStatus(status: JSONObject) {
        state = status.optString(RemoteMessages.STATE)
        // Something started on the TV itself, not from this phone: show it all the same, by its title.
        if (item == null && state.isNotEmpty() && state != RemoteMessages.STATE_IDLE) {
            item = MediaItem.Builder().setMediaId(TV_ITEM_ID).build()
        } else if (item?.mediaId == TV_ITEM_ID && state == RemoteMessages.STATE_IDLE) {
            item = null
        }
        status.optString(RemoteMessages.TITLE).takeIf { it.isNotEmpty() }?.let { title = it }
        positionMs = status.optLong(RemoteMessages.POSITION).coerceAtLeast(0)
        durationMs = status.optLong(RemoteMessages.DURATION, -1)
        live = status.optBoolean(RemoteMessages.LIVE)
        windowStartMs = status.optLong(RemoteMessages.WINDOW_START, -1)
        updatedAt = SystemClock.elapsedRealtime()
        invalidateState()
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setSeekBackIncrementMs(LivePlayback.SEEK_BACK_MS)
            .setSeekForwardIncrementMs(LivePlayback.SEEK_FORWARD_MS)
        val item = item
        if (session == null || item == null || state.isEmpty()) return builder.setPlaybackState(STATE_IDLE).build()
        val metadata = item.mediaMetadata.buildUpon().setTitle(title).build()
        val data = MediaItemData.Builder(item).setMediaItem(item).setMediaMetadata(metadata).setIsSeekable(true)
        val position: Long
        val elapsed = SystemClock.elapsedRealtime() - updatedAt
        val playing = state == RemoteMessages.STATE_PLAYING
        if (live && durationMs > 0) {
            // The window of a live stream slides with the clock: the position in it stays put while
            // playing, and falls back while paused.
            data.setLiveConfiguration(MediaItem.LiveConfiguration.UNSET)
                .setIsDynamic(true)
                .setDurationUs(durationMs * 1000)
                .setDefaultPositionUs(durationMs * 1000)
                .setWindowStartTimeMs(if (windowStartMs > 0) windowStartMs + elapsed else System.currentTimeMillis() - durationMs)
            position = (if (playing) positionMs else positionMs - elapsed).coerceIn(0L, durationMs)
        } else {
            if (durationMs > 0) data.setDurationUs(durationMs * 1000)
            position = (if (playing) positionMs + elapsed else positionMs).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        }
        builder.setPlaylist(listOf(data.build())).setContentPositionMs(position)
        when (state) {
            RemoteMessages.STATE_PLAYING -> builder.setPlaybackState(STATE_READY).setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            RemoteMessages.STATE_PAUSED -> builder.setPlaybackState(STATE_READY).setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            RemoteMessages.STATE_BUFFERING -> builder.setPlaybackState(STATE_BUFFERING).setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            RemoteMessages.STATE_ENDED -> builder.setPlaybackState(STATE_ENDED)
            else -> builder.setPlaybackState(STATE_IDLE)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val session = session ?: return Futures.immediateVoidFuture()
        // Show the change at once; the TV confirms in its next status.
        settle()
        state = if (playWhenReady) RemoteMessages.STATE_PLAYING else RemoteMessages.STATE_PAUSED
        session.send(JSONObject().put(RemoteMessages.TYPE, if (playWhenReady) RemoteMessages.RESUME else RemoteMessages.PAUSE))
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val session = session ?: return Futures.immediateVoidFuture()
        // An unset position is the default one: the live edge, or the start of on-demand content.
        val target = if (positionMs == C.TIME_UNSET) -1L else positionMs.coerceAtLeast(0)
        session.send(JSONObject().put(RemoteMessages.TYPE, RemoteMessages.SEEK).put(RemoteMessages.POSITION, target))
        settle()
        this.positionMs = if (target < 0) durationMs.coerceAtLeast(0) else target
        return Futures.immediateVoidFuture()
    }

    // Takes where playback was worked out to be by now as what the TV last said, before changing it.
    private fun settle() {
        val now = SystemClock.elapsedRealtime()
        positionMs = currentPosition
        if (live && windowStartMs > 0) windowStartMs += now - updatedAt
        updatedAt = now
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacks(ticker)
        session = null
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val TICK_MS = 1_000L
        // The item standing for what the TV plays when this phone did not send it.
        const val TV_ITEM_ID = "etincelle-tv"

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
