// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.MediaQueue
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.UserSession
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.remote.RemoteAuthException
import it.allard.etincelle.core.remote.RemoteMessages
import it.allard.etincelle.core.remote.RemoteTv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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
private const val CONNECT_ERROR_MESSAGE = "Connexion au Chromecast impossible"
private const val TV_CONNECT_MESSAGE = "Connexion au téléviseur impossible"
private const val TV_ACCOUNT_MESSAGE = "Ce téléviseur n'utilise pas le même compte"

// Recover a failing cast by re-resolving fresh credentials (the receiver cannot refresh its own
// short-lived stream/DRM tokens, so reloading them on the receiver loops forever). Retry up to this
// many times per failure burst, backing off between tries, before surfacing an error.
private const val MAX_CAST_RECOVERIES = 5
private const val CAST_RETRY_BACKOFF_MS = 1500L
// Cap the backoff well under the receiver's reconnect grace window so this fresh cast lands before the
// receiver starts its own (stale-token) reload.
private const val CAST_RETRY_MAX_BACKOFF_MS = 2000L
// The phone's own player waits this long before its second attempt, and twice as long before each next.
private const val LOCAL_RETRY_BACKOFF_MS = 2000L
// Failures more than this far apart are separate incidents, not one burst: restore the retry budget so
// a long cast that self-heals occasionally is never eventually refused recovery.
private const val CAST_RETRY_RESET_MS = 60_000L
// How often the receiver's playhead is noted while it plays, for a recovery to resume there.
private const val RECEIVER_PROGRESS_MS = 1_000L

// media3's CastPlayer never reports a receiver-side failure as a player error, so the only way the
// phone can notice a wedged cast (a stalled load, or a mid-cast token expiry the receiver cannot
// refresh) is to watch it. Sample this often, and treat the receiver as stuck after this much
// continuous buffering while it should be playing, then re-resolve a fresh stream and reload. The
// threshold is generously past any normal rebuffer so a healthy cast is never disturbed.
private const val LIVENESS_INTERVAL_MS = 5_000L
private const val LIVENESS_STALL_MS = 30_000L

// One volume-key press steps the cast device volume by this fraction (the Cast SDK default).
private const val VOLUME_STEP = 0.05

// How long a TV app may stay paused, or on a programme that has ended, before the phone app lets go of
// it while away from the screen: the time of a break, and more than the next episode takes to start.
private const val TV_REST_MS = 10 * 60_000L

// How long the phone app, once left, waits before letting go of a TV app that plays nothing of its own:
// what was tapped just before leaving is still being worked out, and may be for that TV.
private const val TV_PARK_GRACE_MS = 5_000L

// When a direct cast's connection drops, wait this long before each reconnection attempt.
private val REJOIN_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000, 10_000)

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
 * @param remoteKey the account key that lets the phone drive an etincelle TV app (see core/remote).
 */
@UnstableApi
class CastPlayerController(
    context: Context,
    castContext: CastContext,
    private val localPlayer: ExoPlayer,
    private val reResolve: (suspend (PlaybackSource) -> PlaybackSource?)? = null,
    private val onError: ((String) -> Unit)? = null,
    private val onPlaybackStopped: (() -> Unit)? = null,
    private val sessionProvider: () -> UserSession? = { null },
    private val remoteKey: (suspend () -> ByteArray?)? = null,
) : CastController(context, castContext) {

    private val appContext = context.applicationContext

    // Skips by the same steps as the phone's own player (the Cast player defaults to 5 s and 15 s).
    private val castPlayer = CastPlayer(
        castContext,
        FuboCastMediaItemConverter(context, sessionProvider),
        LivePlayback.SEEK_BACK_MS,
        LivePlayback.SEEK_FORWARD_MS,
    )
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // A session with a Chromecast Play Services does not see, driven over our own connection.
    private var directSession: DirectCastSession? = null
    // The player the UI binds to while such a session plays (the counterpart of castPlayer).
    private val directPlayer = DirectCastPlayer()
    // Reconnecting a direct cast whose connection dropped (see rejoinDirect).
    private var rejoinJob: Job? = null
    // Counts the direct loads, so a load overtaken by a newer one leaves the receiver and the UI to it.
    private var directLoads = 0
    // As receiverFailed and receiverPositionMs do for Play Services: the direct receiver stopped on an
    // error that was acted on already, and where it last played (it says 0 while it loads).
    private var directFailed = false
    private var directPositionMs = 0L

    // An etincelle TV app driven by etincelle's own remote control, and its player for the UI.
    private var tvSession: TvRemoteSession? = null
    private val tvPlayer = TvRemotePlayer()
    // The TV has played what was last sent to it: its going idle then means it stopped, and its playing
    // something else that it moved on (zapped there, or played the next episode itself).
    private var tvStarted = false
    // Numbers the play requests sent to the TV app, which says in its status which one failed.
    private var tvRequests = 0L
    // Where the phone was in what it handed over to the TV app, until the TV has started it: playback
    // comes back there should the TV fail to. -1 when nothing was handed over.
    private var tvHandoverMs = -1L
    // The TV went on by itself from what this phone sent (the next episode, another channel) and has
    // played ever since: the notification still drives it.
    private var tvMovedOn = false
    // Since when (elapsedRealtime ms) the TV has been paused or on a programme that has ended, as far
    // as it said since the app was last left: 0 while it plays, or has not said.
    private var tvRestingSince = 0L
    // Since when (elapsedRealtime ms) the app has been away from the screen (between stop and start), 0
    // while it is on it.
    private var awaySince = 0L
    // Lets go of an idle TV app a moment after the app was left (see parkIdleTv).
    private var parkJob: Job? = null
    // The TV app whose link was dropped while away, to connect to again on return (see parkIdleTv).
    private var parkedTv: RemoteTv? = null

    // Where playback coming back to the phone resumes (see loadResolved), for a TV app picked before
    // the phone has started it again.
    private var handoffPositionMs = 0L

    // The TV app is connected (not just connecting).
    private val tvConnected: Boolean
        get() = tvSession != null && state.value.toTvApp

    /** Connected to a TV app that plays nothing this phone started: a link let go of once the app is left. */
    val tvIdle: Boolean
        get() = tvConnected && currentItem == null && !tvMovedOn

    // A direct session exists, or is about to: a reconnection has none yet in its first second.
    private val directPending: Boolean
        get() = directSession != null || rejoinJob?.isActive == true

    // The direct session is connected (not just starting).
    private val directConnected: Boolean
        get() = directSession != null && state.value.connectedRouteId?.startsWith(DIRECT_ROUTE_PREFIX) == true

    override val isCastSessionActive: Boolean get() = super.isCastSessionActive || directConnected || tvConnected

    // A Chromecast plays the stream (through Play Services or directly), with its session up.
    private val castingRemotely: Boolean
        get() = (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null) ||
            (_currentPlayer.value === directPlayer && directConnected)

    // The TV has not taken over the direct cast yet and the phone still plays.
    private val phoneStillPlays: Boolean
        get() = _currentPlayer.value === localPlayer && localPlayer.playbackState != Player.STATE_IDLE

    // Scratch window for measuring how far the local player is behind the live edge at cast-out.
    private val liveWindow = Timeline.Window()

    private val _currentPlayer = MutableStateFlow<Player>(localPlayer)

    // The Play Services cast player as the UI and the media session use it, with skips on live streams.
    private val castPlayerShown = LiveSkipCastPlayer(
        castPlayer,
        live = { (currentItem?.localConfiguration?.tag as? PlaybackSource)?.isLive == true },
        skipLive = ::skipCastLive,
    )
    val currentPlayer: StateFlow<Player> = _currentPlayer
        .map { if (it === castPlayer) castPlayerShown else it }
        .stateIn(scope, SharingStarted.Eagerly, localPlayer)

    private var currentItem: MediaItem? = null
    private var endingPosition = 0L
    // The Chromecast of the Play Services session now ending, and whether that end was asked for (see
    // takeBack).
    private var endingDevice: DirectCastDevice? = null
    private var endingOnPurpose = false
    private var endingPlayWhenReady = true
    private var endingCaptured = false
    private var castRetryCount = 0
    private var lastCastErrorAt = 0L

    // The duration the phone's own player had when it last failed: a reload that has not loaded yet
    // leaves it without one, and with it no way to tell where to resume from.
    private var failedDurationMs = C.TIME_UNSET
    private var reResolveJob: Job? = null

    // True only when the user explicitly asked to bring playback back to this phone ("Cet appareil"):
    // the session end then resumes locally and shows the player. Every other end (TV off, external
    // stop, logout, failed transfer) stops quietly and clears the UI's playing state instead.
    private var returnToPhoneOnEnd = false

    // A direct device or TV app picked while a Play Services cast was up: connected once that cast has
    // ended and playback is back on the phone (see connectTo).
    private var pendingDirectRoute: String? = null

    // "Cet appareil" was picked while a Play Services session was still being started: it is ended as
    // it comes up, before anything is sent to it (see giveUpStarting).
    private var connectGivenUp = false

    // Our own scan's entry for the device a cast-out through Play Services is connecting to: used
    // directly if Play Services fails to start the session (see onSessionStartFailed).
    private var directFallback: DirectCastDevice? = null

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

    // Media3's CastPlayer never reports an error (getPlayerError is always null), so a receiver that
    // stopped on one, typically a stream or DRM token that expired, went unnoticed and retried its stale
    // token until it gave up. Watch the session's own status for that stop and recover from here with a
    // fresh stream, once per failure, resuming where the receiver last was.
    private var watchedClient: RemoteMediaClient? = null
    private var receiverFailed = false
    private var receiverPositionMs = 0L
    // How far behind the live edge the receiver plays, as its status says: the cast player has no live
    // window to measure it in, and a recovery or a switch to another device read it as none, bringing a
    // rewound live stream back to the edge.
    private var receiverRewindMs = 0L
    private val receiverStatusCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = watchedClient ?: return
            val status = client.mediaStatus
            val failed = status?.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR
            noteReceiverPosition(client)
            // Only for what this phone loaded: a session resumed after the app was killed holds nothing
            // to reload, and a receiver that gave up earlier would only bring an error and a disconnect.
            if (failed && !receiverFailed && _currentPlayer.value === castPlayer && currentItem != null) onPlaybackError(castPlayer)
            receiverFailed = failed
        }
    }

    // The receiver's status only comes on a change of state, so a recovery long after the last pause or
    // seek would resume there: also follow its playhead while it plays.
    private val receiverProgressListener = RemoteMediaClient.ProgressListener { _, _ ->
        watchedClient?.let(::noteReceiverPosition)
    }

    // Where the receiver plays, kept for a recovery. Not while it is stopped, and never 0: a stream
    // still loading reports 0 until it has seeked to where it resumes.
    private fun noteReceiverPosition(client: RemoteMediaClient) {
        val state = client.mediaStatus?.playerState ?: return
        if (state == MediaStatus.PLAYER_STATE_IDLE || state == MediaStatus.PLAYER_STATE_UNKNOWN) return
        client.approximateStreamPosition.takeIf { it > 0 }?.let { receiverPositionMs = it }
        // Both as the status gave them: its range stands still between two statuses while the position
        // worked out since goes on, which would eat into the rewind.
        client.mediaStatus?.let { status ->
            status.liveSeekableRange?.let { receiverRewindMs = (it.endTime - status.streamPosition).coerceAtLeast(0) }
        }
    }

    // How far behind the live edge the Play Services cast plays, for what is loaded next to resume
    // there: by its player when that knows, else as the receiver last said. Close to the edge is the
    // edge, as for the phone's own player.
    private fun castLiveRewindMs(): Long =
        LivePlayback.castRewindOffsetMs(castPlayer, liveWindow).takeIf { it > 0 }
            ?: receiverRewindMs.takeIf { it > LivePlayback.LIVE_REWIND_THRESHOLD_MS }
            ?: 0L

    private fun watchReceiver(session: CastSession?) {
        val client = session?.remoteMediaClient
        if (client === watchedClient) return
        watchedClient?.unregisterCallback(receiverStatusCallback)
        watchedClient?.removeProgressListener(receiverProgressListener)
        watchedClient?.mediaQueue?.unregisterCallback(queueCallback)
        watchedClient = client
        receiverFailed = false
        receiverPositionMs = 0L
        receiverRewindMs = 0L
        client?.registerCallback(receiverStatusCallback)
        client?.addProgressListener(receiverProgressListener, RECEIVER_PROGRESS_MS)
        queueStatusAsked = false
        client?.mediaQueue?.registerCallback(queueCallback)
    }

    // Media3's CastPlayer rebuilds its playlist from the queue's item ids when a status arrives. A
    // session joined while the receiver plays (the app restarted during a cast) gets its first status
    // before those ids and no other while playback just goes on: what plays, with its title for the
    // notification, stayed unknown until the next pause or seek. Ask for a status once the ids are in,
    // once per session.
    private var queueStatusAsked = false
    private val queueCallback = object : MediaQueue.Callback() {
        override fun mediaQueueChanged() {
            val client = watchedClient ?: return
            if (queueStatusAsked || !castPlayer.currentTimeline.isEmpty || client.mediaQueue.itemCount == 0) return
            queueStatusAsked = true
            client.requestStatus()
        }
    }

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
        failedDurationMs = C.TIME_UNSET
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
        // alone instead of reloading and restarting it from the original position. A "depuis le début"
        // restart carries an explicit rewind, so it must reload even on the same channel, and so must a
        // stream that has ended there.
        val newSource = item.localConfiguration?.tag as? PlaybackSource
        val remoteState = _currentPlayer.value.playbackState
        // On a TV app, only once it has started it: until then nothing says it ever will.
        if ((castingRemotely || (tvConnected && tvStarted)) &&
            sameContent(currentItem, item) &&
            remoteState != Player.STATE_IDLE && remoteState != Player.STATE_ENDED &&
            (newSource?.liveRewindOffsetMs ?: 0L) <= 0L
        ) {
            return
        }
        if (tvConnected) {
            playOnTv(item, startPositionMs, newSource?.liveRewindOffsetMs ?: 0L)
            return
        }
        reResolveJob?.cancel()
        castRetryCount = 0
        transferring = false
        transferFallbackJob?.cancel()
        val direct = directSession
        if (direct != null && state.value.isCasting) {
            // loadDirect makes it the current item, and puts back the one the TV kept if it fails.
            scope.launch { loadDirect(direct, item, startPositionMs, resolve = false) }
            return
        }
        loadOn(_currentPlayer.value, item, startPositionMs)
    }

    /**
     * Route to a Cast device. When already casting, this is a device-to-device switch: capture the
     * current position/state so the new device resumes there, and flag a transfer so the old
     * session's end does not bounce playback back to the phone.
     */
    override fun connectTo(routeId: String): Boolean {
        // A switch to or from a direct cast goes through the phone: playback comes back to it where the
        // TV was, then it is cast to the new device as usual, which picks up from there. A direct cast
        // still connecting is given up the same way.
        val toDirect = routeId.startsWith(DIRECT_ROUTE_PREFIX) || routeId.startsWith(TV_ROUTE_PREFIX)
        connectGivenUp = false
        directFallback = null
        if (directPending || tvSession != null) returnToThisDevice()
        if (toDirect && super.isCastSessionActive) {
            // The Play Services cast ends asynchronously; connect once it is back on the phone.
            pendingDirectRoute = routeId
            returnToThisDevice()
            return true
        }
        if (toDirect) return connectPicked(routeId)
        val willTransfer = _currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null
        val position = if (willTransfer) castPlayer.currentPosition else 0L
        val playWhenReady = if (willTransfer) castPlayer.playWhenReady else true
        // Capture the live rewind now, while the outgoing cast player still has a timeline, so a
        // rewound live stream keeps its position across the device switch.
        val rewind = if (willTransfer) castLiveRewindMs() else 0L
        val twin = if (willTransfer) null else scannedTwin(routeId)
        val selected = super.connectTo(routeId)
        if (selected) {
            receiverEverReady = false
            startConnectWatchdog()
            directFallback = twin
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

    // A device from our own scans: a Chromecast, or an etincelle TV app.
    private fun connectPicked(routeId: String): Boolean =
        if (routeId.startsWith(TV_ROUTE_PREFIX)) scannedTv(routeId)?.let { connectTv(it) } ?: false else connectDirect(routeId)

    // Connect to the etincelle TV app [tv], then have it play what the phone is playing. The TV fetches
    // the stream with its own sign-in; the two only need to share the account (see core/remote). A
    // failure is not said when [quiet]: nobody asked for a connection made again on return.
    private fun connectTv(tv: RemoteTv, quiet: Boolean = false): Boolean {
        val key = remoteKey ?: return false
        markConnecting(tv.name)
        lateinit var session: TvRemoteSession
        session = TvRemoteSession(
            tv, wifiNetwork(),
            onMessage = { message -> if (tvSession === session) onTvMessage(message) },
            onEnded = { if (tvSession === session) endTvCast() },
        )
        tvSession = session
        scope.launch {
            val started = runCatching { session.start(key() ?: throw RemoteAuthException("signed out")) }
            if (tvSession !== session) {
                session.close()
                return@launch
            }
            if (started.isFailure) {
                tvSession = null
                session.close()
                clearConnecting()
                if (!quiet) onError?.invoke(if (started.exceptionOrNull() is RemoteAuthException) TV_ACCOUNT_MESSAGE else TV_CONNECT_MESSAGE)
                return@launch
            }
            markTvConnected(tv)
            tvPlayer.attach(session)
            val item = currentItem
            val phonePlays = phoneStillPlays
            // From now on the controls (remote screen, notification) drive the TV, whatever it plays.
            // A hand-off to the phone still resolving (a switch from a Chromecast) gives up seeing it.
            _currentPlayer.value = tvPlayer
            if (item != null) {
                // Casting out a rewound live stream: resume the TV that far behind the edge.
                val isLive = (item.localConfiguration?.tag as? PlaybackSource)?.isLive == true
                val rewind = if (isLive && phonePlays) LivePlayback.castRewindOffsetMs(localPlayer, liveWindow) else 0L
                val position = if (phonePlays) localPlayer.currentPosition else handoffPositionMs
                playOnTv(item, position, rewind)
                if (phonePlays) tvHandoverMs = position
                runCatching { localPlayer.stop() }
            } else {
                clearConnecting()
                // The app was left while this was connecting.
                parkIdleTv()
            }
        }
        return true
    }

    // Has the TV app play [item] from [positionMs] (on demand) or [rewindMs] behind the live edge.
    private fun playOnTv(item: MediaItem, positionMs: Long, rewindMs: Long) {
        val session = tvSession ?: return
        val (kind, id) = remoteTargets(item).firstOrNull() ?: return
        val message = JSONObject().put(RemoteMessages.TYPE, RemoteMessages.PLAY).put(RemoteMessages.KIND, kind).put(RemoteMessages.ID, id)
        if (kind == RemoteMessages.KIND_LIVE) message.put(RemoteMessages.REWIND, rewindMs) else message.put(RemoteMessages.POSITION, positionMs)
        message.put(RemoteMessages.REQUEST, ++tvRequests)
        currentItem = item
        tvStarted = false
        tvMovedOn = false
        tvRestingSince = 0L
        tvHandoverMs = -1L
        tvPlayer.setItem(item)
        _currentPlayer.value = tvPlayer
        session.send(message)
    }

    // What [item] is for the TV app: the kinds and ids the play message and the TV's status name it by.
    private fun remoteTargets(item: MediaItem?): List<Pair<String, String>> =
        (item?.localConfiguration?.tag as? PlaybackSource)?.remoteTargets().orEmpty()

    private fun onTvMessage(message: JSONObject) {
        if (message.optString(RemoteMessages.TYPE) != RemoteMessages.STATUS) return
        tvPlayer.onStatus(message)
        val playing = message.optString(RemoteMessages.ID).takeIf { it.isNotEmpty() }?.let { message.optString(RemoteMessages.KIND) to it }
        val sent = remoteTargets(currentItem)
        if (playing != null && sent.isNotEmpty()) {
            if (playing in sent) {
                tvStarted = true
                tvHandoverMs = -1L
            } else if (tvStarted) {
                // The TV moved on: what the phone sent no longer plays there, so sending it again plays it.
                tvStarted = false
                tvMovedOn = true
                currentItem = null
                tvPlayer.showTvContent()
            }
        }
        // The TV could not start what was sent: say why, rather than "Connexion à …" for ever.
        val error = message.optString(RemoteMessages.ERROR)
        if (!tvStarted && currentItem != null && error.isNotEmpty() && message.optLong(RemoteMessages.REQUEST) == tvRequests) {
            // Handed over from the phone, which stopped for it: playback comes back to the phone where
            // it was, and the TV is let go of. Not aloud with the app away: it waits there, paused.
            val item = currentItem
            val back = tvHandoverMs
            if (item != null && back >= 0) {
                stopTvSession(stopTv = false)
                onError?.invoke(error)
                loadResolved(localPlayer, item, back, fromPlayWhenReady = awaySince == 0L)
                return
            }
            currentItem = null
            tvPlayer.showTvContent()
            clearConnecting()
            onError?.invoke(error)
            parkIdleTv()
            return
        }
        val state = message.optString(RemoteMessages.STATE)
        val resting = state == RemoteMessages.STATE_PAUSED || state == RemoteMessages.STATE_ENDED
        if (!resting) tvRestingSince = 0L else if (tvRestingSince == 0L) tvRestingSince = SystemClock.elapsedRealtime()
        when (state) {
            RemoteMessages.STATE_PLAYING -> setReceiverPlaying(true)
            RemoteMessages.STATE_PAUSED, RemoteMessages.STATE_BUFFERING, RemoteMessages.STATE_ENDED -> setReceiverPlaying(false)
            RemoteMessages.STATE_IDLE -> {
                setReceiverPlaying(false)
                tvMovedOn = false
                // Stopped on the TV (Back from its player): the phone drops what it was playing too.
                val stopped = tvStarted && currentItem != null
                if (stopped) {
                    tvStarted = false
                    currentItem = null
                    // What the TV plays next is its own, not to be shown as the programme just stopped.
                    tvPlayer.showTvContent()
                }
                // Away, the link goes with what it was kept for, and the pages are told there.
                if (!parkIdleTv() && stopped) onPlaybackStopped?.invoke()
            }
        }
        // Each status is a chance to let go: the wait after leaving counts the time awake only, and a TV
        // playing something of its own says nothing else that would.
        parkIdleTv()
    }

    // Away from the screen, the link to a TV app keeps the phone from sleeping for as long as that TV
    // runs, days maybe. It is only worth that while the TV plays what this phone started, which the
    // notification then drives: otherwise drop it, to connect again on return (see start). A TV left
    // paused, as when it is switched off, or on a programme that has ended, no longer plays it after a
    // while. Not in the first moments away, when what was just asked for may still be on its way to the
    // TV: dropping the link tells the pages that playback stopped, which gives that up. [waited] says
    // those moments are over, from the wait itself: its clock and the one read here may differ by a hair.
    // True when dropped.
    private fun parkIdleTv(waited: Boolean = false): Boolean {
        val session = tvSession ?: return false
        val now = SystemClock.elapsedRealtime()
        if (awaySince == 0L || !tvConnected) return false
        if (!waited && now - awaySince < TV_PARK_GRACE_MS) return false
        val rested = tvRestingSince != 0L && now - tvRestingSince >= TV_REST_MS
        if (!tvIdle && !rested) return false
        parkedTv = session.tv
        // Not to be sent again on return to a TV that may still hold it, paused.
        currentItem = null
        stopTvSession(stopTv = false)
        // What the pages still held as playing, if anything, was no longer on the TV.
        onPlaybackStopped?.invoke()
        return true
    }

    /** Sends a remote-control key to the etincelle TV app (up, down, left, right, ok, back). */
    fun sendTvKey(key: String) {
        tvSession?.takeIf { tvConnected }?.send(JSONObject().put(RemoteMessages.TYPE, RemoteMessages.KEY).put(RemoteMessages.KEY_NAME, key))
    }

    // The TV app connection ended on its own (the app closed, the network dropped): the TV keeps what
    // it plays, and the phone stops showing a cast.
    private fun endTvCast() {
        val phonePlays = phoneStillPlays
        tvSession = null
        leaveTvPlayer()
        onSessionDisconnected()
        if (phonePlays) return
        currentItem = null
        onPlaybackStopped?.invoke()
    }

    // End the TV app session, stopping what it plays when [stopTv]; true when there was one.
    private fun stopTvSession(stopTv: Boolean): Boolean {
        val session = tvSession ?: return false
        tvSession = null
        if (stopTv) session.send(JSONObject().put(RemoteMessages.TYPE, RemoteMessages.STOP))
        session.close()
        leaveTvPlayer()
        onSessionDisconnected()
        return true
    }

    private fun leaveTvPlayer() {
        tvPlayer.detach()
        tvStarted = false
        tvMovedOn = false
        tvRestingSince = 0L
        tvHandoverMs = -1L
        if (_currentPlayer.value === tvPlayer) _currentPlayer.value = localPlayer
    }

    // Connect to a Chromecast from our own scan, then cast what the phone is playing onto it.
    private fun connectDirect(routeId: String): Boolean {
        val device = scannedDevice(routeId) ?: return false
        directSession?.stop()
        markConnecting(device.name)
        rejoinJob?.cancel()
        val session = newDirectSession(device)
        directSession = session
        scope.launch {
            val started = runCatching { session.start() }
            if (directSession !== session) return@launch
            if (started.isFailure) {
                directSession = null
                session.close()
                clearConnecting()
                onError?.invoke(CONNECT_ERROR_MESSAGE)
                return@launch
            }
            markDirectConnected(device)
            directPlayer.attach(session)
            val item = currentItem ?: run { clearConnecting(); return@launch }
            // Casting out a rewound live stream: resume the TV that far behind the edge.
            val isLive = (item.localConfiguration?.tag as? PlaybackSource)?.isLive == true
            val rewind = if (isLive) LivePlayback.castRewindOffsetMs(localPlayer, liveWindow) else 0L
            val position = localPlayer.currentPosition
            if (loadDirect(session, item, position, resolve = true, rewindMs = rewind) == false && directSession === session) {
                // The TV did not take it: end the session, the phone keeps playing.
                stopDirect()
            }
        }
        return true
    }

    // Load [item] on the direct session, re-resolving a fresh stream first when [resolve]: true once
    // the receiver has accepted it, false when it failed, null when a newer load (a channel picked
    // while the cast connected, a quick zap) or the end of the session overtook it.
    private suspend fun loadDirect(
        session: DirectCastSession,
        item: MediaItem,
        positionMs: Long,
        resolve: Boolean,
        rewindMs: Long = (item.localConfiguration?.tag as? PlaybackSource)?.liveRewindOffsetMs ?: 0L,
    ): Boolean? {
        val source = item.localConfiguration?.tag as? PlaybackSource ?: return false
        val previous = currentItem
        val load = ++directLoads
        val current = { directSession === session && load == directLoads }
        val resolver = reResolve
        // A cancelled load (a recovery dropped for a new choice) ends here: it is no failure to report.
        val fresh = if (resolve && resolver != null) {
            try {
                resolver(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else {
            source
        }
        if (!current()) return null
        if (fresh == null) {
            clearConnecting()
            onError?.invoke(PLAYBACK_ERROR_MESSAGE)
            return false
        }
        val freshItem = MediaItemFactory.create(fresh.copy(liveRewindOffsetMs = rewindMs))
        currentItem = freshItem
        directPlayer.setItem(freshItem, if (fresh.isLive) rewindMs else 0L)
        directPositionMs = if (fresh.isLive) 0L else positionMs.coerceAtLeast(0)
        val media = CastLoadData.media(appContext, freshItem, sessionProvider())
        // A live stream asks for a position past the DVR window so the receiver clamps to the edge (it
        // then applies live_rewind_sec); on demand resumes where the phone was.
        val seconds = (if (fresh.isLive) LIVE_EDGE_POSITION_MS else positionMs.coerceAtLeast(0)) / 1000.0
        val loaded = try {
            session.load(CastLoadData.mediaJson(media), seconds)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!current()) return null
        if (loaded) {
            // Whatever the receiver says from here on is about this load.
            directFailed = false
            _currentPlayer.value = directPlayer
            // The phone stops once the TV has the stream, whichever load got it there.
            runCatching { localPlayer.stop() }
        } else {
            // The TV keeps what it had, and so does the session: picking the programme again retries it.
            currentItem = previous
            previous?.let { directPlayer.setItem(it) }
            clearConnecting()
            onError?.invoke(PLAYBACK_ERROR_MESSAGE)
        }
        return loaded
    }

    private fun onDirectMediaStatus(status: org.json.JSONObject) {
        directPlayer.onStatus(status)
        val state = status.optString("playerState")
        if (state == "PLAYING" || state == "PAUSED" || state == "BUFFERING") {
            directPlayer.currentPosition.takeIf { it > 0 }?.let { directPositionMs = it }
        }
        when (state) {
            "PLAYING" -> setReceiverPlaying(true)
            "PAUSED" -> setReceiverPlaying(false)
        }
        // The receiver cannot refresh its stream tokens itself: recover from here, as for Play Services,
        // and once per failure. It says the same again to every status asked for, and each would drop
        // the recovery under way and use up one of its attempts.
        val failed = state == "IDLE" && status.optString("idleReason") == "ERROR"
        if (failed && !directFailed) onPlaybackError(directPlayer)
        directFailed = failed
    }

    private fun newDirectSession(device: DirectCastDevice): DirectCastSession {
        lateinit var session: DirectCastSession
        session = DirectCastSession(
            device, receiverAppId, wifiNetwork(),
            onMediaStatus = { status -> if (directSession === session) onDirectMediaStatus(status) },
            onEnded = { error -> if (directSession === session) onDirectEnded(error) },
        )
        return session
    }

    // The direct session ended on its own. A lost connection (an error, while connected) is rejoined:
    // the TV keeps playing meanwhile. Anything else (stopped on the TV, by another phone) ends the cast.
    private fun onDirectEnded(error: Throwable?) {
        val lost = directSession
        if (error != null && lost != null && directConnected) {
            rejoinDirect(lost.device)
            return
        }
        endDirectCast()
    }

    // Reconnect to [device] and join the receiver still playing there, without reloading it; give up
    // (ending the cast) after a few tries or when the receiver is no longer running.
    private fun rejoinDirect(device: DirectCastDevice) {
        rejoinJob?.cancel()
        rejoinJob = scope.launch {
            for (wait in REJOIN_DELAYS_MS) {
                delay(wait)
                val fresh = newDirectSession(device)
                directSession = fresh
                val joined = runCatching { fresh.start(joinOnly = true) }
                // runCatching also catches a cancellation (Cet appareil meanwhile): stop there.
                if (!isActive || directSession !== fresh) {
                    fresh.close()
                    return@launch
                }
                if (joined.isSuccess) {
                    markDirectConnected(device)
                    clearConnecting()
                    directPlayer.attach(fresh)
                    fresh.requestStatus()
                    return@launch
                }
                fresh.close()
                // Reached, but nothing of ours runs there any more (stopped on the TV): no use waiting.
                if (joined.exceptionOrNull() is ReceiverNotRunningException) break
            }
            endDirectCast()
        }
    }

    // Like any other end of a cast the user did not ask to continue here: stop, and let the UI drop its
    // playing state. Before the TV took over, the phone still plays: leave it so.
    private fun endDirectCast() {
        val phonePlays = phoneStillPlays
        directSession = null
        leaveDirectPlayer()
        onSessionDisconnected()
        if (phonePlays) return
        currentItem = null
        onPlaybackStopped?.invoke()
    }

    // End the direct session and stop the receiver; true when there was one.
    private fun stopDirect(): Boolean {
        val rejoining = rejoinJob?.isActive == true
        rejoinJob?.cancel()
        val session = directSession
        // A reconnection has no session yet in its first second: the direct player is left all the
        // same, or it would stay the current one with nothing behind it.
        if (session == null && !rejoining) return false
        directSession = null
        session?.stop()
        leaveDirectPlayer()
        onSessionDisconnected()
        return true
    }

    private fun leaveDirectPlayer() {
        directPlayer.detach()
        if (_currentPlayer.value === directPlayer) _currentPlayer.value = localPlayer
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
            val remote = _currentPlayer.value
            if (suspended || !castingRemotely) {
                bufferingForMs = 0L
                continue
            }
            // A healthy cast is STATE_READY; a user-paused one has playWhenReady=false. Only a session
            // that should be playing yet stays buffering counts toward a stall.
            if (remote.playWhenReady && remote.playbackState == Player.STATE_BUFFERING) {
                bufferingForMs += LIVENESS_INTERVAL_MS
                if (bufferingForMs >= LIVENESS_STALL_MS) {
                    bufferingForMs = 0L
                    onPlaybackError(remote)
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
        awaySince = SystemClock.elapsedRealtime()
        // A break taken with the app on screen does not count: the wait starts on leaving.
        tvRestingSince = 0L
        parkJob?.cancel()
        parkJob = scope.launch {
            delay(TV_PARK_GRACE_MS)
            parkIdleTv(waited = true)
        }
        super.stop()
    }

    override fun start() {
        awaySince = 0L
        parkJob?.cancel()
        super.start()
        // The TV app left on the way out is driven again, unless something else got connected meanwhile.
        val tv = parkedTv ?: return
        parkedTv = null
        if (tvSession == null && !isCastSessionActive) connectTv(tv, quiet = true)
    }

    /**
     * Bring playback back to this phone ("Cet appareil" in the picker): end the session and resume
     * locally at the cast position, re-surfacing the player. Distinct from [disconnect], which stops.
     */
    fun returnToThisDevice() {
        // TV app: stop it and play on the phone again, where the TV was.
        if (tvSession != null) {
            // Only what the phone knows can continue here; something else the TV plays stays on it.
            val onTv = _currentPlayer.value === tvPlayer
            val playsOnTv = onTv && currentItem != null
            val position = tvPlayer.currentPosition
            stopTvSession(stopTv = playsOnTv)
            if (playsOnTv) {
                currentItem?.let { loadResolved(localPlayer, it, position, true) }
            } else if (onTv) {
                // The TV went on to something else, which stays on it: nothing is left to play here, and
                // the phone would open its player on what it had sent, with nothing behind it.
                currentItem = null
                onPlaybackStopped?.invoke()
            }
            return
        }
        // Direct session: stop the TV and play on the phone again, where the TV was (or where the phone
        // still is, when the TV has not taken over yet).
        if (directPending) {
            if (phoneStillPlays) {
                stopDirect()
                return
            }
            // A receiver still loading says 0: where it was asked to start, then.
            val position = directPlayer.currentPosition.takeIf { it > 0 } ?: directPositionMs
            val playWhenReady = directPlayer.playWhenReady
            stopDirect()
            currentItem?.let { loadResolved(localPlayer, it, position, playWhenReady) }
            return
        }
        if (giveUpStarting()) {
            // A switch from another device was under way: playback comes back here now, not once the
            // wait for the new device is over.
            if (transferring) {
                transferring = false
                transferFallbackJob?.cancel()
                swapTo(localPlayer, transferPosition, transferPlayWhenReady)
            }
            return
        }
        // No live session to end (it already tore down): do nothing. Arming returnToPhoneOnEnd with no
        // onSessionEnded coming would leave it set to mis-fire a return-to-phone on a later cast.
        if (castContext.sessionManager.currentCastSession == null) return
        returnToPhoneOnEnd = true
        transferring = false
        transferFallbackJob?.cancel()
        reResolveJob?.cancel()
        super.disconnect()
    }

    // Gives up a Play Services session that is still being started, as when a device was picked by
    // mistake; true when there was one. It cannot be called off: the receiver was asked for already,
    // and ending the session now would leave it running on the TV with nobody to stop it. The phone
    // stops showing the connection at once, and the session is ended as it comes up, which closes the
    // receiver (see onSessionConnected).
    private fun giveUpStarting(): Boolean {
        val session = castContext.sessionManager.currentCastSession
        val starting = session?.isConnecting == true || (session == null && state.value.connecting)
        if (!starting || tvSession != null || directPending) return false
        connectGivenUp = true
        directFallback = null
        connectWatchdogJob?.cancel()
        clearConnecting()
        return true
    }

    /**
     * Ends the cast with nothing to bring back to this phone, as when the pages no longer hold what
     * plays there. What this phone sent stops; what a TV app plays of its own accord is not this
     * phone's to stop, and stays on it.
     */
    fun endCast() {
        if (tvSession == null) return disconnect()
        val phonePlays = phoneStillPlays
        stopTvSession(stopTv = _currentPlayer.value === tvPlayer && currentItem != null)
        if (!phonePlays) currentItem = null
    }

    override fun disconnect() {
        val phonePlays = phoneStillPlays
        // As for endCast: what a TV app plays of its own accord is not this phone's to stop.
        if (stopTvSession(stopTv = _currentPlayer.value === tvPlayer && currentItem != null) || stopDirect()) {
            // Nothing of it is left to send to the next device picked, unless the phone still plays it
            // (the device had not taken over yet).
            if (!phonePlays) currentItem = null
            return
        }
        if (giveUpStarting()) {
            // A switch from another device was under way: nothing of it is brought back to the phone
            // here, now or once the wait for the new device would have run out.
            if (transferring) {
                transferring = false
                transferFallbackJob?.cancel()
                reResolveJob?.cancel()
                stopQuietly()
            }
            return
        }
        // A plain disconnect (e.g. logout) stops casting without resuming on the phone; the session
        // end takes the quiet-stop path. Clear transfer/re-resolve so nothing ejects after teardown.
        returnToPhoneOnEnd = false
        transferring = false
        transferFallbackJob?.cancel()
        reResolveJob?.cancel()
        // Only a session can end: with none, nothing would take the mark back, and the next cast to
        // lose its device would pass for ended on purpose.
        endingOnPurpose = castContext.sessionManager.currentCastSession != null
        super.disconnect()
    }

    private fun Uri.bare(): Uri = buildUpon().clearQuery().fragment(null).build()

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
     * What a Chromecast or the TV app plays for this phone, with its position and duration, so the
     * phone keeps a resume point meanwhile: most ends of a cast leave nothing to read it from. Null
     * when playing here, while loading, or when the TV plays something else than what this phone sent
     * (the next episode it moved on to, or a stream not yet replaced by a fresh one).
     */
    fun castProgress(): Triple<PlaybackSource, Long, Long>? {
        val player = _currentPlayer.value
        if (player === localPlayer || player.playbackState != Player.STATE_READY) return null
        // A TV app may still be playing what it had before, which is not this item's position.
        if (player === tvPlayer && !tvStarted) return null
        val item = currentItem ?: return null
        val source = item.localConfiguration?.tag as? PlaybackSource ?: return null
        // By the stream's address without what follows it: its query is the access token of the stream,
        // which a receiver may keep out of the status it sends to everyone on the network.
        if (source.resumeKey == null || player.currentMediaItem?.localConfiguration?.uri?.bare() != item.localConfiguration?.uri?.bare()) return null
        return Triple(source, player.currentPosition, player.duration)
    }

    /**
     * Stop local playback and return (position, duration) for resume persistence. Returns null while
     * casting (the stream stays on the Chromecast) or when nothing is loaded locally.
     */
    fun stopPlayback(): Pair<Long, Long>? {
        // Casting: leaving or switching the player keeps the TV stream running, nothing to save here.
        if (_currentPlayer.value === castPlayer || ((directSession != null || tvSession != null) && state.value.isCasting)) return null
        // Nothing is loaded locally (e.g. a cast session was just stopped quietly): nothing to save.
        if (currentItem == null) return null
        reResolveJob?.cancel()
        clearDeferredStop()
        val player = _currentPlayer.value
        val pos = player.currentPosition
        // Given up on in the middle of a reload, the player no longer knows its duration.
        val dur = player.duration.takeIf { it > 0 } ?: failedDurationMs
        failedDurationMs = C.TIME_UNSET
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
        if (tvConnected) {
            tvSession?.send(JSONObject().put(RemoteMessages.TYPE, RemoteMessages.VOLUME).put(RemoteMessages.DIRECTION, if (up) "up" else "down"))
            return
        }
        directSession?.takeIf { directConnected }?.let { direct ->
            val next = ((castVolume ?: direct.volumeLevel) + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0.0, 1.0)
            castVolume = next
            direct.setVolume(next)
            if (direct.muted) direct.setMuted(false)
            publishVolume(next.toFloat())
            return
        }
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
        if (tvConnected) {
            tvSession?.send(JSONObject().put(RemoteMessages.TYPE, RemoteMessages.VOLUME).put(RemoteMessages.DIRECTION, "mute"))
            return
        }
        directSession?.takeIf { directConnected }?.let { direct ->
            val muted = direct.muted
            direct.setMuted(!muted)
            publishVolume(if (!muted) 0f else (castVolume ?: direct.volumeLevel).toFloat())
            return
        }
        val session = castContext.sessionManager.currentCastSession ?: return
        val muted = runCatching { session.isMute }.getOrDefault(false)
        runCatching { session.isMute = !muted }
        val level = if (!muted) 0f else (castVolume ?: runCatching { session.volume }.getOrDefault(0.0)).toFloat()
        publishVolume(level)
    }

    override fun release() {
        super.release()
        tvSession?.close()
        tvSession = null
        tvPlayer.release()
        rejoinJob?.cancel()
        directSession?.close()
        directSession = null
        directPlayer.release()
        clearDeferredStop()
        connectWatchdogJob?.cancel()
        localPlayer.removeListener(localErrorListener)
        castPlayer.removeListener(castErrorListener)
        castPlayer.removeListener(castPlaybackListener)
        watchReceiver(null)
        scope.cancel()
        castPlayer.release()
    }

    // A transient suspend (network blip): note it so the stall recovery stands down until it resumes.
    override fun onSessionSuspendedInternal(session: CastSession) {
        suspended = true
    }

    // Some phones list a Chromecast through Play Services that they cannot actually connect to. When
    // our own scan found the same device, cast to it directly instead; the phone is still playing.
    override fun onSessionStartFailed() {
        connectGivenUp = false
        val twin = directFallback
        directFallback = null
        onSessionDisconnected()
        if (twin != null && _currentPlayer.value === localPlayer) {
            // The failed session's watchdog would otherwise cut the direct connect's animation short.
            connectWatchdogJob?.cancel()
            connectDirect(DIRECT_ROUTE_PREFIX + twin.id)
        }
    }

    override fun onSessionConnected(session: CastSession) {
        if (connectGivenUp) {
            // Given up while it started: ended with its receiver once it is up, and never shown as
            // connected. Coming back to the app gets here too, with the session still starting.
            if (session.isConnected) {
                connectGivenUp = false
                castContext.sessionManager.endCurrentSession(true)
            }
            return
        }
        super.onSessionConnected(session)
        watchReceiver(session)
        directFallback = null
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
        endingDevice = session.castDevice?.let { cast ->
            cast.inetAddress?.hostAddress?.let { DirectCastDevice(cast.deviceId, cast.friendlyName ?: cast.deviceId, it, cast.servicePort) }
        }
    }

    override fun onSessionDisconnected() {
        // "No Play Services session" says nothing about a direct cast or a TV app, which end through their
        // own paths (that clear their session first). Without this, coming back to the foreground, where
        // start() reports no Play Services session, would wipe the state of one still playing, or of a
        // TV app being connected to, whose remote screen would close on it.
        if (directConnected || tvSession != null) return
        val lostDevice = endingDevice.takeIf { !endingOnPurpose }
        endingDevice = null
        endingOnPurpose = false
        watchReceiver(null)
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
            // A direct device picked meanwhile: the phone already plays, connect it now.
            pendingDirectRoute?.takeIf { !super.isCastSessionActive }?.let {
                pendingDirectRoute = null
                connectPicked(it)
            }
            return
        }
        if (transferring) {
            // Device-to-device switch in flight: the old session ended; keep the cast player current
            // and the connect animation alive toward the new device, and wait for it to connect
            // (onSessionConnected loads it). If it never does (transfer failed), stop quietly.
            markConnecting(transferTargetName)
            endingCaptured = false
            transferFallbackJob?.cancel()
            // Not while the app is away (see stop): the phone would start playing aloud in a pocket.
            // Coming back to the app gets here again.
            if (awaySince != 0L) return
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
            pendingDirectRoute?.let {
                pendingDirectRoute = null
                connectPicked(it)
            }
            return
        }
        pendingDirectRoute = null
        val item = currentItem
        if (lostDevice != null && item != null) {
            takeBack(lostDevice, item)
            return
        }
        // Any other end (TV off, external stop, logout): stop, and tell the UI to drop its playing
        // state so the local player is not popped over whatever the user is now browsing.
        stopQuietly()
        onPlaybackStopped?.invoke()
    }

    // A Play Services cast that ended without anyone asking, typically the phone's Wi-Fi dropping for a
    // few seconds: Play Services then drops the route and the session with it, while the TV plays on.
    // Take it back over our own connection when our receiver still runs there, without reloading;
    // stopped on the TV, or out of reach for good, it ends as any other cast (see rejoinDirect).
    private fun takeBack(device: DirectCastDevice, item: MediaItem) {
        clearDeferredStop()
        runCatching { castPlayer.stop() }
        castSessionId = null
        markConnecting(device.name)
        directPlayer.setItem(item)
        _currentPlayer.value = directPlayer
        rejoinDirect(device)
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
        if (target === localPlayer) handoffPositionMs = position
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
        // A new item on the receiver: the last position it reported was the previous item's. Until it
        // reports one, it is where this load starts, so a load failing before it plays retries there.
        if (player === castPlayer) {
            receiverPositionMs = if (isLive) 0L else positionMs.coerceAtLeast(0)
            receiverRewindMs = (item.localConfiguration?.tag as? PlaybackSource)?.liveRewindOffsetMs ?: 0L
        }
        // CastPlayer loads live DASH at the start of the receiver's DVR window (currentTime=0); ask
        // for a position well past the window so the receiver clamps to the live edge. ExoPlayer
        // already starts live at the edge, so it keeps the caller's position.
        val effectivePos = if (player === castPlayer && isLive) LIVE_EDGE_POSITION_MS else positionMs
        if (effectivePos > 0) player.setMediaItem(item, effectivePos) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    // Moves a live Play Services cast by [deltaMs] around the receiver's position, kept within the
    // stream's seekable range when the receiver reports one.
    private fun skipCastLive(deltaMs: Long) {
        val client = castContext.sessionManager.currentCastSession?.remoteMediaClient ?: return
        var target = client.approximateStreamPosition + deltaMs
        val start = client.approximateLiveSeekableRangeStart
        val end = client.approximateLiveSeekableRangeEnd
        // Both are 0 when the receiver reports no range: nothing to keep within then.
        if (start >= 0 && end > start) target = target.coerceIn(start, end)
        client.seek(MediaSeekOptions.Builder().setPosition(target.coerceAtLeast(0)).build())
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
        if (failedPlayer === localPlayer) localPlayer.duration.takeIf { it > 0 }?.let { failedDurationMs = it }
        if (source == null || resolver == null || castRetryCount >= MAX_CAST_RECOVERIES) {
            // Out of recovery attempts (or nothing to re-resolve): cut any source kept alive for the
            // hand-off so it stops decoding with nothing taking over, and stop the connect animation.
            clearDeferredStop()
            clearConnecting()
            onError?.invoke(PLAYBACK_ERROR_MESSAGE)
            // If the dead stream was on the cast player, end the session so the bar stops claiming an
            // active cast over playback that never recovered.
            // On the phone itself, close the player: it would stay up over a frozen picture.
            val onPhone = _currentPlayer.value === localPlayer
            if (_currentPlayer.value === castPlayer && castContext.sessionManager.currentCastSession != null) disconnect()
            if (_currentPlayer.value === directPlayer && stopDirect()) {
                currentItem = null
                onPlaybackStopped?.invoke()
            }
            if (onPhone) onPlaybackStopped?.invoke()
            return
        }
        castRetryCount++
        val target = _currentPlayer.value
        // Back off on each consecutive failure so a persistently broken stream is not hammered. For a
        // receiver the wait is capped, staying under its reconnect grace so its own stale-token reload
        // does not fire first. The phone's own player has no such race, and waits as the TV app's does:
        // 2, 4, 8 then 16 s, so that half a minute without network does not close it.
        val backoff = when {
            target !== localPlayer -> ((castRetryCount - 1) * CAST_RETRY_BACKOFF_MS).coerceAtMost(CAST_RETRY_MAX_BACKOFF_MS)
            castRetryCount == 1 -> 0L
            else -> LOCAL_RETRY_BACKOFF_MS shl (castRetryCount - 2)
        }
        // A receiver stopped on an error may no longer report where it was: fall back to the position
        // it last reported while playing.
        val position = when {
            target === localPlayer -> target.currentPosition
            target === directPlayer -> target.currentPosition.takeIf { it > 0 } ?: directPositionMs
            else -> target.currentPosition.takeIf { it > 0 } ?: receiverPositionMs
        }.coerceAtLeast(0)
        // Capture the live rewind from the failing player before re-resolving so recovery resumes the
        // viewer's position on the receiver instead of snapping to the live edge (0 if not applicable
        // or its timeline is already gone).
        val rewind = when {
            !source.isLive || target === localPlayer -> 0L
            target === castPlayer -> castLiveRewindMs()
            else -> LivePlayback.castRewindOffsetMs(target, liveWindow)
        }
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
                // The re-resolve itself failed - usually a transient blip (a Wi-Fi hiccup, a 5xx, the
                // documented 528), not a permanently dead stream. Retry within the budget rather than
                // tearing the cast down on a single miss: onPlaybackError re-arms with backoff and only
                // gives up (ending the session) once the budget is exhausted. target===currentPlayer is
                // already guaranteed here, so this consumes one more attempt and re-resolves.
                onPlaybackError(target)
                return@launch
            }
            val freshItem = MediaItemFactory.create(fresh.copy(liveRewindOffsetMs = rewind))
            val direct = directSession
            if (target === directPlayer && direct != null) {
                // A load the receiver refuses is the same failure still: on to the next attempt, as
                // its status, unchanged, will not ask for one.
                if (loadDirect(direct, freshItem, position, resolve = false, rewindMs = rewind) == false) onPlaybackError(target)
            } else {
                loadOn(target, freshItem, if (fresh.isLive) 0 else position)
            }
        }
    }
}
