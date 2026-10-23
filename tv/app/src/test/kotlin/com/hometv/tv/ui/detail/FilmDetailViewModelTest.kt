package com.hometv.tv.ui.detail

import com.hometv.tv.R
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.Genre
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.WatchedRequest
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.util.Runtime
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.testing.MainDispatcherExtension
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class FilmDetailViewModelTest {
    @JvmField @RegisterExtension val main = MainDispatcherExtension()

    private val repos = TestRepos()
    private val refresh = HomeRefresh()
    private var caps = CapsForFile.of(F.caps)
    private var film = FilmDetail(id = 12, title = "Dune", match_status = "matched")

    init {
        repos.api.onFilm = { film }
    }

    private fun vm() = FilmDetailViewModel(12, repos.catalog, repos.watch, repos.store, { caps }, repos.events, refresh)

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
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED), vm.buttons())
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
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED), vm.buttons())

        withFiles(F.file(1), progress = F.progress(1, position = 3000.0, watched = true))
        val watched = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED), watched.buttons())
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
    fun `D11 resumes a matching version when the last-played one is not playable here`() = runTest(main.dispatcher) {
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
    fun `D11 another cut starts at 0 with Play, and picking a non-matching version turns Resume into Play`() = runTest(main.dispatcher) {
        val uhd = F.hevc(1, duration = 9360.0)
        val cut = F.file(2, duration = 8100.0, label = "1080p H264 · AC3 5.1")
        withFiles(uhd, cut, progress = F.progress(1, position = 3723.0, duration = 9360.0))
        val noHevc = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.VERSION), noHevc.buttons())

        caps = CapsForFile.of(F.hevcCaps)
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.VERSION), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_version, listOf("2160p HEVC HDR10 · EAC3 5.1")), vm.text(ButtonId.VERSION))
        vm.onButton(ButtonId.VERSION)
        val dialog = vm.content().dialog!!
        assertEquals(listOf<UiText>(UiText.Data("2160p HEVC HDR10 · EAC3 5.1"), UiText.Data("1080p H264 · AC3 5.1")), dialog.items)
        assertEquals(0, dialog.selected)
        vm.onDialogSelect(1)
        assertEquals(null, vm.content().dialog)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.VERSION), vm.buttons())
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
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.VERSION), vm.buttons(), "no progress: the standalone version")
        vm.onButton(ButtonId.VERSION)
        assertEquals(listOf<UiText>(UiText.Data("2160p H264"), UiText.Res(R.string.detail_in_parts, listOf("2"))), vm.content().dialog!!.items)
        vm.onDialogSelect(1)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.VERSION, ButtonId.PART, ButtonId.PART_VERSION), vm.buttons())
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
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.VERSION, ButtonId.PART), vm.buttons(), "part 2 has one version")
        vm.onButton(ButtonId.PLAY)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 21)), vm.navigation.first())
    }

    @Test
    fun `a film with only parts shows Part 1 of 2 and resume applies to the part of the progress`() = runTest(main.dispatcher) {
        withFiles(F.part(11, 1), F.part(21, 2), progress = F.progress(21, position = 600.0, duration = 3000.0))
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.RESUME, ButtonId.PLAY_FROM_START, ButtonId.WATCHED, ButtonId.PART), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_part_of, listOf("2", "2")), vm.text(ButtonId.PART))
        vm.onButton(ButtonId.PART)
        vm.onDialogSelect(0)
        assertEquals(listOf(ButtonId.PLAY, ButtonId.WATCHED, ButtonId.PART), vm.buttons())
    }

    @Test
    fun `a finished non-final part offers Play part 2 from 0 with the same label`() = runTest(main.dispatcher) {
        withFiles(
            F.part(11, 1, label = "Remux"), F.part(12, 1, label = "Web"), F.part(21, 2, label = "Web"), F.part(22, 2, label = "Remux"),
            progress = F.progress(11, position = 2900.0, duration = 3000.0),
        )
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(ButtonId.PLAY_PART, ButtonId.WATCHED, ButtonId.PART, ButtonId.PART_VERSION), vm.buttons())
        assertEquals(UiText.Res(R.string.detail_play_part, listOf("2")), vm.text(ButtonId.PLAY_PART))
        vm.onButton(ButtonId.PLAY_PART)
        assertEquals(NavRequest.Play(PlayRequest("film", 12, mediaFileId = 22)), vm.navigation.first())
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
}
