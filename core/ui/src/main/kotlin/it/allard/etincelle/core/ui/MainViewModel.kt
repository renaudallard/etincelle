// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.FavoriteOrder
import it.allard.etincelle.core.model.LoginResult
import it.allard.etincelle.core.model.MfaChallenge
import it.allard.etincelle.core.model.PlayKind
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.Profile
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.ProgramWindow
import it.allard.etincelle.core.model.RecordAction
import it.allard.etincelle.core.model.UpNext
import it.allard.etincelle.core.model.withUpNext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap

// TV pairing poll cadence: poll every few seconds, and regenerate the code a little before its
// server expiry so a confirm racing the deadline is not lost.
private const val PAIRING_POLL_SEC = 3L
private const val PAIRING_EXPIRY_MARGIN_SEC = 15L
private const val PAIRING_RETRY_MS = 5000L
// Cap on how many codes the login screen will cycle through before giving up, so a TV left idle on
// the login screen cannot poll indefinitely.
private const val PAIRING_MAX_ROUNDS = 12
// Shown while no code could be had, until one is.
private const val PAIRING_RETRY_NOTICE = "Connexion impossible, nouvel essai…"
private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val UPDATE_RECHECK_MS = 60 * 60 * 1000L
// A page that shows what is on air goes stale as programmes change, and nothing on it says when. It is
// loaded again once it is this old when someone comes back to it, and this old while it stays on screen.
private const val STALE_ON_RETURN_MS = 2 * 60 * 1000L
private const val STALE_ON_SCREEN_MS = 5 * 60 * 1000L
private const val STALE_CHECK_MS = 30 * 1000L
// The guide, a day of every channel and megabytes to fetch, is shown again as it was when its tab is
// chosen again within this time, and loaded again only if it has gone stale meanwhile.
private const val GUIDE_KEPT_MS = 30 * 60 * 1000L
// Signed in, but the first page did not come: the tabs are there to load one.
private const val HOME_FAILED = "Échec du chargement de l'accueil"
// The official app caps a profile name at 30 characters.
const val PROFILE_NAME_MAX = 30

// [shortLabel] is what the phone's crowded bottom bar shows.
enum class Tab(val label: String, val icon: String, val path: String, val shortLabel: String = label) {
    HOME("Accueil", "🏠", "papi/v1/page/home"),
    LIVE("Direct", "📡", "papi/v1/page/live-tv"),
    CHANNELS("Chaînes", "📺", "papi/v1/page/channels"),
    GUIDE("Guide", "🗓", ""),
    MOVIES("Films", "🎬", "papi/v1/page/films"),
    SERIES("Séries", "📺", "papi/v1/page/series"),
    RECORDINGS("Enregistrements", "⏺", "papi/v1/my-stuff/status/recordings", shortLabel = "Enregistrés"),
    SEARCH("Recherche", "🔍", ""),
}

/** One open detail page plus the DVR recording (if any) that its "Regarder" should play. [id]/[kind]
 * are how it was fetched, so it can be refetched in place (e.g. after a record action toggles its CTA). */
data class DetailEntry(
    val detail: ProgramDetail,
    val recordingAssetId: String?,
    val id: String,
    val kind: DetailKind,
)

data class UiState(
    val checking: Boolean = true,
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val tab: Tab = Tab.HOME,
    val backStack: List<ContentPage> = emptyList(),
    // A stack of open detail pages, so a detail opened from within one (e.g. an episode) backs out to
    // its parent rather than jumping straight to browse.
    val detailStack: List<DetailEntry> = emptyList(),
    val playing: PlaybackSource? = null,
    val settings: Boolean = false,
    // The favourite channels being reordered (the reorder screen is open), or null.
    val favoriteOrder: FavoriteOrder? = null,
    // A two-factor sign-in waiting for its code (the code screen is shown), or null.
    val mfa: MfaChallenge? = null,
    // The profiles screen (asking who is watching, or opened from the settings), or null.
    val profileScreen: ProfileScreen? = null,
    // A profile being created (profile null) or edited, over the profiles screen.
    val profileEditor: ProfileEditor? = null,
    val update: UpdateInfo? = null,
    // The proposed update is downloading (percent), and the message when installing it failed.
    val updateProgress: Int? = null,
    val updateError: String? = null,
    // An explicit "check for updates" is running, and its result message when up-to-date or failed.
    val checkingUpdate: Boolean = false,
    val updateStatus: String? = null,
    val error: String? = null,
    val info: String? = null,
    // The TV pairing code currently displayed (and being polled), null when not pairing.
    val pairingCode: String? = null,
    // Result of the phone-side "Connecter une TV" confirm, kept separate from the global info/error so
    // the dialog never shows a stale or unrelated message.
    val tvConnectInfo: String? = null,
    val tvConnectError: String? = null,
    val hideLocked: Boolean = false,
    // A lightweight reload is running (pull to refresh / resume), as opposed to a full-screen [busy] load.
    val refreshing: Boolean = false,
) {
    val current: ContentPage? get() = backStack.lastOrNull()
    val canGoBack: Boolean get() = backStack.size > 1

    /** The channel grid is shown, from its tab or a rail's "Tout voir": the favourites are reordered there. */
    val onChannelsPage: Boolean get() = current?.reloadUrl?.substringBefore('?')?.endsWith(Tab.CHANNELS.path) == true

    /** The detail page currently shown (the top of the stack), or null when browsing. */
    val detail: ProgramDetail? get() = detailStack.lastOrNull()?.detail

    /** When the open detail was reached from a DVR recording, the recording its "Regarder" plays. */
    val detailRecordingAssetId: String? get() = detailStack.lastOrNull()?.recordingAssetId

    /** The current page's rails with locked (unentitled) cards removed when the user hid them. Computed
     * once per state instance (a getter would re-filter and reallocate on every read/recomposition). */
    val visibleRails: List<ContentRail> by lazy {
        val rails = current?.rails ?: return@lazy emptyList()
        if (!hideLocked) return@lazy rails
        rails.mapNotNull { rail ->
            val cards = rail.cards.filterNot { it.isLocked }
            if (cards.isEmpty()) null else rail.copy(cards = cards)
        }
    }
}

// A card with the backend's tag (DIRECT) that opens a channel is the programme now on that channel.
private val ContentPage.showsWhatIsOnAir: Boolean
    get() = rails.any { rail -> rail.cards.any { it.channelId != null && it.tag != null } }

/** The profiles screen: [asking] when the app asks who is watching, else opened from the settings. */
data class ProfileScreen(val list: Profiles, val currentId: String?, val asking: Boolean, val editing: Boolean = false)

/** The profile being edited, or a new one when [profile] is null. */
data class ProfileEditor(val profile: Profile?)

/** Shared presentation logic for both the phone and TV apps. */
class MainViewModel(
    private val repo: MolotovRepository,
    private val updates: UpdateChecker,
    private val auto: AutoUpdateStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // When each page of the back stack was loaded, by instance: a page gets its time as it comes in.
    private val loadedAt = IdentityHashMap<ContentPage, Long>()

    // The last guide loaded, when, and for which account and profile.
    private class KeptGuide(val page: ContentPage, val at: Long, val user: String, val profile: String)
    private var keptGuide: KeptGuide? = null

    init {
        viewModelScope.launch {
            var seen: List<ContentPage>? = null
            _state.collect { s ->
                val stack = s.backStack
                if (stack === seen) return@collect
                seen = stack
                loadedAt.keys.retainAll { known -> stack.any { it === known } }
                for (page in stack) if (page !in loadedAt) loadedAt[page] = clock()
            }
        }
        viewModelScope.launch {
            val restored = runCatching { repo.restoreSession() }.getOrDefault(false)
            if (restored) {
                runCatchingNav { repo.loadHome() }
                    .onSuccess { page ->
                        _state.update { it.copy(checking = false, loggedIn = true, tab = Tab.HOME, backStack = listOf(page)) }
                        consumeDeepLink()
                        askWhoIsWatching()
                    }
                    .onFailure { e ->
                        // Only a genuinely dead session (the repo cleared it) returns to login; a transient
                        // blip keeps the restored session signed in with an error, like the pairing path,
                        // instead of ejecting a still-valid session.
                        val sessionDead = e is AppError.Unauthorized && repo.currentSession() == null
                        _state.update {
                            if (sessionDead) {
                                it.copy(checking = false, loggedIn = false)
                            } else {
                                it.copy(checking = false, loggedIn = true, tab = Tab.HOME, error = HOME_FAILED)
                            }
                        }
                        // Still signed in: a link that opened the app and the profile question go ahead.
                        if (!sessionDead) {
                            consumeDeepLink()
                            askWhoIsWatching()
                        }
                    }
            } else {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun login(email: String, password: String) {
        codeTaken = false
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.login(email.trim(), password) }
                .onSuccess { result ->
                    when (result) {
                        is LoginResult.SignedIn -> enterHome()
                        // Two-factor login: the code screen asks for the code the backend sent.
                        is LoginResult.CodeRequired -> _state.update { it.copy(busy = false, mfa = result.challenge) }
                    }
                }
                .onFailure { e -> applyFailure(e, "Échec de connexion") }
        }
    }

    // Signed in: load the home, then follow a pending deep link and ask who is watching.
    private suspend fun enterHome() {
        runCatching { repo.loadHome() }
            .onSuccess { page ->
                _state.update { it.copy(busy = false, mfa = null, loggedIn = true, tab = Tab.HOME, backStack = listOf(page)) }
                consumeDeepLink()
                askWhoIsWatching()
            }
            .onFailure { e ->
                // The sign-in went through and is saved: a home page that fails to load must not read
                // as a failed sign-in, least of all after a code that is now spent.
                if (repo.currentSession() == null) return@onFailure applyFailure(e, "Échec de connexion")
                _state.update { it.copy(busy = false, mfa = null, loggedIn = true, tab = Tab.HOME, error = HOME_FAILED) }
                consumeDeepLink()
                askWhoIsWatching()
            }
    }

    /** Two-factor login: sends the [code] the user received. */
    fun verifyCode(code: String) {
        val challenge = _state.value.mfa ?: return
        if (code.isBlank() || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        codeJob = viewModelScope.launch {
            runCatchingNav { repo.verifyCode(challenge, code) }
                .onSuccess {
                    codeTaken = true
                    enterHome()
                }
                .onFailure { e -> applyFailure(e, "Code refusé") }
        }
    }

    /** Two-factor login: asks the backend to send a new code. */
    fun resendCode() {
        val challenge = _state.value.mfa ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        codeJob = viewModelScope.launch {
            runCatchingNav { repo.resendCode(challenge) }
                .onSuccess { next -> _state.update { it.copy(busy = false, mfa = next, info = "Nouveau code envoyé") } }
                .onFailure { e -> applyFailure(e, "Échec de l'envoi du code") }
        }
    }

    // The code being checked, or sent again.
    private var codeJob: Job? = null

    // The code of the sign-in under way was accepted.
    private var codeTaken = false

    /**
     * Leaves the code screen, back to the sign-in form. A request still out is dropped with it: its
     * answer would bring this screen back, or sign in someone who has just declined to.
     */
    fun cancelCode() {
        // The code was taken: signed in already, with the home page on its way.
        if (codeTaken) return
        codeJob?.cancel()
        _state.update { it.copy(mfa = null, busy = false, error = null, info = null) }
    }

    private var pairingJob: Job? = null
    private var confirmJob: Job? = null

    // Run [block], returning null on a plain failure but rethrowing CancellationException so cancelling
    // the pairing job actually stops the loop instead of being swallowed into another poll iteration.
    private suspend fun <T> orNull(block: suspend () -> T): T? =
        try { block() } catch (c: CancellationException) { throw c } catch (e: Exception) { null }

    /**
     * TV "connect my TV" login: shows a code (in [UiState.pairingCode]) and polls until the user
     * confirms it from a signed-in device, regenerating a fresh code each time one expires.
     */
    fun loginWithCode() {
        pairingJob?.cancel()
        // A session that expired is why this screen is shown: that stays said until the sign-in.
        _state.update { it.copy(busy = true, error = it.error.takeIf { e -> e == AppError.Unauthorized.message }, pairingCode = null) }
        pairingJob = viewModelScope.launch {
            var rounds = 0
            // A code was shown at least once: only then can the end of all this be one that expired.
            var shown = false
            while (rounds < PAIRING_MAX_ROUNDS) {
                rounds++
                val pairing = orNull { repo.startCodeLogin() }
                if (pairing == null) {
                    // Still busy: the loop retries on its own after the delay, so the screen shows the
                    // notice without offering a manual retry button that would just restart this loop.
                    _state.update { it.copy(busy = true, pairingCode = null, error = PAIRING_RETRY_NOTICE) }
                    delay(PAIRING_RETRY_MS)
                    continue
                }
                shown = true
                _state.update { it.copy(busy = false, error = it.error.takeIf { e -> e != PAIRING_RETRY_NOTICE }, pairingCode = pairing.code) }
                // Poll until confirmed or the code is about to expire (then loop to regenerate), by the
                // time gone since it came, with a margin, rather than the wall clock against the
                // server's. Not by a count of polls: each takes its own time on top of the wait, a
                // stalled one its whole timeout, and the code stayed on screen past its expiry.
                val since = TimeSource.Monotonic.markNow()
                val lifetime = (pairing.ttlSeconds - PAIRING_EXPIRY_MARGIN_SEC).coerceAtLeast(PAIRING_POLL_SEC).seconds
                while (since.elapsedNow() < lifetime) {
                    delay(PAIRING_POLL_SEC * 1000)
                    val session = orNull { repo.pollCodeLogin(pairing.code, pairing.deviceId) }
                    if (session != null) {
                        // Session is valid and persisted; if the home fails to load (a transient blip),
                        // still sign in but surface it so the user can reload a tab, rather than landing
                        // on a silent empty browse screen.
                        val page = orNull { repo.loadHome() }
                        _state.update {
                            if (page != null) {
                                it.copy(loggedIn = true, pairingCode = null, error = null, busy = false, tab = Tab.HOME, backStack = listOf(page))
                            } else {
                                it.copy(loggedIn = true, pairingCode = null, busy = false, tab = Tab.HOME, error = HOME_FAILED)
                            }
                        }
                        consumeDeepLink()
                        askWhoIsWatching()
                        return@launch
                    }
                }
            }
            // Polled across many codes without a confirmation: stop and let the user retry, so the loop
            // cannot keep polling forever (e.g. a TV left idle on the login screen).
            _state.update { it.copy(busy = false, pairingCode = null, error = if (shown) "Code expiré, réessayez." else "Connexion impossible, réessayez.") }
        }
    }

    /** Stops the pairing poll without changing the visible state (when the login screen leaves). */
    fun stopPairingPoll() {
        pairingJob?.cancel()
        pairingJob = null
    }

    /** Stops the TV pairing poll and clears its UI (the user switched to email login). */
    fun cancelCodeLogin() {
        pairingJob?.cancel()
        pairingJob = null
        _state.update { it.copy(pairingCode = null, busy = false, error = null) }
    }

    /** Confirms a TV's pairing code from this signed-in device, logging that TV into this account. */
    fun confirmTvCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || confirmJob?.isActive == true) return
        _state.update { it.copy(busy = true, tvConnectInfo = null, tvConnectError = null) }
        confirmJob = viewModelScope.launch {
            runCatching { repo.confirmTvCode(trimmed) }
                .onSuccess { _state.update { it.copy(busy = false, tvConnectInfo = "TV connectée") } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    // Not for want of a network, nor a session that has just died: only a code refused.
                    if (e is AppError.Unauthorized && repo.currentSession() == null) return@onFailure applyFailure(e, "")
                    val message = (e as? AppError.Network)?.message ?: "Code invalide ou expiré"
                    _state.update { it.copy(busy = false, tvConnectError = message) }
                }
        }
    }

    /** Clears the "Connecter une TV" result (when the dialog opens or closes). */
    fun clearTvConnect() = _state.update { it.copy(tvConnectInfo = null, tvConnectError = null) }

    private var pendingDeepLink: Pair<String, DetailKind>? = null

    // One in-flight navigation (page/detail load) at a time: launching a new one cancels the previous
    // so a slower earlier load cannot overwrite the state of a newer one (last-writer-wins races).
    private var navJob: Job? = null

    // [refresh] marks a silent reload, which keeps the progress bar it just put up; any other
    // navigation supersedes a reload still in flight, whose bar would otherwise never come down.
    private fun navLaunch(refresh: Boolean = false, block: suspend () -> Unit) {
        navJob?.cancel()
        overlayJob?.cancel()
        if (!refresh) _state.update { it.copy(refreshing = false) }
        navJob = viewModelScope.launch { block() }
    }

    // The load of a screen that opens over the pages (the profiles, the favourites order). Going
    // elsewhere meanwhile drops it: its screen would open over where the user went.
    private var overlayJob: Job? = null

    // Drops the in-flight navigation along with the spinner or progress bar it put up.
    private fun cancelNav() {
        navJob?.cancel()
        overlayJob?.cancel()
        _state.update { it.copy(busy = false, refreshing = false) }
    }

    // Like runCatching, but lets coroutine cancellation propagate: when navJob cancels a superseded
    // load, its suspend call throws CancellationException, which must NOT be turned into an onFailure
    // (that would flash a spurious error, clear busy, or - in showRecordingDetail - start playback).
    private inline fun <T> runCatchingNav(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Result.failure(e)
        }

    // Surfaces a failure. Only when the session is genuinely gone (the repo cleared it) do we return
    // to login - a 403 maps to AppError.Forbidden (not Unauthorized) and the session is still valid, so
    // it stays an in-place banner. Local prefs survive the reset.
    private fun applyFailure(e: Throwable, fallback: String) {
        if (e is AppError.Unauthorized && repo.currentSession() == null) {
            lastQuery = null
            keptGuide = null
            _state.update { UiState(checking = false, error = e.message, hideLocked = it.hideLocked, update = it.update) }
        } else {
            // An error that says nothing of its own is told by what was being done.
            val message = e.message.takeUnless { e is AppError.Unspecified } ?: fallback
            _state.update { it.copy(busy = false, refreshing = false, error = message) }
        }
    }

    /** Opens a show from an external deep link (etincelle://series|program|channel/{id}); defers until login. */
    fun onDeepLink(id: String, kind: DetailKind) {
        pendingDeepLink = id to kind
        if (_state.value.loggedIn) consumeDeepLink()
    }

    private fun consumeDeepLink() {
        val (id, kind) = pendingDeepLink ?: return
        pendingDeepLink = null
        // Clear any open player/settings too, so the deep-linked show surfaces instead of being
        // stranded behind them.
        _state.update {
            it.copy(busy = true, error = null, info = null, detailStack = emptyList(), playing = null, settings = false)
        }
        navLaunch {
            runCatchingNav { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detailStack = listOf(DetailEntry(d, null, id, kind))) } }
                .onFailure { e -> applyFailure(e, "Contenu introuvable") }
        }
    }

    fun selectTab(tab: Tab) {
        if (tab == Tab.SEARCH) {
            // A page still loading for the tab being left must not land in the search results.
            cancelNav()
            // With its results gone, the search has nothing to load again.
            lastQuery = null
            _state.update { it.copy(tab = tab, error = null, backStack = emptyList()) }
            return
        }
        if (tab == Tab.GUIDE) {
            val kept = keptGuide()
            if (tab != _state.value.tab && kept != null) {
                cancelNav()
                // With the time it was loaded at, which the check below goes by.
                loadedAt[kept.page] = kept.at
                _state.update { it.copy(tab = tab, error = null, backStack = listOf(kept.page)) }
                reloadIfStale(STALE_ON_RETURN_MS)
                return
            }
            // The guide is time-sensitive, so re-selecting it refreshes (silently when already loaded)
            // rather than keeping a stale snapshot.
            reloadGuide(silent = tab == _state.value.tab && _state.value.backStack.isNotEmpty())
            return
        }
        if (tab == Tab.HOME) {
            // Accueil must go through loadHome (which prepends the broadcaster apps rail), not the raw page.
            if (tab == _state.value.tab && _state.value.backStack.size == 1) return
            reloadHome(silent = false)
            return
        }
        if (tab == _state.value.tab && _state.value.backStack.size == 1) return
        // Without the pages of the tab being left: should the load fail they would stay under the new
        // tab's name, and count for this tab's own page, which choosing it again would then not load.
        _state.update { it.copy(busy = true, error = null, tab = tab, backStack = emptyList()) }
        // The Direct and Chaînes tabs load a page (/live-tv, /channels) that is also reachable from a
        // rail header, so render them as the same full-screen grid for a coherent experience.
        loadPageInto(tab.path, replace = true, fallbackTitle = tab.label, grid = tab == Tab.LIVE || tab == Tab.CHANNELS || tab == Tab.RECORDINGS)
    }

    private fun reloadGuide(silent: Boolean, quiet: Boolean = false) {
        _state.update {
            if (silent) it.copy(tab = Tab.GUIDE, error = null, refreshing = true)
            else it.copy(tab = Tab.GUIDE, error = null, busy = true, backStack = emptyList())
        }
        navLaunch(refresh = silent) {
            val asker = repo.currentSession()
            runCatchingNav { repo.loadGuide() }
                .onSuccess { page ->
                    // Not kept for the account or profile that took over while it was on its way.
                    val now = repo.currentSession()
                    keptGuide = if (asker != null && now?.userId == asker.userId && now.profileId == asker.profileId) {
                        KeptGuide(page, clock(), asker.userId, asker.profileId)
                    } else {
                        null
                    }
                    _state.update { it.copy(busy = false, refreshing = false, backStack = listOf(page)) }
                }
                .onFailure { e -> reloadFailed(e, "Guide indisponible", quiet) }
        }
    }

    /** The app is short of memory, or left: what is kept to show a page sooner is let go of. */
    fun trimMemory() {
        keptGuide = null
    }

    // The guide kept from the last load, while it is recent enough and of the account and profile in use.
    private fun keptGuide(): KeptGuide? {
        val kept = keptGuide ?: return null
        val session = repo.currentSession()
        val fresh = session?.userId == kept.user && session.profileId == kept.profile && clock() - kept.at in 0 until GUIDE_KEPT_MS
        if (!fresh) keptGuide = null
        return kept.takeIf { fresh }
    }

    private fun reloadHome(silent: Boolean, quiet: Boolean = false) {
        _state.update {
            if (silent) it.copy(tab = Tab.HOME, error = null, refreshing = true)
            else it.copy(tab = Tab.HOME, error = null, busy = true, backStack = emptyList())
        }
        navLaunch(refresh = silent) {
            runCatchingNav { repo.loadHome() }
                .onSuccess { page -> _state.update { it.copy(busy = false, refreshing = false, backStack = listOf(page)) } }
                .onFailure { e -> reloadFailed(e, "Accueil indisponible", quiet) }
        }
    }

    /** Refreshes what the page shows as on air when the app returns to the foreground. */
    fun refreshOnResume() = reloadIfStale(STALE_ON_RETURN_MS)

    /**
     * Keeps what the page on screen shows as on air current for as long as the caller runs: the activity
     * runs this while it is started.
     */
    suspend fun keepPageFresh() {
        while (true) {
            delay(STALE_CHECK_MS)
            reloadIfStale(STALE_ON_SCREEN_MS)
            // A day of every channel, not to be held past the time it is kept for.
            keptGuide()
        }
    }

    // Loads the page on screen again when it shows programmes on air and is at least [limitMs] old.
    private fun reloadIfStale(limitMs: Long) {
        val s = _state.value
        val page = s.current ?: return
        if (!s.loggedIn || s.tab == Tab.SEARCH || !(s.tab == Tab.GUIDE || page.showsWhatIsOnAir)) return
        val age = clock() - (loadedAt[page] ?: return)
        // A clock set back makes the age meaningless: load again.
        if (age in 0 until limitMs) return
        reloadCurrent(quiet = true)
    }

    /** Reloads the page currently shown (pull to refresh, or overscroll past an end). */
    fun refreshCurrent() = reloadCurrent(quiet = false)

    // What plays leaves the pages on screen: the phone, while it plays on a TV.
    private var playerAside = false

    /**
     * Whether the pages stay on screen under what plays, as on the phone during a cast: they are then
     * kept fresh as when nothing plays. A player covering them leaves that for when it closes.
     */
    fun setPlayerAside(aside: Boolean) {
        playerAside = aside
    }

    // [quiet] for a reload nobody asked for: when it fails the page stays as it is, with no message.
    // [restart] when a reload already on its way is to be replaced, as one that may predate a change.
    private fun reloadCurrent(quiet: Boolean, restart: Boolean = false) {
        val s = _state.value
        if ((s.playing != null && !playerAside) || s.detail != null || s.settings || s.busy || (s.refreshing && !restart)) return
        val top = s.backStack.lastOrNull()
        val reloadUrl = top?.reloadUrl
        when {
            // A drilled-in papi page reloads itself, whatever tab it lives under.
            reloadUrl != null -> reloadPageTop(reloadUrl, top?.isGrid ?: false, quiet)
            // A drilled-in page with no backing url (a synthetic see-all) has nothing to reload.
            s.backStack.size > 1 -> {}
            // The tab's page never loaded: it is asked for as choosing the tab does.
            top == null && s.tab != Tab.SEARCH -> selectTab(s.tab)
            s.tab == Tab.GUIDE -> reloadGuide(silent = true, quiet)
            s.tab == Tab.SEARCH -> lastQuery?.let { search(it, silent = true) }
            s.tab == Tab.HOME -> reloadHome(silent = true, quiet)
            else -> {}
        }
    }

    // A failed reload: said like any failed load, unless nobody asked for it and the session still stands.
    private fun reloadFailed(e: Throwable, fallback: String, quiet: Boolean) {
        if (quiet && !(e is AppError.Unauthorized && repo.currentSession() == null)) {
            _state.update { it.copy(refreshing = false) }
        } else {
            applyFailure(e, fallback)
        }
    }

    /** Re-fetches the top page's papi url, replacing only that entry so the back stack is kept. */
    private fun reloadPageTop(url: String, grid: Boolean, quiet: Boolean = false) {
        _state.update { it.copy(refreshing = true, error = null) }
        navLaunch(refresh = true) {
            runCatchingNav { repo.loadPage(url) }
                .onSuccess { page ->
                    _state.update {
                        val current = it.backStack.lastOrNull()
                        // If the user navigated away during the fetch, drop the stale result.
                        if (current == null || current.reloadUrl != url) it.copy(refreshing = false)
                        else {
                            val titled = page.copy(title = current.title, isGrid = grid, reloadUrl = url)
                            it.copy(refreshing = false, backStack = it.backStack.dropLast(1) + titled)
                        }
                    }
                }
                .onFailure { e -> reloadFailed(e, "Échec de chargement", quiet) }
        }
    }

    private var lastQuery: String? = null

    fun search(query: String, silent: Boolean = false) {
        if (query.isBlank()) return
        lastQuery = query.trim()
        _state.update { if (silent) it.copy(refreshing = true, error = null) else it.copy(busy = true, error = null) }
        navLaunch(refresh = silent) {
            runCatchingNav { repo.search(query.trim()) }
                .onSuccess { page -> _state.update { it.copy(busy = false, refreshing = false, backStack = listOf(page.copy(title = "Résultats"))) } }
                .onFailure { e -> applyFailure(e, "Échec de recherche") }
        }
    }

    fun onCardClick(card: ContentCard) {
        val recordingAssetId = card.recordingAssetId
        val channelId = card.channelId
        val vodId = card.vodId
        val seriesId = card.seriesId
        when {
            recordingAssetId != null -> when {
                seriesId != null -> showRecordingDetail(card, seriesId, DetailKind.SERIES, recordingAssetId)
                vodId != null -> showRecordingDetail(card, vodId, DetailKind.PROGRAM, recordingAssetId)
                else -> watchRecording(recordingAssetId)
            }
            channelId != null -> showDetail(card, channelId, DetailKind.CHANNEL)
            vodId != null -> showDetail(card, vodId, DetailKind.PROGRAM)
            seriesId != null -> showDetail(card, seriesId, DetailKind.SERIES)
            else -> {
                // A locked app (TF1+, M6+, ...) only carries a tracking/upsell action, so do not try
                // to open it; tell the user a subscription is needed instead.
                if (card.isLocked) {
                    _state.update { it.copy(error = "Abonnement requis pour ${card.title ?: "ce contenu"}") }
                    return
                }
                val url = card.actionUrl ?: return
                _state.update { it.copy(busy = true, error = null) }
                loadPageInto(url, replace = false, fallbackTitle = card.title)
            }
        }
    }

    /** Opens a rail as a full grid: its backend "see all" page, or its own cards if there is none. */
    fun onRailSeeAll(rail: ContentRail) {
        val url = rail.seeAllUrl
        if (url != null) {
            _state.update { it.copy(busy = true, error = null) }
            loadPageInto(url, replace = false, fallbackTitle = rail.title, grid = true)
        } else {
            // A reload of the page under it still on its way would replace the pages, this one included.
            cancelNav()
            val page = ContentPage(rail.title, listOf(rail), isGrid = true)
            _state.update { it.copy(busy = false, error = null, backStack = it.backStack + page) }
        }
    }

    /**
     * Plays a DVR recording by its dvr asset id (from a recording card or a detail's recordings list),
     * offering [upNext] at its end when it is known (from the episode's own page, [pageVodId] when
     * that page names the recording itself: see PlaybackSource.originPageVodId).
     */
    fun watchRecording(assetId: String, upNext: UpNext? = null, pageVodId: String? = null) {
        _state.update { it.copy(busy = true, error = null, info = null) }
        navLaunch {
            runCatchingNav {
                repo.resolveRecording(assetId).copy(originPageVodId = pageVodId).let { if (upNext != null) it.withUpNext(upNext) else it }
            }
                .onSuccess { src -> _state.update { it.copy(busy = false, playing = src) } }
                .onFailure { e -> applyFailure(e, "Échec de lecture") }
        }
    }

    /** Opens a card's detail page (info, cast, year) instead of playing it immediately. */
    private fun showDetail(card: ContentCard, id: String, kind: DetailKind) {
        if (card.isLocked) {
            _state.update { it.copy(error = "${card.title ?: "Ce contenu"} est réservé à Molotov Extra") }
            return
        }
        _state.update { it.copy(busy = true, error = null, info = null) }
        navLaunch {
            runCatchingNav { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d ->
                    // A FAST channel's detail has no programme poster, so fall back to the channel's own
                    // logo (the card the user tapped) to give the page a header instead of leaving it bare.
                    val detail = if (kind == DetailKind.CHANNEL) d.copy(channelLogoUrl = card.imageUrl) else d
                    _state.update { it.copy(busy = false, detailStack = it.detailStack + DetailEntry(detail, null, id, kind)) }
                }
                .onFailure { e -> applyFailure(e, "Détail indisponible") }
        }
    }

    /**
     * Opens the detail page of a recording's show, so it can be browsed (info, cast) before playing.
     * If that show has no detail page we can fetch, falls back to playing the recording directly.
     */
    private fun showRecordingDetail(card: ContentCard, id: String, kind: DetailKind, assetId: String) {
        _state.update { it.copy(busy = true, error = null, info = null) }
        navLaunch {
            runCatchingNav { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detailStack = it.detailStack + DetailEntry(d, assetId, id, kind)) } }
                .onFailure { watchRecording(assetId) }
        }
    }

    /** Plays the show currently shown on the detail page; a live detail plays its channel directly. */
    fun watchDetail() {
        val d = _state.value.detail ?: return
        // A detail opened from a recording, or whose page says it is one, plays that recording: its
        // catch-up stream does not exist.
        (_state.value.detailRecordingAssetId ?: d.recordingAssetId)?.let { asset ->
            // A page that names the recording itself leads a TV to it as well; one opened from a
            // recording does not, and is not given as where it came from.
            val entry = _state.value.detailStack.lastOrNull()
            val page = entry?.id?.takeIf { d.recordingAssetId == asset && entry.kind == DetailKind.PROGRAM }
            watchRecording(asset, d.upNext, page)
            return
        }
        // A series has no stream of its own (its vodId is the series id, not a playable asset); the UI
        // hides the Watch button for it, but guard here too so a stray caller cannot mis-resolve it.
        if (d.isSeries) return
        val vodId = d.vodId
        val channelId = d.channelId
        _state.update { it.copy(busy = true, error = null) }
        navLaunch {
            runCatchingNav {
                when {
                    d.isLive && channelId != null -> repo.resolveLiveChannel(channelId)
                    // An episode carries the next one, offered at its end.
                    vodId != null -> repo.resolveVod(vodId).withUpNext(d.upNext)
                    channelId != null -> repo.resolveLiveChannel(channelId)
                    else -> null
                }
            }.onSuccess { src ->
                _state.update { if (src != null) it.copy(busy = false, playing = src) else it.copy(busy = false) }
            }.onFailure { e -> applyFailure(e, "Échec de lecture") }
        }
    }

    /**
     * Plays the episode that follows the one playing (its end slate counted down, or À suivre was
     * pressed). Its page replaces the current one under the player, as in the official app, so Back
     * lands on the episode being watched.
     */
    fun playUpNext() {
        val current = _state.value.playing ?: return
        val next = current.upNext ?: return
        // A cast plays on while the viewer browses: its next episode then only changes what plays, and
        // neither takes over the page on screen nor cancels a page being opened (nor is cancelled).
        val onEpisodePage = { st: UiState -> current.originVodId != null && st.detailStack.lastOrNull()?.id == current.originVodId }
        val busy = onEpisodePage(_state.value)
        if (busy) _state.update { it.copy(busy = true, error = null, info = null) }
        upNextJob?.cancel()
        upNextJob = viewModelScope.launch {
            runCatchingNav {
                val detail = repo.fetchProgramDetail(next.nextVodId, DetailKind.PROGRAM)
                detail to resolveProgramme(next.nextVodId, detail).withUpNext(detail.upNext)
            }.onSuccess { (detail, src) ->
                _state.update { st ->
                    // Stopped, or something else chosen, meanwhile: leave it so.
                    if (st.playing !== current) return@update if (busy) st.copy(busy = false) else st
                    val entry = DetailEntry(detail, null, next.nextVodId, DetailKind.PROGRAM)
                    val stack = if (onEpisodePage(st)) st.detailStack.dropLast(1) + entry else st.detailStack
                    st.copy(busy = if (busy) false else st.busy, playing = src, detailStack = stack)
                }
                // Left in its credits, the episode before is watched: it starts over next time. On a
                // cast nothing else says so, the phone only having sampled where it was now and then.
                if (_state.value.playing === src) savePlaybackPosition(current.resumeKey, 0)
            }.onFailure { e ->
                // Stopped, or something else chosen, meanwhile: nobody waits for this episode any more,
                // and only the spinner it put up is taken down.
                if (_state.value.playing === current) {
                    applyFailure(e, "Échec de lecture de l'épisode suivant")
                } else if (busy) {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private var upNextJob: Job? = null

    // The stream of programme [id], from the DVR when its page says it is a recording (it then has no
    // catch-up stream).
    private suspend fun resolveProgramme(id: String, detail: ProgramDetail): PlaybackSource =
        detail.recordingAssetId?.let { repo.resolveRecording(it).copy(originPageVodId = id) } ?: repo.resolveVod(id)

    /**
     * Plays what a phone of this account sent to this TV (see core/remote): [id] as [kind], from
     * [positionMs] on demand or [rewindMs] behind the live edge. A position of 0 is this TV's own
     * resume point, unless the phone asked for the beginning ([fromStart]). A programme opens its page
     * under the player, as when it is chosen here, so Back lands on it and its next episode follows.
     */
    fun playFromRemote(kind: PlayKind, id: String, positionMs: Long, rewindMs: Long, fromStart: Boolean = false) {
        _state.update {
            it.copy(busy = true, error = null, info = null, settings = false, favoriteOrder = null, profileScreen = null, profileEditor = null)
        }
        navLaunch {
            runCatchingNav {
                when (kind) {
                    PlayKind.LIVE -> null to repo.resolveLiveChannel(id).copy(liveRewindOffsetMs = rewindMs.coerceAtLeast(0))
                    PlayKind.RECORDING -> null to repo.resolveRecording(id).startingAt(positionMs, fromStart)
                    PlayKind.VOD -> {
                        val detail = repo.fetchProgramDetail(id, DetailKind.PROGRAM)
                        // The slate's rule first: it sends a resume point past the credits back to the
                        // start, which is not for where the phone is watching now.
                        DetailEntry(detail, null, id, DetailKind.PROGRAM) to resolveProgramme(id, detail).withUpNext(detail.upNext).startingAt(positionMs, fromStart)
                    }
                }
            }.onSuccess { (entry, src) ->
                _state.update { st ->
                    st.copy(busy = false, playing = src, detailStack = if (entry != null) listOf(entry) else st.detailStack)
                }
            }.onFailure { e -> applyFailure(e, "Échec de lecture") }
        }
    }

    // Where the phone was, rather than this TV's own resume point.
    private fun PlaybackSource.startingAt(positionMs: Long, fromStart: Boolean) =
        if (positionMs > 0 || fromStart) copy(startPositionMs = positionMs.coerceAtLeast(0)) else this

    /**
     * Plays the on-demand copy of the live programme on the detail page ([ProgramDetail.replay]) from
     * its beginning, wherever it was left. Its own page opens under the player, as when it is chosen in
     * the list of episodes, so Back lands on it and its next episode follows.
     */
    fun watchReplay() {
        val vodId = _state.value.detail?.replay?.vodId ?: return
        _state.update { it.copy(busy = true, error = null, info = null) }
        navLaunch {
            runCatchingNav {
                val detail = repo.fetchProgramDetail(vodId, DetailKind.PROGRAM)
                val source = resolveProgramme(vodId, detail).copy(startPositionMs = 0L, fromStart = true).withUpNext(detail.upNext)
                DetailEntry(detail, null, vodId, DetailKind.PROGRAM) to source
            }.onSuccess { (entry, src) ->
                _state.update { it.copy(busy = false, playing = src, detailStack = it.detailStack + entry) }
            }.onFailure { e -> applyFailure(e, "Échec de lecture") }
        }
    }

    /** Plays the live show on the detail page from the beginning of the current programme: resolves
     * the channel, then asks playback to start that far behind the live edge (within the DVR window)
     * instead of at the edge. Falls back to a normal live start when the programme times are unknown. */
    fun startOverDetail() {
        val d = _state.value.detail ?: return
        if (!d.isLive) return
        val channelId = d.channelId ?: return
        _state.update { it.copy(busy = true, error = null) }
        navLaunch {
            runCatchingNav { repo.resolveLiveChannel(channelId) }
                .onSuccess { src ->
                    val fromStart = src.programWindow?.let { pw ->
                        src.copy(liveRewindOffsetMs = (System.currentTimeMillis() - pw.startMs).coerceAtLeast(0L))
                    } ?: src
                    _state.update { it.copy(busy = false, playing = fromStart) }
                }
                .onFailure { e -> applyFailure(e, "Échec de lecture") }
        }
    }

    /** Runs the given record [action] (schedule the episode/series, or stop an existing series recording)
     * from the detail page, then refetches so the CTA toggles to reflect the new state. */
    fun recordDetail(action: RecordAction) {
        val entry = _state.value.detailStack.lastOrNull() ?: return
        val stopping = (action.payload["action_name"] as? String)?.contains("stop", ignoreCase = true) == true
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            val result = runCatching { repo.record(action) }
            // Only reflect the outcome if still on the detail that started it; a slow action completing
            // after the user navigated away must not stamp busy/banner onto an unrelated screen.
            if (_state.value.detailStack.lastOrNull() !== entry) return@launch
            result
                .onSuccess {
                    // Refetch so the record CTA flips (Enregistrer <-> Arrêter d'enregistrer); best-effort,
                    // as the banner already confirms the action succeeded.
                    val refreshed = runCatching { repo.fetchProgramDetail(entry.id, entry.kind) }.getOrNull()
                    _state.update { st ->
                        if (st.detailStack.lastOrNull() !== entry) return@update st
                        val stack = if (refreshed == null) st.detailStack else {
                            // fetchProgramDetail does not re-inject the channel logo showDetail added for a
                            // CHANNEL detail, so carry it over or a poster-less channel loses its header image.
                            val merged = if (entry.kind == DetailKind.CHANNEL)
                                refreshed.copy(channelLogoUrl = entry.detail.channelLogoUrl) else refreshed
                            st.detailStack.dropLast(1) + entry.copy(detail = merged)
                        }
                        st.copy(
                            busy = false,
                            info = if (stopping) "Enregistrement annulé" else "Enregistrement programmé",
                            detailStack = stack,
                        )
                    }
                }
                .onFailure { e -> applyFailure(e, "Échec de l'enregistrement") }
        }
    }

    // With several profiles, asks who is watching when the official app would (best-effort: a failed
    // lookup keeps the current profile).
    private fun askWhoIsWatching() {
        viewModelScope.launch {
            val list = runCatching { repo.profilesToPick() }.getOrNull() ?: return@launch
            _state.update { if (it.loggedIn) it.copy(profileScreen = ProfileScreen(list, repo.currentSession()?.profileId, asking = true)) else it }
        }
    }

    /** Opens the profiles screen from the settings. */
    fun openProfiles() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            runCatchingNav { repo.profiles() }
                .onSuccess { list ->
                    _state.update { if (it.loggedIn) it.copy(busy = false, profileScreen = ProfileScreen(list, repo.currentSession()?.profileId, asking = false)) else it }
                }
                .onFailure { e -> if (_state.value.loggedIn) applyFailure(e, "Échec de chargement des profils") }
        }
    }

    /** Watches as [profile]: closes the profiles screen and, for another profile, reloads from Accueil. */
    fun pickProfile(profile: Profile) {
        if (_state.value.busy) return
        val switching = profile.id != repo.currentSession()?.profileId
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.selectProfile(profile.id) }
                .onSuccess {
                    // The same profile keeps what was open, such as a programme opened from a link.
                    _state.update {
                        if (switching) {
                            it.copy(busy = false, profileScreen = null, profileEditor = null, settings = false, detailStack = emptyList())
                        } else {
                            it.copy(busy = false, profileScreen = null, profileEditor = null)
                        }
                    }
                    if (switching) reloadHome(silent = false)
                }
                .onFailure { e -> applyFailure(e, "Échec du changement de profil") }
        }
    }

    /**
     * Closes the profiles screen. What it said goes with it, save for the question asked at the start,
     * under which the message may be the home page's own.
     */
    fun closeProfiles() = _state.update {
        it.copy(profileScreen = null, profileEditor = null, error = if (it.profileScreen?.asking == true) it.error else null, info = null)
    }

    /** Modifier on the profiles screen: a tile then opens its profile's editor. Kept across the editor. */
    fun setProfilesEditing(editing: Boolean) = _state.update { it.copy(profileScreen = it.profileScreen?.copy(editing = editing)) }

    /** Opens the editor on [profile], or on a new profile when null. */
    fun editProfile(profile: Profile?) = _state.update { it.copy(profileEditor = ProfileEditor(profile), error = null) }

    fun closeProfileEditor() = _state.update { it.copy(profileEditor = null, error = null) }

    /** Creates the profile in the editor, or renames it and changes its avatar, then refreshes the list. */
    fun saveProfile(name: String, avatarId: Int?) {
        val editor = _state.value.profileEditor ?: return
        val trimmed = name.trim().take(PROFILE_NAME_MAX)
        if (trimmed.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching {
                val profile = editor.profile
                if (profile == null) repo.createProfile(trimmed, avatarId) else repo.updateProfile(profile.id, trimmed, avatarId)
                // Saved: a failed refresh of the list must not report the save as failed.
                runCatching { repo.profiles() }.getOrNull()
            }.onSuccess { list ->
                _state.update { st ->
                    st.copy(
                        busy = false,
                        profileEditor = null,
                        profileScreen = st.profileScreen?.let { screen -> list?.let { screen.copy(list = it) } ?: screen },
                        info = if (editor.profile == null) "Profil créé" else "Profil modifié",
                    )
                }
            }.onFailure { e -> applyFailure(e, "Échec de l'enregistrement du profil") }
        }
    }

    /** Deletes the profile in the editor; deleting the current one switches to another and reloads. */
    fun deleteProfile() {
        val profile = _state.value.profileEditor?.profile ?: return
        if (_state.value.busy) return
        val current = profile.id == repo.currentSession()?.profileId
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching {
                repo.deleteProfile(profile.id)
                // Deleted: when the list cannot be refreshed, drop the profile from the one on screen.
                val list = runCatching { repo.profiles() }.getOrNull()
                // Still the one in use, the repository not having got its list either: on to one of
                // those left on screen, or every request goes on naming a profile that is gone.
                if (repo.currentSession()?.profileId == profile.id) {
                    val left = (list ?: _state.value.profileScreen?.list)?.profiles?.firstOrNull { it.id != profile.id }
                    if (left != null) runCatching { repo.selectProfile(left.id) }
                }
                list
            }.onSuccess { list ->
                _state.update { st ->
                    st.copy(
                        busy = false,
                        profileEditor = null,
                        profileScreen = st.profileScreen?.let { screen ->
                            val shown = list ?: screen.list.copy(profiles = screen.list.profiles.filterNot { it.id == profile.id })
                            screen.copy(list = shown, currentId = repo.currentSession()?.profileId)
                        },
                        info = "Profil supprimé",
                    )
                }
                if (current) reloadHome(silent = false)
            }.onFailure { e -> applyFailure(e, "Échec de la suppression du profil") }
        }
    }

    /** Adds the channel of [card] to the favourites or removes it, then reloads the page to show it. */
    fun toggleFavorite(card: ContentCard) {
        val action = card.favorite ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.toggleFavorite(action) }
                .onSuccess {
                    // A page opened meanwhile owns the spinner, and the refresh would cancel it. Not a
                    // silent reload, which has no spinner to take down and is replaced by this one: it
                    // may have asked for the page before the save, and would show the star as it was.
                    val navigating = navJob?.isActive == true && !_state.value.refreshing
                    _state.update {
                        it.copy(busy = navigating && it.busy, info = if (card.isFavorite) "Retirée des favoris" else "Ajoutée aux favoris")
                    }
                    if (!navigating) reloadCurrent(quiet = false, restart = true)
                }
                .onFailure { e -> applyFailure(e, "Échec de la mise à jour des favoris") }
        }
    }

    /** Opens the screen that reorders the favourite channels. */
    fun openFavoriteOrder() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            runCatchingNav { repo.favoriteOrder() }
                .onSuccess { order -> _state.update { if (it.loggedIn) it.copy(busy = false, favoriteOrder = order) else it } }
                .onFailure { e -> if (_state.value.loggedIn) applyFailure(e, "Échec de chargement des favoris") }
        }
    }

    /** Moves the favourite at [from] to position [to] (not saved until [saveFavoriteOrder]). */
    fun moveFavorite(from: Int, to: Int) = _state.update { st ->
        val order = st.favoriteOrder ?: return@update st
        val list = order.channels
        if (from !in list.indices || to !in list.indices || from == to) return@update st
        val moved = list.toMutableList().apply { add(to, removeAt(from)) }
        st.copy(favoriteOrder = order.copy(channels = moved))
    }

    /** Saves the order shown, closes the screen and reloads the page under it. */
    fun saveFavoriteOrder() {
        val order = _state.value.favoriteOrder ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.saveFavoriteOrder(order.save, order.channels.map { it.id }) }
                .onSuccess {
                    _state.update { it.copy(busy = false, favoriteOrder = null, info = "Ordre des favoris enregistré") }
                    // In the place of a reload already on its way, which may predate the save.
                    reloadCurrent(quiet = false, restart = true)
                }
                .onFailure { e -> applyFailure(e, "Échec de l'enregistrement des favoris") }
        }
    }

    fun closeFavoriteOrder() = _state.update { it.copy(favoriteOrder = null, error = null, info = null) }

    /** Closes the detail page, back to browsing. */
    /** Closes the top detail page: back to its parent detail if any, otherwise back to browsing. */
    fun closeDetail() {
        // Drop any in-flight load/play so it cannot re-open this screen after the user backed out.
        cancelNav()
        _state.update { it.copy(detailStack = it.detailStack.dropLast(1), error = null, info = null) }
        reloadIfStale(STALE_ON_RETURN_MS)
    }

    private fun loadPageInto(url: String, replace: Boolean, fallbackTitle: String?, grid: Boolean = false) {
        navLaunch {
            runCatchingNav { repo.loadPage(url) }
                .onSuccess { page ->
                    val titled = page.copy(title = page.title ?: fallbackTitle, isGrid = grid || page.isGrid, reloadUrl = url)
                    _state.update {
                        val stack = if (replace) listOf(titled) else it.backStack + titled
                        it.copy(busy = false, backStack = stack)
                    }
                }
                .onFailure { e -> applyFailure(e, "Échec de chargement") }
        }
    }

    fun back() {
        cancelNav()
        _state.update { if (it.canGoBack) it.copy(backStack = it.backStack.dropLast(1)) else it }
    }

    fun stopPlaying() {
        cancelNav()
        upNextJob?.cancel()
        _state.update { it.copy(playing = null) }
        reloadIfStale(STALE_ON_RETURN_MS)
    }

    /**
     * Playback of [source] failed for good: closes its player on what it was started from, which says
     * so. Something else asked for meanwhile is left alone.
     */
    fun playbackFailed(source: PlaybackSource) {
        _state.update { if (it.playing == source) it.copy(playing = null, error = "Lecture impossible, réessayez") else it }
    }

    // Where the player ends up is saved as the activity is destroyed, and by then a finishing
    // activity has already cancelled viewModelScope, so these short writes run in their own scope.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Persists a VOD/replay resume position; a null [key] (live) is ignored. */
    private var saveJob: Job? = null

    fun savePlaybackPosition(key: String?, positionMs: Long) {
        if (key == null) return
        saveJob = saveScope.launch { runCatching { repo.savePlaybackPosition(key, positionMs) } }
    }

    /** Reports progress to the server playhead (continue-watching); best-effort and a no-op for live. */
    fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long) {
        saveScope.launch { runCatching { repo.reportProgress(source, positionMs, durationMs) } }
    }

    /** Re-resolves a stream (fresh tokens/URL): for a Cast transfer whose token has expired, or to bring
     * back a playback that failed. Null when there is none to be had. */
    suspend fun reResolve(source: PlaybackSource): PlaybackSource? = try {
        val channelId = source.originChannelId
        val vodId = source.originVodId
        val recordingAssetId = source.originRecordingAssetId
        when {
            channelId != null -> repo.resolveLiveChannel(channelId)
            vodId != null -> repo.resolveVod(vodId)
            // A fresh stream of the same recording, from the same page.
            recordingAssetId != null -> repo.resolveRecording(recordingAssetId).copy(originPageVodId = source.originPageVodId)
            else -> null
        // The same programme: what follows it comes from its page, which a stream alone does not say,
        // nor that it was asked for from its beginning.
        }?.copy(upNext = source.upNext, fromStart = source.fromStart)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        // Surface a genuinely dead session so the loggedIn shell is not left zombied; applyFailure is
        // gated on currentSession()==null, so it ejects only when the session is really gone. Other
        // failures (forbidden/geo/network) return null and the cast caller surfaces the playback error.
        if (e is AppError.Unauthorized) applyFailure(e, "")
        null
    }

    /** The on-screen programme's air-window for a live channel, for the player's programme-scoped seek
     * bar; best-effort, so a failed lookup just leaves the bar spanning the full window. */
    suspend fun liveProgramWindow(channelId: String, atMs: Long): ProgramWindow? =
        runCatching { repo.liveProgramWindow(channelId, atMs) }.getOrNull()

    // Without the error of the page left: the settings screen shows what fails there.
    fun openSettings() = _state.update { it.copy(settings = true, updateStatus = null, error = null) }

    fun closeSettings() {
        // The profiles asked for from here and not there yet: no longer wanted.
        val loading = overlayJob?.isActive == true
        overlayJob?.cancel()
        _state.update { it.copy(settings = false, busy = it.busy && !loading, error = null, info = null) }
    }

    /** Hides or shows locked (unentitled) cards across the content lists; see [UiState.visibleRails]. */
    fun setHideLocked(hide: Boolean) = _state.update { it.copy(hideLocked = hide) }

    /**
     * Checks GitHub for a newer release now, then once a day for as long as the caller runs; the app
     * proposes it when found, or takes it for an automatic install. The activity runs this while it is
     * started, so each start checks again, unless the last check is less than an hour old (a TV coming
     * back from its screensaver, a phone opened many times a day). No check is made while an update is
     * already known.
     */
    suspend fun watchForUpdates() {
        while (true) {
            val known = _state.value.update
            if (known == null) {
                // Only GitHub is spared: a clock set back makes the age meaningless, and it is asked again.
                val since = clock() - updatesCheckedAt
                if (updatesCheckedAt != 0L && since in 0 until UPDATE_RECHECK_MS) {
                    delay(UPDATE_INTERVAL_MS - since)
                    continue
                }
                updatesCheckedAt = clock()
                updates.latestUpdate()?.let(::take)
            } else if (known.quiet) {
                // An automatic install that has not happened: fetch it again, or propose it if it needed someone.
                take(known)
            }
            delay(UPDATE_INTERVAL_MS)
        }
    }

    // When GitHub was last asked for a newer release (clock), 0 before.
    private var updatesCheckedAt = 0L

    private val _autoUpdate = MutableStateFlow(auto?.enabled == true)

    /** The automatic updates setting (see [AutoUpdate]). */
    val autoUpdate: StateFlow<Boolean> = _autoUpdate.asStateFlow()

    fun setAutoUpdate(on: Boolean) {
        auto?.enabled = on
        _autoUpdate.value = on
        // A version already known follows the setting too.
        _state.value.update?.let(::take)
    }

    // The version downloaded for an automatic install, waiting in the store's file.
    private var fetched: String? = null
    private var fetchJob: Job? = null

    // Takes [info] as the update: installed by itself once the app is left when automatic updates are on
    // and Android allows them for this version, proposed to the user otherwise.
    internal fun take(info: UpdateInfo) {
        val store = auto
        val quiet = store != null && _autoUpdate.value && store.possible && store.byHand != info.version
        _state.update { it.copy(update = info.copy(quiet = quiet)) }
        if (store == null || !quiet || fetched == info.version || fetchJob?.isActive == true) return
        fetchJob = viewModelScope.launch {
            if (downloadUpdate(info, store.file) {}) fetched = info.version
        }
    }

    /**
     * The app is left with nothing playing: hands the update downloaded for an automatic install to
     * [install] with its version. Android then replaces this process, or [InstallResultReceiver] notes
     * that this version needs someone.
     */
    fun installFetchedUpdate(install: suspend (File, String) -> Unit) {
        val store = auto ?: return
        // Not over something under way: what was just asked for, or a sign-in whose code is being
        // fetched from another app or typed on another device, which closing the app would lose.
        val state = _state.value
        if (state.busy || state.mfa != null || state.pairingCode != null) return
        val info = _state.value.update?.takeIf { it.quiet && it.version == fetched } ?: return
        fetched = null
        viewModelScope.launch {
            // The activity may be going away with its view model: see the hand-over through.
            withContext(NonCancellable) {
                try {
                    install(store.file, info.version)
                } catch (e: Exception) {
                    store.byHand = info.version
                } finally {
                    // The installer keeps its own copy.
                    store.file.delete()
                }
            }
        }
    }

    private var updateJob: Job? = null

    /**
     * Downloads the proposed update to [target] and hands it to [install] (the system installer, which
     * asks the user to confirm). Progress and failures show in the update prompt.
     */
    fun installUpdate(target: File, install: suspend (File) -> Unit) {
        val info = _state.value.update ?: return
        if (updateJob?.isActive == true) return
        _state.update { it.copy(updateProgress = 0, updateError = null) }
        val dismissed = updateJob
        updateJob = viewModelScope.launch {
            // A dismissed download may still be winding down on the same file.
            dismissed?.cancelAndJoin()
            val error = try {
                if (!downloadUpdate(info, target) { p -> _state.update { it.copy(updateProgress = p) } }) {
                    "Téléchargement impossible, réessayez."
                } else {
                    install(target)
                    null
                }
            } catch (e: CancellationException) {
                // Given up, as when the screen it waited for is gone: the prompt is not left counting.
                _state.update { it.copy(updateProgress = null) }
                throw e
            } catch (e: Exception) {
                "Installation impossible, réessayez."
            } finally {
                // The installer keeps its own copy.
                target.delete()
            }
            _state.update {
                if (error == null) it.copy(update = null, updateProgress = null) else it.copy(updateProgress = null, updateError = error)
            }
        }
    }

    fun setUpdateError(message: String) = _state.update { it.copy(updateError = message) }

    fun dismissUpdate() {
        updateJob?.cancel()
        _state.update { it.copy(update = null, updateProgress = null, updateError = null) }
    }

    /** Explicit "check for updates" from settings; reports newer / up-to-date / failed to the user. */
    fun checkForUpdateNow() {
        if (_state.value.checkingUpdate) return
        _state.update { it.copy(checkingUpdate = true, updateStatus = null) }
        viewModelScope.launch {
            when (val result = updates.check()) {
                is UpdateCheck.Available -> _state.update { it.copy(checkingUpdate = false, update = result.info) }
                UpdateCheck.UpToDate -> _state.update { it.copy(checkingUpdate = false, updateStatus = "Vous avez la dernière version") }
                UpdateCheck.Failed -> _state.update { it.copy(checkingUpdate = false, updateStatus = "Vérification impossible, réessayez") }
            }
        }
    }

    fun logout() {
        // Drop every in-flight job so a stale completion can't stamp a banner onto the reset login
        // screen (nav, TV pairing, TV-code confirm).
        navJob?.cancel()
        confirmJob?.cancel()
        pairingJob?.cancel()
        upNextJob?.cancel()
        lastQuery = null
        keptGuide = null
        viewModelScope.launch {
            // Wait out a pending resume-position save so it cannot land after the progress wipe below
            // and leak the previous account's continue-watching position to the next one.
            saveJob?.cancelAndJoin()
            runCatching { repo.logout() }
            // Local prefs survive, as in applyFailure.
            _state.update { UiState(checking = false, hideLocked = it.hideLocked, update = it.update) }
        }
    }
}

class MainViewModelFactory(
    private val repo: MolotovRepository,
    private val updates: UpdateChecker,
    private val auto: AutoUpdateStore? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo, updates, auto) as T
}
