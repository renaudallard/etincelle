// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.LiveProgramBar
import it.allard.etincelle.core.designsystem.LiveProgramBarBottomOffset
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.designsystem.UpNextSlate
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlayKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.AdaptiveLoadControl
import it.allard.etincelle.core.player.LiveBarGeometry
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.player.PlaybackRecovery
import it.allard.etincelle.core.player.keepScreenOnWhilePlaying
import it.allard.etincelle.core.remote.RemoteMessages
import it.allard.etincelle.core.ui.installFetchedUpdate
import it.allard.etincelle.core.ui.installUpdate
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.AutoUpdate
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

// How long a TV set may take to settle once the phone's programme woke it.
private const val TV_WAKING_MS = 30_000L

// How long after the activity stops the screensaver's broadcast may come.
private const val DREAM_GRACE_MS = 3_000L

class MainActivity : ComponentActivity(), TvRemote.Screen {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleTvApp).container.repository, UpdateChecker(BuildConfig.VERSION_NAME, "tv"), AutoUpdate(this).takeIf { BuildConfig.SELF_UPDATE })
    }
    private var player: ExoPlayer? = null
    private var loadControl: AdaptiveLoadControl? = null
    private var pausedForBackground = false
    // A phone of the same account driving this app while it is open.
    private val remote: TvRemote get() = (application as EtincelleTvApp).remote
    private val remoteWindow = Timeline.Window()
    // The last play request from the phone, echoed in the status (see RemoteMessages.REQUEST).
    private var remoteRequest = 0L
    // When the phone last asked for a programme (SystemClock.elapsedRealtime).
    private var remotePlayAt = 0L
    // The screensaver stops this activity but not the remote control: a phone can still find this app and
    // wake the TV with a command. Leaving the app or the TV going to sleep stop it, unless it is kept at
    // all times (see TvRemote).
    private var dreaming = false
    private val handler = Handler(Looper.getMainLooper())
    private val stopRemoteUnlessDreaming = Runnable { if (!dreaming) leave() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> dreaming = false
                Intent.ACTION_SCREEN_OFF -> {
                    dreaming = false
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) leave()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val control = AdaptiveLoadControl.create(this)
        loadControl = control
        val exo = ExoPlayer.Builder(this)
            // Buffer further ahead for replays/recordings/VOD so a brief drop drains the buffer
            // instead of stalling; sized to the device heap and trimmed under memory pressure.
            .setLoadControl(control)
            // Pause on audio-focus loss (other media) and on a headset disconnect.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Skip steps for the player's rewind/forward controls (live shows a seekable DVR window).
            .setSeekBackIncrementMs(LivePlayback.SEEK_BACK_MS)
            .setSeekForwardIncrementMs(LivePlayback.SEEK_FORWARD_MS)
            .build()
        // A TV set waking for what the phone sent drops its HDMI link and brings it back a few seconds
        // into playback, which Android reports as the audio output being unplugged: the player pauses for
        // it, as it should for headphones. Play on.
        exo.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val unplugged = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
                if (unplugged && SystemClock.elapsedRealtime() - remotePlayAt < TV_WAKING_MS) exo.playWhenReady = true
            }
        })
        player = exo
        remote.attach(this)
        // Opening the app turns the screen on (see the manifest), which older versions only take from here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) turnScreenOn(true)
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        // Only on a fresh launch; a process recreation must not replay the original deep link.
        if (savedInstanceState == null) handleDeepLink(intent)
        // A build from Google Play is updated by Google Play, and may not update itself.
        if (BuildConfig.SELF_UPDATE) lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchForUpdates() } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepPageFresh() } }
        // Signed out, by hand or as the session died: the account's phones lose this TV at once.
        lifecycleScope.launch {
            var signedIn = false
            viewModel.state.map { it.loggedIn }.distinctUntilChanged().collect { now ->
                if (signedIn && !now) remote.signedOut()
                signedIn = now
            }
        }
        setContent {
            EtincelleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val autoUpdate by viewModel.autoUpdate.collectAsStateWithLifecycle()
                // Returning to the foreground refreshes the guide, which goes stale over time.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOnResume() }
                Surface(modifier = Modifier.fillMaxSize()) {
                    val playing = state.playing
                    val detail = state.detail
                    val context = LocalContext.current
                    // Grid density (cards per row on the "tout voir" pages); persisted, set live from settings.
                    var gridColumns by remember { mutableStateOf(TvPrefs.gridColumns(context)) }
                    // Preserve the browse list's scroll position while a detail/settings/player covers it,
                    // so backing out keeps the grid where it was instead of jumping to the top. Lives above
                    // the screen `when` so it survives that swap (the browse branch leaves composition).
                    val contentStateHolder = rememberSaveableStateHolder()
                    val favoriteOrder = state.favoriteOrder
                    val profileScreen = state.profileScreen
                    val profileEditor = state.profileEditor
                    val mfa = state.mfa
                    CompositionLocalProvider(LocalFavoriteToggle provides viewModel::toggleFavorite) {
                    Box(Modifier.fillMaxSize()) {
                    when {
                        state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        playing != null -> {
                            BackHandler { viewModel.stopPlaying() }
                            TvPlayerSurface(
                                playing, exo, viewModel::savePlaybackPosition, viewModel::reportProgress,
                                viewModel::liveProgramWindow, viewModel::reResolve, viewModel::playbackFailed, viewModel::playUpNext,
                            )
                        }

                        detail != null -> {
                            BackHandler { viewModel.closeDetail() }
                            TvProgramDetailScreen(
                                detail, state.busy, state.error, state.info,
                                viewModel::watchDetail, viewModel::startOverDetail, viewModel::watchReplay, viewModel::recordDetail,
                                viewModel::watchRecording,
                                viewModel::onCardClick,
                                isRecording = state.detailRecordingAssetId != null,
                            )
                        }

                        profileEditor != null -> {
                            BackHandler { viewModel.closeProfileEditor() }
                            TvProfileEditorScreen(
                                profileEditor,
                                avatars = profileScreen?.list?.avatars.orEmpty(),
                                canDelete = (profileScreen?.list?.profiles?.size ?: 0) > 1,
                                busy = state.busy,
                                error = state.error,
                                onSave = viewModel::saveProfile,
                                onDelete = viewModel::deleteProfile,
                                onBack = viewModel::closeProfileEditor,
                            )
                        }

                        profileScreen != null -> {
                            BackHandler { viewModel.closeProfiles() }
                            TvProfilesScreen(
                                profileScreen, state.busy, state.error,
                                viewModel::pickProfile, viewModel::editProfile, viewModel::closeProfiles,
                            )
                        }

                        favoriteOrder != null -> {
                            BackHandler { viewModel.closeFavoriteOrder() }
                            TvFavoriteOrderScreen(
                                favoriteOrder, state.busy, state.error,
                                viewModel::moveFavorite, viewModel::saveFavoriteOrder, viewModel::closeFavoriteOrder,
                            )
                        }

                        state.settings -> {
                            BackHandler { viewModel.closeSettings() }
                            TvSettingsScreen(
                                onBack = viewModel::closeSettings,
                                onLogout = viewModel::logout,
                                onProfiles = viewModel::openProfiles,
                                gridColumns = gridColumns,
                                onGridColumns = { gridColumns = it },
                                autoUpdate = autoUpdate.takeIf { BuildConfig.SELF_UPDATE && AutoUpdate.SUPPORTED },
                                onAutoUpdate = viewModel::setAutoUpdate,
                            )
                        }

                        state.loggedIn -> {
                            BackHandler(enabled = state.canGoBack) { viewModel.back() }
                            // Search keeps a constant key (its back-stack depth flips 0->1 when results
                            // arrive, which would otherwise reset the typed query); other pages key by the
                            // back-stack entry so each keeps its own scroll, plus hideLocked so toggling it
                            // starts fresh at the top.
                            val pageKey = if (state.tab == Tab.SEARCH) {
                                "SEARCH"
                            } else {
                                "${state.tab.name}#${state.backStack.size}#${state.hideLocked}#" +
                                    (state.current?.reloadUrl ?: state.current?.title.orEmpty())
                            }
                            contentStateHolder.SaveableStateProvider(pageKey) {
                                TvBrowseScreen(
                                    state, viewModel::selectTab, viewModel::onCardClick, viewModel::search,
                                    viewModel::openSettings, viewModel::onRailSeeAll, gridColumns,
                                    onFavoriteOrder = viewModel::openFavoriteOrder,
                                )
                            }
                        }

                        mfa != null -> {
                            BackHandler { viewModel.cancelCode() }
                            TvCodeScreen(mfa, state.busy, state.error, state.info, viewModel::verifyCode, viewModel::resendCode, viewModel::cancelCode)
                        }

                        else -> TvLoginScreen(
                            state.pairingCode, state.busy, state.error,
                            viewModel::loginWithCode, viewModel::stopPairingPoll, viewModel::cancelCodeLogin, viewModel::login,
                        )
                    }
                    state.update?.takeIf { !it.quiet }?.let { up ->
                        TvUpdateDialog(
                            up, state.updateProgress, state.updateError,
                            onInstall = { installUpdate(viewModel) },
                            onDismiss = viewModel::dismissUpdate,
                        )
                    }
                    }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** Opens a show from an etincelle://series|program|channel/{id} deep link. */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "etincelle") return
        val kind = when (data.host) {
            "series" -> DetailKind.SERIES
            "program" -> DetailKind.PROGRAM
            "channel" -> DetailKind.CHANNEL
            else -> return
        }
        val id = data.pathSegments.firstOrNull() ?: return
        // Mark the intent consumed so getIntent() does not hand the same link back after a recreation.
        intent.data = null
        viewModel.onDeepLink(id, kind)
    }

    // A command from the phone driving this app (see TvRemote).
    override fun onRemoteCommand(message: JSONObject) {
        val player = player ?: return
        val type = message.optString(RemoteMessages.TYPE)
        // Not in front (the screensaver, another app, the TV asleep): what needs the screen wakes the TV
        // first, which brings this app back. A key only does that, as the first press on the TV's own
        // remote only wakes it.
        val inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val resumes = type == RemoteMessages.RESUME && viewModel.state.value.playing != null
        if (!inFront && (type == RemoteMessages.PLAY || type == RemoteMessages.KEY || resumes)) {
            wakeScreen()
            if (type == RemoteMessages.KEY) return
        }
        when (type) {
            RemoteMessages.PLAY -> {
                val kind = when (message.optString(RemoteMessages.KIND)) {
                    RemoteMessages.KIND_LIVE -> PlayKind.LIVE
                    RemoteMessages.KIND_VOD -> PlayKind.VOD
                    RemoteMessages.KIND_RECORDING -> PlayKind.RECORDING
                    else -> return
                }
                val id = message.optString(RemoteMessages.ID).takeIf { it.isNotEmpty() } ?: return
                remoteRequest = message.optLong(RemoteMessages.REQUEST)
                remotePlayAt = SystemClock.elapsedRealtime()
                viewModel.playFromRemote(kind, id, message.optLong(RemoteMessages.POSITION), message.optLong(RemoteMessages.REWIND))
            }
            RemoteMessages.PAUSE -> {
                player.playWhenReady = false
                pausedForBackground = false
            }
            // Not in front, playback resumes as the app comes back (see onStart), not behind what the
            // TV shows meanwhile.
            RemoteMessages.RESUME -> if (inFront) player.playWhenReady = true else pausedForBackground = true
            RemoteMessages.SEEK -> {
                val position = message.optLong(RemoteMessages.POSITION, -1)
                if (position < 0) player.seekToDefaultPosition() else player.seekTo(position)
            }
            RemoteMessages.STOP -> viewModel.stopPlaying()
            RemoteMessages.KEY -> when (val name = message.optString(RemoteMessages.KEY_NAME)) {
                // Straight to what handles Back: from Android 16 an activity no longer turns a Back key
                // event into it for an app built for that version.
                "back" -> onBackPressedDispatcher.onBackPressed()
                else -> remoteKeyCode(name)?.let { code ->
                    // Through the activity, as a remote press would come, so the screens and the
                    // player see it the same way.
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
            // The media volume, which Android passes on to the TV set over HDMI-CEC when the device allows
            // it. A device whose HDMI output has a fixed volume and CEC volume control off leaves it as is.
            RemoteMessages.VOLUME -> {
                val adjust = when (message.optString(RemoteMessages.DIRECTION)) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
                    else -> return
                }
                getSystemService(AudioManager::class.java)?.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun remoteKeyCode(name: String): Int? = when (name) {
        "up" -> KeyEvent.KEYCODE_DPAD_UP
        "down" -> KeyEvent.KEYCODE_DPAD_DOWN
        "left" -> KeyEvent.KEYCODE_DPAD_LEFT
        "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
        else -> null
    }

    override fun remoteStatus(): JSONObject {
        val status = JSONObject().put(RemoteMessages.TYPE, RemoteMessages.STATUS).put(RemoteMessages.REQUEST, remoteRequest)
        val state = viewModel.state.value
        val source = state.playing
        val player = player
        if (source == null || player == null) {
            state.error?.let { status.put(RemoteMessages.ERROR, it) }
            return status.put(RemoteMessages.STATE, RemoteMessages.STATE_IDLE)
        }
        val playback = when (player.playbackState) {
            Player.STATE_BUFFERING -> RemoteMessages.STATE_BUFFERING
            Player.STATE_READY -> if (player.playWhenReady) RemoteMessages.STATE_PLAYING else RemoteMessages.STATE_PAUSED
            Player.STATE_ENDED -> RemoteMessages.STATE_ENDED
            // Not loaded yet, or being brought back after an error: on its way. Idle would tell the
            // phone that the programme was stopped here, and it would drop it.
            else -> RemoteMessages.STATE_BUFFERING
        }
        status.put(RemoteMessages.STATE, playback)
            .put(RemoteMessages.TITLE, source.title.orEmpty())
            .put(RemoteMessages.POSITION, player.currentPosition)
            .put(RemoteMessages.DURATION, player.duration.takeIf { it != C.TIME_UNSET } ?: -1L)
            .put(RemoteMessages.LIVE, source.isLive)
        val timeline = player.currentTimeline
        if (source.isLive && !timeline.isEmpty) {
            timeline.getWindow(player.currentMediaItemIndex, remoteWindow)
            if (remoteWindow.windowStartTimeMs != C.TIME_UNSET) status.put(RemoteMessages.WINDOW_START, remoteWindow.windowStartTimeMs)
        }
        val channelId = source.originChannelId
        val vodId = source.originVodId
        val recordingId = source.originRecordingAssetId
        when {
            channelId != null -> status.put(RemoteMessages.KIND, RemoteMessages.KIND_LIVE).put(RemoteMessages.ID, channelId)
            recordingId != null -> status.put(RemoteMessages.KIND, RemoteMessages.KIND_RECORDING).put(RemoteMessages.ID, recordingId)
            vodId != null -> status.put(RemoteMessages.KIND, RemoteMessages.KIND_VOD).put(RemoteMessages.ID, vodId)
        }
        return status
    }

    override fun onStop() {
        // Closed with Back, the app is left for good, and this activity is gone before a wait would end.
        // Otherwise the screensaver starting is told apart from leaving the app only by its broadcast,
        // which may come just after.
        if (isFinishing) leave() else handler.postDelayed(stopRemoteUnlessDreaming, DREAM_GRACE_MS)
        // What was just watched belongs in Watch Next.
        TvHomeJob.schedule(this)
        player?.let {
            // Persist the resume point now too, playing or paused, and tell the server, which Watch Next
            // is read from: the player surface only does so on dispose, which a kill of the backgrounded
            // process or a TV switched off skips, losing the position. Only ever save a real position
            // here, never clear (positionToSave returns 0 near the start/end), so a background blip
            // cannot wipe a valid resume point; clearing is the dispose path's job.
            viewModel.state.value.playing?.let { src ->
                val pos = PlaybackProgress.positionToSave(src, it.currentPosition, it.duration)
                if (pos > 0) {
                    viewModel.savePlaybackPosition(src.resumeKey, pos)
                    viewModel.reportProgress(src, it.currentPosition, it.duration)
                }
            }
            // Pause playback in the background (no decoding/audio); resume on return. playWhenReady, not
            // isPlaying: a player still buffering would otherwise start with sound behind the launcher.
            if (it.playWhenReady) {
                pausedForBackground = true
                it.playWhenReady = false
            }
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(stopRemoteUnlessDreaming)
        remote.start()
        RemoteKeepAliveService.start(this)
        // Back in the foreground: any memory pressure that shrank the buffer has passed.
        loadControl?.restoreFullBuffer()
        // Refresh the home screen row and Watch Next (and make sure their periodic sync is scheduled).
        TvHomeJob.schedule(this)
        if (pausedForBackground) {
            pausedForBackground = false
            player?.playWhenReady = true
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Shrink the forward buffer under memory pressure so it stops growing and frees its slack.
        loadControl?.onTrimMemory(level)
    }

    private fun stopRemote() {
        remote.stop()
        RemoteKeepAliveService.stop(this)
    }

    // The app is left for good, not just behind the screensaver where a phone may still call it: the
    // remote control stops unless it is kept at all times, and with nothing playing it is the moment for
    // an automatic update, which closes the app.
    private fun leave() {
        if (!remote.always) stopRemote()
        if (viewModel.state.value.playing == null) installFetchedUpdate(viewModel)
    }

    // Brought back to the front with the screen to be turned on, this activity ends the screensaver or
    // wakes the TV. Android lets an app come back from behind like this only when it may display over
    // other apps (see TvSettingsScreen); otherwise what was asked starts once someone wakes the TV.
    private fun wakeScreen() {
        turnScreenOn(true)
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
    }

    @Suppress("DEPRECATION")
    private fun turnScreenOn(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(on)
        } else if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    override fun onResume() {
        super.onResume()
        // Woken by a phone, or not: from now on the screen goes to sleep as usual.
        turnScreenOn(false)
        remote.takeAsked()?.let(::onRemoteCommand)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRemoteUnlessDreaming)
        runCatching { unregisterReceiver(screenReceiver) }
        remote.detach(this)
        if (!remote.always) stopRemote()
        player?.release()
        player = null
        loadControl = null
        super.onDestroy()
    }
}

@Composable
private fun TvPlayerSurface(
    source: PlaybackSource,
    player: ExoPlayer,
    onSavePosition: (String?, Long) -> Unit,
    onReportProgress: (PlaybackSource, Long, Long) -> Unit,
    fetchProgramWindow: suspend (String) -> ProgramWindow?,
    reResolve: suspend (PlaybackSource) -> PlaybackSource?,
    onPlaybackFailed: (PlaybackSource) -> Unit,
    onPlayNext: () -> Unit = {},
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    DisposableEffect(player) {
        // Mark the window secure while a stream is on screen (parity with mobile): it protects the
        // software/L3 decode path on top of Widevine L1 + HDCP. The screen is kept on while the stream
        // plays only, so the TV still goes to its screensaver and to sleep on a paused programme, as it
        // does idling on browse/login.
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val letScreenSleep = keepScreenOnWhilePlaying(window, player)
        onDispose {
            letScreenSleep()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    DisposableEffect(source) {
        val item = MediaItemFactory.create(source)
        if (source.startPositionMs > 0) player.setMediaItem(item, source.startPositionMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        val recovery = PlaybackRecovery(player, scope, source, reResolve) { onPlaybackFailed(source) }
        player.addListener(recovery)
        onDispose {
            player.removeListener(recovery)
            recovery.stop()
            val pos = player.currentPosition
            val dur = player.duration
            PlaybackProgress.closingToSave(source, pos, dur)?.let { onSavePosition(source.resumeKey, it) }
            onReportProgress(source, pos, dur)
            player.stop()
            player.clearMediaItems()
        }
    }
    // Track how far behind the live edge we are, so a focusable "back to live" pill appears once the
    // viewer has scrubbed into the DVR window of a live show (and disappears again at the edge).
    val liveWindow = remember { Timeline.Window() }
    var behindLive by remember { mutableStateOf(false) }
    // The on-screen programme's air-window, marked on the live bar; kept locally so a rollover re-scope
    // does not restart the stream. The TV bar is a position indicator (the remote scrubs with the
    // rewind/forward buttons), so it carries no seek callback.
    var programWindow by remember(source) { mutableStateOf(source.programWindow) }
    var barGeometry by remember { mutableStateOf<LiveBarGeometry?>(null) }
    // A "depuis le début" start, or a rewound programme handed over by the phone, asks playback to begin
    // that far behind the live edge; the player joins at the edge first, so seek back to what aired then
    // once the live window has loaded, then stop retrying.
    var pendingRewind by remember(source) { mutableStateOf(source.isLive && source.liveRewindOffsetMs > 0L) }
    val rewindTo = remember(source) { System.currentTimeMillis() - source.liveRewindOffsetMs }
    var controlsVisible by remember { mutableStateOf(true) }
    // The remote only reaches the PlayerView (OK or a direction shows the controls) while that view
    // holds the Android focus; otherwise the keys stop at Compose and the controls cannot be brought
    // back once hidden. A Compose focus request does not move the focus into the embedded view, so ask
    // the view itself: on start, when the back-to-live pill shows or goes away, and each time the
    // controls hide, since the focused control button then vanishes and Android hands the focus to
    // the Compose host. This holds behind the live edge too, where OK must not land on the pill.
    // Only when the view does not hold it already: asking again moves it to the first control.
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    LaunchedEffect(behindLive, playerView) { playerView?.takeUnless { it.hasFocus() }?.requestFocus() }
    // Up from the controls makes the view's own focus search leave the view for the Compose host,
    // where the remote then does nothing until the controls hide. So Up is handled here: from the
    // bottom bar it goes to play/pause, and from the main row it stays put, except behind the live
    // edge where it selects the back-to-live pill (Compose, so out of the view's reach) while the
    // view keeps the focus. OK then goes back to live, Down returns to the controls, and any other
    // key or the controls hiding leaves the pill. Up from the time bar of a catch-up is left to the
    // view, whose own search goes up to the main row from there.
    var pillSelected by remember { mutableStateOf(false) }
    LaunchedEffect(behindLive, controlsVisible) { if (!behindLive || !controlsVisible) pillSelected = false }
    fun remoteKeyUsed(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val bottomBar = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val mainRow = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)
        return when {
            pillSelected && ok -> {
                if (!down) {
                    pillSelected = false
                    player.seekToDefaultPosition()
                }
                true
            }
            pillSelected && up -> true
            pillSelected -> {
                if (down) pillSelected = false
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            }
            down && up && bottomBar?.hasFocus() == true ->
                playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus() == true
            controlsVisible && down && up && (mainRow?.hasFocus() == true || playerView?.isFocused == true) -> {
                if (behindLive) pillSelected = true
                true
            }
            else -> false
        }
    }
    // Called by the player view before its own key handling; true when the key was used here. Such a
    // key never reaches the controls, so restart their hide timer by hand.
    fun remoteKey(event: KeyEvent): Boolean = remoteKeyUsed(event).also { if (it) playerView?.showController() }
    // Per source, not per kind: another live channel has its own programme, rewind and channel to ask.
    LaunchedEffect(source) {
        // VOD and recordings have no live edge, so do not poll: stay at "not behind" without a 1s loop.
        if (!source.isLive) {
            behindLive = false
            barGeometry = null
            return@LaunchedEffect
        }
        var nextRolloverMs = 0L
        while (true) {
            behindLive = LivePlayback.behindLiveEdgeMs(player, liveWindow) > LivePlayback.LIVE_REWIND_THRESHOLD_MS
            barGeometry = LivePlayback.liveBarGeometry(player, liveWindow, programWindow)
            if (pendingRewind && LivePlayback.seekToTimeIfReady(player, liveWindow, rewindTo)) pendingRewind = false
            val pw = programWindow
            val edge = LivePlayback.liveEdgeEpochMs(player, liveWindow)
            val now = System.currentTimeMillis()
            if (pw != null && edge != null && edge >= pw.endMs && now >= nextRolloverMs) {
                nextRolloverMs = now + 15_000
                source.originChannelId?.let { channelId ->
                    fetchProgramWindow(channelId)?.takeIf { it.endMs > pw.endMs }?.let { programWindow = it }
                }
            }
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                object : PlayerView(context) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                        remoteKey(event) || super.dispatchKeyEvent(event)
                }.apply {
                    this.player = player
                    playerView = this
                    // The programme bar replaces the default time bar, which spans the bare 4h DVR
                    // window with no show markers.
                    LivePlayback.useProgramBar(this, source.isLive)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                            if (!controlsVisible) post { requestFocus() }
                        },
                    )
                }
            },
            update = { LivePlayback.useProgramBar(it, source.isLive) },
            onRelease = {
                playerView = null
                it.player = null
                it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
            },
            modifier = Modifier.fillMaxSize(),
        )
        barGeometry?.let { bar ->
            if (controlsVisible) {
                val pw = programWindow?.takeIf { bar.hasProgramBand }
                LiveProgramBar(
                    playedFraction = bar.playedFraction,
                    liveFraction = bar.liveFraction,
                    seekFloorFraction = bar.seekFloorFraction,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 48.dp, end = 48.dp, bottom = LiveProgramBarBottomOffset),
                    startLabel = pw?.let { LivePlayback.clockLabel(it.startMs) },
                    endLabel = pw?.let { LivePlayback.clockLabel(it.endMs) },
                )
            }
        }
        if (behindLive && controlsVisible) {
            // Shown with the controls, like the bar, and selected from them (see above) rather than
            // focused, so the OK meant to pause or show the controls does not jump back to live.
            ReturnToLiveButton(
                onClick = { player.seekToDefaultPosition() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .then(if (pillSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
            )
        }
        // The next episode, near the end of this one; its play button takes the focus.
        source.upNext?.let { upNext ->
            UpNextSlate(
                upNext,
                position = { player.currentPosition },
                duration = { player.duration },
                onPlayNext = onPlayNext,
                button = { label, primary, onClick, mod -> TvButton(onClick = onClick, modifier = mod, highlighted = primary) { Text(label) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
                takeFocus = true,
                // Back to the player view, or the remote no longer reaches the controls.
                onLeave = { playerView?.let { v -> v.post { if (!v.hasFocus()) v.requestFocus() } } },
            )
        }
    }
}
