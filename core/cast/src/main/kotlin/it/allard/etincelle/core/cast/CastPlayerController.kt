// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import android.os.SystemClock
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.player.LivePlayback
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

// How long to wait for the new device to connect during a Chromecast-to-Chromecast transfer before
// falling back to the phone.
private const val TRANSFER_TIMEOUT_MS = 8000L

// Safety-net cap on the connect animation: if a session connects but never reports playback within
// this window, stop showing "Connexion à …" so the bar cannot stick. Generously past a real DRM
// handshake (seen around fifteen seconds) so it never trips a legitimate connect.
private const val CONNECT_TIMEOUT_MS = 30_000L

private const val PLAYBACK_ERROR_MESSAGE = "Lecture impossible, réessayez"

// Recover a failing cast by re-resolving fresh credentials (the receiver cannot refresh its own
// short-lived stream/DRM tokens, so reloading them on the receiver loops forever). Retry up to this
// many times per failure burst, backing off between tries, before surfacing an error.
private const val MAX_CAST_RECOVERIES = 5
private const val CAST_RETRY_BACKOFF_MS = 1500L
// Cap the backoff well under the receiver's reconnect grace window so this fresh cast lands before the
// receiver starts its own (stale-token) reload.
private const val CAST_RETRY_MAX_BACKOFF_MS = 2000L
// Failures more than this far apart are separate incidents, not one burst: restore the retry budget so
// a long cast that self-heals occasionally is never eventually refused recovery.
private const val CAST_RETRY_RESET_MS = 60_000L

// media3's CastPlayer never reports a receiver-side failure as a player error, so the only way the
// phone can notice a wedged cast (a stalled load, or a mid-cast token expiry the receiver cannot
// refresh) is to watch it. Sample this often, and treat the receiver as stuck after this much
// continuous buffering while it should be playing, then re-resolve a fresh stream and reload. The
// threshold is generously past any normal rebuffer so a healthy cast is never disturbed.
private const val LIVENESS_INTERVAL_MS = 5_000L
private const val LIVENESS_STALL_MS = 30_000L

// One volume-key press steps the cast device volume by this fraction (the Cast SDK default).
private const val VOLUME_STEP = 0.05

/**
 * Plays a stream on the phone ([ExoPlayer]) or a Chromecast ([CastPlayer]), swapping between them
 * when a Cast session connects/disconnects and carrying the item + position across the swap. The UI
 * binds to [currentPlayer] (and shows a "casting to…" placeholder while remote).
 *
 * @param reResolve re-fetches a fresh [PlaybackSource] (new tokens/URL) for the playing item; run on
 *   each player swap and once more on a recoverable playback error (e.g. an expired token).
 * @param onError surfaces an unrecoverable playback error message to the user.
 * @param onPlaybackStopped fires when a Cast session ended without an explicit return-to-this-phone,
 *   so the UI can drop its "playing" state instead of re-surfacing the local player over browsing.
 */
@UnstableApi
class CastPlayerController(
    context: Context,
    castContext: CastContext,
    private val localPlayer: ExoPlayer,
    private val reResolve: (suspend (PlaybackSource) -> PlaybackSource?)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onPlaybackStopped: (() -> Unit)? = null,
    sessionProvider: () -> UserSession? = { null },
) : CastController(context, castContext) {

    private val castPlayer = CastPlayer(castContext, FuboCastMediaItemConverter(context, sessionProvider))
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // Scratch window for measuring how far the local player is behind the live edge at cast-out.
    private val liveWindow = Timeline.Window()

    private val _currentPlayer = MutableStateFlow<Player>(localPlayer)
    val currentPlayer: StateFlow<Player> = _currentPlayer.asStateFlow()

    private var currentItem: MediaItem? = null
    private var endingPosition = 0L
    private var endingPlayWhenReady = true
    private var endingCaptured = false
    private var castRetryCount = 0
    private var lastCastErrorAt = 0L
    private var reResolveJob: Job? = null

    // True only when the user explicitly asked to bring playback back to this phone ("Cet appareil"):
    // the session end then resumes locally and shows the player. Every other end (TV off, external
    // stop, logout, failed transfer) stops quietly and clears the UI's playing state instead.
    private var returnToPhoneOnEnd = false

    // Last cast volume we set (0..1), so rapid volume-key presses step from our own value rather than
    // a stale read of the receiver's lagging reported volume. Reset on each session end.
    private var castVolume: Double? = null

    // Safety net so the connect animation can never stick on "Connexion à …" if the receiver connects
    // but never reports playback (e.g. a stalled load that neither errors nor drops the session).
    private var connectWatchdogJob: Job? = null

    // The session is transiently suspended (a network blip the design rides out). media3's CastPlayer
    // FREEZES its playback state across a suspend, so the liveness/connect recovery must stand down
    // while suspended or it would mistake the frozen "buffering" state for a wedge and tear down a cast
    // that is about to resume. Set on suspend, cleared on (re)connect / end.
    private var suspended = false

    // Whether the receiver has reported ready/playing since the current connect was armed. The connect
    // watchdog only recovers a receiver that NEVER became ready; a healthy cast that played then paused
    // must be left alone (recovering it would re-resolve and force play, un-pausing it).
    private var receiverEverReady = false

    // A transfer keeps the old player running until the new one starts; these track that pending stop.
    private var deferStopSource: Player? = null
    private var deferStopTarget: Player? = null
    private var deferStopListener: Player.Listener? = null

    // A Chromecast -> Chromecast switch: the old session ends before the new one connects, so capture
    // the position up front, hold it across the gap, and load it on the new device when it connects
    // (instead of bouncing playback back to the phone in between).
    private var transferring = false
    private var transferPosition = 0L
    private var transferRewindMs = 0L
    private var transferPlayWhenReady = true
    private var transferFallbackJob: Job? = null
    // The id of the Cast session our content is currently loaded on. A connecting session with a
    // different id is a genuinely new receiver (a device switch or a reconnect) and gets our content
    // loaded onto it; the same id is just the session resuming, so nothing is reloaded.
    private var castSessionId: String? = null

    // Per-player so onPlaybackError knows WHICH player failed: a local-player error during a cast-out
    // hand-off (while the cast player is already current) must not be misread as a cast failure.
    private fun errorListenerFor(player: Player) = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = onPlaybackError(player)
    }
    private val localErrorListener = errorListenerFor(localPlayer)
    private val castErrorListener = errorListenerFor(castPlayer)

    // Drives the connect animation: the bar fills while connecting and snaps full once the receiver
    // actually plays. Only meaningful while the cast player is current.
    private val castPlaybackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_currentPlayer.value === castPlayer) {
                if (isPlaying) receiverEverReady = true
                setReceiverPlaying(isPlaying)
            }
        }

        // Once the receiver has the media ready the connect handshake is done, even if it has not
        // flipped to actively playing yet (still buffering): stop the connecting animation either way.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (_currentPlayer.value === castPlayer && playbackState == Player.STATE_READY) {
                receiverEverReady = true
                setReceiverPlaying(true)
            }
        }
    }

    init {
        localPlayer.addListener(localErrorListener)
        castPlayer.addListener(castErrorListener)
        castPlayer.addListener(castPlaybackListener)
        scope.launch { monitorCastLiveness() }
    }

    /** Load + play [item] on whichever player is current (phone or cast). */
    fun play(item: MediaItem, startPositionMs: Long) {
        // Nominally on the cast player but the session is gone (e.g. an abandoned transfer): drop back
        // to local first so the load below does not target a dead CastPlayer.
        if (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession == null) {
            clearDeferredStop()
            transferring = false
            transferFallbackJob?.cancel()
            castSessionId = null
            _currentPlayer.value = localPlayer
        }
        // Re-entering the player while this exact stream is already running on a Chromecast: leave it
        // alone instead of reloading and restarting it from the original position.
        if (_currentPlayer.value === castPlayer &&
            castContext.sessionManager.currentCastSession != null &&
            sameContent(currentItem, item)
        ) {
            return
        }
        reResolveJob?.cancel()
        castRetryCount = 0
        transferring = false
        transferFallbackJob?.cancel()
        loadOn(_currentPlayer.value, item, startPositionMs)
    }

    /**
     * Route to a Cast device. When already casting, this is a device-to-device switch: capture the
     * current position/state so the new device resumes there, and flag a transfer so the old
     * session's end does not bounce playback back to the phone.
     */
    override fun connectTo(routeId: String): Boolean {
        val willTransfer = _currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null
        val position = if (willTransfer) castPlayer.currentPosition else 0L
        val playWhenReady = if (willTransfer) castPlayer.playWhenReady else true
        // Capture the live rewind now, while the outgoing cast player still has a timeline, so a
        // rewound live stream keeps its position across the device switch.
        val rewind = if (willTransfer) LivePlayback.castRewindOffsetMs(castPlayer, liveWindow) else 0L
        val selected = super.connectTo(routeId)
        if (selected) {
            receiverEverReady = false
            startConnectWatchdog()
        }
        // Arm the transfer only once a route was actually selected; it just records the position the
        // new device should resume at (the actual load happens in onSessionConnected by session id).
        if (willTransfer && selected) {
            transferPosition = position
            transferRewindMs = rewind
            transferPlayWhenReady = playWhenReady
            transferring = true
        }
        return selected
    }

    private fun startConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            // Receiver already playing: nothing to do.
            if (state.value.playing) return@launch
            // The receiver NEVER became ready in the connect window (a wedged DRM handshake / a load it
            // silently never started): the CastPlayer will not raise this as an error, so recover it -
            // re-resolve a fresh stream and reload (and give up cleanly, ending the session, if recovery
            // is exhausted). A receiver that DID become ready and is merely paused/buffering, or a
            // suspend riding out a blip, is left alone (recovering would un-pause it). Either way, cap
            // the connect spinner so it can never outlive the deadline.
            if (!receiverEverReady && !suspended && _currentPlayer.value === castPlayer &&
                currentItem != null && castContext.sessionManager.currentCastSession != null
            ) {
                onPlaybackError(castPlayer)
            }
            clearConnecting()
        }
    }

    // While casting, a receiver that should be playing but sits buffering for a long stretch is wedged
    // (typically its short-lived stream/DRM token expired and it cannot refresh it): re-resolve a fresh
    // stream and reload so a stalled cast self-heals instead of sitting black indefinitely. Runs for the
    // controller's lifetime, idle unless the cast player is current with a live session.
    private suspend fun monitorCastLiveness() {
        var bufferingForMs = 0L
        while (true) {
            delay(LIVENESS_INTERVAL_MS)
            // Stand down while suspended: the CastPlayer freezes its state across a suspend, so a frozen
            // "buffering" would otherwise be mistaken for a wedge and tear down a cast about to resume.
            if (suspended || _currentPlayer.value !== castPlayer ||
                castContext.sessionManager.currentCastSession == null
            ) {
                bufferingForMs = 0L
                continue
            }
            // A healthy cast is STATE_READY; a user-paused one has playWhenReady=false. Only a session
            // that should be playing yet stays buffering counts toward a stall.
            if (castPlayer.playWhenReady && castPlayer.playbackState == Player.STATE_BUFFERING) {
                bufferingForMs += LIVENESS_INTERVAL_MS
                if (bufferingForMs >= LIVENESS_STALL_MS) {
                    bufferingForMs = 0L
                    onPlaybackError(castPlayer)
                }
            } else {
                bufferingForMs = 0L
            }
        }
    }

    override fun stop() {
        // Pause the fallback while backgrounded. The transfer needs no special survival logic: on
        // return, a connected new device is loaded by onSessionConnected (different session id) and a
        // failed one re-arms its fallback. Nothing here can mis-fire because no flag is force-cleared.
        transferFallbackJob?.cancel()
        super.stop()
    }

    /**
     * Bring playback back to this phone ("Cet appareil" in the picker): end the session and resume
     * locally at the cast position, re-surfacing the player. Distinct from [disconnect], which stops.
     */
    fun returnToThisDevice() {
        // No live session to end (it already tore down): do nothing. Arming returnToPhoneOnEnd with no
        // onSessionEnded coming would leave it set to mis-fire a return-to-phone on a later cast.
        if (castContext.sessionManager.currentCastSession == null) return
        returnToPhoneOnEnd = true
        transferring = false
        transferFallbackJob?.cancel()
        reResolveJob?.cancel()
        super.disconnect()
    }

    override fun disconnect() {
        // A plain disconnect (e.g. logout) stops casting without resuming on the phone; the session
        // end takes the quiet-stop path. Clear transfer/re-resolve so nothing ejects after teardown.
        returnToPhoneOnEnd = false
        transferring = false
        transferFallbackJob?.cancel()
        reResolveJob?.cancel()
        super.disconnect()
    }

    // True when [a] and [b] are the same content (by its stable origin ids), ignoring the per-resolve
    // URL/token differences.
    private fun sameContent(a: MediaItem?, b: MediaItem): Boolean {
        val sa = (a?.localConfiguration?.tag as? PlaybackSource) ?: return false
        val sb = (b.localConfiguration?.tag as? PlaybackSource) ?: return false
        if (sa.originChannelId == null && sa.originVodId == null && sa.originRecordingAssetId == null) return false
        return sa.originChannelId == sb.originChannelId &&
            sa.originVodId == sb.originVodId &&
            sa.originRecordingAssetId == sb.originRecordingAssetId
    }

    // Tear down to the phone without resuming playback (a Cast session ended and the user did not ask
    // to continue here, or a transfer failed).
    private fun stopQuietly() {
        clearDeferredStop()
        runCatching { castPlayer.stop() }
        runCatching { localPlayer.stop() }
        currentItem = null
        castSessionId = null
        _currentPlayer.value = localPlayer
    }

    /**
     * Stop local playback and return (position, duration) for resume persistence. Returns null while
     * casting (the stream stays on the Chromecast) or when nothing is loaded locally.
     */
    fun stopPlayback(): Pair<Long, Long>? {
        // Casting: leaving or switching the player keeps the TV stream running, nothing to save here.
        if (_currentPlayer.value === castPlayer) return null
        // Nothing is loaded locally (e.g. a cast session was just stopped quietly): nothing to save.
        if (currentItem == null) return null
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

    /**
     * Nudge the cast device volume one step (the phone's volume keys route here while casting). No-op
     * when no session is active. Publishes the new level so the UI can flash a brief overlay.
     */
    fun adjustCastVolume(up: Boolean) {
        val session = castContext.sessionManager.currentCastSession ?: return
        // Step from our own last value, not a fresh read: the receiver's reported volume lags the
        // writes, so reading it on every press would collapse a fast burst of presses into one step.
        val base = castVolume ?: runCatching { session.volume }.getOrDefault(0.0)
        val next = (base + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0.0, 1.0)
        castVolume = next
        // setVolume throws IOException if the session dropped mid-press; ignore and keep the UI level.
        // A volume press means the viewer wants sound, so clear any active mute too; otherwise the new
        // level would sit on a still-muted device and the overlay would show a level while it is silent.
        runCatching {
            session.volume = next
            if (session.isMute) session.isMute = false
        }
        publishVolume(next.toFloat())
    }

    /** Toggle mute on the cast device (the phone's mute key routes here while casting). */
    fun toggleCastMute() {
        val session = castContext.sessionManager.currentCastSession ?: return
        val muted = runCatching { session.isMute }.getOrDefault(false)
        runCatching { session.isMute = !muted }
        val level = if (!muted) 0f else (castVolume ?: runCatching { session.volume }.getOrDefault(0.0)).toFloat()
        publishVolume(level)
    }

    fun release() {
        clearDeferredStop()
        connectWatchdogJob?.cancel()
        localPlayer.removeListener(localErrorListener)
        castPlayer.removeListener(castErrorListener)
        castPlayer.removeListener(castPlaybackListener)
        scope.cancel()
        castPlayer.release()
    }

    // A transient suspend (network blip): note it so the stall recovery stands down until it resumes.
    override fun onSessionSuspendedInternal(session: CastSession) {
        suspended = true
    }

    override fun onSessionConnected(session: CastSession) {
        super.onSessionConnected(session)
        suspended = false
        if (_currentPlayer.value !== castPlayer) {
            // Casting out from the phone: swap to the cast player (re-resolves a fresh URL). Clear any
            // stale return-to-phone request so it cannot fire when this fresh session later ends.
            returnToPhoneOnEnd = false
            transferring = false
            transferFallbackJob?.cancel()
            castSessionId = session.sessionId
            swapTo(castPlayer, localPlayer.currentPosition, localPlayer.playWhenReady)
            return
        }
        // Already on the cast player. The same session resuming needs no reload; a different session id
        // is a new receiver (a device switch, or a reconnect after the old one dropped) - load onto it.
        if (session.sessionId == castSessionId) {
            // The current session resumed: any in-flight transfer is done, so clear the flag and the
            // fallback, else a later disconnect is misread as a transfer still in progress.
            transferring = false
            transferFallbackJob?.cancel()
            return
        }
        // A deliberate switch resumes at the captured position; an unexpected reconnect just clamps live
        // to the edge / starts VOD afresh (the old position is unreadable once the session is gone).
        val position = if (transferring) transferPosition else 0L
        val playWhenReady = if (transferring) transferPlayWhenReady else true
        // A deliberate switch keeps the live rewind; an unexpected reconnect drops it (the live edge
        // has moved on and the offset captured at connect would no longer line up).
        val rewind = if (transferring) transferRewindMs else 0L
        transferring = false
        transferFallbackJob?.cancel()
        endingCaptured = false
        reResolveJob?.cancel()
        castRetryCount = 0
        clearDeferredStop()
        castSessionId = session.sessionId
        // Nothing loaded to move onto the new session: the cleanup above still applies (so a later
        // disconnect is not misread as a transfer with a stale session id), but there is no item to load.
        val item = currentItem ?: return
        loadResolved(castPlayer, item, position, playWhenReady, rewind)
    }

    override fun onSessionEndingInternal(session: CastSession) {
        // The cast player's position is unreadable once the session has fully ended, so grab it now.
        endingPosition = castPlayer.currentPosition
        endingPlayWhenReady = castPlayer.playWhenReady
        endingCaptured = true
    }

    override fun onSessionDisconnected() {
        // Capture the in-flight transfer target name before super clears it, so the bar can keep
        // animating the connect toward the incoming device across the hand-off gap.
        val transferTargetName = state.value.connectingDeviceName
        super.onSessionDisconnected()
        suspended = false
        castSessionId = null
        castVolume = null
        // The connect watchdog is left to self-expire (it is a no-op once connecting is cleared); it
        // must NOT be cancelled here, or a device-to-device transfer would lose its safety net.
        // If we never actually swapped to the cast player there is nothing remote to wind down and no
        // local playback to disturb (a foreground tick with no session via start(), or a cast-out that
        // failed before connecting): leave local playback as it is. super() already cleared cast UI.
        if (_currentPlayer.value !== castPlayer) {
            transferring = false
            transferFallbackJob?.cancel()
            return
        }
        if (transferring) {
            // Device-to-device switch in flight: the old session ended; keep the cast player current
            // and the connect animation alive toward the new device, and wait for it to connect
            // (onSessionConnected loads it). If it never does (transfer failed), stop quietly.
            markConnecting(transferTargetName)
            endingCaptured = false
            transferFallbackJob?.cancel()
            transferFallbackJob = scope.launch {
                delay(TRANSFER_TIMEOUT_MS)
                if (transferring) {
                    // The new device did not connect in time: fall back to the phone at the captured
                    // position so the user-initiated transfer survives. A device that connects late
                    // then swaps onto its content from here instead of becoming an idle session.
                    transferring = false
                    clearConnecting()
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
        if (returnToPhoneOnEnd) {
            // The user asked to continue on this phone ("Cet appareil"): resume locally and re-surface
            // the player. Re-resolve gives a fresh (live edge) URL since the cast may have run a while.
            returnToPhoneOnEnd = false
            swapTo(localPlayer, endingPosition, endingPlayWhenReady)
            return
        }
        // Any other end (TV off, external stop, logout): stop, and tell the UI to drop its playing
        // state so the local player is not popped over whatever the user is now browsing.
        stopQuietly()
        onPlaybackStopped?.invoke()
    }

    private fun swapTo(target: Player, fromPosition: Long, fromPlayWhenReady: Boolean) {
        val from = _currentPlayer.value
        if (from === target) return
        reResolveJob?.cancel()
        // Keep `from` playing until `target` starts, so the hand-off does not blank out the picture;
        // deferStop cuts the old screen once the new one is actually playing.
        clearDeferredStop()
        _currentPlayer.value = target
        castRetryCount = 0
        // Nothing to load (e.g. casting was started with nothing playing): do not leave the connect
        // animation pulsing forever with no stream coming.
        val item = currentItem ?: run { runCatching { from.stop() }; clearConnecting(); return }
        // Casting out a rewound live stream: carry how far behind the edge the phone is so the
        // receiver resumes there instead of clamping to the live edge.
        val isLive = (item.localConfiguration?.tag as? PlaybackSource)?.isLive == true
        val castRewindMs = if (target === castPlayer && isLive) LivePlayback.castRewindOffsetMs(from, liveWindow) else 0L
        loadResolved(target, item, fromPosition, fromPlayWhenReady, castRewindMs)
        deferStop(from, target)
    }

    // Load [item] on [target] at [fromPosition] (a live stream clamps to the edge), re-resolving for
    // a fresh token/URL first when possible. Shared by player swaps and Chromecast->Chromecast moves.
    private fun loadResolved(
        target: Player,
        item: MediaItem,
        fromPosition: Long,
        fromPlayWhenReady: Boolean,
        castRewindMs: Long = 0L,
    ) {
        val source = item.localConfiguration?.tag as? PlaybackSource
        val position = if (source?.isLive == true) 0 else fromPosition
        val resolver = reResolve
        if (source != null && resolver != null) {
            reResolveJob = scope.launch {
                val fresh = runCatching { resolver(source) }.getOrNull()
                ensureActive()
                // Bail if a later swap/stop superseded this one while we were resolving.
                if (target !== _currentPlayer.value || currentItem == null) return@launch
                // Re-resolve failed while casting: do not push the stale item (its tokens are dead) to
                // the receiver; stop the connect animation and surface the error instead of leaving
                // the bar stuck on "Connexion à …" with a stream that will never load.
                if (fresh == null && target === castPlayer) {
                    // A transient re-resolve failure (a Wi-Fi blip, a backend 5xx / the documented 528)
                    // should not abort the whole cast on the first miss: consume one recovery attempt and
                    // retry after a backoff, mirroring the receiver-error path, before giving up.
                    if (castRetryCount < MAX_CAST_RECOVERIES) {
                        castRetryCount++
                        val backoff = ((castRetryCount - 1) * CAST_RETRY_BACKOFF_MS).coerceAtMost(CAST_RETRY_MAX_BACKOFF_MS)
                        if (backoff > 0) delay(backoff)
                        ensureActive()
                        if (target !== _currentPlayer.value || currentItem == null) return@launch
                        loadResolved(target, item, fromPosition, fromPlayWhenReady, castRewindMs)
                        return@launch
                    }
                    // Out of attempts: cut the source kept alive for the hand-off so it stops decoding
                    // behind a dead cast and its listener is not leaked, drop the item so a re-tap
                    // reloads, surface the error, and END the session so the cast bar stops claiming an
                    // active cast over a stream that never loaded (onSessionDisconnected clears the UI).
                    clearDeferredStop()
                    currentItem = null
                    clearConnecting()
                    onError?.invoke(PLAYBACK_ERROR_MESSAGE)
                    disconnect()
                    return@launch
                }
                // Carry the live rewind (if any) onto the fresh source so the receiver's customData
                // tells it where to resume.
                val freshItem = if (fresh != null) {
                    MediaItemFactory.create(fresh.copy(liveRewindOffsetMs = castRewindMs))
                } else {
                    item
                }
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

    private fun onPlaybackError(failedPlayer: Player) {
        // Ignore an error from a player that is no longer current (e.g. the local player being torn
        // down during a cast-out hand-off): recovering would reload the wrong, now-current player.
        if (failedPlayer !== _currentPlayer.value) return
        val source = currentItem?.localConfiguration?.tag as? PlaybackSource
        val resolver = reResolve
        // A failure long after the previous one is a fresh incident, not part of a burst: restore the
        // retry budget so a long cast that self-heals occasionally is never eventually refused recovery.
        val now = SystemClock.elapsedRealtime()
        if (now - lastCastErrorAt > CAST_RETRY_RESET_MS) castRetryCount = 0
        lastCastErrorAt = now
        if (source == null || resolver == null || castRetryCount >= MAX_CAST_RECOVERIES) {
            // Out of recovery attempts (or nothing to re-resolve): cut any source kept alive for the
            // hand-off so it stops decoding with nothing taking over, and stop the connect animation.
            clearDeferredStop()
            clearConnecting()
            onError?.invoke(PLAYBACK_ERROR_MESSAGE)
            // If the dead stream was on the cast player, end the session so the bar stops claiming an
            // active cast over playback that never recovered.
            if (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null) disconnect()
            return
        }
        castRetryCount++
        // Back off on each consecutive failure (capped) so a persistently broken stream is not hammered,
        // staying under the receiver's reconnect grace so its own stale-token reload does not fire first.
        val backoff = ((castRetryCount - 1) * CAST_RETRY_BACKOFF_MS).coerceAtMost(CAST_RETRY_MAX_BACKOFF_MS)
        val target = _currentPlayer.value
        val position = target.currentPosition.coerceAtLeast(0)
        // Capture the live rewind from the failing player before re-resolving so recovery resumes the
        // viewer's position on the receiver instead of snapping to the live edge (0 if not applicable
        // or its timeline is already gone).
        val rewind = if (target === castPlayer && source.isLive) LivePlayback.castRewindOffsetMs(target, liveWindow) else 0L
        reResolveJob?.cancel()
        reResolveJob = scope.launch {
            if (backoff > 0) delay(backoff)
            ensureActive()
            if (target !== _currentPlayer.value || currentItem == null) return@launch
            // Re-resolve a fresh stream URL + DRM token each try: the failure is usually a stale token
            // the receiver cannot refresh by itself, so reloading the same one on the receiver loops.
            val fresh = runCatching { resolver(source) }.getOrNull()
            ensureActive()
            // Bail if a swap/stop superseded this stream while we were resolving.
            if (target !== _currentPlayer.value || currentItem == null) return@launch
            if (fresh == null) {
                // Re-resolve itself failed: tear down the kept-alive source and connect animation too,
                // not just the toast, like the give-up branch above.
                clearDeferredStop()
                clearConnecting()
                onError?.invoke(PLAYBACK_ERROR_MESSAGE)
                if (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null) disconnect()
                return@launch
            }
            loadOn(target, MediaItemFactory.create(fresh.copy(liveRewindOffsetMs = rewind)), if (fresh.isLive) 0 else position)
        }
    }
}
