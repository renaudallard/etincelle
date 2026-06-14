// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.cast.CastPlayerController
import it.allard.etincelle.core.cast.CastUiState
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleApp).container.repository)
    }
    private var player: ExoPlayer? = null
    private var castController: CastPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        val controller = (application as EtincelleApp).castContext?.let {
            CastPlayerController(
                this, it, exo,
                reResolve = viewModel::reResolve,
                onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
            )
        }
        castController = controller

        val onPlay: (PlaybackSource) -> Unit = { source ->
            val item = MediaItemFactory.create(source)
            if (controller != null) {
                controller.play(item, source.startPositionMs)
            } else {
                if (source.startPositionMs > 0) exo.setMediaItem(item, source.startPositionMs) else exo.setMediaItem(item)
                exo.prepare()
                exo.playWhenReady = true
            }
        }
        val onStop: (PlaybackSource) -> Unit = { source ->
            val (pos, dur) = controller?.stopPlayback() ?: run {
                val p = exo.currentPosition
                val d = exo.duration
                exo.stop()
                exo.clearMediaItems()
                p to d
            }
            viewModel.savePlaybackPosition(source.resumeKey, PlaybackProgress.positionToSave(pos, dur))
        }
        val playerFlow: StateFlow<Player> = controller?.currentPlayer ?: MutableStateFlow<Player>(exo).asStateFlow()

        setContent {
            EtincelleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val castState by (controller?.state ?: EMPTY_CAST_FLOW).collectAsStateWithLifecycle()
                val currentPlayer by playerFlow.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        state = state,
                        currentPlayer = currentPlayer,
                        vm = viewModel,
                        castState = castState,
                        onPlay = onPlay,
                        onStop = onStop,
                        onCastConnect = { controller?.connectTo(it) },
                        onCastDisconnect = { controller?.disconnect() },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        castController?.start()
    }

    override fun onStop() {
        castController?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        castController?.release()
        player?.release()
        player = null
        super.onDestroy()
    }
}

private val EMPTY_CAST_FLOW: StateFlow<CastUiState> = MutableStateFlow(CastUiState()).asStateFlow()

/** Brand-pack vector icon for the tabs it covers; the rest keep their emoji glyph. */
@DrawableRes
private fun tabIconRes(tab: Tab): Int? = when (tab) {
    Tab.LIVE -> R.drawable.ic_direct
    Tab.GUIDE -> R.drawable.ic_grille
    Tab.MOVIES -> R.drawable.ic_films
    Tab.SERIES -> R.drawable.ic_series
    Tab.SEARCH -> R.drawable.ic_recherche
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    state: UiState,
    currentPlayer: Player,
    vm: MainViewModel,
    castState: CastUiState,
    onPlay: (PlaybackSource) -> Unit,
    onStop: (PlaybackSource) -> Unit,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
) {
    val playing = state.playing
    val detail = state.detail
    when {
        state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        playing != null -> {
            BackHandler { vm.stopPlaying() }
            PlayerSurface(
                playing, currentPlayer, castState,
                onPlay, onStop, onCastConnect, onCastDisconnect,
            )
        }

        detail != null -> {
            BackHandler { vm.closeDetail() }
            ProgramDetailScreen(
                detail = detail,
                busy = state.busy,
                error = state.error,
                onWatch = { vm.watchDetail() },
                onBack = { vm.closeDetail() },
            )
        }

        state.loggedIn -> {
            BackHandler(enabled = state.canGoBack) { vm.back() }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(state.current?.title ?: state.tab.label) },
                        navigationIcon = {
                            if (state.canGoBack) {
                                IconButton(onClick = { vm.back() }) { Text("←") }
                            }
                        },
                        actions = {
                            CastButton(castState, onCastConnect, onCastDisconnect)
                            TextButton(onClick = { vm.logout() }) { Text("Déconnexion") }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = state.tab == tab && !state.canGoBack,
                                onClick = { vm.selectTab(tab) },
                                icon = {
                                    val iconRes = tabIconRes(tab)
                                    if (iconRes != null) {
                                        Icon(painterResource(iconRes), contentDescription = tab.label)
                                    } else {
                                        Text(tab.icon)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                if (state.tab == Tab.SEARCH) {
                    SearchScreen(
                        rails = state.current?.rails.orEmpty(),
                        busy = state.busy,
                        error = state.error,
                        onSubmit = vm::search,
                        onCardClick = vm::onCardClick,
                        modifier = modifier,
                    )
                } else {
                    PageContent(
                        rails = state.current?.rails.orEmpty(),
                        busy = state.busy,
                        error = state.error,
                        onCardClick = vm::onCardClick,
                        modifier = modifier,
                    )
                }
            }
        }

        else -> LoginScreen(state.busy, state.error, vm::login)
    }
}

@Composable
private fun PlayerSurface(
    source: PlaybackSource,
    currentPlayer: Player,
    castState: CastUiState,
    onPlay: (PlaybackSource) -> Unit,
    onStop: (PlaybackSource) -> Unit,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
) {
    DisposableEffect(source) {
        onPlay(source)
        onDispose { onStop(source) }
    }
    Box(Modifier.fillMaxSize()) {
        val deviceName = castState.connectedDeviceName
        if (deviceName != null) {
            CastingPlaceholder(source.title, deviceName)
        } else {
            AndroidView(
                factory = { context -> PlayerView(context).apply { player = currentPlayer } },
                update = { it.player = currentPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            CastButton(castState, onCastConnect, onCastDisconnect)
        }
    }
}
