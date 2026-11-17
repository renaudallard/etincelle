// Copyright (c) 2026 Renaud Allard <renaud@allard.it>
// SPDX-License-Identifier: BSD-2-Clause

package it.allard.etincelle.core.ui

import it.allard.etincelle.core.domain.DetailKind
import it.allard.etincelle.core.domain.MolotovRepository
import it.allard.etincelle.core.model.AppError
import it.allard.etincelle.core.model.CardTag
import it.allard.etincelle.core.model.ContentCard
import it.allard.etincelle.core.model.ContentPage
import it.allard.etincelle.core.model.ContentRail
import it.allard.etincelle.core.model.DrmSpec
import it.allard.etincelle.core.model.LoginResult
import it.allard.etincelle.core.model.PlaybackSource
import it.allard.etincelle.core.model.ProgramDetail
import it.allard.etincelle.core.model.Profiles
import it.allard.etincelle.core.model.UpNext
import it.allard.etincelle.core.model.UserSession
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Playing from the episode's page, which the next one will replace.
        vm.onCardClick(CARD)
        vm.watchRecording("REC_1")
        repo.detail = { _, _ -> CompletableDeferred<ProgramDetail>().await() }
        vm.playUpNext()
        assertTrue(vm.state.value.busy)

        vm.stopPlaying()
        assertFalse(vm.state.value.busy)
        assertEquals(null, vm.state.value.playing)
    }

    @Test
    fun theNextEpisodeOfACastLeavesThePageBeingBrowsed() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.watchRecording("REC_1")
        // The viewer went back to Accueil while it plays on (a cast): no page is pushed over it.
        vm.playUpNext()

        assertEquals(NEXT, vm.state.value.playing)
        assertTrue(vm.state.value.detailStack.isEmpty())
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun aSignInWhoseHomePageFailsIsStillASignIn() {
        val repo = FakeRepo()
        repo.restore = { false }
        val vm = MainViewModel(repo, CHECKER)
        assertFalse(vm.state.value.loggedIn)
        repo.home = { throw AppError.Network("offline") }
        vm.login("a@b.c", "secret")

        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.busy)
        assertEquals("Échec du chargement de l'accueil", vm.state.value.error)
        // Accueil loads when chosen again.
        repo.home = { HOME }
        vm.selectTab(Tab.HOME)
        assertEquals(HOME, vm.state.value.current)
    }

    @Test
    fun thePageRefreshesUnderWhatPlaysOnATv() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        vm.watchRecording("REC_1")
        repo.home = { CompletableDeferred<ContentPage>().await() }
        // The player covers the page: nothing to refresh until it closes.
        vm.refreshCurrent()
        assertFalse(vm.state.value.refreshing)

        // Playing on a TV, the page is the one being browsed.
        vm.setPlayerAside(true)
        vm.refreshCurrent()
        assertTrue(vm.state.value.refreshing)
    }

    @Test
    fun aTabThatFailedToLoadLoadsWhenChosenAgain() {
        val repo = FakeRepo()
        val vm = viewModel(repo)
        repo.page = { throw AppError.Network("down") }
        vm.selectTab(Tab.MOVIES)
        // Not the page of the tab that was left, under the new tab's name.
        assertEquals(Tab.MOVIES, vm.state.value.tab)
        assertTrue(vm.state.value.backStack.isEmpty())
        assertEquals("down", vm.state.value.error)

        repo.page = { url -> ContentPage("Films", emptyList(), reloadUrl = url) }
        vm.selectTab(Tab.MOVIES)
        assertEquals("Films", vm.state.value.current?.title)
        assertEquals(null, vm.state.value.error)
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
        val vm = MainViewModel(repo, CHECKER)
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

    @Test
    fun aRecordingPlayedFromItsPageOffersTheNextEpisode() {
        val next = UpNext(1500, 9, "Next", null, "Commence dans %s", "Regarder", "Générique", "VOD_3")
        val repo = FakeRepo()
        repo.detail = { _, _ -> DETAIL.copy(vodId = "LIVE_1", recordingAssetId = "LIVE_1", upNext = next) }
        val vm = viewModel(repo)
        vm.onCardClick(CARD.copy(vodId = "LIVE_1"))
        vm.watchDetail()

        assertEquals(next, vm.state.value.playing?.upNext)
    }

    @Test
    fun aLiveProgrammeOffersTheEpisodeOnDemandThatGoesByItsName() {
        val episode = CARD.copy(id = "e16", title = "S26 E16 Dans le coma", vodId = "VOD_16")
        val other = CARD.copy(id = "e9", title = "S13 E9 Perdu en chemin", vodId = "VOD_9")
        val live = DETAIL.copy(
            channelId = "46", vodId = null, isLive = true, subtitle = "S26 E16 Dans le coma", episodes = listOf(other, episode),
        )

        assertEquals(episode, live.replay)
        // A programme that is no episode goes by its title.
        val film = episode.copy(title = "Show")
        assertEquals(film, live.copy(subtitle = null, episodes = listOf(other, film)).replay)
        // The programme on air is listed by that name too, and is no copy of itself.
        val airing = episode.copy(id = "a", vodId = "LIVE_4864992")
        assertNull(live.copy(episodes = listOf(airing, other)).replay)
        assertEquals(episode, live.copy(episodes = listOf(airing, episode)).replay)
        // No episode by that name, or not a live page: none.
        assertNull(live.copy(episodes = listOf(other)).replay)
        assertNull(live.copy(isLive = false).replay)
    }

    @Test
    fun theReplayOfALiveProgrammePlaysFromItsBeginningOverItsOwnPage() {
        val episode = CARD.copy(id = "e16", title = "S26 E16", vodId = "VOD_16")
        val live = DETAIL.copy(channelId = "46", vodId = null, isLive = true, subtitle = "S26 E16", episodes = listOf(episode))
        val repo = FakeRepo()
        repo.detail = { _, kind -> if (kind == DetailKind.CHANNEL) live else DETAIL.copy(vodId = "VOD_16") }
        // Left in the middle earlier.
        repo.vod = { NEXT.copy(originVodId = it, startPositionMs = 42_000) }
        val vm = viewModel(repo)
        vm.onCardClick(CARD.copy(vodId = null, channelId = "46"))
        vm.watchReplay()

        assertEquals("VOD_16", vm.state.value.playing?.originVodId)
        assertEquals(0L, vm.state.value.playing?.startPositionMs)
        assertEquals(listOf("46", "VOD_16"), vm.state.value.detailStack.map { it.id })
    }

    @Test
    fun anUpdateIsFetchedAndInstalledWithoutAWordWhenAutomaticUpdatesAreOn() = withUpdate { store, info ->
        val vm = MainViewModel(FakeRepo(), CHECKER, store)
        vm.take(info)
        assertTrue(vm.state.value.update!!.quiet)

        // Nothing is handed over before the download is complete.
        val installed = mutableListOf<Pair<String, Int>>()
        waitFor {
            vm.installFetchedUpdate { apk, version -> installed += version to apk.readBytes().size }
            installed.isNotEmpty()
        }
        assertEquals(listOf(info.version to APK.size), installed)
        assertFalse(store.file.exists())
        assertNull(store.byHand)

        // Once only.
        vm.installFetchedUpdate { _, _ -> error("installed twice") }
    }

    @Test
    fun anUpdateIsProposedWhenAutomaticUpdatesAreOffOrNotPossible() = withUpdate { store, info ->
        store.enabled = false
        val vm = MainViewModel(FakeRepo(), CHECKER, store)
        vm.take(info)
        assertFalse(vm.state.value.update!!.quiet)

        // Turning the setting on takes the known version along, and off again proposes it.
        vm.setAutoUpdate(true)
        assertTrue(store.enabled)
        assertTrue(vm.state.value.update!!.quiet)
        vm.setAutoUpdate(false)
        assertFalse(vm.state.value.update!!.quiet)

        store.possible = false
        vm.setAutoUpdate(true)
        assertFalse(vm.state.value.update!!.quiet)
    }

    @Test
    fun aVersionWhoseAutomaticInstallNeededSomeoneIsProposed() = withUpdate { store, info ->
        val vm = MainViewModel(FakeRepo(), CHECKER, store)
        vm.take(info)
        waitFor {
            vm.installFetchedUpdate { _, _ -> throw IllegalStateException("refused") }
            store.byHand != null
        }
        assertEquals(info.version, store.byHand)
        assertFalse(store.file.exists())

        vm.take(info)
        assertFalse(vm.state.value.update!!.quiet)
        // A later version is automatic again.
        vm.take(info.copy(version = "9.9.10"))
        assertTrue(vm.state.value.update!!.quiet)
    }

    @Test
    fun aPageShowingWhatIsOnAirIsLoadedAgainOnceItIsOld() {
        val repo = FakeRepo()
        var loads = 0
        // Each load answers a page of its own, as the backend does.
        repo.home = { loads++; ON_AIR.copy() }
        var now = 1_000_000L
        val vm = MainViewModel(repo, CHECKER, clock = { now })
        assertEquals(1, loads)

        // Back in the foreground soon after: still fresh.
        now += 60_000
        vm.refreshOnResume()
        assertEquals(1, loads)

        now += 60_000
        vm.refreshOnResume()
        assertEquals(2, loads)

        // The new page starts its own two minutes, also when coming back from a programme's page.
        now += 60_000
        vm.onCardClick(CARD)
        vm.closeDetail()
        assertEquals(2, loads)
        now += 60_000
        vm.onCardClick(CARD)
        vm.closeDetail()
        assertEquals(3, loads)
    }

    @Test
    fun aPageWithNothingOnAirIsLeftAlone() {
        val repo = FakeRepo()
        var loads = 0
        repo.home = { loads++; HOME }
        var now = 1_000_000L
        val vm = MainViewModel(repo, CHECKER, clock = { now })
        now += 3_600_000
        vm.refreshOnResume()
        assertEquals(1, loads)
    }

    @Test
    fun aReloadNobodyAskedForFailsWithoutAWord() {
        val repo = FakeRepo()
        var now = 1_000_000L
        repo.home = { ON_AIR.copy() }
        val vm = MainViewModel(repo, CHECKER, clock = { now })
        val shown = vm.state.value.current

        repo.home = { throw AppError.Network("offline") }
        now += 600_000
        vm.refreshOnResume()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.refreshing)
        assertTrue(vm.state.value.current === shown)

        // Asked for, the same failure is said.
        vm.refreshCurrent()
        assertEquals("offline", vm.state.value.error)
    }

    // Runs [test] with a store in a scratch directory and an update whose APK a local server hands out.
    private fun withUpdate(test: (FakeStore, UpdateInfo) -> Unit) {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(Buffer().write(APK))
        }
        server.start()
        val dir = Files.createTempDirectory("auto-update").toFile()
        val store = FakeStore(File(dir, "update-auto.apk"))
        try {
            val sha = MessageDigest.getInstance("SHA-256").digest(APK).joinToString("") { "%02x".format(it) }
            test(store, UpdateInfo("9.9.9", server.url("/etincelle-9.9.9-mobile.apk").toString(), APK.size.toLong(), sha))
        } finally {
            server.shutdown()
            // Only the two files a download may leave; no tree walk.
            File(store.file.path + ".part").delete()
            store.file.delete()
            dir.delete()
        }
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private class FakeStore(override val file: File) : AutoUpdateStore {
        override var enabled = true
        override var byHand: String? = null
        override var possible = true
    }

    private fun viewModel(repo: FakeRepo) = MainViewModel(repo, CHECKER).also {
        assertTrue(it.state.value.loggedIn)
    }

    private class FakeRepo : MolotovRepository by UNEXPECTED {
        var restore: suspend () -> Boolean = { true }
        var home: suspend () -> ContentPage = { HOME }
        var page: suspend (String) -> ContentPage = { url -> ContentPage("page", emptyList(), reloadUrl = url) }
        var detail: suspend (String, DetailKind) -> ProgramDetail = { _, _ -> DETAIL }
        var session: UserSession? = UserSession("access", "refresh", "user", "profile")

        override suspend fun restoreSession() = restore()
        override suspend fun login(email: String, password: String): LoginResult = LoginResult.SignedIn(session!!)
        override fun currentSession() = session
        override suspend fun loadHome() = home()
        override suspend fun loadPage(url: String) = page(url)
        override suspend fun fetchProgramDetail(id: String, kind: DetailKind) = detail(id, kind)
        override suspend fun profilesToPick(): Profiles? = null
        val resolvedRecordings = mutableListOf<String>()
        var vod: suspend (String) -> PlaybackSource = { NEXT }
        override suspend fun resolveVod(vodId: String) = vod(vodId)
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
        // Never reached by these tests: nothing listens there.
        val CHECKER = UpdateChecker("0", "mobile", "http://127.0.0.1:9/")
        val APK = ByteArray(50_000) { (it * 31).toByte() }
        // Any repository call a test did not plan for fails it.
        val UNEXPECTED = Proxy.newProxyInstance(
            MolotovRepository::class.java.classLoader,
            arrayOf(MolotovRepository::class.java),
        ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as MolotovRepository

        val HOME = ContentPage("Accueil", emptyList())
        // A page with a programme now on a channel, as the backend tags it.
        val ON_AIR = ContentPage(
            "Accueil",
            listOf(ContentRail("live", "En direct", listOf(ContentCard("l1", "Journal", null, false, "12", null, null, null, tag = CardTag("DIRECT", null))))),
        )
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
            originVodId = "VOD_1",
        )
        val NEXT = PlaybackSource(manifestUrl = "https://example.invalid/b.mpd", drm = DrmSpec.None, isLive = false, originVodId = "VOD_2")
    }
}
