package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.VideoInfo
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.api.WatchedResponse
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.playback.AudioRouteCaps
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.CodecInventory
import com.hometv.tv.core.playback.EngineHistory
import com.hometv.tv.core.playback.EngineInputs
import com.hometv.tv.core.playback.EngineSelector
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.Mimes
import com.hometv.tv.core.playback.TieredVersions
import com.hometv.tv.core.playback.VersionCaps
import com.hometv.tv.core.playback.VersionSelector
import com.hometv.tv.core.playback.VideoDecoder
import com.hometv.tv.core.util.Runtime
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class FilmDetailViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos(appScope = CoroutineScope(SupervisorJob() + main.dispatcher))
    private val refresh = HomeRefresh()
    private val detailRefresh = DetailRefresh()
    private var caps = CapsForFile.of(F.caps)

    /** The libVLC tiers (null = libVLC unavailable: `exo == any == caps`). */
    private var versionCaps: VersionCaps? = null
    private var film = FilmDetail(id = 12, title = "Dune", match_status = "matched")

    init {
        repos.api.onFilm = { film }
    }

    private fun vm() = FilmDetailViewModel(12, repos.catalog, repos.watch, repos.watchlist, repos.ratings, repos.ratingPrompts, repos.store, { versionCaps ?: VersionCaps(caps, caps) }, repos.events, refresh, detailRefresh)

    private fun FilmDetailViewModel.content() = state.value as FilmDetailUiState.Content

    private fun FilmDetailViewModel.buttons() = content().buttons.map { it.id }

    private fun FilmDetailViewModel.text(id: ButtonId) = content().buttons.first { it.id == id }.text

    private fun withFiles(vararg files: MediaFile, progress: Progress? = null) {
        film = film.copy(media_files = files.toList(), progress = progress)
    }

    @Test
    fun `an in-progress film offers Resume from the position and Play from the start`() = runTest(main.dispatcher) {
        withFiles(F.file(1, duration = 7200.0), progress = F.progress(1, position = 3723.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_resume_from, listOf("1:02:03")), vm.text(ButtonId.RESUME))
        assertEquals(UiText.Res(R.string.mark_watched), vm.text(ButtonId.WATCHED))
        vm.onButton(ButtonId.RESUME)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 1)), vm.navigation.first())
        vm.onButton(ButtonId.PLAY_FROM_START)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 1, fromStart = true)), vm.navigation.first())
    }

    @Test
    fun `under 30 s or watched there is a single Play button`() = runTest(main.dispatcher) {
        withFiles(F.file(1), progress = F.progress(1, position = 20.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons())

        withFiles(F.file(1), progress = F.progress(1, position = 3000.0, watched = true))
        val watched = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST), watched.buttons())
        assertEquals(UiText.Res(R.string.mark_unwatched), watched.text(ButtonId.WATCHED))
    }

    @Test
    fun `the watched toggle posts the change, refetches the detail and marks Home stale`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        film = film.copy(progress = Progress(watched = true))
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        assertEquals(listOf(WatchedRequest("film", 12, true)), repos.api.watchedBodies)
        assertEquals(2, repos.api.count("GET /api/films/12"))
        assertEquals(UiText.Res(R.string.mark_unwatched), vm.text(ButtonId.WATCHED))
        assertTrue(refresh.consumeStale())
        assertEquals(ButtonId.WATCHED, vm.content().focus)
    }

    @Test
    fun `resumes a matching version when the last-played one is not playable here`() = runTest(main.dispatcher) {
        val uhd = F.hevc(1, duration = 9360.0)
        val hd = F.file(2, duration = 9372.0, label = "1080p H264 · AC3 5.1")
        withFiles(uhd, hd, progress = F.progress(1, position = 3723.0, duration = 9360.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.detail_resume_from, listOf("1:02:03")), vm.text(ButtonId.RESUME))
        vm.onButton(ButtonId.RESUME)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 2)), vm.navigation.first())
    }

    @Test
    fun `another cut starts at 0 with Play, and picking a non-matching version turns Resume into Play`() = runTest(main.dispatcher) {
        val uhd = F.hevc(1, duration = 9360.0)
        val cut = F.file(2, duration = 8100.0, label = "1080p H264 · AC3 5.1")
        withFiles(uhd, cut, progress = F.progress(1, position = 3723.0, duration = 9360.0))
        val noHevc = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION), noHevc.buttons())

        caps = CapsForFile.of(F.hevcCaps)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_version, listOf("2160p HEVC HDR10 · EAC3 5.1")), vm.text(ButtonId.VERSION))
        vm.onButton(ButtonId.VERSION)
        val dialog = vm.content().dialog!!
        assertEquals(listOf<UiText>(UiText.Data("2160p HEVC HDR10 · EAC3 5.1"), UiText.Data("1080p H264 · AC3 5.1")), dialog.items)
        assertEquals(0, dialog.selected)
        vm.onDialogSelect(1)
        assertEquals(null, vm.content().dialog)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION), vm.buttons())
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 2)), vm.navigation.first())
        assertEquals(ButtonId.VERSION, vm.content().focus, "focus returns to the dialog opener")
    }

    @Test
    fun `the version button lists standalone versions and In N parts, parts get their own buttons`() = runTest(main.dispatcher) {
        val standalone = F.file(1, label = "2160p H264")
        val p1a = F.part(11, 1, label = "1080p H264 · AC3 5.1")
        val p1b = F.part(12, 1, label = "720p H264 · AAC 2.0").copy(video = F.H264.copy(height = 720))
        val p2a = F.part(21, 2, label = "1080p H264 · AC3 5.1")
        withFiles(p2a, p1b, standalone, p1a)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION), vm.buttons(), "no progress: the standalone version")
        vm.onButton(ButtonId.VERSION)
        assertEquals(listOf<UiText>(UiText.Data("2160p H264"), UiText.Res(R.string.detail_in_parts, listOf("2"))), vm.content().dialog!!.items)
        vm.onDialogSelect(1)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION, ButtonId.PART, ButtonId.PART_VERSION), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_version, listOf(UiText.Res(R.string.detail_in_parts, listOf("2")))), vm.text(ButtonId.VERSION))
        assertEquals(UiText.Res(R.string.detail_part_of, listOf("1", "2")), vm.text(ButtonId.PART))
        assertEquals(UiText.Res(R.string.detail_part_version, listOf("1080p H264 · AC3 5.1")), vm.text(ButtonId.PART_VERSION))

        vm.onButton(ButtonId.PART_VERSION)
        assertEquals(listOf<UiText>(UiText.Data("1080p H264 · AC3 5.1"), UiText.Data("720p H264 · AAC 2.0")), vm.content().dialog!!.items)
        vm.onDialogSelect(1)
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 12)), vm.navigation.first())

        vm.onButton(ButtonId.PART)
        assertEquals(listOf<UiText>(UiText.Res(R.string.part_n, listOf("1")), UiText.Res(R.string.part_n, listOf("2"))), vm.content().dialog!!.items)
        vm.onDialogSelect(1)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION, ButtonId.PART), vm.buttons(), "part 2 has one version")
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 21)), vm.navigation.first())
    }

    @Test
    fun `a film with only parts shows Part 1 of 2 and resume applies to the part of the progress`() = runTest(main.dispatcher) {
        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(21, position = 600.0, duration = 3000.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_part_of, listOf("2", "2")), vm.text(ButtonId.PART))
        vm.onButton(ButtonId.PART)
        vm.onDialogSelect(0)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART), vm.buttons())
    }

    @Test
    fun `a finished non-final part offers Play part 2 from 0 with the same label`() = runTest(main.dispatcher) {
        withFiles(
            F.part(11, 1, label = "Remux"), F.part(12, 1, label = "Web"), F.part(21, 2, label = "Web"), F.part(22, 2, label = "Remux"),
            progress = F.progress(11, position = 2900.0, duration = 3000.0),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY_PART, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART, ButtonId.PART_VERSION), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_play_part, listOf("2")), vm.text(ButtonId.PLAY_PART))
        vm.onButton(ButtonId.PLAY_PART)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 22)), vm.navigation.first())
    }

    /** The navigator launched the Player (`AppNavigator.play` marks details stale), which then popped back. */
    private fun returnFromPlayer(vm: FilmDetailViewModel) {
        detailRefresh.markStale()
        vm.onVisible()
    }

    @Test
    fun `back from the Player after Part 1 ended offers Play part 2 without a loading screen`() = runTest(main.dispatcher) {
        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(11, position = 600.0, duration = 3000.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART), vm.buttons())
        vm.onButton(ButtonId.RESUME)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 11)), vm.navigation.first())

        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(11, position = 2990.0, duration = 3000.0))
        returnFromPlayer(vm)
        assertTrue(vm.state.value is FilmDetailUiState.Content, "refetched in place, never Loading")
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/films/12"))
        assertEquals(listOf(ButtonId.PLAY_PART, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_play_part, listOf("2")), vm.text(ButtonId.PLAY_PART))
        assertEquals(ButtonId.PLAY_PART, vm.content().focus, "focus moves to the button that replaced Resume")
        vm.onButton(ButtonId.PLAY_PART)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 21)), vm.navigation.first())
    }

    @Test
    fun `becoming visible without a Player launch does not refetch`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(1, repos.api.count("GET /api/films/12"))
        returnFromPlayer(vm)
        advanceUntilIdle()
        vm.onVisible()
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/films/12"), "each Player launch is seen once")
    }

    @Test
    fun `back from the Player keeps an explicit version choice while its file still exists`() = runTest(main.dispatcher) {
        val uhd = F.file(1, duration = 9360.0, label = "2160p H264")
        val cut = F.file(2, duration = 8100.0, label = "1080p H264 · AC3 5.1")
        withFiles(uhd, cut, progress = F.progress(1, position = 3723.0, duration = 9360.0))
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.VERSION)
        vm.onDialogSelect(1)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.VERSION), vm.buttons())
        vm.onButton(ButtonId.VERSION)
        vm.onDialogDismiss()

        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.detail_version, listOf("1080p H264 · AC3 5.1")), vm.text(ButtonId.VERSION))
        assertEquals(ButtonId.VERSION, vm.content().focus, "focus stays where it was")
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 2)), vm.navigation.first())

        withFiles(uhd, progress = F.progress(1, position = 3723.0, duration = 9360.0))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons(), "the chosen file is gone: default choice")
    }

    @Test
    fun `an explicitly chosen Part 1 that ended moves on to Part 2`() = runTest(main.dispatcher) {
        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(21, position = 600.0, duration = 3000.0))
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.PART)
        vm.onDialogSelect(0)
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 11)), vm.navigation.first())

        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(11, position = 2990.0, duration = 3000.0))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY_PART, ButtonId.WATCHED, ButtonId.WATCHLIST, ButtonId.PART), vm.buttons())
        vm.onButton(ButtonId.PLAY_PART)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 21)), vm.navigation.first())
    }

    @Test
    fun `no readable file disables Play with the catalog message`() = runTest(main.dispatcher) {
        withFiles(F.file(1, probeError = "moov atom not found"))
        val vm = vm()
        advanceUntilIdle()
        val play = vm.content().buttons.first()
        assertEquals(ButtonId.PLAY, play.id)
        assertFalse(play.enabled)
        assertEquals(UiText.Res(R.string.detail_unplayable), vm.content().unplayableMessage)
        vm.onButton(ButtonId.PLAY)
        withFiles()
        val empty = vm()
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.detail_unplayable), empty.content().unplayableMessage)
    }

    @Test
    fun `meta line, unmatched films and the cast`() = runTest(main.dispatcher) {
        film = FilmDetail(
            id = 12, title = "Dune", year = 2021, runtime_minutes = 155, vote_average = 7.84, match_status = "matched",
            genres = listOf(Genre(1, "Ciencia ficción"), Genre(2, "Aventura")), media_files = listOf(F.file(1)),
        )
        val vm = vm()
        advanceUntilIdle()
        val expected = UiText.Joined(listOf(
            UiText.Data("2021"),
            UiText.Value(Runtime(2, 35)),
            UiText.Res(R.string.rating, listOf("7,8")),
            UiText.Joined(listOf(UiText.Data("Ciencia ficción"), UiText.Data("Aventura")), R.string.separator_comma),
        ))
        assertEquals(expected, vm.content().meta)
        assertTrue(vm.content().matched)
        film = film.copy(match_status = "unmatched")
        val unmatched = vm()
        advanceUntilIdle()
        assertFalse(unmatched.content().matched)
    }

    @Test
    fun `a removed title shows the gone screen and emits ItemGone`() = runTest(main.dispatcher) {
        repos.recordEvents(backgroundScope)
        repos.api.onFilm = { throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "not_found") }
        val vm = vm()
        advanceUntilIdle()
        assertEquals(FilmDetailUiState.Error(ScreenError.ItemGone), vm.state.value)
        assertEquals(listOf<AppEvent>(AppEvent.ItemGone("film", 12)), repos.received)
    }

    // ---- Cast navigation ---------------------------------------------------------------------

    private val cast = listOf(CastMember(3, "Timothée Chalamet", "Paul Atreides"), CastMember(4, "Zendaya", "Chani"), CastMember(5, "Rebecca Ferguson"))

    private fun withCast(vararg files: MediaFile, progress: Progress? = null, members: List<CastMember> = cast) {
        film = film.copy(media_files = files.toList(), progress = progress, cast = members)
    }

    @Test
    fun `castKey is unique per position, also for a person credited twice`() {
        assertEquals("3:0", castKey(cast[0], 0))
        assertEquals("3:2", castKey(cast[0], 2))
    }

    @Test
    fun `OK on a cast card remembers the card and asks for the Person screen`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(null, vm.content().castFocus)
        val version = vm.content().focusVersion
        vm.onCastSelected(castKey(cast[1], 1), 1, 4)
        assertEquals(CastFocus("4:1", 1), vm.content().castFocus)
        assertEquals(version, vm.content().focusVersion, "the card already has focus: no focusVersion bump")
        assertEquals(NavRequest.Person(4), vm.navigation.first())
    }

    @Test
    fun `a button press after a cast selection gives focus back to that button at once`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("3:0", 0, 3)
        vm.navigation.first()
        vm.onButton(ButtonId.WATCHED)
        assertEquals(null, vm.content().castFocus, "published before the toggle's request")
        assertEquals(ButtonId.WATCHED, vm.content().focus, "the pressed button, not the first one")
        advanceUntilIdle()
        assertEquals(null, vm.content().castFocus)
        assertEquals(ButtonId.WATCHED, vm.content().focus)
    }

    @Test
    fun `Play after a cast selection publishes the cleared cast focus before the Play request`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("3:0", 0, 3)
        vm.navigation.first()
        val version = vm.content().focusVersion
        val log = mutableListOf<String>()
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(unconfined) {
            vm.state.collect { s -> (s as? FilmDetailUiState.Content)?.let { log += "state cast=${it.castFocus} focus=${it.focus} v=${it.focusVersion}" } }
        }
        backgroundScope.launch(unconfined) { vm.navigation.collect { log += "nav $it" } }
        vm.onButton(ButtonId.PLAY)
        assertEquals(
            listOf(
                "state cast=CastFocus(key=3:0, index=0) focus=PLAY v=$version",
                "state cast=null focus=PLAY v=$version",
                "nav ${NavRequest.Play(PlayRequest("film", 12, mediaFileId = 1))}",
            ),
            log,
        )
    }

    @Test
    fun `a button press without a remembered cast card publishes nothing extra`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        val states = mutableListOf<FilmDetailUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { states += it } }
        vm.onButton(ButtonId.PLAY)
        vm.navigation.first()
        assertEquals(1, states.size, "only the current state: $states")
    }

    @Test
    fun `after Play turns into Resume focus falls back to the first button, never to the cast`() = runTest(main.dispatcher) {
        withCast(F.file(1, duration = 7200.0))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("4:1", 1, 4)
        vm.navigation.first()
        vm.onButton(ButtonId.PLAY)
        vm.navigation.first()
        withCast(F.file(1, duration = 7200.0), progress = F.progress(1, position = 600.0))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons())
        assertEquals(ButtonId.RESUME, vm.content().focus)
        assertEquals(null, vm.content().castFocus)
    }

    @Test
    fun `Play from the start keeps focus on that button after the Player when it still exists`() = runTest(main.dispatcher) {
        withCast(F.file(1, duration = 7200.0), progress = F.progress(1, position = 600.0))
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.PLAY_FROM_START)
        vm.navigation.first()
        withCast(F.file(1, duration = 7200.0), progress = F.progress(1, position = 900.0))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(ButtonId.PLAY_FROM_START, vm.content().focus)
    }

    @Test
    fun `a refetch keeps the remembered cast card only while the cast still has it at that position`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("4:1", 1, 4)
        vm.navigation.first()
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(CastFocus("4:1", 1), vm.content().castFocus, "same cast: kept")

        withCast(F.file(1), members = listOf(cast[0], cast[2], cast[1]))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(null, vm.content().castFocus, "the key moved to another index: dropped")
        assertEquals(ButtonId.PLAY, vm.content().focus)
    }

    @Test
    fun `a dropped cast card does not come back when a later refetch restores the old cast`() = runTest(main.dispatcher) {
        withCast(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("4:1", 1, 4)
        vm.navigation.first()
        withCast(F.file(1), members = listOf(cast[0], cast[2], cast[1]))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(null, vm.content().castFocus, "the key moved to another index: dropped")

        withCast(F.file(1))
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(null, vm.content().castFocus, "the stale card stays dropped")
        assertEquals(ButtonId.PLAY, vm.content().focus)
    }

    @Test
    fun `closing a dialog clears the remembered cast card`() = runTest(main.dispatcher) {
        withCast(F.file(1, label = "A"), F.file(2, label = "B"))
        val vm = vm()
        advanceUntilIdle()
        vm.onCastSelected("3:0", 0, 3)
        vm.navigation.first()
        vm.onButton(ButtonId.VERSION)
        vm.onDialogDismiss()
        assertEquals(null, vm.content().castFocus)
        assertEquals(ButtonId.VERSION, vm.content().focus)
    }

    // ---- Mi lista ---------------------------------------------------------------------------

    /** The write answers only when the returned gate is completed (suspended until released). */
    private fun gateWrites(): MutableList<CompletableDeferred<Unit>> {
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        repos.api.onWatchlistWrite = { _, _, _ -> CompletableDeferred<Unit>().also { gates += it }.await() }
        return gates
    }

    private fun FilmDetailViewModel.watchlistButton() = content().buttons.first { it.id == ButtonId.WATCHLIST }

    private fun writes() = repos.api.calls.filter { it.contains("/api/watchlist/") }

    @Test
    fun `the Mi lista button follows the watched button, with its text and accessible name`() = runTest(main.dispatcher) {
        withFiles(F.file(1, duration = 7200.0), progress = F.progress(1, position = 3723.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons())
        assertEquals(DetailButton(ButtonId.WATCHLIST, UiText.Res(R.string.watchlist_toggle), description = UiText.Res(R.string.watchlist_add)), vm.watchlistButton())

        film = film.copy(in_watchlist = true)
        val inList = vm()
        advanceUntilIdle()
        assertEquals(DetailButton(ButtonId.WATCHLIST, UiText.Res(R.string.watchlist_in_list), description = UiText.Res(R.string.watchlist_remove)), inList.watchlistButton())
    }

    @Test
    fun `OK flips the label before the server answers and never refetches or moves focus`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        val version = vm.content().focusVersion
        vm.onButton(ButtonId.WATCHLIST)
        assertEquals(UiText.Res(R.string.watchlist_in_list), vm.watchlistButton().text, "flipped at once")
        assertTrue(vm.content().film.in_watchlist)
        assertNull(vm.content().watchlistError)
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12"), writes())
        gates.single().complete(Unit)
        advanceUntilIdle()
        assertEquals(1, repos.api.count("GET /api/films/12"), "no refetch")
        assertEquals(UiText.Res(R.string.watchlist_in_list), vm.watchlistButton().text)
        assertEquals(ButtonId.WATCHLIST, vm.content().focus)
        assertEquals(version, vm.content().focusVersion)
        assertNull(vm.content().watchlistError)
        assertFalse(refresh.consumeStale(), "Home has no list row")

        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        gates.last().complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/films/12"), writes())
        assertEquals(UiText.Res(R.string.watchlist_toggle), vm.watchlistButton().text)
    }

    @Test
    fun `a failed write reverts the label and shows the inline line until the next action`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        repos.api.onWatchlistWrite = { _, _, _ -> throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(500, "internal_error") }
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        assertFalse(vm.content().film.in_watchlist)
        assertEquals(UiText.Res(R.string.watchlist_toggle), vm.watchlistButton().text)
        assertEquals(UiText.Res(R.string.watchlist_update_failed), vm.content().watchlistError)
        assertEquals(ButtonId.WATCHLIST, vm.content().focus)
        vm.onButton(ButtonId.WATCHED)
        assertNull(vm.content().watchlistError, "cleared by the next action")
    }

    @Test
    fun `the inline line is also cleared by a cast card and by a dialog`() = runTest(main.dispatcher) {
        withCast(F.file(1), F.file(2, label = "720p"))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        runCurrent()
        gates[0].completeExceptionally(java.io.IOException("reset"))
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.watchlist_update_failed), vm.content().watchlistError)
        vm.onCastSelected(castKey(cast[0], 0), 0, 3)
        assertNull(vm.content().watchlistError)

        // The failure lands while the Version dialog is open: closing the dialog clears the line.
        vm.onButton(ButtonId.WATCHLIST)
        runCurrent()
        vm.onButton(ButtonId.VERSION)
        gates[1].completeExceptionally(java.io.IOException("reset"))
        advanceUntilIdle()
        assertEquals(UiText.Res(R.string.watchlist_update_failed), vm.content().watchlistError)
        vm.onDialogDismiss()
        assertNull(vm.content().watchlistError)
    }

    @Test
    fun `a PUT that succeeded before a failing DELETE leaves the title in the list with the inline line`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        runCurrent()
        vm.onButton(ButtonId.WATCHLIST)
        assertFalse(vm.content().film.in_watchlist)
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12", "DELETE /api/watchlist/films/12"), writes())
        gates[1].completeExceptionally(com.hometv.tv.core.data.FakeHomeTvApi.httpError(503, "internal_error"))
        advanceUntilIdle()
        assertTrue(vm.content().film.in_watchlist)
        assertEquals(UiText.Res(R.string.watchlist_in_list), vm.watchlistButton().text)
        assertEquals(UiText.Res(R.string.watchlist_update_failed), vm.content().watchlistError)
    }

    @Test
    fun `unreachable, 5xx, other HTTP errors and an undecodable answer roll back with the inline line`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        film = film.copy(in_watchlist = true)
        val failures = listOf<() -> Throwable>(
            { java.net.ConnectException("refused") },
            { com.hometv.tv.core.data.FakeHomeTvApi.httpError(500, "internal_error") },
            { com.hometv.tv.core.data.FakeHomeTvApi.httpError(409, "conflict") },
            { com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "profile_not_found") },
            { kotlinx.serialization.SerializationException("bad body") },
        )
        val vm = vm()
        advanceUntilIdle()
        failures.forEach { failure ->
            repos.api.onWatchlistWrite = { _, _, _ -> throw failure() }
            vm.onButton(ButtonId.WATCHLIST)
            assertFalse(vm.content().film.in_watchlist)
            advanceUntilIdle()
            assertTrue(vm.content().film.in_watchlist, "rolled back after ${failure()}")
            assertEquals(UiText.Res(R.string.watchlist_update_failed), vm.content().watchlistError)
        }
        assertTrue(vm.state.value is FilmDetailUiState.Content)
    }

    @Test
    fun `a late rollback keeps an error screen the watched toggle put up`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        runCurrent()
        repos.api.onWatched = { throw java.net.ConnectException("refused") }
        vm.onButton(ButtonId.WATCHED)
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue(error is FilmDetailUiState.Error, "the watched toggle failed: $error")
        gates.single().completeExceptionally(java.io.IOException("reset"))
        advanceUntilIdle()
        assertEquals(error, vm.state.value, "the rollback does not replace the error screen")

        // Retry reloads the detail with the server's value (the rollback only updated the model).
        repos.api.onWatched = { WatchedResponse(1) }
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.content().film.in_watchlist)
        assertNull(vm.content().watchlistError)
    }

    @Test
    fun `a late rollback keeps the Loading screen of a retry`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        runCurrent()
        val load = CompletableDeferred<Unit>()
        repos.api.onFilm = { load.await(); film }
        vm.retry()
        runCurrent()
        assertEquals(FilmDetailUiState.Loading, vm.state.value)
        gates.single().completeExceptionally(java.io.IOException("reset"))
        runCurrent()
        assertEquals(FilmDetailUiState.Loading, vm.state.value, "the rollback does not replace Loading")
        load.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.content().film.in_watchlist)
    }

    @Test
    fun `a 404 on the write shows the gone screen and emits ItemGone`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        repos.recordEvents(backgroundScope)
        repos.api.onWatchlistWrite = { _, _, _ -> throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "not_found") }
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        assertEquals(FilmDetailUiState.Error(ScreenError.ItemGone), vm.state.value)
        assertEquals(listOf<AppEvent>(AppEvent.ItemGone("film", 12)), repos.received)
        assertTrue(refresh.consumeStale())
    }

    @Test
    fun `a 401 on the retried write shows the sign-in error, not the inline line`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        repos.api.onWatchlistWrite = { _, _, _ -> throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(401, "unauthorized") }
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12", "PUT /api/watchlist/films/12"), writes(), "one renewal, one retry")
        assertEquals(FilmDetailUiState.Error(ScreenError.SignIn), vm.state.value)
    }

    @Test
    fun `a renewal answering profile_not_found leaves only ProfileGone, with no inline line and no ItemGone`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        repos.recordEvents(backgroundScope)
        repos.api.onWatchlistWrite = { _, _, _ -> throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(401, "unauthorized") }
        repos.api.onCreateSession = { throw com.hometv.tv.core.data.FakeHomeTvApi.httpError(404, "profile_not_found") }
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        assertEquals(FilmDetailUiState.Error(ScreenError.ProfileGone), vm.state.value)
        assertEquals(listOf<AppEvent>(AppEvent.AuthExpired), repos.received, "only the guard's event")
    }

    @Test
    fun `three quick presses send the final state once`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val vm = vm()
        advanceUntilIdle()
        repeat(3) { vm.onButton(ButtonId.WATCHLIST) }
        assertTrue(vm.content().film.in_watchlist)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12"), writes())
        assertTrue(vm.content().film.in_watchlist)
        assertNull(vm.content().watchlistError)
    }

    @Test
    fun `the button works on a film with no playable file`() = runTest(main.dispatcher) {
        withFiles()
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.WATCHLIST), vm.buttons())
        vm.onButton(ButtonId.WATCHLIST)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/watchlist/films/12"), writes())
        assertTrue(vm.content().film.in_watchlist)
    }

    @Test
    fun `a refetch while the write is in flight keeps the shown value`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertEquals(2, repos.api.count("GET /api/films/12"))
        assertTrue(vm.content().film.in_watchlist, "the fetched false is overridden while writing")
        gates.single().complete(Unit)
        advanceUntilIdle()
        film = film.copy(in_watchlist = true)
        returnFromPlayer(vm)
        advanceUntilIdle()
        assertTrue(vm.content().film.in_watchlist)
    }

    @Test
    fun `a write survives the ViewModel being cleared`() = runTest(main.dispatcher) {
        withFiles(F.file(1))
        val gates = gateWrites()
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.WATCHLIST)
        val store = androidx.lifecycle.ViewModelStore()
        store.put("detail", vm)
        store.clear()
        runCurrent()
        assertEquals(listOf("PUT /api/watchlist/films/12"), writes())
        assertTrue(repos.watchlist.writing.value)
        gates.single().complete(Unit)
        advanceUntilIdle()
        assertFalse(repos.watchlist.writing.value, "the write completed")
    }

    // ---- Exo-first version tiers (libVLC available) ------------------------------------------

    /** A device without hardware HEVC, libVLC available: the tiers `AppContainer.versionCaps()` builds. */
    private fun vlcTiers(): VersionCaps {
        val inputs = EngineInputs(
            CodecInventory(listOf(VideoDecoder(Mimes.VIDEO_H264, true, 1920, 1080)), emptySet()),
            AudioRouteCaps(emptySet(), 2),
            vlcAvailable = true,
        )
        return VersionCaps(EngineSelector.exoCapsForFile(inputs) { EngineHistory() }, EngineSelector.capsForFile(inputs) { EngineHistory() })
    }

    @Test
    fun `AC-1_12 an Exo-playable H264 720p version wins over a libVLC-only HEVC 2160p one`() = runTest(main.dispatcher) {
        versionCaps = vlcTiers()
        withFiles(F.file(1, video = F.HEVC), F.file(2, video = F.H264.copy(height = 720)))
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 2)), vm.navigation.first())
    }

    @Test
    fun `AC-1_13 with no Exo-playable version the default is the libVLC tier's choice`() = runTest(main.dispatcher) {
        val tiers = vlcTiers()
        versionCaps = tiers
        val files = listOf(F.file(1, video = VideoInfo("mpeg4", 720, 576), container = "avi", fileName = "film.avi"), F.file(2, video = F.HEVC))
        // Last played on libVLC: the Exo tier alone would fall back to the first readable file (the HEVC one).
        val progress = F.progress(1, position = 1000.0)
        withFiles(*files.toTypedArray(), progress = progress)
        val expected = TieredVersions.defaultChoice(files, tiers, progress)!!.file.id
        assertEquals(1L, expected)
        assertEquals(2L, VersionSelector.defaultChoice(files, tiers.exo, progress)?.file?.id)
        val vm = vm()
        advanceUntilIdle()
        vm.onButton(ButtonId.RESUME)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = expected)), vm.navigation.first())
    }
}
