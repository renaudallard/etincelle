// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.LiveProgramBar
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.LiveBarGeometry
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleTvApp).container.repository)
    }
    private var player: ExoPlayer? = null
    private var pausedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exo = ExoPlayer.Builder(this)
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
        viewModel.checkForUpdate(BuildConfig.VERSION_NAME)
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
                    Box(Modifier.fillMaxSize()) {
                    when {
                        state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        playing != null -> {
                            BackHandler { viewModel.stopPlaying() }
                            TvPlayerSurface(
                                playing, exo, viewModel::savePlaybackPosition, viewModel::reportProgress,
                                viewModel::liveProgramWindow,
                            )
                        }

                        detail != null -> {
                            BackHandler { viewModel.closeDetail() }
                            TvProgramDetailScreen(
                                detail, state.busy, state.error, state.info,
                                viewModel::watchDetail, viewModel::recordDetail, viewModel::watchRecording,
                                viewModel::onCardClick,
                                isRecording = state.detailRecordingAssetId != null,
                            )
                        }

                        state.settings -> {
                            BackHandler { viewModel.closeSettings() }
                            TvSettingsScreen(
                                onBack = viewModel::closeSettings,
                                onLogout = viewModel::logout,
                                gridColumns = gridColumns,
                                onGridColumns = { gridColumns = it },
                            )
                        }

                        state.loggedIn -> {
                            BackHandler(enabled = state.canGoBack) { viewModel.back() }
                            TvBrowseScreen(
                                state, viewModel::selectTab, viewModel::onCardClick, viewModel::search,
                                viewModel::openSettings, viewModel::onRailSeeAll, gridColumns,
                            )
                        }

                        else -> TvLoginScreen(
                            state.pairingCode, state.busy, state.error,
                            viewModel::loginWithCode, viewModel::stopPairingPoll, viewModel::cancelCodeLogin, viewModel::login,
                        )
                    }
                    state.update?.let { up ->
                        TvUpdateDialog(
                            info = up,
                            onDownload = {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(up.apkUrl))) }
                                viewModel.dismissUpdate()
                            },
                            onDismiss = viewModel::dismissUpdate,
                        )
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
                    val pos = PlaybackProgress.positionToSave(it.currentPosition, it.duration)
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
        if (pausedForBackground) {
            pausedForBackground = false
            player?.playWhenReady = true
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
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
            onSavePosition(source.resumeKey, PlaybackProgress.positionToSave(pos, dur))
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
    var controlsVisible by remember { mutableStateOf(true) }
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
                PlayerView(context).apply {
                    this.player = player
                    // The programme bar replaces the default time bar, which spans the bare 4h DVR
                    // window with no show markers.
                    if (source.isLive) findViewById<View?>(androidx.media3.ui.R.id.exo_progress)?.visibility = View.GONE
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                }
            },
            update = {
                if (source.isLive) it.findViewById<View?>(androidx.media3.ui.R.id.exo_progress)?.visibility = View.GONE
            },
            onRelease = {
                it.player = null
                it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
            },
            modifier = Modifier.fillMaxSize(),
        )
        barGeometry?.let { bar ->
            if (controlsVisible) {
                LiveProgramBar(
                    playedFraction = bar.playedFraction,
                    liveFraction = bar.liveFraction,
                    showStartFraction = if (bar.hasProgramBand) bar.showStartFraction else 0f,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(horizontal = 48.dp, vertical = 24.dp),
                )
            }
        }
        if (behindLive) {
            // The PlayerView controller owns D-pad focus, so claim it for the pill while it is shown
            // (as the rest of the TV UI does) - otherwise the remote could never reach it.
            val pillFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { pillFocus.requestFocus() } }
            ReturnToLiveButton(
                onClick = { player.seekToDefaultPosition() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).focusRequester(pillFocus),
            )
        }
    }
}
