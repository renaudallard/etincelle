// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.sp
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.cast.CastPlayerController
import it.allard.etincelle.core.cast.CastUiState
import it.allard.etincelle.core.cast.LiveSkipCastPlayer
import it.allard.etincelle.core.designsystem.LiveProgramBar
import it.allard.etincelle.core.designsystem.LiveProgramBarBottomOffset
import it.allard.etincelle.core.designsystem.R as DesignR
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.theme.BrandBlack
import it.allard.etincelle.core.designsystem.theme.BrandYellow
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.designsystem.UpNextSlate
import it.allard.etincelle.core.designsystem.UpNextSlateState
import it.allard.etincelle.core.designsystem.UpNextSlates
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.AdaptiveLoadControl
import it.allard.etincelle.core.player.LiveBarGeometry
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.player.PlaybackRecovery
import it.allard.etincelle.core.player.keepScreenOnWhilePlaying
import it.allard.etincelle.core.ui.installFetchedUpdate
import it.allard.etincelle.core.ui.installUpdate
import it.allard.etincelle.core.ui.AutoUpdate
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UiState
import it.allard.etincelle.core.ui.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleApp).container.repository, UpdateChecker(BuildConfig.VERSION_NAME, "mobile"), AutoUpdate(this).takeIf { BuildConfig.SELF_UPDATE })
    }
    private var player: ExoPlayer? = null
    private var castController: CastPlayerController? = null
    // The next-episode countdown of what plays, shared by the loop that runs it and the slate on screen.
    private val upNextSlates = UpNextSlates()
    private var loadControl: AdaptiveLoadControl? = null
    private var mediaSession: MediaSession? = null
    // Stops what plays and saves its resume point; set in onCreate, run when the activity ends.
    private var endPlayback: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val control = AdaptiveLoadControl.create(this)
        loadControl = control
        val exo = ExoPlayer.Builder(this)
            // Buffer further ahead for replays/recordings/VOD so a brief drop drains the buffer
            // instead of stalling; sized to the device heap and trimmed under memory pressure.
            .setLoadControl(control)
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
                remoteKey = { (application as EtincelleApp).container.repository.remoteKey() },
            )
        }
        castController = controller
        // Only on a fresh launch; a process recreation must not replay the original deep link, nor the
        // app reopened from the recent apps, which Android starts with the intent it was first opened by.
        val reopened = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !reopened) handleDeepLink(intent)
        // A build from Google Play is updated by Google Play, and may not update itself.
        if (BuildConfig.SELF_UPDATE) lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchForUpdates() } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepPageFresh() } }
        viewModel.setHideLocked(LocalPrefs.hideLocked(this))

        // Without Google Play services there is no cast controller, nor the recovery from playback errors
        // it holds: the player then gets the one the TV app uses.
        var recovery: PlaybackRecovery? = null
        val dropRecovery = {
            recovery?.let {
                exo.removeListener(it)
                it.stop()
            }
            recovery = null
        }
        val onPlay: (PlaybackSource) -> Unit = { source ->
            // Hosts the media session so playback can go on in the background. The next episode starts
            // from the background, where Android may refuse the call; the service then still runs from
            // the episode before, whose playback kept it in the foreground.
            runCatching { startService(Intent(this, PlaybackService::class.java)) }
            val item = MediaItemFactory.create(source)
            if (controller != null) {
                controller.play(item, source.startPositionMs)
            } else {
                dropRecovery()
                if (source.startPositionMs > 0) exo.setMediaItem(item, source.startPositionMs) else exo.setMediaItem(item)
                exo.prepare()
                exo.playWhenReady = true
                recovery = PlaybackRecovery(exo, lifecycleScope, source, viewModel::reResolve) { viewModel.playbackFailed(source) }
                    .also(exo::addListener)
            }
        }
        val onStop: (PlaybackSource) -> Unit = { source ->
            if (controller != null) {
                // null while casting: the stream keeps playing on the TV, so nothing to stop or save.
                controller.stopPlayback()?.let { (pos, dur) ->
                    PlaybackProgress.closingToSave(source, pos, dur)?.let { viewModel.savePlaybackPosition(source.resumeKey, it) }
                    viewModel.reportProgress(source, pos, dur)
                }
            } else {
                // Given up on in the middle of a reload, the player no longer knows its duration.
                val known = recovery?.durationMs ?: C.TIME_UNSET
                dropRecovery()
                val pos = exo.currentPosition
                val dur = exo.duration.takeIf { it > 0 } ?: known
                exo.stop()
                exo.clearMediaItems()
                PlaybackProgress.closingToSave(source, pos, dur)?.let { viewModel.savePlaybackPosition(source.resumeKey, it) }
                viewModel.reportProgress(source, pos, dur)
            }
        }
        val playerFlow: StateFlow<Player> = controller?.currentPlayer ?: MutableStateFlow<Player>(exo).asStateFlow()
        // The media notification and lock screen control what plays: this phone, or the Chromecast
        // during a cast. The Cast framework posts no notification of its own here (its notification
        // service is not declared), so the session follows the cast player too.
        val session = PlaybackService.createSession(this, exo)
        mediaSession = session
        PlaybackService.session = session
        lifecycleScope.launch { playerFlow.collect { session.player = it } }
        // The phone resumes from its own store, which nothing writes during a cast, and most ends of a
        // cast (stopped on the TV, TV off, receiver giving up) leave no position to read: keep it as it goes.
        if (controller != null) {
            lifecycleScope.launch {
                while (true) {
                    delay(CAST_PROGRESS_MS)
                    val (source, pos, dur) = controller.castProgress() ?: continue
                    PlaybackProgress.sampledToSave(source, pos, dur)?.let { viewModel.savePlaybackPosition(source.resumeKey, it) }
                }
            }
        }
        // The next episode follows from here, not from the slate on screen: the UI stops updating while
        // the app is away, so the slate would go on counting down the episode it last showed and the
        // second episode in the background would never follow. An etincelle TV app plays it itself.
        // Only while what plays has one: this wakes the app four times a second, in the background too.
        lifecycleScope.launch {
            viewModel.state.map { it.playing?.upNext }.distinctUntilChanged().collectLatest { upNext ->
                if (upNext == null) return@collectLatest
                while (true) {
                    delay(UP_NEXT_TICK_MS)
                    if (controller?.state?.value?.toTvApp == true) continue
                    val p = playerFlow.value
                    if (upNextSlates.of(upNext).tick(upNext, p.currentPosition, p.duration)) viewModel.playUpNext()
                }
            }
        }

        // Playback follows `playing`, not the player screen: while casting the show page stays up and
        // the stream keeps running, and in the background the next episode starts on its own. The UI
        // pauses while the app is away, so the switch is made here rather than from a composable.
        var current: PlaybackSource? = null
        lifecycleScope.launch {
            viewModel.state.map { it.playing }.distinctUntilChanged().collect { next ->
                current?.let(onStop)
                current = next
                next?.let(onPlay)
            }
        }
        endPlayback = {
            current?.let(onStop)
            current = null
        }

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
                        upNextSlates = upNextSlates,
                        castState = castState,
                        // connectTo returns false when the tapped route is already gone (a Chromecast
                        // that dropped between opening the picker and tapping); tell the user instead
                        // of failing silently with no connect animation.
                        onCastConnect = {
                            if (controller?.connectTo(it) == false) {
                                Toast.makeText(this@MainActivity, "Connexion impossible, réessayez", Toast.LENGTH_SHORT).show()
                            }
                        },
                        // "Cet appareil" in the picker: bring playback back to this phone (not a stop).
                        // With nothing playing as far as the pages know (a link opened during the cast
                        // closed it, or the phone only drives a TV app), there is no player to bring it
                        // back to: the cast just ends.
                        onCastDisconnect = {
                            if (viewModel.state.value.playing == null) controller?.endCast() else controller?.returnToThisDevice()
                        },
                        // Active-scan while the cast picker is open so the device list stays fresh.
                        onCastActiveScan = { controller?.setActiveScan(it) },
                        onTvKey = { controller?.sendTvKey(it) },
                    )
                }
                state.update?.takeIf { !it.quiet }?.let { up ->
                    UpdateDialog(
                        up, state.updateProgress, state.updateError,
                        onInstall = { installUpdate(viewModel) },
                        onDismiss = viewModel::dismissUpdate,
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
        // Back in the foreground: any memory pressure that shrank the buffer has passed.
        loadControl?.restoreFullBuffer()
        castController?.start()
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build() }
    }

    override fun onStop() {
        castController?.stop()
        // Playback goes on in the background, sound only: no video decoding for a screen nobody sees.
        player?.let {
            if (it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING) {
                // Persist the resume point now too, playing or paused, and tell the server: the player
                // surface only does so on dispose, which a kill of the backgrounded process skips,
                // losing the position. Only ever save a real position here, never clear (positionToSave
                // returns 0 near the start/end), so a background blip cannot wipe a valid resume point;
                // clearing is the dispose path's job. (No-op while casting: the local player is idle.)
                viewModel.state.value.playing?.let { src ->
                    val pos = PlaybackProgress.positionToSave(src, it.currentPosition, it.duration)
                    if (pos > 0) {
                        viewModel.savePlaybackPosition(src.resumeKey, pos)
                        viewModel.reportProgress(src, it.currentPosition, it.duration)
                    }
                }
            }
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        }
        // Left with nothing playing, here or on a TV: the moment for an automatic update, which closes the app.
        // A TV app connected with nothing of this phone's on it does not count: it is about to be let go of.
        val cast = castController?.state?.value
        val casting = cast?.isCasting == true && castController?.tvIdle != true
        if (!isChangingConfigurations && viewModel.state.value.playing == null && !casting && cast?.connecting != true) {
            installFetchedUpdate(viewModel)
        }
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Shrink the forward buffer under memory pressure so it stops growing and frees its slack.
        loadControl?.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        loadControl?.onLowMemory()
    }

    override fun onDestroy() {
        endPlayback()
        // Unless a newer activity has taken them over: this one may be destroyed after that one opened.
        if (PlaybackService.session === mediaSession) {
            stopService(Intent(this, PlaybackService::class.java))
            PlaybackService.session = null
        }
        mediaSession?.release()
        mediaSession = null
        castController?.release()
        player?.release()
        player = null
        loadControl = null
        super.onDestroy()
    }
}

private val EMPTY_CAST_FLOW: StateFlow<CastUiState> = MutableStateFlow(CastUiState()).asStateFlow()

// Lifts the next-episode slate on the show page above the bottom bar and the cast bar.
private val UP_NEXT_ABOVE_BARS = 140.dp

// How long the cast volume overlay stays up after the last volume-key press.
private const val VOLUME_OVERLAY_MS = 1500L

// How often the position of a cast is kept as its resume point.
private const val CAST_PROGRESS_MS = 15_000L

// How often the next-episode countdown moves.
private const val UP_NEXT_TICK_MS = 250L

// What the navigation bar leaves between two of its tabs.
private val TAB_SPACING = 8.dp

// Kept free on the two sides of a tab's label together, so that none touches its neighbour or the edge.
private val TAB_LABEL_MARGIN = 6.dp

// How often a programme started over is checked for its live window being known.
private const val START_OVER_POLL_MS = 500L

/** Brand-pack vector icon for the tabs it covers; the rest keep their emoji glyph. */
@DrawableRes
private fun tabIconRes(tab: Tab): Int? = when (tab) {
    Tab.HOME -> DesignR.drawable.ic_accueil
    Tab.LIVE -> DesignR.drawable.ic_direct
    Tab.CHANNELS -> DesignR.drawable.ic_chaines
    Tab.GUIDE -> DesignR.drawable.ic_grille
    Tab.MOVIES -> DesignR.drawable.ic_films
    Tab.SERIES -> DesignR.drawable.ic_series
    Tab.RECORDINGS -> DesignR.drawable.ic_enregistrements
    Tab.SEARCH -> DesignR.drawable.ic_recherche
}

// The phone's bottom bar has room for seven tabs: Chaînes gives way to Enregistrements, as the channel
// grid is also one "Tout voir" away on Accueil.
private val PHONE_TABS = Tab.entries - Tab.CHANNELS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    state: UiState,
    currentPlayer: Player,
    onLocalPlayer: Boolean,
    vm: MainViewModel,
    upNextSlates: UpNextSlates,
    castState: CastUiState,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
    onCastActiveScan: (Boolean) -> Unit = {},
    onTvKey: (String) -> Unit = {},
) {
    val playing = state.playing
    val detail = state.detail
    val context = LocalContext.current
    // The immersive local player takes the screen only when the local player is actually current and
    // we are not casting. Keying on "current player is local" (not just !isCasting) keeps a brief
    // device-to-device transfer gap on the show page instead of flashing an idle cast surface.
    val playerFullscreen = playing != null && onLocalPlayer && !castState.isCasting
    // During a cast the pages are browsed under what plays: they refresh as usual.
    LaunchedEffect(playerFullscreen) { vm.setPlayerAside(!playerFullscreen) }
    // What was already started "depuis le début", here or on the TV it was cast to: the player leaves
    // the screen during a cast, and coming back must not send the programme to its start once more.
    var startedOver by remember { mutableStateOf<PlaybackSource?>(null) }
    LaunchedEffect(playing, castState.isCasting) { if (castState.isCasting) startedOver = playing }
    // Tapping the cast bar opens a full-screen controller for the active cast (play/pause + seek that
    // drive the receiver); Back returns to browsing while the cast keeps playing. Closed when casting
    // ends. Gated on the app holding the playing source: the controls only drive the receiver while the
    // app is the caster. After an app kill the cast session resumes but the cast player carries no
    // loaded media (its transport stays inert), so leave that case to the system cast notification.
    var castControlsOpen by remember { mutableStateOf(false) }
    // Close the controller only when casting truly ends, not during a device-to-device switch: there
    // isCasting briefly dips to false while connecting stays true, and closing then would eject the
    // controller mid-switch with no path to reopen it on the new device.
    LaunchedEffect(castState.isCasting, castState.connecting) {
        if (!castState.isCasting && !castState.connecting) castControlsOpen = false
    }
    // It also closes once nothing plays: a link opened during a cast clears what plays, and the
    // next Regarder must stay on its show page as any cast does.
    LaunchedEffect(playing == null) {
        if (playing == null) castControlsOpen = false
    }
    val castControls = playing != null && castState.isCasting && castControlsOpen
    // Driving an etincelle TV app, the cast bar opens its remote control instead, playing or not. It
    // stays up while the TV app, left alone while the app was away, is being connected to again.
    var tvRemoteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(castState.toTvApp, castState.connecting) {
        if (!castState.toTvApp && !castState.connecting) tvRemoteOpen = false
    }
    val tvRemote = (castState.toTvApp || castState.connecting) && tvRemoteOpen
    // The next-episode slate shows in the player, the cast controller or over the show page as the
    // viewer moves between them, each on the countdown the activity runs for the episode (with its
    // credits choice).
    val upNextSlate = upNextSlates.shown(playing?.upNext)
    // The cast bar is shown (and the nav bar then drops its bottom inset) when there is a device to
    // name and the full-screen player is not up - except while a connect is in flight, so casting
    // from the player still shows the "Connexion à …" animation instead of no feedback at all. It is
    // also hidden while its own controller is open (the bar is what you tapped to get there).
    val showCastBar = castState.showStatus &&
        (!playerFullscreen || castState.connecting) &&
        !castControls &&
        !tvRemote &&
        castState.statusDeviceName != null
    // Grid density (cards per row on the "tout voir" pages); persisted, set live from the settings.
    var gridColumns by remember { mutableStateOf(LocalPrefs.gridColumns(context)) }
    val autoUpdate by vm.autoUpdate.collectAsStateWithLifecycle()


    // Flash a brief volume overlay on each cast volume-key press (the system bar is suppressed).
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(castState.volumeNonce) {
        if (castState.volumeNonce > 0) {
            showVolume = true
            delay(VOLUME_OVERLAY_MS)
            showVolume = false
        }
    }

    // Retains each page's saveable UI state (e.g. list/grid scroll) while a detail or the player covers
    // the logged-in content, so backing out restores it. Lives above the screen `when` so it survives
    // that swap (the logged-in branch leaves composition while a detail is shown).
    val contentStateHolder = rememberSaveableStateHolder()
    // What was typed in the search is kept with its results (see the SEARCH page key below): not for a
    // later search to start from, nor for the next account.
    LaunchedEffect(state.loggedIn, state.tab) {
        if (!state.loggedIn || state.tab != Tab.SEARCH) contentStateHolder.removeState("SEARCH")
    }
    val favoriteOrder = state.favoriteOrder
    val profileScreen = state.profileScreen
    val profileEditor = state.profileEditor
    val mfa = state.mfa
    CompositionLocalProvider(LocalFavoriteToggle provides vm::toggleFavorite) {
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
                            PlayerSurface(
                                playing!!, currentPlayer, castState, onCastConnect, onCastDisconnect, onCastActiveScan,
                                fetchProgramWindow = vm::liveProgramWindow,
                                onPlayNext = vm::playUpNext,
                                upNextSlate = upNextSlate,
                                startOver = playing !== startedOver,
                                onStartedOver = { startedOver = playing },
                            )
                        }

                        tvRemote -> {
                            BackHandler { tvRemoteOpen = false }
                            TvRemoteScreen(currentPlayer, castState.statusDeviceName.orEmpty(), onTvKey, onClose = { tvRemoteOpen = false })
                        }

                        // The cast controller: the same player surface bound to the cast player, so its
                        // transport controls drive the receiver. Back closes it; the cast keeps playing.
                        castControls -> {
                            BackHandler { castControlsOpen = false }
                            PlayerSurface(
                                playing!!, currentPlayer, castState, onCastConnect, onCastDisconnect, onCastActiveScan,
                                fetchProgramWindow = vm::liveProgramWindow, secure = false,
                                onPlayNext = vm::playUpNext,
                                upNextSlate = upNextSlate,
                            )
                        }

                        detail != null -> {
                            BackHandler { vm.closeDetail() }
                            ProgramDetailScreen(
                                detail = detail,
                                busy = state.busy,
                                error = state.error,
                                info = state.info,
                                onWatch = { vm.watchDetail() },
                                onStartOver = { vm.startOverDetail() },
                                onWatchReplay = { vm.watchReplay() },
                                onRecord = { vm.recordDetail(it) },
                                onWatchRecording = { vm.watchRecording(it) },
                                onBack = { vm.closeDetail() },
                                onEpisode = { vm.onCardClick(it) },
                                isRecording = state.detailRecordingAssetId != null,
                                castButton = { CastButton(castState, onCastConnect, onCastDisconnect, onCastActiveScan) },
                            )
                        }

                        profileEditor != null -> {
                            BackHandler { vm.closeProfileEditor() }
                            ProfileEditorScreen(
                                profileEditor,
                                avatars = state.profileScreen?.list?.avatars.orEmpty(),
                                canDelete = (state.profileScreen?.list?.profiles?.size ?: 0) > 1,
                                busy = state.busy,
                                error = state.error,
                                onSave = vm::saveProfile,
                                onDelete = vm::deleteProfile,
                                onBack = vm::closeProfileEditor,
                            )
                        }

                        profileScreen != null -> {
                            BackHandler { vm.closeProfiles() }
                            ProfilesScreen(
                                profileScreen, state.busy, state.error,
                                onPick = vm::pickProfile,
                                onEdit = vm::editProfile,
                                onBack = vm::closeProfiles,
                            )
                        }

                        favoriteOrder != null -> {
                            BackHandler { vm.closeFavoriteOrder() }
                            FavoriteOrderScreen(
                                favoriteOrder, state.busy, state.error,
                                onMove = vm::moveFavorite,
                                onSave = vm::saveFavoriteOrder,
                                onBack = vm::closeFavoriteOrder,
                            )
                        }

                        state.settings -> {
                            BackHandler { vm.closeSettings() }
                            SettingsScreen(
                                onBack = { vm.closeSettings() },
                                onLogout = { vm.logout() },
                                onProfiles = vm::openProfiles,
                                hideLocked = state.hideLocked,
                                onHideLocked = vm::setHideLocked,
                                gridColumns = gridColumns,
                                onGridColumns = { gridColumns = it },
                                appVersion = BuildConfig.VERSION_NAME,
                                checkingUpdate = state.checkingUpdate,
                                updateStatus = state.updateStatus,
                                onCheckUpdate = { vm.checkForUpdateNow() },
                                selfUpdate = BuildConfig.SELF_UPDATE,
                                autoUpdate = autoUpdate.takeIf { BuildConfig.SELF_UPDATE && AutoUpdate.SUPPORTED },
                                onAutoUpdate = vm::setAutoUpdate,
                                onConnectTv = { vm.confirmTvCode(it) },
                                onClearTvConnect = { vm.clearTvConnect() },
                                connectInfo = state.tvConnectInfo,
                                connectError = state.tvConnectError,
                                busy = state.busy,
                                error = state.error,
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
                                            if (state.onChannelsPage) {
                                                IconButton(onClick = { vm.openFavoriteOrder() }) {
                                                    Icon(painterResource(DesignR.drawable.ic_favoris), contentDescription = "Réorganiser les favoris")
                                                }
                                            }
                                            CastButton(castState, onCastConnect, onCastDisconnect, onCastActiveScan)
                                            IconButton(onClick = { vm.openSettings() }) {
                                                Icon(Icons.Outlined.Settings, contentDescription = "Paramètres")
                                            }
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
                                        PHONE_TABS.forEach { tab ->
                                            // The tab stays marked on its sub-pages ("Tout voir", categories),
                                            // so the bar always says where you are; tapping it goes back to
                                            // the tab's first page.
                                            NavigationBarItem(
                                                selected = state.tab == tab,
                                                onClick = { vm.selectTab(tab) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    indicatorColor = BrandYellow,
                                                    selectedIconColor = BrandBlack,
                                                    selectedTextColor = BrandYellow,
                                                ),
                                                icon = {
                                                    val iconRes = tabIconRes(tab)
                                                    if (iconRes != null) {
                                                        Icon(painterResource(iconRes), contentDescription = tab.label)
                                                    } else {
                                                        Text(tab.icon)
                                                    }
                                                },
                                                label = {
                                                    // Seven tabs leave little room: a name that does not fit
                                                    // shrinks rather than being cut. To its tab's own width,
                                                    // less a margin: the bar lets a label be wider than its
                                                    // tab, and the last one then ran off the screen.
                                                    val labelStyle = MaterialTheme.typography.labelSmall
                                                    val barWidth = LocalConfiguration.current.screenWidthDp.dp
                                                    val tabWidth = (barWidth - TAB_SPACING * (PHONE_TABS.size - 1)) / PHONE_TABS.size
                                                    BasicText(
                                                        tab.shortLabel,
                                                        modifier = Modifier.widthIn(max = tabWidth - TAB_LABEL_MARGIN),
                                                        style = labelStyle.copy(color = LocalContentColor.current),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = labelStyle.fontSize),
                                                    )
                                                },
                                            )
                                        }
                                    }
                                },
                            ) { padding ->
                                val modifier = Modifier.padding(padding)
                                // Key the saveable state by the back-stack entry so each page keeps its own
                                // scroll: the key is unchanged while a detail covers the page (the back stack
                                // does not move), so the scroll offset is restored when the detail closes.
                                // Search keeps a constant key: it always shows SearchScreen, and its back-stack
                                // depth flips 0->1 when results arrive, which would otherwise tear down the
                                // search field and blank the typed query.
                                // hideLocked is folded in so toggling "hide locked" (which shrinks the lists)
                                // starts the page fresh at the top instead of restoring a now-out-of-range index.
                                val pageKey = if (state.tab == Tab.SEARCH) {
                                    "SEARCH"
                                } else {
                                    "${state.tab.name}#${state.backStack.size}#${state.hideLocked}#" +
                                        (state.current?.reloadUrl ?: state.current?.title.orEmpty())
                                }
                                contentStateHolder.SaveableStateProvider(pageKey) {
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
                                            isGrid = state.current?.isGrid == true,
                                            columns = gridColumns,
                                            pageTitle = state.current?.title,
                                            searched = state.current != null,
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
                        }

                        mfa != null -> {
                            BackHandler { vm.cancelCode() }
                            CodeScreen(mfa, state.busy, state.error, state.info, vm::verifyCode, vm::resendCode, vm::cancelCode)
                        }

                        else -> LoginScreen(state.busy, state.error, vm::login)
                    }
                }
                if (showCastBar) {
                    // Offer the tap-to-open affordance only when a tap will actually open functional
                    // controls (the app holds the playing source). A cast resumed after an app kill has
                    // none, so the bar stays plain status there (the system cast notification controls it).
                    CastStatusBar(
                        castState,
                        onClick = when {
                            castState.toTvApp -> ({ tvRemoteOpen = true })
                            playing != null -> ({ castControlsOpen = true })
                            else -> null
                        },
                    )
                }
            }
            // Casting from the show page: the next episode is announced here and follows on the TV. An
            // etincelle TV app announces and plays it itself.
            if (playing != null && castState.isCasting && !castState.toTvApp && !castControls) {
                UpNextOverlay(
                    playing,
                    currentPlayer,
                    vm::playUpNext,
                    upNextSlate,
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = UP_NEXT_ABOVE_BARS),
                )
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
}

/**
 * The slate announcing [source]'s next episode near its end, counting down on [player]: the phone's or
 * the Chromecast's, so the next episode also follows on the TV.
 */
@Composable
private fun UpNextOverlay(source: PlaybackSource, player: Player, onPlayNext: () -> Unit, slate: UpNextSlateState?, modifier: Modifier) {
    val upNext = source.upNext ?: return
    slate ?: return
    UpNextSlate(
        upNext,
        position = { player.currentPosition },
        duration = { player.duration },
        onPlayNext = onPlayNext,
        drive = false,
        button = { label, primary, onClick, mod ->
            if (primary) {
                Button(onClick = onClick, modifier = mod, colors = ButtonDefaults.buttonColors(containerColor = BrandYellow, contentColor = BrandBlack)) { Text(label) }
            } else {
                OutlinedButton(onClick = onClick, modifier = mod) { Text(label, color = Color.White) }
            }
        },
        modifier = modifier,
        state = slate,
    )
}

@Composable
private fun PlayerSurface(
    source: PlaybackSource,
    currentPlayer: Player,
    castState: CastUiState,
    onCastConnect: (String) -> Unit,
    onCastDisconnect: () -> Unit,
    onCastActiveScan: (Boolean) -> Unit,
    fetchProgramWindow: suspend (String) -> ProgramWindow?,
    secure: Boolean = true,
    onPlayNext: () -> Unit = {},
    upNextSlate: UpNextSlateState? = null,
    startOver: Boolean = false,
    onStartedOver: () -> Unit = {},
) {
    val view = LocalView.current
    DisposableEffect(secure, currentPlayer) {
        // Keep the screen awake while playing, here or on what the cast controller drives. For local
        // playback also block screenshots/recording (FLAG_SECURE); the cast controller (secure=false)
        // renders no video here, so it does not need it.
        val window = (view.context as? Activity)?.window
        if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val letScreenSleep = keepScreenOnWhilePlaying(window, currentPlayer)
        onDispose {
            letScreenSleep()
            if (secure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Track how far behind the live edge we are, so a "back to live" pill can appear once the viewer
    // has scrubbed into the DVR window of a live show (and disappear again at the edge).
    val liveWindow = remember { Timeline.Window() }
    var behindLive by remember { mutableStateOf(false) }
    // The on-screen programme's air-window, marked on the live bar. Kept locally (not on the source)
    // so re-scoping it on a programme rollover does not restart the stream. Media3's CastPlayer reports
    // no live window here, so its controller keeps its own bar; the local player and a direct cast
    // (which do report one) get the programme bar.
    val showBar = source.isLive && (secure || currentPlayer !is LiveSkipCastPlayer)
    var programWindow by remember(source) { mutableStateOf(source.programWindow) }
    var barGeometry by remember { mutableStateOf<LiveBarGeometry?>(null) }
    // A "depuis le début" start asks playback to begin at the current show's start; the local player
    // joins at the live edge first, so seek back once the live window has loaded, then stop retrying.
    // Local only: a cast is told where to start in its LOAD, and opening the cast controls later must
    // not send it back to the show's start. Once only ([startOver]), not each time this player comes back.
    var pendingStartOver by remember(source) { mutableStateOf(startOver && secure && source.isLive && source.liveRewindOffsetMs > 0L) }
    // A programme started over begins at the live edge and is taken back to its start once the live
    // window is known, with the app on screen or not: left before that, it would go on at the edge in
    // the background and jump back on return.
    LaunchedEffect(currentPlayer, source) {
        val window = Timeline.Window()
        while (pendingStartOver) {
            if (LivePlayback.seekToProgramStartIfReady(currentPlayer, window, programWindow)) {
                pendingStartOver = false
                onStartedOver()
            } else {
                delay(START_OVER_POLL_MS)
            }
        }
    }
    // Per source, not per kind: another live channel has its own programme, rewind and channel to ask.
    // Only while the app is on screen: it draws the bar, and asks the guide for the programme that follows.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(currentPlayer, source, lifecycle) {
        // VOD and recordings have no live edge, so do not poll: stay at "not behind" without a 1s loop.
        if (!source.isLive) {
            behindLive = false
            barGeometry = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var nextRolloverMs = 0L
            while (true) {
                behindLive = LivePlayback.behindLiveEdgeMs(currentPlayer, liveWindow) > LivePlayback.LIVE_REWIND_THRESHOLD_MS
                if (showBar) {
                    barGeometry = LivePlayback.liveBarGeometry(currentPlayer, liveWindow, programWindow)
                    // Rollover: once the live edge passes the show's end, re-scope to the now-current
                    // programme. Throttled, and only adopted when it actually advances, so a backend that
                    // lags the boundary is not polled every second.
                    val pw = programWindow
                    val edge = LivePlayback.liveEdgeEpochMs(currentPlayer, liveWindow)
                    val now = System.currentTimeMillis()
                    if (pw != null && edge != null && edge >= pw.endMs && now >= nextRolloverMs) {
                        nextRolloverMs = now + 15_000
                        source.originChannelId?.let { channelId ->
                            fetchProgramWindow(channelId)?.takeIf { it.endMs > pw.endMs }?.let { programWindow = it }
                        }
                    }
                }
                delay(1000)
            }
        }
    }
    // Follow the player controls' show/hide so the cast button and the back-to-live pill fade with the
    // position bar (tap to bring them back) instead of sitting permanently over the video.
    // They start hidden, and what the player view does as it is given its player is heard below.
    var controlsVisible by remember { mutableStateOf(false) }
    // The cast menu open, or the programme bar under a finger: the controls stay up meanwhile. They hide
    // after five seconds otherwise, which closed the menu being read and dropped the drag under way.
    var castMenuOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val controlsHeld = castMenuOpen || scrubbing
    LaunchedEffect(controlsHeld, playerView) {
        // The cast controller's are pinned already.
        val view = playerView?.takeIf { secure } ?: return@LaunchedEffect
        view.controllerShowTimeoutMs = if (controlsHeld) 0 else PlayerControlView.DEFAULT_SHOW_TIMEOUT_MS
        // The timeout alone only takes on controls shown in full: past their five seconds they are
        // already on their way out, and would still take the menu or the drag with them.
        if (controlsHeld) view.showController()
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    // Before the player is set: the view shows its controls then or leaves them hidden,
                    // and says so only as it changes them.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                    player = currentPlayer
                    playerView = this
                    if (!secure) {
                        // The cast controller has no video to reveal, so pin the transport controls on
                        // screen instead of auto-hiding to a black surface.
                        controllerShowTimeoutMs = 0
                        setControllerHideOnTouch(false)
                    }
                    // The live programme bar replaces the default time bar (which spans the bare 4h
                    // DVR window with no show markers), so hide the built-in one for the local player.
                    LivePlayback.useProgramBar(this, showBar)
                }
            },
            update = {
                it.player = currentPlayer
                LivePlayback.useProgramBar(it, showBar)
                // The cast player never auto-shows the controller, so show it explicitly and keep it
                // pinned (controllerShowTimeoutMs=0 / hideOnTouch=false) for the cast controller.
                if (!secure) it.showController()
            },
            onRelease = {
                playerView = null
                it.player = null
                // Clear the listener too: it captures the controlsVisible setter, so leaving it set holds
                // the recomposition scope on the detached view until it is garbage-collected.
                it.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (controlsVisible) {
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                CastButton(castState, onCastConnect, onCastDisconnect, onCastActiveScan, onOpenChange = { castMenuOpen = it })
            }
        }
        if (behindLive && controlsVisible) {
            ReturnToLiveButton(
                onClick = { currentPlayer.seekToDefaultPosition() },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            )
        }
        barGeometry?.let { bar ->
            if (controlsVisible) {
                val pw = programWindow?.takeIf { bar.hasProgramBand }
                LiveProgramBar(
                    playedFraction = bar.playedFraction,
                    liveFraction = bar.liveFraction,
                    seekFloorFraction = bar.seekFloorFraction,
                    onSeekToFraction = { LivePlayback.seekToFraction(currentPlayer, liveWindow, it, programWindow) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = LiveProgramBarBottomOffset),
                    startLabel = pw?.let { LivePlayback.clockLabel(it.startMs) },
                    endLabel = pw?.let { LivePlayback.clockLabel(it.endMs) },
                    onScrubbing = { scrubbing = it },
                )
            }
        }
        // The next episode, near the end of this one, on the phone or the Chromecast (an etincelle TV
        // app plays it itself).
        if (!castState.toTvApp) {
            UpNextOverlay(source, currentPlayer, onPlayNext, upNextSlate, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp))
        }
    }
}
