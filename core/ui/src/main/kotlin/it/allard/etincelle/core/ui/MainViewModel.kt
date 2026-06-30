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
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.ProgramWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// TV pairing poll cadence: poll every few seconds, and regenerate the code a little before its
// server expiry so a confirm racing the deadline is not lost.
private const val PAIRING_POLL_SEC = 3L
private const val PAIRING_EXPIRY_MARGIN_SEC = 15L
private const val PAIRING_RETRY_MS = 5000L
// Cap on how many codes the login screen will cycle through before giving up, so a TV left idle on
// the login screen cannot poll indefinitely.
private const val PAIRING_MAX_ROUNDS = 12

enum class Tab(val label: String, val icon: String, val path: String) {
    HOME("Accueil", "🏠", "papi/v1/page/home"),
    LIVE("Direct", "📡", "papi/v1/page/live-tv"),
    CHANNELS("Chaînes", "📺", "papi/v1/page/channels"),
    GUIDE("Guide", "🗓", ""),
    MOVIES("Films", "🎬", "papi/v1/page/films"),
    SERIES("Séries", "📺", "papi/v1/page/series"),
    SEARCH("Recherche", "🔍", ""),
}

/** One open detail page plus the DVR recording (if any) that its "Regarder" should play. */
data class DetailEntry(val detail: ProgramDetail, val recordingAssetId: String?)

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
    val update: UpdateInfo? = null,
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

/** Shared presentation logic for both the phone and TV apps. */
class MainViewModel(private val repo: MolotovRepository) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val restored = runCatching { repo.restoreSession() }.getOrDefault(false)
            if (restored) {
                runCatchingNav { repo.loadHome() }
                    .onSuccess { page ->
                        _state.update { it.copy(checking = false, loggedIn = true, tab = Tab.HOME, backStack = listOf(page)) }
                        consumeDeepLink()
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
                                it.copy(checking = false, loggedIn = true, tab = Tab.HOME, error = "Échec du chargement de l'accueil")
                            }
                        }
                    }
            } else {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    fun login(email: String, password: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                repo.login(email.trim(), password)
                repo.loadHome()
            }.onSuccess { page ->
                _state.update { it.copy(busy = false, loggedIn = true, tab = Tab.HOME, backStack = listOf(page)) }
                consumeDeepLink()
            }.onFailure { e ->
                applyFailure(e, "Échec de connexion")
            }
        }
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
        _state.update { it.copy(busy = true, error = null, pairingCode = null) }
        pairingJob = viewModelScope.launch {
            var rounds = 0
            while (rounds < PAIRING_MAX_ROUNDS) {
                rounds++
                val pairing = orNull { repo.startCodeLogin() }
                if (pairing == null) {
                    // Still busy: the loop retries on its own after the delay, so the screen shows the
                    // notice without offering a manual retry button that would just restart this loop.
                    _state.update { it.copy(busy = true, pairingCode = null, error = "Connexion impossible, nouvel essai…") }
                    delay(PAIRING_RETRY_MS)
                    continue
                }
                _state.update { it.copy(busy = false, error = null, pairingCode = pairing.code) }
                // Poll until confirmed or the code is about to expire (then loop to regenerate). Count
                // polls locally with a margin rather than trusting the wall clock against server time.
                val polls = ((pairing.ttlSeconds - PAIRING_EXPIRY_MARGIN_SEC) / PAIRING_POLL_SEC).coerceAtLeast(1)
                repeat(polls.toInt()) {
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
                                it.copy(loggedIn = true, pairingCode = null, busy = false, tab = Tab.HOME, error = "Échec du chargement de l'accueil")
                            }
                        }
                        consumeDeepLink()
                        return@launch
                    }
                }
            }
            // Polled across many codes without a confirmation: stop and let the user retry, so the loop
            // cannot keep polling forever (e.g. a TV left idle on the login screen).
            _state.update { it.copy(busy = false, pairingCode = null, error = "Code expiré, réessayez.") }
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
                    _state.update { it.copy(busy = false, tvConnectError = "Code invalide ou expiré") }
                }
        }
    }

    /** Clears the "Connecter une TV" result (when the dialog opens or closes). */
    fun clearTvConnect() = _state.update { it.copy(tvConnectInfo = null, tvConnectError = null) }

    private var pendingDeepLink: Pair<String, DetailKind>? = null

    // One in-flight navigation (page/detail load) at a time: launching a new one cancels the previous
    // so a slower earlier load cannot overwrite the state of a newer one (last-writer-wins races).
    private var navJob: Job? = null

    private fun navLaunch(block: suspend () -> Unit) {
        navJob?.cancel()
        navJob = viewModelScope.launch { block() }
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
            _state.update { UiState(checking = false, error = e.message, hideLocked = it.hideLocked, update = it.update) }
        } else {
            _state.update { it.copy(busy = false, refreshing = false, error = e.message ?: fallback) }
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
            it.copy(busy = true, error = null, detailStack = emptyList(), playing = null, settings = false)
        }
        navLaunch {
            runCatchingNav { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detailStack = listOf(DetailEntry(d, null))) } }
                .onFailure { e -> applyFailure(e, "Contenu introuvable") }
        }
    }

    fun selectTab(tab: Tab) {
        if (tab == Tab.SEARCH) {
            _state.update { it.copy(tab = tab, busy = false, error = null, backStack = emptyList()) }
            return
        }
        if (tab == Tab.GUIDE) {
            // The guide is time-sensitive, so re-selecting it refreshes (silently when already loaded)
            // rather than keeping a stale snapshot.
            reloadGuide(silent = tab == _state.value.tab && _state.value.backStack.isNotEmpty())
            return
        }
        if (tab == Tab.HOME) {
            // Accueil must go through loadHome (which prepends the recordings rail), not the raw page.
            if (tab == _state.value.tab && _state.value.backStack.size == 1) return
            reloadHome(silent = false)
            return
        }
        if (tab == _state.value.tab && _state.value.backStack.size == 1) return
        _state.update { it.copy(busy = true, error = null, tab = tab) }
        // The Direct and Chaînes tabs load a page (/live-tv, /channels) that is also reachable from a
        // rail header, so render them as the same full-screen grid for a coherent experience.
        loadPageInto(tab.path, replace = true, fallbackTitle = tab.label, grid = tab == Tab.LIVE || tab == Tab.CHANNELS)
    }

    private fun reloadGuide(silent: Boolean) {
        _state.update {
            if (silent) it.copy(tab = Tab.GUIDE, error = null, refreshing = true)
            else it.copy(tab = Tab.GUIDE, error = null, busy = true, backStack = emptyList())
        }
        navLaunch {
            runCatchingNav { repo.loadGuide() }
                .onSuccess { page -> _state.update { it.copy(busy = false, refreshing = false, backStack = listOf(page)) } }
                .onFailure { e -> applyFailure(e, "Guide indisponible") }
        }
    }

    private fun reloadHome(silent: Boolean) {
        _state.update {
            if (silent) it.copy(tab = Tab.HOME, error = null, refreshing = true)
            else it.copy(tab = Tab.HOME, error = null, busy = true, backStack = emptyList())
        }
        navLaunch {
            runCatchingNav { repo.loadHome() }
                .onSuccess { page -> _state.update { it.copy(busy = false, refreshing = false, backStack = listOf(page)) } }
                .onFailure { e -> applyFailure(e, "Accueil indisponible") }
        }
    }

    /** Refreshes time-sensitive content when the app returns to the foreground (the guide goes stale). */
    fun refreshOnResume() {
        val s = _state.value
        if (!s.loggedIn || s.playing != null || s.detail != null || s.settings || s.busy || s.refreshing) return
        if (s.tab == Tab.GUIDE && s.backStack.isNotEmpty()) reloadGuide(silent = true)
    }

    /** Reloads the page currently shown (pull to refresh, or overscroll past an end). */
    fun refreshCurrent() {
        val s = _state.value
        if (s.playing != null || s.detail != null || s.settings || s.busy || s.refreshing) return
        val top = s.backStack.lastOrNull()
        val reloadUrl = top?.reloadUrl
        when {
            // A drilled-in papi page reloads itself, whatever tab it lives under.
            reloadUrl != null -> reloadPageTop(reloadUrl, top?.isGrid ?: false)
            // A drilled-in page with no backing url (a synthetic see-all) has nothing to reload.
            s.backStack.size > 1 -> {}
            s.tab == Tab.GUIDE -> reloadGuide(silent = true)
            s.tab == Tab.SEARCH -> lastQuery?.let { search(it, silent = true) }
            s.tab == Tab.HOME -> reloadHome(silent = true)
            else -> {}
        }
    }

    /** Re-fetches the top page's papi url, replacing only that entry so the back stack is kept. */
    private fun reloadPageTop(url: String, grid: Boolean) {
        _state.update { it.copy(refreshing = true, error = null) }
        navLaunch {
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
                .onFailure { e -> applyFailure(e, "Échec de chargement") }
        }
    }

    private var lastQuery: String? = null

    fun search(query: String, silent: Boolean = false) {
        if (query.isBlank()) return
        lastQuery = query.trim()
        _state.update { if (silent) it.copy(refreshing = true, error = null) else it.copy(busy = true, error = null) }
        navLaunch {
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
            val page = ContentPage(rail.title, listOf(rail), isGrid = true)
            _state.update { it.copy(busy = false, error = null, backStack = it.backStack + page) }
        }
    }

    /** Plays a DVR recording by its dvr asset id (from a recording card or a detail's recordings list). */
    fun watchRecording(assetId: String) {
        _state.update { it.copy(busy = true, error = null, info = null) }
        navLaunch {
            runCatchingNav { repo.resolveRecording(assetId) }
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
                    _state.update { it.copy(busy = false, detailStack = it.detailStack + DetailEntry(detail, null)) }
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
                .onSuccess { d -> _state.update { it.copy(busy = false, detailStack = it.detailStack + DetailEntry(d, assetId)) } }
                .onFailure { watchRecording(assetId) }
        }
    }

    /** Plays the show currently shown on the detail page; a live detail plays its channel directly. */
    fun watchDetail() {
        val d = _state.value.detail ?: return
        // A detail opened from a recording plays that recording, not the (often unavailable) VOD.
        _state.value.detailRecordingAssetId?.let { watchRecording(it); return }
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
                    vodId != null -> repo.resolveVod(vodId)
                    channelId != null -> repo.resolveLiveChannel(channelId)
                    else -> null
                }
            }.onSuccess { src ->
                _state.update { if (src != null) it.copy(busy = false, playing = src) else it.copy(busy = false) }
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

    /** Records the live airing shown on the detail page. */
    fun recordDetail() {
        val assetId = _state.value.detail?.recordAssetId ?: return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            val result = runCatching { repo.recordEpisode(assetId) }
            // Only reflect the outcome if still on the detail that started it; a slow record completing
            // after the user navigated away must not stamp busy/banner onto an unrelated screen.
            if (_state.value.detail?.recordAssetId != assetId) return@launch
            result
                .onSuccess { _state.update { it.copy(busy = false, info = "Enregistrement programmé") } }
                .onFailure { e -> applyFailure(e, "Échec de l'enregistrement") }
        }
    }

    /** Closes the detail page, back to browsing. */
    /** Closes the top detail page: back to its parent detail if any, otherwise back to browsing. */
    fun closeDetail() {
        // Drop any in-flight load/play so it cannot re-open this screen after the user backed out.
        navJob?.cancel()
        _state.update { it.copy(detailStack = it.detailStack.dropLast(1), busy = false, error = null, info = null) }
    }

    private fun loadPageInto(url: String, replace: Boolean, fallbackTitle: String?, grid: Boolean = false) {
        navLaunch {
            runCatchingNav { repo.loadPage(url) }
                .onSuccess { page ->
                    val titled = page.copy(title = page.title ?: fallbackTitle, isGrid = grid, reloadUrl = url)
                    _state.update {
                        val stack = if (replace) listOf(titled) else it.backStack + titled
                        it.copy(busy = false, backStack = stack)
                    }
                }
                .onFailure { e -> applyFailure(e, "Échec de chargement") }
        }
    }

    fun back() {
        navJob?.cancel()
        _state.update { if (it.canGoBack) it.copy(backStack = it.backStack.dropLast(1), busy = false) else it }
    }

    fun stopPlaying() {
        navJob?.cancel()
        _state.update { it.copy(playing = null) }
    }

    /** Persists a VOD/replay resume position; a null [key] (live) is ignored. */
    private var saveJob: Job? = null

    fun savePlaybackPosition(key: String?, positionMs: Long) {
        if (key == null) return
        saveJob = viewModelScope.launch { runCatching { repo.savePlaybackPosition(key, positionMs) } }
    }

    /** Reports progress to the server playhead (continue-watching); best-effort and a no-op for live. */
    fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { runCatching { repo.reportProgress(source, positionMs, durationMs) } }
    }

    /** Re-resolves a stream (fresh tokens/URL), used by Cast transfers when a token has expired. */
    suspend fun reResolve(source: PlaybackSource): PlaybackSource? = try {
        val channelId = source.originChannelId
        val vodId = source.originVodId
        val recordingAssetId = source.originRecordingAssetId
        when {
            channelId != null -> repo.resolveLiveChannel(channelId)
            vodId != null -> repo.resolveVod(vodId)
            recordingAssetId != null -> repo.resolveRecording(recordingAssetId)
            else -> null
        }
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
    suspend fun liveProgramWindow(channelId: String): ProgramWindow? =
        runCatching { repo.liveProgramWindow(channelId) }.getOrNull()

    fun openSettings() = _state.update { it.copy(settings = true, updateStatus = null) }

    fun closeSettings() = _state.update { it.copy(settings = false) }

    /** Hides or shows locked (unentitled) cards across the content lists; see [UiState.visibleRails]. */
    fun setHideLocked(hide: Boolean) = _state.update { it.copy(hideLocked = hide) }

    private var updateChecked = false

    /** Checks GitHub for a newer release once per process; the app proposes the download if found. */
    fun checkForUpdate(currentVersion: String) {
        if (updateChecked) return
        updateChecked = true
        viewModelScope.launch {
            UpdateChecker(currentVersion).latestUpdate()?.let { info -> _state.update { it.copy(update = info) } }
        }
    }

    fun dismissUpdate() = _state.update { it.copy(update = null) }

    /** Explicit "check for updates" from settings; reports newer / up-to-date / failed to the user. */
    fun checkForUpdateNow(currentVersion: String) {
        if (_state.value.checkingUpdate) return
        _state.update { it.copy(checkingUpdate = true, updateStatus = null) }
        viewModelScope.launch {
            when (val result = UpdateChecker(currentVersion).check()) {
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
        viewModelScope.launch {
            // Wait out a pending resume-position save so it cannot land after the progress wipe below
            // and leak the previous account's continue-watching position to the next one.
            saveJob?.cancelAndJoin()
            runCatching { repo.logout() }
            _state.value = UiState(checking = false)
        }
    }
}

class MainViewModelFactory(private val repo: MolotovRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
}
