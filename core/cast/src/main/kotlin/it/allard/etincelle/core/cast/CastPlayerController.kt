// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.player.MediaItemFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// A live DASH DVR window spans at most a few hours; this is comfortably past any of them, so a load
// at this position clamps to the receiver's live edge.
private const val LIVE_EDGE_POSITION_MS = 24L * 60 * 60 * 1000

private const val PLAYBACK_ERROR_MESSAGE = "Lecture impossible, réessayez"

/**
 * Plays a stream on the phone ([ExoPlayer]) or a Chromecast ([CastPlayer]), swapping between them
 * when a Cast session connects/disconnects and carrying the item + position across the swap. The UI
 * binds to [currentPlayer] (and shows a "casting to…" placeholder while remote).
 *
 * @param reResolve re-fetches a fresh [PlaybackSource] (new tokens/URL) for the playing item; run on
 *   each player swap and once more on a recoverable playback error (e.g. an expired token).
 * @param onError surfaces an unrecoverable playback error message to the user.
 */
@UnstableApi
class CastPlayerController(
    context: Context,
    castContext: CastContext,
    private val localPlayer: ExoPlayer,
    private val reResolve: (suspend (PlaybackSource) -> PlaybackSource?)? = null,
    private val onError: ((String) -> Unit)? = null,
    sessionProvider: () -> UserSession? = { null },
) : CastController(context, castContext) {

    private val castPlayer = CastPlayer(castContext, FuboCastMediaItemConverter(context, sessionProvider))
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _currentPlayer = MutableStateFlow<Player>(localPlayer)
    val currentPlayer: StateFlow<Player> = _currentPlayer.asStateFlow()

    private var currentItem: MediaItem? = null
    private var endingPosition = 0L
    private var endingPlayWhenReady = true
    private var endingCaptured = false
    private var hasRetried = false
    private var reResolveJob: Job? = null

    // True once the player screen was left while a Cast session was running, so a later session end
    // stops quietly instead of popping the player back over whatever the user is now browsing.
    private var leftPlayerWhileCasting = false

    // A transfer keeps the old player running until the new one starts; these track that pending stop.
    private var deferStopSource: Player? = null
    private var deferStopTarget: Player? = null
    private var deferStopListener: Player.Listener? = null

    // A Chromecast -> Chromecast switch: the old session ends before the new one connects, so capture
    // the position up front, hold it across the gap, and load it on the new device when it connects
    // (instead of bouncing playback back to the phone in between).
    private var transferring = false
    private var transferPosition = 0L
    private var transferPlayWhenReady = true
    private var transferFallbackJob: Job? = null

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = onPlaybackError()
    }

    init {
        localPlayer.addListener(errorListener)
        castPlayer.addListener(errorListener)
    }

    /** Load + play [item] on whichever player is current (phone or cast). */
    fun play(item: MediaItem, startPositionMs: Long) {
        reResolveJob?.cancel()
        hasRetried = false
        leftPlayerWhileCasting = false
        transferring = false
        transferFallbackJob?.cancel()
        loadOn(_currentPlayer.value, item, startPositionMs)
    }

    /**
     * Route to a Cast device. When already casting, this is a device-to-device switch: capture the
     * current position/state so the new device resumes there, and flag a transfer so the old
     * session's end does not bounce playback back to the phone.
     */
    override fun connectTo(routeId: String) {
        if (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null) {
            transferPosition = castPlayer.currentPosition
            transferPlayWhenReady = castPlayer.playWhenReady
            transferring = true
        }
        super.connectTo(routeId)
    }

    /**
     * Stop local playback and return (position, duration) for resume persistence. Returns null while
     * casting: leaving the player screen must keep the stream running on the Chromecast.
     */
    fun stopPlayback(): Pair<Long, Long>? {
        if (_currentPlayer.value === castPlayer) {
            // Leaving the player while casting keeps the TV stream running; remember it was left.
            leftPlayerWhileCasting = true
            return null
        }
        reResolveJob?.cancel()
        clearDeferredStop()
        val player = _currentPlayer.value
        val pos = player.currentPosition
        val dur = player.duration
        player.stop()
        player.clearMediaItems()
        currentItem = null
        return pos to dur
    }

    fun release() {
        clearDeferredStop()
        localPlayer.removeListener(errorListener)
        castPlayer.removeListener(errorListener)
        scope.cancel()
        castPlayer.release()
    }

    override fun onSessionConnected(session: CastSession) {
        super.onSessionConnected(session)
        if (transferring) {
            // The new Chromecast connected: load the held stream on it at the captured position,
            // re-resolving for fresh tokens. The old receiver was stopped by the session switch.
            transferring = false
            transferFallbackJob?.cancel()
            endingCaptured = false
            reResolveJob?.cancel()
            hasRetried = false
            clearDeferredStop()
            val item = currentItem ?: return
            loadResolved(castPlayer, item, transferPosition, transferPlayWhenReady)
            return
        }
        // Re-resolve when casting out too: the local stream may have been playing for a while and
        // its tokens are stale, so the receiver needs a freshly minted URL to load.
        swapTo(castPlayer, localPlayer.currentPosition, localPlayer.playWhenReady)
    }

    override fun onSessionEndingInternal(session: CastSession) {
        // The cast player's position is unreadable once the session has fully ended, so grab it now.
        endingPosition = castPlayer.currentPosition
        endingPlayWhenReady = castPlayer.playWhenReady
        endingCaptured = true
    }

    override fun onSessionDisconnected() {
        super.onSessionDisconnected()
        if (transferring) {
            // Device-to-device switch in flight: the old session ended; keep the cast player current
            // and wait for the new device to connect. If it never does (transfer failed), fall back
            // to the phone at the captured position so playback is not lost.
            endingCaptured = false
            transferFallbackJob?.cancel()
            transferFallbackJob = scope.launch {
                delay(8000)
                if (transferring) {
                    transferring = false
                    swapTo(localPlayer, transferPosition, transferPlayWhenReady)
                }
            }
            return
        }
        // The suspend and failure paths never fire onSessionEnding, so capture the position here
        // instead of trusting a stale value from the last clean end.
        if (!endingCaptured) {
            endingPosition = castPlayer.currentPosition
            endingPlayWhenReady = castPlayer.playWhenReady
        }
        endingCaptured = false
        if (leftPlayerWhileCasting) {
            // The user had left the player to keep casting; the session ended, so stop quietly rather
            // than resuming on the phone or popping the player over what they are now browsing.
            leftPlayerWhileCasting = false
            clearDeferredStop()
            runCatching { castPlayer.stop() }
            runCatching { localPlayer.stop() }
            currentItem = null
            _currentPlayer.value = localPlayer
            return
        }
        // Re-resolve when coming back to the phone: the cast may have run for a while and the
        // original (live) URL is stale, so a fresh resolve gives the current edge.
        swapTo(localPlayer, endingPosition, endingPlayWhenReady)
    }

    private fun swapTo(target: Player, fromPosition: Long, fromPlayWhenReady: Boolean) {
        val from = _currentPlayer.value
        if (from === target) return
        reResolveJob?.cancel()
        // Keep `from` playing until `target` starts, so the hand-off does not blank out the picture;
        // deferStop cuts the old screen once the new one is actually playing.
        clearDeferredStop()
        _currentPlayer.value = target
        hasRetried = false
        val item = currentItem ?: run { runCatching { from.stop() }; return }
        loadResolved(target, item, fromPosition, fromPlayWhenReady)
        deferStop(from, target)
    }

    // Load [item] on [target] at [fromPosition] (a live stream clamps to the edge), re-resolving for
    // a fresh token/URL first when possible. Shared by player swaps and Chromecast->Chromecast moves.
    private fun loadResolved(target: Player, item: MediaItem, fromPosition: Long, fromPlayWhenReady: Boolean) {
        val source = item.localConfiguration?.tag as? PlaybackSource
        val position = if (source?.isLive == true) 0 else fromPosition
        val resolver = reResolve
        if (source != null && resolver != null) {
            reResolveJob = scope.launch {
                val fresh = runCatching { resolver(source) }.getOrNull()
                ensureActive()
                // Bail if a later swap/stop superseded this one while we were resolving.
                if (target !== _currentPlayer.value || currentItem == null) return@launch
                val freshItem = if (fresh != null) MediaItemFactory.create(fresh) else item
                loadOn(target, freshItem, if ((fresh ?: source).isLive) 0 else position)
                target.playWhenReady = fromPlayWhenReady
            }
        } else {
            loadOn(target, item, position)
            target.playWhenReady = fromPlayWhenReady
        }
    }

    // Stop `from` only once `target` is actually playing (or immediately if it already is).
    private fun deferStop(from: Player, target: Player) {
        if (from === target) return
        if (target.isPlaying) {
            runCatching { from.stop() }
            return
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) clearDeferredStop()
            }

            // STATE_READY covers targets that buffer the first frame without ever flipping isPlaying,
            // so the hand-off still cuts the old stream instead of leaving it decoding behind it.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) clearDeferredStop()
            }
        }
        deferStopSource = from
        deferStopTarget = target
        deferStopListener = listener
        target.addListener(listener)
    }

    // Cut the pending source now (the target started, or a new swap superseded this one).
    private fun clearDeferredStop() {
        deferStopListener?.let { deferStopTarget?.removeListener(it) }
        deferStopSource?.let { runCatching { it.stop() } }
        deferStopSource = null
        deferStopTarget = null
        deferStopListener = null
    }

    private fun loadOn(player: Player, item: MediaItem, positionMs: Long) {
        currentItem = item
        val isLive = (item.localConfiguration?.tag as? PlaybackSource)?.isLive == true
        // CastPlayer loads live DASH at the start of the receiver's DVR window (currentTime=0); ask
        // for a position well past the window so the receiver clamps to the live edge. ExoPlayer
        // already starts live at the edge, so it keeps the caller's position.
        val effectivePos = if (player === castPlayer && isLive) LIVE_EDGE_POSITION_MS else positionMs
        if (effectivePos > 0) player.setMediaItem(item, effectivePos) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    private fun onPlaybackError() {
        val source = currentItem?.localConfiguration?.tag as? PlaybackSource
        val resolver = reResolve
        if (source == null || resolver == null || hasRetried) {
            // Giving up on the target: cut any source still kept alive for the hand-off so it does
            // not keep decoding once there is nothing taking over.
            clearDeferredStop()
            onError?.invoke(PLAYBACK_ERROR_MESSAGE)
            return
        }
        hasRetried = true
        val target = _currentPlayer.value
        val position = target.currentPosition.coerceAtLeast(0)
        reResolveJob?.cancel()
        reResolveJob = scope.launch {
            val fresh = runCatching { resolver(source) }.getOrNull()
            ensureActive()
            // Bail if a swap/stop superseded this stream while we were resolving.
            if (target !== _currentPlayer.value || currentItem == null) return@launch
            if (fresh == null) {
                onError?.invoke(PLAYBACK_ERROR_MESSAGE)
                return@launch
            }
            loadOn(target, MediaItemFactory.create(fresh), if (fresh.isLive) 0 else position)
        }
    }
}
