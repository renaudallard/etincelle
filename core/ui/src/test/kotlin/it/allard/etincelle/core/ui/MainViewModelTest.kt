// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.UpNext
import it.allard.etincelle.core.model.UserSession
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingAPageDuringARefreshTakesTheProgressBarDown() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        val reload = CompletableDeferred<ContentPage>()
        repo.home = { reload.await() }
        vm.refreshCurrent()
        assertTrue(vm.state.value.refreshing)

        vm.onCardClick(CARD)
        assertFalse(vm.state.value.refreshing)
        vm.closeDetail()

        // Pull to refresh works again.
        vm.refreshCurrent()
        assertTrue(vm.state.value.refreshing)
    }

    @Test
    fun backDuringARefreshTakesTheProgressBarDown() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.onCardClick(CARD.copy(vodId = null, actionUrl = "papi/v1/page/x"))
        repo.page = { CompletableDeferred<ContentPage>().await() }
        vm.refreshCurrent()
        assertTrue(vm.state.value.refreshing)

        vm.back()
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun stoppingWhileTheNextEpisodeLoadsLeavesNoSpinner() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.watchRecording("REC_1")
        repo.detail = { _, _ -> CompletableDeferred<ProgramDetail>().await() }
        vm.playUpNext()
        assertTrue(vm.state.value.busy)

        vm.stopPlaying()
        assertFalse(vm.state.value.busy)
        assertEquals(null, vm.state.value.playing)
    }

    @Test
    fun aPageStillLoadingDoesNotLandInSearch() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        val films = CompletableDeferred<ContentPage>()
        repo.page = { films.await() }
        vm.selectTab(Tab.MOVIES)
        vm.selectTab(Tab.SEARCH)
        films.complete(ContentPage("Films", emptyList()))

        assertEquals(Tab.SEARCH, vm.state.value.tab)
        assertTrue(vm.state.value.backStack.isEmpty())
    }

    @Test
    fun loggingOutKeepsHideLocked() {
        val vm = viewModel(FakeRepo())
        vm.setHideLocked(true)
        vm.logout()

        assertFalse(vm.state.value.loggedIn)
        assertTrue(vm.state.value.hideLocked)
    }

    @Test
    fun aLinkStillOpensWhenTheHomePageFailsAtStart() {
        val repo = FakeRepo()
        val restore = CompletableDeferred<Boolean>()
        repo.restore = { restore.await() }
        repo.home = { throw AppError.Network("offline") }
        val vm = MainViewModel(repo, UpdateChecker("0", "mobile", "http://127.0.0.1:9/"))
        vm.onDeepLink("VOD_1", DetailKind.PROGRAM)
        restore.complete(true)

        assertTrue(vm.state.value.loggedIn)
        assertEquals("VOD_1", vm.state.value.detailStack.single().id)
    }

    @Test
    fun theResumePointIsSavedAfterTheScreenIsGone() {
        val repo = FakeRepo()
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(store, MainViewModelFactory(repo, UpdateChecker("0", "mobile", "http://127.0.0.1:9/")))[MainViewModel::class]
        store.clear()

        vm.savePlaybackPosition("VOD_1", 42_000)
        vm.reportProgress(SOURCE, 42_000, 90_000)
        assertEquals(listOf("save VOD_1 42000", "report 42000"), repo.saved)
    }

    @Test
    fun aRecordedProgrammePlaysItsRecording() {
        val repo = FakeRepo()
        repo.detail = { _, _ -> DETAIL.copy(vodId = "LIVE_1", recordingAssetId = "LIVE_1") }
        val vm = viewModel(repo)
        vm.onCardClick(CARD.copy(vodId = "LIVE_1"))
        vm.watchDetail()

        assertEquals(listOf("LIVE_1"), repo.resolvedRecordings)
        assertEquals(SOURCE, vm.state.value.playing)
    }

    private fun viewModel(repo: FakeRepo) = MainViewModel(repo, UpdateChecker("0", "mobile", "http://127.0.0.1:9/")).also {
        assertTrue(it.state.value.loggedIn)
    }

    private class FakeRepo : MolotovRepository by UNEXPECTED {
        var restore: suspend () -> Boolean = { true }
        var home: suspend () -> ContentPage = { HOME }
        var page: suspend (String) -> ContentPage = { url -> ContentPage("page", emptyList(), reloadUrl = url) }
        var detail: suspend (String, DetailKind) -> ProgramDetail = { _, _ -> DETAIL }
        var session: UserSession? = UserSession("access", "refresh", "user", "profile")

        override suspend fun restoreSession() = restore()
        override fun currentSession() = session
        override suspend fun loadHome() = home()
        override suspend fun loadPage(url: String) = page(url)
        override suspend fun fetchProgramDetail(id: String, kind: DetailKind) = detail(id, kind)
        override suspend fun profilesToPick(): Profiles? = null
        val resolvedRecordings = mutableListOf<String>()
        override suspend fun resolveRecording(assetId: String): PlaybackSource {
            resolvedRecordings += assetId
            return SOURCE
        }
        val saved = mutableListOf<String>()
        override suspend fun savePlaybackPosition(key: String, positionMs: Long) {
            saved += "save $key $positionMs"
        }
        override suspend fun reportProgress(source: PlaybackSource, positionMs: Long, durationMs: Long) {
            saved += "report $positionMs"
        }
        override suspend fun logout() {
            session = null
        }
    }

    private companion object {
        // Any repository call a test did not plan for fails it.
        val UNEXPECTED = Proxy.newProxyInstance(
            MolotovRepository::class.java.classLoader,
            arrayOf(MolotovRepository::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as MolotovRepository

        val HOME = ContentPage("Accueil", emptyList())
        val CARD = ContentCard("c1", "Show", null, false, null, "VOD_1", null, null)
        val DETAIL = ProgramDetail(
            title = "Show", subtitle = null, synopsis = null, posterUrl = null, genre = null, year = null,
            classification = null, credits = null, tags = emptyList(), channelId = null, vodId = "VOD_1",
            isLive = false,
        )
        val SOURCE = PlaybackSource(
            manifestUrl = "https://example.invalid/a.mpd",
            drm = DrmSpec.None,
            isLive = false,
            upNext = UpNext(60, 10, "Next", null, "Commence dans %s", "Regarder", "Générique", "VOD_2"),
        )
    }
}
