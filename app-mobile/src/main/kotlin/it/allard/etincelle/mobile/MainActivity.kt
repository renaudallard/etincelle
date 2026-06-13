// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleApp).container.repository)
    }
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        setContent {
            EtincelleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(state, exo, viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(state: UiState, player: ExoPlayer, vm: MainViewModel) {
    val playing = state.playing
    when {
        state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        playing != null -> {
            BackHandler { vm.stopPlaying() }
            PlayerSurface(playing, player, vm::savePlaybackPosition)
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
                                icon = { Text(tab.icon) },
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
private fun PlayerSurface(source: PlaybackSource, player: ExoPlayer, onSavePosition: (String?, Long) -> Unit) {
    DisposableEffect(source) {
        val item = MediaItemFactory.create(source)
        if (source.startPositionMs > 0) player.setMediaItem(item, source.startPositionMs) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        onDispose {
            onSavePosition(source.resumeKey, PlaybackProgress.positionToSave(player.currentPosition, player.duration))
            player.stop()
            player.clearMediaItems()
        }
    }
    AndroidView(
        factory = { context -> PlayerView(context).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}
