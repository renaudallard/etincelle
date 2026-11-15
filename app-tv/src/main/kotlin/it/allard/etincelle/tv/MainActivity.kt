// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Activity
import android.content.Intent
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.LiveProgramBar
import it.allard.etincelle.core.designsystem.LiveProgramBarBottomOffset
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.designsystem.UpNextSlate
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.AdaptiveLoadControl
import it.allard.etincelle.core.player.LiveBarGeometry
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.installUpdate
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleTvApp).container.repository, UpdateChecker(BuildConfig.VERSION_NAME, "tv"))
    }
    private var player: ExoPlayer? = null
    private var loadControl: AdaptiveLoadControl? = null
    private var pausedForBackground = false

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
        player = exo
        // Only on a fresh launch; a process recreation must not replay the original deep link.
        if (savedInstanceState == null) handleDeepLink(intent)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchForUpdates() } }
        setContent {
            EtincelleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
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
                                viewModel::liveProgramWindow, viewModel::playUpNext,
                            )
                        }

                        detail != null -> {
                            BackHandler { viewModel.closeDetail() }
                            TvProgramDetailScreen(
                                detail, state.busy, state.error, state.info,
                                viewModel::watchDetail, viewModel::startOverDetail, viewModel::recordDetail, viewModel::watchRecording,
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
                    state.update?.let { up ->
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

    override fun onStop() {
        // Pause playback in the background (no decoding/audio); resume on return.
        player?.let {
            if (it.isPlaying) {
                // Persist the resume point now too: the player surface only saves on dispose, which a
                // kill of the backgrounded process skips, losing the position. Only ever save a real
                // position here, never clear (positionToSave returns 0 near the start/end), so a
                // background blip cannot wipe a valid resume point; clearing is the dispose path's job.
                viewModel.state.value.playing?.let { src ->
                    val pos = PlaybackProgress.positionToSave(src, it.currentPosition, it.duration)
                    if (pos > 0) viewModel.savePlaybackPosition(src.resumeKey, pos)
                }
                pausedForBackground = true
                it.playWhenReady = false
            }
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground: any memory pressure that shrank the buffer has passed.
        loadControl?.restoreFullBuffer()
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

    override fun onDestroy() {
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
    onPlayNext: () -> Unit = {},
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Keep the screen on and mark the window secure while a stream is on screen (parity with mobile):
        // secure protects the software/L3 decode path on top of Widevine L1 + HDCP, and scoping
        // keep-screen-on here lets the TV sleep when idling on browse/login instead of staying awake.
        val window = (view.context as? Activity)?.window
        val flags = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        window?.addFlags(flags)
        onDispose { window?.clearFlags(flags) }
    }
    DisposableEffect(source) {
        val item = MediaItemFactory.create(source)
        if (source.startPositionMs > 0) player.setMediaItem(item, source.startPositionMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        onDispose {
            val pos = player.currentPosition
            val dur = player.duration
            onSavePosition(source.resumeKey, PlaybackProgress.positionToSave(source, pos, dur))
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
    // A "depuis le début" start asks playback to begin at the current show's start; the player joins at
    // the live edge first, so seek back once the live window has loaded, then stop retrying.
    var pendingStartOver by remember(source) { mutableStateOf(source.isLive && source.liveRewindOffsetMs > 0L) }
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
    LaunchedEffect(source.isLive) {
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
            if (pendingStartOver && LivePlayback.seekToProgramStartIfReady(player, liveWindow, programWindow)) {
                pendingStartOver = false
            }
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
            )
        }
    }
}
