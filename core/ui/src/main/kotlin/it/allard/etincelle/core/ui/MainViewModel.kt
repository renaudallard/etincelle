// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: String, val path: String) {
    HOME("Accueil", "🏠", "papi/v1/page/home"),
    LIVE("Direct", "📡", "papi/v1/page/live-tv"),
    GUIDE("Guide", "🗓", ""),
    MOVIES("Films", "🎬", "papi/v1/page/films"),
    SERIES("Séries", "📺", "papi/v1/page/series"),
    SEARCH("Recherche", "🔍", ""),
}

data class UiState(
    val checking: Boolean = true,
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val tab: Tab = Tab.HOME,
    val backStack: List<ContentPage> = emptyList(),
    val detail: ProgramDetail? = null,
    // When the detail was opened from a DVR recording, the recording to play on "Regarder".
    val detailRecordingAssetId: String? = null,
    val playing: PlaybackSource? = null,
    val settings: Boolean = false,
    val update: UpdateInfo? = null,
    val error: String? = null,
    val info: String? = null,
    val hideLocked: Boolean = false,
) {
    val current: ContentPage? get() = backStack.lastOrNull()
    val canGoBack: Boolean get() = backStack.size > 1

    /** The current page's rails with locked (unentitled) cards removed when the user hid them. */
    val visibleRails: List<ContentRail>
        get() {
            val rails = current?.rails ?: return emptyList()
            if (!hideLocked) return rails
            return rails.mapNotNull { rail ->
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
                runCatching { repo.loadHome() }
                    .onSuccess { page ->
                        _state.update { it.copy(checking = false, loggedIn = true, tab = Tab.HOME, backStack = listOf(page)) }
                        consumeDeepLink()
                    }
                    .onFailure { _state.update { it.copy(checking = false, loggedIn = false) } }
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
                _state.update { it.copy(busy = false, error = e.message ?: "Échec de connexion") }
            }
        }
    }

    private var pendingDeepLink: Pair<String, DetailKind>? = null

    /** Opens a show from an external deep link (etincelle://series|program|channel/{id}); defers until login. */
    fun onDeepLink(id: String, kind: DetailKind) {
        pendingDeepLink = id to kind
        if (_state.value.loggedIn) consumeDeepLink()
    }

    private fun consumeDeepLink() {
        val (id, kind) = pendingDeepLink ?: return
        pendingDeepLink = null
        _state.update { it.copy(busy = true, error = null, detail = null, detailRecordingAssetId = null) }
        viewModelScope.launch {
            runCatching { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detail = d, detailRecordingAssetId = null) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Contenu introuvable") } }
        }
    }

    fun selectTab(tab: Tab) {
        if (tab == Tab.SEARCH) {
            _state.update { it.copy(tab = tab, busy = false, error = null, backStack = emptyList()) }
            return
        }
        if (tab == Tab.GUIDE) {
            if (tab == _state.value.tab && _state.value.backStack.isNotEmpty()) return
            _state.update { it.copy(busy = true, error = null, tab = tab, backStack = emptyList()) }
            viewModelScope.launch {
                runCatching { repo.loadGuide() }
                    .onSuccess { page -> _state.update { it.copy(busy = false, backStack = listOf(page)) } }
                    .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Guide indisponible") } }
            }
            return
        }
        if (tab == Tab.HOME) {
            // Accueil must go through loadHome (which prepends the recordings rail), not the raw page.
            if (tab == _state.value.tab && _state.value.backStack.size == 1) return
            _state.update { it.copy(busy = true, error = null, tab = tab, backStack = emptyList()) }
            viewModelScope.launch {
                runCatching { repo.loadHome() }
                    .onSuccess { page -> _state.update { it.copy(busy = false, backStack = listOf(page)) } }
                    .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Accueil indisponible") } }
            }
            return
        }
        if (tab == _state.value.tab && _state.value.backStack.size == 1) return
        _state.update { it.copy(busy = true, error = null, tab = tab) }
        // The Direct tab loads the same /live-tv page as the "En direct à la TV" rail header, so
        // render it as the same full-screen grid for a coherent experience.
        loadPageInto(tab.path, replace = true, fallbackTitle = tab.label, grid = tab == Tab.LIVE)
    }

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.search(query.trim()) }
                .onSuccess { page -> _state.update { it.copy(busy = false, backStack = listOf(page.copy(title = "Résultats"))) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de recherche") } }
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
        viewModelScope.launch {
            runCatching { repo.resolveRecording(assetId) }
                .onSuccess { src -> _state.update { it.copy(busy = false, playing = src) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de lecture") } }
        }
    }

    /** Opens a card's detail page (info, cast, year) instead of playing it immediately. */
    private fun showDetail(card: ContentCard, id: String, kind: DetailKind) {
        if (card.isLocked) {
            _state.update { it.copy(error = "${card.title ?: "Ce contenu"} est réservé à Molotov Extra") }
            return
        }
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detail = d, detailRecordingAssetId = null) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Détail indisponible") } }
        }
    }

    /**
     * Opens the detail page of a recording's show, so it can be browsed (info, cast) before playing.
     * If that show has no detail page we can fetch, falls back to playing the recording directly.
     */
    private fun showRecordingDetail(card: ContentCard, id: String, kind: DetailKind, assetId: String) {
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.fetchProgramDetail(id, kind) }
                .onSuccess { d -> _state.update { it.copy(busy = false, detail = d, detailRecordingAssetId = assetId) } }
                .onFailure { watchRecording(assetId) }
        }
    }

    /** Plays the show currently shown on the detail page; a live detail plays its channel directly. */
    fun watchDetail() {
        val d = _state.value.detail ?: return
        // A detail opened from a recording plays that recording, not the (often unavailable) VOD.
        _state.value.detailRecordingAssetId?.let { watchRecording(it); return }
        val vodId = d.vodId
        val channelId = d.channelId
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                when {
                    d.isLive && channelId != null -> repo.resolveLiveChannel(channelId)
                    vodId != null -> repo.resolveVod(vodId)
                    channelId != null -> repo.resolveLiveChannel(channelId)
                    else -> null
                }
            }.onSuccess { src ->
                _state.update { if (src != null) it.copy(busy = false, playing = src) else it.copy(busy = false) }
            }.onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de lecture") } }
        }
    }

    /** Records the live airing shown on the detail page. */
    fun recordDetail() {
        val assetId = _state.value.detail?.recordAssetId ?: return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            runCatching { repo.recordEpisode(assetId) }
                .onSuccess { _state.update { it.copy(busy = false, info = "Enregistrement programmé") } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de l'enregistrement") } }
        }
    }

    /** Closes the detail page, back to browsing. */
    fun closeDetail() = _state.update { it.copy(detail = null, detailRecordingAssetId = null, error = null, info = null) }

    private fun loadPageInto(url: String, replace: Boolean, fallbackTitle: String?, grid: Boolean = false) {
        viewModelScope.launch {
            runCatching { repo.loadPage(url) }
                .onSuccess { page ->
                    val titled = page.copy(title = page.title ?: fallbackTitle, isGrid = grid)
                    _state.update {
                        val stack = if (replace) listOf(titled) else it.backStack + titled
                        it.copy(busy = false, backStack = stack)
                    }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de chargement") } }
        }
    }

    fun back() = _state.update { if (it.canGoBack) it.copy(backStack = it.backStack.dropLast(1)) else it }

    fun stopPlaying() = _state.update { it.copy(playing = null) }

    /** Persists a VOD/replay resume position; a null [key] (live) is ignored. */
    fun savePlaybackPosition(key: String?, positionMs: Long) {
        if (key == null) return
        viewModelScope.launch { runCatching { repo.savePlaybackPosition(key, positionMs) } }
    }

    /** Re-resolves a stream (fresh tokens/URL), used by Cast transfers when a token has expired. */
    suspend fun reResolve(source: PlaybackSource): PlaybackSource? = runCatching {
        val channelId = source.originChannelId
        val vodId = source.originVodId
        val recordingAssetId = source.originRecordingAssetId
        when {
            channelId != null -> repo.resolveLiveChannel(channelId)
            vodId != null -> repo.resolveVod(vodId)
            recordingAssetId != null -> repo.resolveRecording(recordingAssetId)
            else -> null
        }
    }.getOrNull()

    fun openSettings() = _state.update { it.copy(settings = true) }

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

    fun logout() {
        viewModelScope.launch {
            runCatching { repo.logout() }
            _state.value = UiState(checking = false)
        }
    }
}

class MainViewModelFactory(private val repo: MolotovRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
}
