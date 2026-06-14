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
    val playing: PlaybackSource? = null,
    val error: String? = null,
    val info: String? = null,
) {
    val current: ContentPage? get() = backStack.lastOrNull()
    val canGoBack: Boolean get() = backStack.size > 1
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
            }.onFailure { e ->
                _state.update { it.copy(busy = false, error = e.message ?: "Échec de connexion") }
            }
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
        if (tab == _state.value.tab && _state.value.backStack.size == 1) return
        _state.update { it.copy(busy = true, error = null, tab = tab) }
        loadPageInto(tab.path, replace = true, fallbackTitle = tab.label)
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
            recordingAssetId != null -> watchRecording(recordingAssetId)
            channelId != null -> showDetail(card, channelId, DetailKind.CHANNEL)
            vodId != null -> showDetail(card, vodId, DetailKind.PROGRAM)
            seriesId != null -> showDetail(card, seriesId, DetailKind.SERIES)
            else -> {
                val url = card.actionUrl ?: return
                _state.update { it.copy(busy = true, error = null) }
                loadPageInto(url, replace = false, fallbackTitle = card.title)
            }
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
                .onSuccess { d -> _state.update { it.copy(busy = false, detail = d) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Détail indisponible") } }
        }
    }

    /** Plays the show currently shown on the detail page; a live detail plays its channel directly. */
    fun watchDetail() {
        val d = _state.value.detail ?: return
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
    fun closeDetail() = _state.update { it.copy(detail = null, error = null, info = null) }

    private fun loadPageInto(url: String, replace: Boolean, fallbackTitle: String?) {
        viewModelScope.launch {
            runCatching { repo.loadPage(url) }
                .onSuccess { page ->
                    val titled = page.copy(title = page.title ?: fallbackTitle)
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
        when {
            channelId != null -> repo.resolveLiveChannel(channelId)
            vodId != null -> repo.resolveVod(vodId)
            else -> null
        }
    }.getOrNull()

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
