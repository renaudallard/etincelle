// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleTvApp).container.repository)
    }
    private var player: ExoPlayer? = null
    private var pausedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    Box(Modifier.fillMaxSize()) {
                    when {
                        state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }

                        playing != null -> {
                            BackHandler { viewModel.stopPlaying() }
                            TvPlayerSurface(playing, exo, viewModel::savePlaybackPosition, viewModel::reportProgress)
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
                            TvSettingsScreen(onBack = viewModel::closeSettings, onLogout = viewModel::logout)
                        }

                        state.loggedIn -> {
                            BackHandler(enabled = state.canGoBack) { viewModel.back() }
                            TvBrowseScreen(
                                state, viewModel::selectTab, viewModel::onCardClick, viewModel::search,
                                viewModel::openSettings, viewModel::onRailSeeAll,
                            )
                        }

                        else -> TvLoginScreen(state.busy, state.error, viewModel::login)
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
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Mark the window secure while a stream is on screen (parity with mobile); protects the
        // software/L3 decode path on top of Widevine L1 + HDCP.
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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
    AndroidView(
        factory = { context -> PlayerView(context).apply { this.player = player } },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
