// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.PlaybackSource
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
    val playing: PlaybackSource? = null,
    val error: String? = null,
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
        val channelId = card.channelId
        val vodId = card.vodId
        when {
            channelId != null -> playOrLocked(card) { repo.resolveLiveChannel(channelId) }
            vodId != null -> playOrLocked(card) { repo.resolveVod(vodId) }
            else -> {
                val url = card.actionUrl ?: return
                _state.update { it.copy(busy = true, error = null) }
                loadPageInto(url, replace = false, fallbackTitle = card.title)
            }
        }
    }

    private fun playOrLocked(card: ContentCard, resolve: suspend () -> PlaybackSource) {
        if (card.isLocked) {
            _state.update { it.copy(error = "${card.title ?: "Ce contenu"} est réservé à Molotov Extra") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { resolve() }
                .onSuccess { src -> _state.update { it.copy(busy = false, playing = src) } }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.message ?: "Échec de lecture") } }
        }
    }

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
