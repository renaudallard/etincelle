// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.AudioManager
import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.ui.PlayerView
import it.allard.etincelle.core.designsystem.LiveProgramBar
import it.allard.etincelle.core.designsystem.LiveProgramBarBottomOffset
import it.allard.etincelle.core.designsystem.ReturnToLiveButton
import it.allard.etincelle.core.designsystem.SkipAdsButton
import it.allard.etincelle.core.designsystem.rememberStackStateKeys
import it.allard.etincelle.core.designsystem.theme.EtincelleTheme
import it.allard.etincelle.core.designsystem.UpNextSlate
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.model.PlayKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.isSameProgramme
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.player.AdaptiveLoadControl
import it.allard.etincelle.core.player.AdBreak
import it.allard.etincelle.core.player.AdSkipper
import it.allard.etincelle.core.player.LiveBarGeometry
import it.allard.etincelle.core.player.LivePlayback
import it.allard.etincelle.core.player.LiveProgramme
import it.allard.etincelle.core.player.MediaItemFactory
import it.allard.etincelle.core.player.PlaybackProgress
import it.allard.etincelle.core.player.PlaybackRecovery
import it.allard.etincelle.core.player.RewindHold
import it.allard.etincelle.core.player.followLive
import it.allard.etincelle.core.player.followRecording
import it.allard.etincelle.core.player.keepScreenOnWhilePlaying
import it.allard.etincelle.core.remote.RemoteMessages
import it.allard.etincelle.core.ui.installFetchedUpdate
import it.allard.etincelle.core.ui.installUpdate
import it.allard.etincelle.core.ui.MainViewModel
import it.allard.etincelle.core.ui.AutoUpdate
import it.allard.etincelle.core.ui.MainViewModelFactory
import it.allard.etincelle.core.ui.Tab
import it.allard.etincelle.core.ui.UpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// How long a TV set may take to settle once the phone's programme woke it.
private const val TV_WAKING_MS = 30_000L

// How long after the activity stops the screensaver's broadcast may come.
private const val DREAM_GRACE_MS = 3_000L

// How often, and how many times, a live stream loaded again on return is checked for its window having
// been read anew, to go back to what was airing when it was left: half a minute in all.
private const val RELOAD_SEEK_POLL_MS = 250L
private const val RELOAD_SEEK_TRIES = 120

// How long the screen is kept on after a key from the phone: about what a TV waits before its screensaver.
private const val PHONE_KEY_AWAKE_MS = 5 * 60_000L

// How long the app is away over a programme before an automatic update is installed all the same.
private const val AWAY_INSTALL_MS = 60 * 60_000L

// The key the sign-in screen keeps its state under.
private const val LOGIN_STATE = "LOGIN"

// A seek from the remote's arrows is made this long after the last press, and its preview stays this
// much longer; a held arrow seeks this much faster at each repeat, up to that many times the step, so
// a held rewind crosses the four hours of a live channel in a few seconds.
private const val SEEK_COMMIT_MS = 400L
private const val SEEK_LINGER_MS = 800L
private const val SEEK_FASTER_STEP = 1.5f
private const val SEEK_MAX_FASTER = 50f

// What the media session lets others do with the player: see what plays, pause, resume, and seek in it.
private val SESSION_COMMANDS = Player.Commands.Builder().addAll(
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD,
    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE,
    Player.COMMAND_GET_METADATA,
    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
).build()

class MainActivity : ComponentActivity(), TvRemote.Screen {

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory((application as EtincelleTvApp).container.repository, UpdateChecker(BuildConfig.VERSION_NAME, "tv"), AutoUpdate(this).takeIf { BuildConfig.SELF_UPDATE })
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var loadControl: AdaptiveLoadControl? = null
    private var pausedForBackground = false
    // The programme held in pause was let go of when the app was left (see unload), to be loaded again
    // on return. A live one is taken up again at what was airing then, noted as its time of day
    // (epoch ms) with the start of the live window it was read in.
    private var unloaded = false
    // The player was stopped for it, and is to be prepared again on return.
    private var stoppedAway = false
    private var resumeLiveAtMs: Long? = null
    private var unloadedWindowStartMs = C.TIME_UNSET
    private var reloadJob: Job? = null
    // What was on as the app was left. Only that is resumed or loaded again on return: the phone may
    // have stopped it meanwhile, or asked for another programme, which starts by itself.
    private var awaySource: PlaybackSource? = null
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
    private val installOnceAway = object : Runnable {
        override fun run() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            // Not under a channel being watched from the system's guide: later, then.
            if (TvLiveInputService.sessions > 0) handler.postDelayed(this, AWAY_INSTALL_MS) else installFetchedUpdate(viewModel)
        }
    }
    // A key from the phone is no activity to Android, which would start the screensaver over someone
    // browsing with the phone alone: the screen is kept on for a while after each.
    private val letScreenRest = Runnable { window.decorView.keepScreenOn = false }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> {
                    dreaming = false
                    // The screensaver may have ended on another app: this one is then left for good,
                    // which nothing else would notice. Coming back to it cancels this.
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        handler.removeCallbacks(stopRemoteUnlessDreaming)
                        handler.postDelayed(stopRemoteUnlessDreaming, DREAM_GRACE_MS)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    dreaming = false
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) leave()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Made first: the saved sign-in it reads, off this thread, is then read while the player is built.
        viewModel
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
        // Only on a fresh launch; a process recreation must not replay the original deep link, nor the
        // app reopened from the recent apps, which Android starts with the intent it was first opened by.
        val reopened = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !reopened) handleDeepLink(intent)
        // A build from Google Play is updated by Google Play, and may not update itself.
        if (BuildConfig.SELF_UPDATE) lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchForUpdates() } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepPageFresh() } }
        // Signed out, by hand or as the session died: the account's phones lose this TV at once.
        lifecycleScope.launch {
            var signedIn = false
            viewModel.state.map { it.loggedIn }.distinctUntilChanged().collect { now ->
                if (signedIn && !now) remote.signedOut()
                if (!signedIn && now) remote.signedIn()
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
                    // What was typed in the search is kept with its results (see the SEARCH page key
                    // below): not for a later search to start from, nor for the next account.
                    // The keys of the pages shown since a tab was last chosen.
                    val pageKeys = remember { mutableSetOf<String>() }
                    LaunchedEffect(state.loggedIn, state.tab) {
                        if (!state.loggedIn || state.tab != Tab.SEARCH) contentStateHolder.removeState("SEARCH")
                        if (state.loggedIn) contentStateHolder.removeState(LOGIN_STATE)
                        // Nor are the pages' scroll and the place to come back to for the next account.
                        if (!state.loggedIn) {
                            pageKeys.forEach(contentStateHolder::removeState)
                            pageKeys.clear()
                        }
                    }
                    val detailKeys = rememberStackStateKeys(contentStateHolder, state.detailStack.map { it.id })
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
                                away = { unloaded },
                                onAdBreak = { skipAdBreak = it },
                            )
                        }

                        detail != null -> {
                            BackHandler { viewModel.closeDetail() }
                            // Each page of the stack has its own scroll, season and episode last opened,
                            // found again on Back from that episode's page, or from the player.
                            contentStateHolder.SaveableStateProvider(detailKeys.last()) {
                                TvProgramDetailScreen(
                                    detail, state.busy, state.error, state.info,
                                    viewModel::watchDetail, viewModel::startOverDetail, viewModel::watchReplay, viewModel::recordDetail,
                                    viewModel::watchRecording,
                                    viewModel::onCardClick,
                                    isRecording = state.detailRecordingAssetId != null,
                                )
                            }
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
                                viewModel::pickProfile, viewModel::editProfile, viewModel::setProfilesEditing, viewModel::closeProfiles,
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
                                busy = state.busy,
                                error = state.error,
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
                            if (state.tab != Tab.SEARCH) SideEffect { pageKeys += pageKey }
                            contentStateHolder.SaveableStateProvider(pageKey) {
                                TvBrowseScreen(
                                    state,
                                    { tab ->
                                        // A tab chosen starts afresh. What its pages kept is for Back: the
                                        // card to come back to would take the focus off the tab bar. The
                                        // tab's own page, chosen again while on it, stays as it is.
                                        if (tab != state.tab || state.backStack.size != 1) {
                                            pageKeys.forEach(contentStateHolder::removeState)
                                            pageKeys.clear()
                                        }
                                        viewModel.selectTab(tab)
                                    },
                                    viewModel::onCardClick, viewModel::search,
                                    viewModel::openSettings, viewModel::onRailSeeAll, gridColumns,
                                    onFavoriteOrder = viewModel::openFavoriteOrder,
                                )
                            }
                        }

                        mfa != null -> {
                            BackHandler { viewModel.cancelCode() }
                            TvCodeScreen(mfa, state.busy, state.error, state.info, viewModel::verifyCode, viewModel::resendCode, viewModel::cancelCode)
                        }

                        // Annuler on the code screen comes back to the form it was sent from, the
                        // address still in it, not to a new pairing code.
                        else -> contentStateHolder.SaveableStateProvider(LOGIN_STATE) {
                            TvLoginScreen(
                                state.pairingCode, state.busy, state.error,
                                viewModel::loginWithCode, viewModel::stopPairingPoll, viewModel::cancelCodeLogin, viewModel::login,
                            )
                        }
                    }
                    // Not over a programme: it comes up at each return to the app, and would take the
                    // first OK meant for the player.
                    state.update?.takeIf { !it.quiet && playing == null }?.let { up ->
                        TvUpdateDialog(
                            up, state.updateProgress, state.updateError,
                            onInstall = { installUpdate(viewModel) { lifecycle.withResumed {} } },
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
                viewModel.playFromRemote(
                    kind, id, message.optLong(RemoteMessages.POSITION), message.optLong(RemoteMessages.REWIND),
                    message.optBoolean(RemoteMessages.FROM_START),
                )
            }
            RemoteMessages.PAUSE -> {
                player.playWhenReady = false
                pausedForBackground = false
            }
            // Not in front, playback resumes as the app comes back (see onStart), not behind what the
            // TV shows meanwhile.
            RemoteMessages.RESUME -> if (inFront) player.playWhenReady = true else pausedForBackground = true
            RemoteMessages.SEEK -> {
                // Asked for while the programme waits to be loaded again: that is where it goes, not
                // back to where it was left. In a live stream the phone names a position in the window
                // read as the app was left, which will have moved on by then: it is kept as the time of
                // day it stands for.
                val position = message.optLong(RemoteMessages.POSITION, -1)
                val start = unloadedWindowStartMs
                // Just back in front, the stream's new window is still to come, and the phone still
                // reads its positions in the old one: the wait for it then starts again, for this place.
                val reloading = reloadJob?.isActive == true
                val stale = (unloaded && stoppedAway) || reloading
                resumeLiveAtMs = if (stale && position >= 0 && start != C.TIME_UNSET) start + position else null
                if (position < 0) player.seekToDefaultPosition() else player.seekTo(position)
                if (reloading) seekBackWhenFresh()
            }
            RemoteMessages.STOP -> viewModel.stopPlaying()
            RemoteMessages.KEY -> {
                val name = message.optString(RemoteMessages.KEY_NAME)
                window.decorView.keepScreenOn = true
                handler.removeCallbacks(letScreenRest)
                handler.postDelayed(letScreenRest, PHONE_KEY_AWAKE_MS)
                when {
                    // The update prompt is a window of its own, which keys sent through this activity
                    // do not reach: they would act on the screen behind it. Its two answers are taken
                    // here, OK for Installer, which it opens on, and Retour for Plus tard. It is not shown
                    // over a programme.
                    viewModel.state.value.let { it.update?.quiet == false && it.playing == null } -> when (name) {
                        "ok" -> installUpdate(viewModel) { lifecycle.withResumed {} }
                        "back" -> if (viewModel.state.value.updateProgress == null) viewModel.dismissUpdate()
                    }
                    // The ad break on offer, whatever the controls are doing. Not while the programme
                    // is being loaded again: the place to skip to was read in the stream as it was.
                    name == RemoteMessages.KEY_SKIP -> if (reloadJob?.isActive != true) skipAdBreak?.invoke()
                    // Straight to what handles Back: from Android 16 an activity no longer turns a Back
                    // key event into it for an app built for that version.
                    name == "back" -> onBackPressedDispatcher.onBackPressed()
                    else -> remoteKeyCode(name)?.let { code ->
                        // Through the activity, as a remote press would come, so the screens and the
                        // player see it the same way.
                        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    }
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

    // Skips the ad break the player screen offers to skip; null when it offers none.
    private var skipAdBreak: (() -> Unit)? = null

    override fun remoteStatus(): JSONObject {
        val status = JSONObject().put(RemoteMessages.TYPE, RemoteMessages.STATUS).put(RemoteMessages.REQUEST, remoteRequest)
        val state = viewModel.state.value
        val source = state.playing
        val player = player
        // Also while something plays: what the phone asked for may have failed over it, and the phone
        // would wait for it for good.
        state.error?.let { status.put(RemoteMessages.ERROR, it) }
        if (source == null || player == null) return status.put(RemoteMessages.STATE, RemoteMessages.STATE_IDLE)
        // The player takes what the pages play once its screen is drawn, which waits for the app to be
        // shown: behind the screensaver it still holds the programme before, or nothing, and neither
        // that one's state nor its position are this one's. On its way, from where it will start.
        val held = (player.currentMediaItem?.localConfiguration?.tag as? PlaybackSource)?.isSameProgramme(source) == true
        if (!held) {
            return status.put(RemoteMessages.STATE, RemoteMessages.STATE_BUFFERING)
                .put(RemoteMessages.TITLE, source.title.orEmpty())
                .put(RemoteMessages.POSITION, source.startPositionMs)
                .put(RemoteMessages.DURATION, -1L)
                .put(RemoteMessages.LIVE, source.isLive)
                .putProgramme(source)
        }
        val playback = when (player.playbackState) {
            Player.STATE_BUFFERING -> RemoteMessages.STATE_BUFFERING
            Player.STATE_READY -> if (player.playWhenReady) RemoteMessages.STATE_PLAYING else RemoteMessages.STATE_PAUSED
            Player.STATE_ENDED -> RemoteMessages.STATE_ENDED
            // Not loaded yet, or being brought back after an error: on its way. Idle would tell the
            // phone that the programme was stopped here, and it would drop it. Let go of as the app was
            // left, it waits in pause for the app to come back.
            else -> if (unloaded) RemoteMessages.STATE_PAUSED else RemoteMessages.STATE_BUFFERING
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
        if (skipAdBreak != null) status.put(RemoteMessages.SKIP, true)
        return status.putProgramme(source)
    }

    // What [source] is, as the phone names it.
    private fun JSONObject.putProgramme(source: PlaybackSource): JSONObject {
        val channelId = source.originChannelId
        val vodId = source.originVodId
        val recordingId = source.originRecordingAssetId
        when {
            channelId != null -> put(RemoteMessages.KIND, RemoteMessages.KIND_LIVE).put(RemoteMessages.ID, channelId)
            recordingId != null -> put(RemoteMessages.KIND, RemoteMessages.KIND_RECORDING).put(RemoteMessages.ID, recordingId)
            vodId != null -> put(RemoteMessages.KIND, RemoteMessages.KIND_VOD).put(RemoteMessages.ID, vodId)
        }
        return this
    }

    override fun onStop() {
        // Left, the player is paused, and the system would still hand the remote's Play to the session
        // that played last: it would play on behind the launcher, heard and not seen.
        session?.release()
        session = null
        awaySource = viewModel.state.value.playing
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
        // What plays, for the system and the Assistant, which can then pause it, resume it or seek in it
        // by voice, while the app is on screen. Nothing more: a stream is a single item, and no other
        // app is to load one. A new id each time: Android may start the next activity before it has
        // stopped this one, and two sessions under one id are refused with an exception.
        player?.let { exo ->
            session = MediaSession.Builder(this, exo)
                .setId(UUID.randomUUID().toString())
                .setCallback(object : MediaSession.Callback {
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailablePlayerCommands(SESSION_COMMANDS)
                            .setAvailableSessionCommands(SessionCommands.EMPTY)
                            .build()
                })
                .build()
        }
        handler.removeCallbacks(stopRemoteUnlessDreaming)
        handler.removeCallbacks(installOnceAway)
        remote.start()
        RemoteKeepAliveService.start(this)
        // Back in the foreground: any memory pressure that shrank the buffer has passed.
        loadControl?.restoreFullBuffer()
        // Refresh the home screen row and Watch Next (and make sure their periodic sync is scheduled).
        TvHomeJob.schedule(this)
        val same = viewModel.state.value.playing === awaySource
        awaySource = null
        if (unloaded) {
            unloaded = false
            // Where it was. A stream whose address went stale meanwhile fails here and is brought back
            // by the recovery, as any other.
            if (same && stoppedAway) reload()
        }
        if (pausedForBackground) {
            pausedForBackground = false
            if (same) player?.playWhenReady = true
        }
    }

    // A programme held in pause when the app is left goes on loading: a live stream asks for its
    // manifest every few seconds, and a decoder, a licence session and the buffer stay held, for as
    // long as this app is kept, days when a phone may reach it at all times. Its loading is stopped;
    // the player keeps what it plays and where.
    private fun unload() {
        val player = player ?: return
        if (unloaded) return
        unloaded = true
        stoppedAway = false
        resumeLiveAtMs = null
        unloadedWindowStartMs = C.TIME_UNSET
        // Nothing loaded, or a stream that failed and is being brought back: that waits for the app to
        // come back (see TvPlayerSurface), and loads the stream again by itself then.
        if (player.playbackState == Player.STATE_IDLE) return
        // The player keeps its position in the live window, which will have moved on by the time it is
        // loaded again: the same position would then be a later programme. What was airing is noted.
        val timeline = player.currentTimeline
        if (player.isCurrentMediaItemLive && !timeline.isEmpty) {
            timeline.getWindow(player.currentMediaItemIndex, remoteWindow)
            if (remoteWindow.windowStartTimeMs != C.TIME_UNSET) {
                unloadedWindowStartMs = remoteWindow.windowStartTimeMs
                resumeLiveAtMs = remoteWindow.windowStartTimeMs + player.currentPosition
            }
        }
        player.stop()
        stoppedAway = true
    }

    // Loads again what was let go of as the app was left, and takes a live stream back to what was
    // airing then once its window has been read anew: not the one the player still holds from before,
    // in which that is where it already stands.
    private fun reload() {
        player?.prepare()
        seekBackWhenFresh()
    }

    // Takes a live stream being loaded again to the time of day noted for it, once its new window is read.
    private fun seekBackWhenFresh() {
        val player = player ?: return
        val source = viewModel.state.value.playing
        reloadJob?.cancel()
        val at = resumeLiveAtMs ?: return
        val staleStart = unloadedWindowStartMs
        resumeLiveAtMs = null
        reloadJob = lifecycleScope.launch {
            val window = Timeline.Window()
            // A seek made meanwhile, with the remote or from the phone, is where playback goes instead.
            var seeked = false
            val seeks = object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) seeked = true
                }
            }
            player.addListener(seeks)
            try {
                // No longer than a stream takes to come back, and not over another programme chosen meanwhile.
                repeat(RELOAD_SEEK_TRIES) {
                    if (seeked || viewModel.state.value.playing !== source) return@launch
                    val timeline = player.currentTimeline
                    if (!timeline.isEmpty) {
                        timeline.getWindow(player.currentMediaItemIndex, window)
                        val fresh = window.windowStartTimeMs != C.TIME_UNSET && window.windowStartTimeMs != staleStart
                        // Away for longer than the stream can be rewound, it comes back live.
                        if (fresh && LivePlayback.seekToTimeIfReady(player, window, at, liveIfGone = true)) return@launch
                    }
                    delay(RELOAD_SEEK_POLL_MS)
                }
            } finally {
                player.removeListener(seeks)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Shrink the forward buffer under memory pressure so it stops growing and frees its slack.
        loadControl?.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) viewModel.trimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        loadControl?.onLowMemory()
        viewModel.trimMemory()
    }

    private fun stopRemote() {
        remote.stop()
        RemoteKeepAliveService.stop(this)
    }

    // The app is left for good, not just behind the screensaver where a phone may still call it: the
    // remote control stops unless it is kept at all times, and with nothing playing it is the moment for
    // an automatic update, which closes the app.
    private fun leave() {
        // Not from a screen closed with Back whose successor is already up: Android may end the old
        // one after it has started the new one, whose remote control this would stop.
        if (!remote.always && remote.shows(this)) stopRemote()
        // Whatever the pages say: a programme the phone stopped behind the screensaver is still loaded,
        // its screen being taken down only once the app is shown again.
        unload()
        if (viewModel.state.value.playing == null) {
            installFetchedUpdate(viewModel)
        } else {
            // A TV is mostly left from the player, with Home or its power button: waiting for a leave
            // with nothing playing, an automatic update would never be installed, and is never offered.
            // So it also goes in once the app has been away for an hour over a programme, which is
            // let go of by then and has its place saved.
            handler.removeCallbacks(installOnceAway)
            handler.postDelayed(installOnceAway, AWAY_INSTALL_MS)
        }
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
        handler.removeCallbacks(installOnceAway)
        handler.removeCallbacks(letScreenRest)
        runCatching { unregisterReceiver(screenReceiver) }
        val shown = remote.shows(this)
        remote.detach(this)
        if (shown && !remote.always) stopRemote()
        session?.release()
        session = null
        player?.release()
        player = null
        loadControl = null
        super.onDestroy()
    }
}

/** The programme bar of a live stream, as far as [geometry] gives it. */
@Composable
private fun TvLiveBar(geometry: () -> LiveBarGeometry?, programWindow: ProgramWindow?, modifier: Modifier) {
    val bar = geometry() ?: return
    val pw = programWindow?.takeIf { bar.hasProgramBand }
    val startLabel = remember(pw?.startMs) { pw?.let { LivePlayback.clockLabel(it.startMs) } }
    val endLabel = remember(pw?.endMs) { pw?.let { LivePlayback.clockLabel(it.endMs) } }
    LiveProgramBar(
        playedFraction = bar.playedFraction,
        liveFraction = bar.liveFraction,
        seekFloorFraction = bar.seekFloorFraction,
        modifier = modifier,
        startLabel = startLabel,
        endLabel = endLabel,
    )
}
@Composable
private fun TvPlayerSurface(
    source: PlaybackSource,
    player: ExoPlayer,
    onSavePosition: (String?, Long) -> Unit,
    onReportProgress: (PlaybackSource, Long, Long) -> Unit,
    fetchProgramWindow: suspend (String, Long) -> ProgramWindow?,
    reResolve: suspend (PlaybackSource) -> PlaybackSource?,
    onPlaybackFailed: (PlaybackSource) -> Unit,
    onPlayNext: () -> Unit = {},
    // The app was left: nothing is to be loaded until it is back.
    away: () -> Boolean = { false },
    // Told what skips the ad break on offer, for a phone to ask for it too, and null when there is none.
    onAdBreak: ((() -> Unit)?) -> Unit = {},
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
        val recovery = PlaybackRecovery(player, scope, source, reResolve, away) { onPlaybackFailed(source) }
        player.addListener(recovery)
        onDispose {
            player.removeListener(recovery)
            recovery.stop()
            val pos = player.currentPosition
            // Given up on in the middle of a reload, the player no longer knows its duration.
            val dur = player.duration.takeIf { it > 0 } ?: recovery.durationMs
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
    val programme = remember(source) {
        LiveProgramme(source.programWindow) { at -> source.originChannelId?.let { fetchProgramWindow(it, at) } }
    }
    var barGeometry by remember { mutableStateOf<LiveBarGeometry?>(null) }
    // A "depuis le début" start, or a rewound programme handed over by the phone, asks playback to begin
    // that far behind the live edge; the player joins at the edge first, so seek back to what aired then
    // once the live window has loaded, then stop retrying.
    var pendingRewind by remember(source) { mutableStateOf(source.isLive && source.liveRewindOffsetMs > 0L) }
    val rewindTo = remember(source) { System.currentTimeMillis() - source.liveRewindOffsetMs }
    // The programme bar and the back-to-live pill go with the player's controls. They start hidden, and
    // what the player view does as it is given its player is heard below: taken as shown from the
    // start, with a view that left them hidden, they stayed on screen until a key was pressed.
    var controlsVisible by remember { mutableStateOf(false) }
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
    // The skip pill is selected the same way, with Right from the last button of the main row, level
    // with it: OK then skips, Left returns to the controls, Up goes on to the back-to-live pill, and
    // any other key or the controls hiding leaves it.
    var skipSelected by remember { mutableStateOf(false) }
    // The ad break playback is in, when the stream's manifest gives it away and it can be skipped.
    val adSkipper = remember(source) { AdSkipper(TvPrefs.skipAds(view.context)) }
    var adBreak by remember(source) { mutableStateOf<AdBreak?>(null) }
    DisposableEffect(adSkipper) {
        player.addListener(adSkipper)
        onDispose { player.removeListener(adSkipper) }
    }
    fun skipAd() {
        adSkipper.skip(player, liveWindow)
        adBreak = null
    }
    val offered = adBreak != null
    DisposableEffect(offered) {
        onAdBreak(if (offered) { { skipAd() } } else null)
        onDispose { onAdBreak(null) }
    }
    // OK while the break on screen can be skipped and says so, the controls being hidden (shown, OK
    // is theirs): the press skips. Its release is taken along, and neither brings the controls up.
    var skipPressed by remember { mutableStateOf(false) }
    // Left and Right with the controls hidden seek at once, as on the Chromecast, rather than bring the
    // controls up: the presses add up to one seek, made once they stop, a held key going faster the
    // longer it is held, and the bar shows where it lands meanwhile.
    var seekTarget by remember(source) { mutableStateOf<Long?>(null) }
    var seekShown by remember(source) { mutableStateOf<Long?>(null) }
    var seekHeld by remember { mutableStateOf(false) }
    var seekDirection by remember { mutableIntStateOf(0) }
    var seekRepeats by remember { mutableIntStateOf(0) }
    val seekWindow = remember { Timeline.Window() }
    // A rewind stops at the start of the programme playback is in, as on the Chromecast (RewindHold).
    val rewindHold = remember(source) { RewindHold() }
    var parked by remember(source) { mutableStateOf(false) }
    // Each press puts the seek off again, at once: an effect keyed on the presses would only be
    // cancelled at the next frame, and could seek and drop the press in between.
    val seekScope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(source) { onDispose { seekJob?.cancel() } }
    fun seekKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        // The release of a press taken here, which would bring the controls up.
        if (event.action != KeyEvent.ACTION_DOWN) return seekHeld.also { seekHeld = false }
        if (controlsVisible || !player.isCurrentMediaItemSeekable || player.currentTimeline.isEmpty) return false
        player.currentTimeline.getWindow(player.currentMediaItemIndex, seekWindow)
        // The live point, or the end of what plays.
        val end = if (seekWindow.isLive()) seekWindow.defaultPositionMs else player.duration
        if (end == C.TIME_UNSET || end <= 0L) return false
        seekRepeats = if (event.repeatCount > 0 && direction == seekDirection) seekRepeats + 1 else 0
        seekDirection = direction
        val step = if (direction < 0) -LivePlayback.SEEK_BACK_MS else LivePlayback.SEEK_FORWARD_MS
        val faster = minOf(SEEK_MAX_FASTER, 1f + seekRepeats * SEEK_FASTER_STEP)
        val programme = programWindow?.takeIf { seekWindow.isLive() && seekWindow.windowStartTimeMs != C.TIME_UNSET }
        val target = rewindHold.press(
            base = seekTarget ?: player.currentPosition,
            step = (step * faster).toLong(),
            end = end,
            start = programme?.let { it.startMs - seekWindow.windowStartTimeMs },
            startOfDay = programme?.startMs,
            letGo = direction < 0 && event.repeatCount == 0 && seekTarget == null,
        )
        parked = rewindHold.parked
        seekTarget = target
        seekShown = target
        seekHeld = true
        seekJob?.cancel()
        seekJob = seekScope.launch {
            delay(SEEK_COMMIT_MS)
            player.seekTo(target)
            seekTarget = null
            seekRepeats = 0
            delay(SEEK_LINGER_MS)
            seekShown = null
        }
        return true
    }
    fun skipKey(event: KeyEvent): Boolean {
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!ok) return false
        if (event.action != KeyEvent.ACTION_DOWN) return skipPressed.also { skipPressed = false }
        if (event.repeatCount > 0) return skipPressed
        if (controlsVisible || adBreak == null) return false
        skipPressed = true
        skipAd()
        return true
    }
    LaunchedEffect(behindLive, controlsVisible) { if (!behindLive || !controlsVisible) pillSelected = false }
    LaunchedEffect(offered, controlsVisible) { if (!offered || !controlsVisible) skipSelected = false }
    fun remoteKeyUsed(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val bottomBar = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val mainRow = playerView?.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)
        return when {
            skipSelected && ok -> {
                if (!down) {
                    skipSelected = false
                    skipAd()
                }
                true
            }
            skipSelected && right -> true
            skipSelected && (left || up) -> {
                if (down) {
                    skipSelected = false
                    if (up && behindLive) pillSelected = true
                }
                true
            }
            skipSelected -> {
                if (down) skipSelected = false
                false
            }
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
            controlsVisible && down && right && adBreak != null && mainRow is ViewGroup && mainRow.hasFocus() &&
                FocusFinder.getInstance().findNextFocus(mainRow, mainRow.findFocus(), View.FOCUS_RIGHT) == null -> {
                skipSelected = true
                true
            }
            else -> false
        }
    }
    // Called by the player view before its own key handling; true when the key was used here. Such a
    // key never reaches the controls, so restart their hide timer by hand.
    fun remoteKey(event: KeyEvent): Boolean =
        skipKey(event) || seekKey(event) || remoteKeyUsed(event).also { if (it) playerView?.showController() }
    // The view below is made once, but the source can change under it (the next episode, a channel
    // the phone sends) along with its ad break and skipper: its keys go to this composition's handler.
    val keyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { remoteKey(it) }
    // Per source, not per kind: another live channel has its own programme, rewind and channel to ask.
    // Only while the app is on screen: it draws the bar, and asks the guide for the programme that follows.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source, lifecycle) {
        // VOD and recordings have no live edge, so do not poll: stay at "not behind" without a 1s loop.
        if (!source.isLive) {
            behindLive = false
            barGeometry = null
            adBreak = null
            // A recording's ad breaks are looked for in it, and offered as playback comes into one.
            val recording = source.originRecordingAssetId ?: return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    adSkipper.followRecording(player, recording, liveWindow) { adBreak = it }
                } finally {
                    // Not looked at while the app is away: no break is on offer then.
                    adBreak = null
                }
            }
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    behindLive = LivePlayback.behindLiveEdgeMs(player, liveWindow) > LivePlayback.LIVE_REWIND_THRESHOLD_MS
                    // An ad break being watched behind the live point, where its end is known: skipped at
                    // once when the settings say so, each break once, and offered with a pill otherwise
                    // (as is one gone back into with the remote).
                    adBreak = adSkipper.tick(player, liveWindow)
                    if (pendingRewind && LivePlayback.seekToTimeIfReady(player, liveWindow, rewindTo)) {
                        pendingRewind = false
                        adSkipper.ownSeek()
                    }
                    // The show playback is in, which follows it past the show's end or back into the one
                    // before, as when it was handed over rewound (see LiveProgramme).
                    programWindow = programme.current(player, liveWindow)
                    barGeometry = LivePlayback.liveBarGeometry(player, liveWindow, programWindow)
                    delay(1000)
                }
            } finally {
                // Not looked at while the app is away: no break is on offer then, to the phone either.
                adBreak = null
            }
        }
    }
    // The ad breaks of a live channel that marks none, or too few, are looked for in the pictures the
    // service keeps of it, while it is watched behind the live point.
    LaunchedEffect(source, lifecycle) {
        val thumbnails = source.breakPictures ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { adSkipper.followLive(player, thumbnails, Timeline.Window()) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                object : PlayerView(context) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                        keyHandler.value(event) || super.dispatchKeyEvent(event)
                }.apply {
                    // Before the player is set: the view shows its controls then or leaves them hidden,
                    // and says so only as it changes them.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                            if (!controlsVisible) post { requestFocus() }
                        },
                    )
                    this.player = player
                    playerView = this
                    // The programme bar replaces the default time bar, which spans the bare 4h DVR
                    // window with no show markers.
                    LivePlayback.useProgramBar(this, source.isLive)
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
        TvSeekAndBar(player, seekWindow, { seekShown }, { parked }, programWindow, { barGeometry }, controlsVisible)
        // With or without the controls, for as long as the break can be skipped. A click is for a
        // mouse or a touch screen: the remote's OK is taken above, and with the controls up it skips
        // once the pill is selected.
        if (adBreak != null) {
            SkipAdsButton(
                onClick = { skipAd() },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp)
                    .then(if (skipSelected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier),
                label = if (controlsVisible && !skipSelected) "Passer la pub" else "Passer la pub : OK",
            )
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

/**
 * Where a seek from the remote lands ([seekShown]) and the live bar, which follow each repeat of a
 * held key: read here, so that only this is composed again meanwhile, not the whole player screen.
 */
@Composable
private fun BoxScope.TvSeekAndBar(
    player: Player,
    seekWindow: Timeline.Window,
    seekShown: () -> Long?,
    parked: () -> Boolean,
    programWindow: ProgramWindow?,
    barGeometry: () -> LiveBarGeometry?,
    controlsVisible: Boolean,
) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    // While seeking from the remote, the bar and the time are those of where it lands.
    val seeking = seekShown()
    if (seeking != null) {
        SeekPreview(
            label = seekLabel(player, seekWindow, seeking, clock) +
                if (parked()) " · Début de l'émission, appuyez encore sur ◄ pour reculer plus loin" else "",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LiveProgramBarBottomOffset + 40.dp),
        )
    }
    // Outside the programme playback is in, a held rewind passing into the one before, the bar spans
    // the rewind window, as it does where the guide does not say what was on.
    val shownProgramme = if (seeking == null) {
        programWindow
    } else {
        programWindow?.takeIf { seekWindow.windowStartTimeMs != C.TIME_UNSET && seekWindow.windowStartTimeMs + seeking in it.startMs until it.endMs }
    }
    // The bar changes every second: read in a composable of its own, which alone is drawn again.
    if (controlsVisible || seeking != null) {
        TvLiveBar(
            geometry = { seeking?.let { LivePlayback.liveBarGeometry(player, seekWindow, shownProgramme, it) } ?: barGeometry() },
            programWindow = shownProgramme,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(start = 48.dp, end = 48.dp, bottom = LiveProgramBarBottomOffset),
        )
    }
}

/**
 * Where a seek chosen with the remote lands: the time of day on a live channel, as the bar's ends
 * are, with [clock], and the position out of the length otherwise. [window] was filled for the item
 * playing.
 */
private fun seekLabel(player: Player, window: Timeline.Window, positionMs: Long, clock: SimpleDateFormat): String =
    if (window.isLive() && window.windowStartTimeMs != C.TIME_UNSET) {
        clock.format(Date(window.windowStartTimeMs + positionMs))
    } else {
        "${duration(positionMs)} / ${duration(player.duration)}"
    }

private fun duration(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SeekPreview(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
