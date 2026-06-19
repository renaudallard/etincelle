// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.cast.CastPlayerController
import it.allard.etincelle.core.cast.CastUiState
import it.allard.etincelle.core.designsystem.R as DesignR
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState
import kotlinx.coroutines.delay
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
    private var pausedForBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exo = ExoPlayer.Builder(this)
            // Pause on audio-focus loss (calls, other media) and on headphones unplugged.
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
        val controller = (application as EtincelleApp).castContext?.let {
            CastPlayerController(
                this, it, exo,
                reResolve = viewModel::reResolve,
                onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                onPlaybackStopped = { viewModel.stopPlaying() },
                sessionProvider = { (application as EtincelleApp).container.repository.currentSession() },
            )
        }
        castController = controller
        // Only on a fresh launch; a process recreation must not replay the original deep link.
        if (savedInstanceState == null) handleDeepLink(intent)
        viewModel.checkForUpdate(BuildConfig.VERSION_NAME)
        viewModel.setHideLocked(LocalPrefs.hideLocked(this))

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
            if (controller != null) {
                // null while casting: the stream keeps playing on the TV, so nothing to stop or save.
                controller.stopPlayback()?.let { (pos, dur) ->
                    viewModel.savePlaybackPosition(source.resumeKey, PlaybackProgress.positionToSave(pos, dur))
                    viewModel.reportProgress(source, pos, dur)
                }
            } else {
                val pos = exo.currentPosition
                val dur = exo.duration
                exo.stop()
                exo.clearMediaItems()
                viewModel.savePlaybackPosition(source.resumeKey, PlaybackProgress.positionToSave(pos, dur))
                viewModel.reportProgress(source, pos, dur)
            }
        }
        val playerFlow: StateFlow<Player> = controller?.currentPlayer ?: MutableStateFlow<Player>(exo).asStateFlow()

        setContent {
            EtincelleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val castState by (controller?.state ?: EMPTY_CAST_FLOW).collectAsStateWithLifecycle()
                val currentPlayer by playerFlow.collectAsStateWithLifecycle()
                // Returning to the foreground refreshes time-sensitive pages (the guide goes stale).
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshOnResume() }
                // Logging out (manually or on a dead session) must end any active Cast session, so the
                // Chromecast does not keep playing the previous account's stream. Skip the cold-start
                // checking phase (and process-death restore) so it only fires on a real logout.
                LaunchedEffect(state.loggedIn) {
                    if (!state.checking && !state.loggedIn) controller?.disconnect()
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        state = state,
                        currentPlayer = currentPlayer,
                        onLocalPlayer = currentPlayer === exo,
                        vm = viewModel,
                        castState = castState,
                        onPlay = onPlay,
                        onStop = onStop,
                        // connectTo returns false when the tapped route is already gone (a Chromecast
                        // that dropped between opening the picker and tapping); tell the user instead
                        // of failing silently with no connect animation.
                        onCastConnect = {
                            if (controller?.connectTo(it) == false) {
                                Toast.makeText(this@MainActivity, "Connexion impossible, réessayez", Toast.LENGTH_SHORT).show()
                            }
                        },
                        // "Cet appareil" in the picker: bring playback back to this phone (not a stop).
                        onCastDisconnect = { controller?.returnToThisDevice() },
                    )
                }
                state.update?.let { up ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissUpdate() },
                        title = { Text("Mise à jour disponible") },
                        text = { Text("La version ${up.version} est disponible. Voulez-vous la télécharger ?") },
                        confirmButton = {
                            TextButton(onClick = {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(up.apkUrl))) }
                                    .onFailure {
                                        Toast.makeText(this@MainActivity, "Impossible d'ouvrir le téléchargement", Toast.LENGTH_SHORT).show()
                                    }
                                viewModel.dismissUpdate()
                            }) { Text("Télécharger") }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.dismissUpdate() }) { Text("Plus tard") }
                        },
                    )
                }
            }
        }
    }

    // While casting, the phone's volume keys drive the cast device (the local stream is idle), and the
    // system volume HUD is suppressed by consuming the event. Local playback is untouched.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controller = castController
        // Gate on a live session, not the UI isCasting flag: when the session is already gone the keys
        // must fall through to the system so they still change the phone's own volume.
        if (controller != null && controller.isCastSessionActive) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) controller.adjustCastVolume(true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) controller.adjustCastVolume(false)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    // Mute is a discrete toggle: fire once per press, not on auto-repeat while held.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) controller.toggleCastMute()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
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

    override fun onStart() {
        super.onStart()
        castController?.start()
        if (pausedForBackground) {
            pausedForBackground = false
            player?.playWhenReady = true
        }
    }

    override fun onStop() {
        castController?.stop()
        // Pause local playback in the background (no decoding/audio); resume on return. While casting
        // the local player is idle, so this is a no-op and the Chromecast keeps playing.
        player?.let {
            if (it.isPlaying) {
                pausedForBackground = true
                it.playWhenReady = false
            }
        }
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

// How long the cast volume overlay stays up after the last volume-key press.
private const val VOLUME_OVERLAY_MS = 1500L

/** Brand-pack vector icon for the tabs it covers; the rest keep their emoji glyph. */
@DrawableRes
private fun tabIconRes(tab: Tab): Int? = when (tab) {
    Tab.HOME -> DesignR.drawable.ic_accueil
    Tab.LIVE -> DesignR.drawable.ic_direct
    Tab.GUIDE -> DesignR.drawable.ic_grille
    Tab.MOVIES -> DesignR.drawable.ic_films
    Tab.SERIES -> DesignR.drawable.ic_series
    Tab.SEARCH -> DesignR.drawable.ic_recherche
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    state: UiState,
    currentPlayer: Player,
    onLocalPlayer: Boolean,
    vm: MainViewModel,
    castState: CastUiState,
    onPlay: (PlaybackSource) -> Unit,
    onStop: (PlaybackSource) -> Unit,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
) {
    val playing = state.playing
    val detail = state.detail
    val context = LocalContext.current
    // The immersive local player takes the screen only when the local player is actually current and
    // we are not casting. Keying on "current player is local" (not just !isCasting) keeps a brief
    // device-to-device transfer gap on the show page instead of flashing an idle cast surface.
    val playerFullscreen = playing != null && onLocalPlayer && !castState.isCasting
    // The cast bar is shown (and the nav bar then drops its bottom inset) when there is a device to
    // name and the full-screen player is not up - except while a connect is in flight, so casting
    // from the player still shows the "Connexion à …" animation instead of no feedback at all.
    val showCastBar = castState.showStatus &&
        (!playerFullscreen || castState.connecting) &&
        castState.statusDeviceName != null
    // Grid density (cards per row on the "tout voir" pages); persisted, set live from the settings.
    var gridColumns by remember { mutableStateOf(LocalPrefs.gridColumns(context)) }

    // Playback follows `playing`, not the player screen. While casting we keep the show page up (the
    // stream is ambient on the TV), yet the stream must keep running, so this effect stays mounted as
    // long as something is playing, whichever screen is on top.
    DisposableEffect(playing) {
        if (playing != null) onPlay(playing)
        onDispose { if (playing != null) onStop(playing) }
    }

    // Flash a brief volume overlay on each cast volume-key press (the system bar is suppressed).
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(castState.volumeNonce) {
        if (castState.volumeNonce > 0) {
            showVolume = true
            delay(VOLUME_OVERLAY_MS)
            showVolume = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when {
                    state.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    // While casting we stay on the show page (the stream is ambient on the TV); only take
                    // over the whole screen with the player when playing locally on the phone.
                    playerFullscreen -> {
                        BackHandler { vm.stopPlaying() }
                        PlayerSurface(playing!!, currentPlayer, castState, onCastConnect, onCastDisconnect)
                    }

                    detail != null -> {
                        BackHandler { vm.closeDetail() }
                        ProgramDetailScreen(
                            detail = detail,
                            busy = state.busy,
                            error = state.error,
                            info = state.info,
                            onWatch = { vm.watchDetail() },
                            onRecord = { vm.recordDetail() },
                            onWatchRecording = { vm.watchRecording(it) },
                            onBack = { vm.closeDetail() },
                            onEpisode = { vm.onCardClick(it) },
                            isRecording = state.detailRecordingAssetId != null,
                            castButton = { CastButton(castState, onCastConnect, onCastDisconnect) },
                        )
                    }

                    state.settings -> {
                        BackHandler { vm.closeSettings() }
                        SettingsScreen(
                            onBack = { vm.closeSettings() },
                            onLogout = { vm.logout() },
                            hideLocked = state.hideLocked,
                            onHideLocked = vm::setHideLocked,
                            gridColumns = gridColumns,
                            onGridColumns = { gridColumns = it },
                            appVersion = BuildConfig.VERSION_NAME,
                            checkingUpdate = state.checkingUpdate,
                            updateStatus = state.updateStatus,
                            onCheckUpdate = { vm.checkForUpdateNow(BuildConfig.VERSION_NAME) },
                            onConnectTv = { vm.confirmTvCode(it) },
                            onClearTvConnect = { vm.clearTvConnect() },
                            connectInfo = state.tvConnectInfo,
                            connectError = state.tvConnectError,
                            connecting = state.busy,
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
                                        TextButton(onClick = { vm.openSettings() }) { Text("Paramètres") }
                                    },
                                )
                            },
                            bottomBar = {
                                // With the cast bar sitting below, drop the nav bar's own bottom inset so
                                // the two do not stack a double gap above the gesture area. Gate on the
                                // same value as the bar so the inset is never dropped without a bar.
                                NavigationBar(
                                    windowInsets = if (showCastBar) {
                                        WindowInsets(0, 0, 0, 0)
                                    } else {
                                        NavigationBarDefaults.windowInsets
                                    },
                                ) {
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
                                            label = {
                                                Text(
                                                    tab.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                        ) { padding ->
                            val modifier = Modifier.padding(padding)
                            if (state.tab == Tab.SEARCH) {
                                SearchScreen(
                                    rails = state.visibleRails,
                                    busy = state.busy,
                                    error = state.error,
                                    onSubmit = vm::search,
                                    onCardClick = vm::onCardClick,
                                    onSeeAll = vm::onRailSeeAll,
                                    refreshing = state.refreshing,
                                    onRefresh = vm::refreshCurrent,
                                    modifier = modifier,
                                )
                            } else if (state.current?.isGrid == true) {
                                GridContent(
                                    rails = state.visibleRails,
                                    busy = state.busy,
                                    error = state.error,
                                    onCardClick = vm::onCardClick,
                                    refreshing = state.refreshing,
                                    onRefresh = vm::refreshCurrent,
                                    columns = gridColumns,
                                    pageTitle = state.current?.title,
                                    modifier = modifier,
                                )
                            } else {
                                PageContent(
                                    rails = state.visibleRails,
                                    busy = state.busy,
                                    error = state.error,
                                    onCardClick = vm::onCardClick,
                                    onSeeAll = vm::onRailSeeAll,
                                    refreshing = state.refreshing,
                                    onRefresh = vm::refreshCurrent,
                                    modifier = modifier,
                                )
                            }
                        }
                    }

                    else -> LoginScreen(state.busy, state.error, vm::login)
                }
            }
            if (showCastBar) {
                CastStatusBar(castState)
            }
        }
        if (castState.isCasting && showVolume) {
            CastVolumeOverlay(
                level = castState.volumeLevel,
                deviceName = castState.connectedDeviceName,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun PlayerSurface(
    source: PlaybackSource,
    currentPlayer: Player,
    castState: CastUiState,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        // Keep the screen awake and block screenshots/recording while the video plays locally on the
        // phone. This surface is shown only for local playback (casting stays on the show page).
        val window = (view.context as? Activity)?.window
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE
        window?.addFlags(flags)
        onDispose { window?.clearFlags(flags) }
    }
    // Track how far behind the live edge we are, so a "back to live" pill can appear once the viewer
    // has scrubbed into the DVR window of a live show (and disappear again at the edge).
    val liveWindow = remember { Timeline.Window() }
    var behindLive by remember { mutableStateOf(false) }
    LaunchedEffect(currentPlayer, source.isLive) {
        while (true) {
            behindLive = source.isLive &&
                LivePlayback.behindLiveEdgeMs(currentPlayer, liveWindow) > LivePlayback.LIVE_REWIND_THRESHOLD_MS
            delay(1000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> PlayerView(context).apply { player = currentPlayer } },
            update = { it.player = currentPlayer },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
            CastButton(castState, onCastConnect, onCastDisconnect)
        }
        if (behindLive) {
            ReturnToLiveButton(
                onClick = { currentPlayer.seekToDefaultPosition() },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            )
        }
    }
}
