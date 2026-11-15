// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.UpNext
import it.allard.etincelle.core.model.UserSession
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
        override suspend fun resolveRecording(assetId: String) = SOURCE
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
