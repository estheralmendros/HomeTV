package com.hometv.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hometv.tv.R
import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.MediaFile
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.AppEventSink
import com.hometv.tv.core.data.CatalogRepository
import com.hometv.tv.core.data.SessionStore
import com.hometv.tv.core.data.WatchRepository
import com.hometv.tv.core.data.WatchTarget
import com.hometv.tv.core.playback.CapsForFile
import com.hometv.tv.core.playback.Choice
import com.hometv.tv.core.playback.Grouping
import com.hometv.tv.core.playback.VersionGroup
import com.hometv.tv.core.playback.VersionSelector
import com.hometv.tv.core.util.Format
import com.hometv.tv.data.DetailRefresh
import com.hometv.tv.data.HomeRefresh
import com.hometv.tv.ui.common.ScreenError
import com.hometv.tv.ui.navigation.NavRequest
import com.hometv.tv.ui.navigation.PlayRequest
import com.hometv.tv.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface FilmDetailUiState {
    data object Loading : FilmDetailUiState

    data class Content(
        val film: FilmDetail,
        /** "2021 · 2 h 35 min · ★ 7,8 · Ciencia ficción, Aventura". */
        val meta: UiText,
        /** `match_status == "matched"`; else "Metadatos no disponibles" replaces the overview. */
        val matched: Boolean,
        val buttons: List<DetailButton>,
        /** "Esta película no se puede reproducir (…)" when no file is readable. */
        val unplayableMessage: UiText?,
        val dialog: ListDialogUi? = null,
        /** The button that should hold focus (initial: the first one). */
        val focus: ButtonId,
        val focusVersion: Int,
    ) : FilmDetailUiState

    data class Error(val error: ScreenError) : FilmDetailUiState
}

/**
 * Film detail (prd3 §6): Resume/Play buttons from `VersionSelector` (D10 next part, D11 matching
 * versions), the Version / Part / Part-version dialogs, the watched toggle, 404 → ItemGone.
 */
class FilmDetailViewModel(
    private val filmId: Long,
    private val catalog: CatalogRepository,
    private val watch: WatchRepository,
    private val store: SessionStore,
    private val caps: suspend () -> CapsForFile,
    private val events: AppEventSink,
    private val refresh: HomeRefresh,
    private val detailRefresh: DetailRefresh,
) : ViewModel() {
    private sealed interface Entry {
        data class Standalone(val fileId: Long) : Entry

        data object InParts : Entry
    }

    private val _state = MutableStateFlow<FilmDetailUiState>(FilmDetailUiState.Loading)
    val state: StateFlow<FilmDetailUiState> = _state.asStateFlow()
    private val _navigation = Channel<NavRequest>(Channel.BUFFERED)
    val navigation: Flow<NavRequest> = _navigation.receiveAsFlow()

    private var film: FilmDetail? = null
    private var capsForFile: CapsForFile? = null
    private var choice: Choice? = null
    private var entry: Entry? = null
    private var part: Int? = null
    private var partFileId: Long? = null
    private var userChose = false
    private var dialog: ListDialogUi? = null
    private var focus: ButtonId? = null
    private var focusVersion = 0
    private var job: Job? = null
    private var seenGeneration = detailRefresh.generation()

    init {
        load()
    }

    fun retry() = load()

    /**
     * The screen resumed (e.g. the Player popped back). When a Player was launched since the last
     * fetch, the detail is refetched in place (no Loading, focus kept): progress, the D10 next part
     * and the watched mark change while playing.
     */
    fun onVisible() {
        if (detailRefresh.generation() == seenGeneration || job?.isActive == true) return
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            try {
                fetch(afterPlayback = true)
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    fun onButton(id: ButtonId) {
        val content = _state.value as? FilmDetailUiState.Content ?: return
        if (content.buttons.firstOrNull { it.id == id }?.enabled != true) return
        val file = currentFile() ?: return
        when (id) {
            ButtonId.RESUME, ButtonId.PLAY, ButtonId.PLAY_PART -> play(file, fromStart = false)
            ButtonId.PLAY_FROM_START -> play(file, fromStart = true)
            ButtonId.WATCHED -> toggleWatched()
            ButtonId.VERSION -> openDialog(versionDialog())
            ButtonId.PART -> openDialog(partDialog())
            ButtonId.PART_VERSION -> openDialog(partVersionDialog())
        }
    }

    fun onDialogSelect(index: Int) {
        val open = dialog ?: return
        val f = film ?: return
        val grouping = VersionSelector.group(f.media_files)
        when (open.opener) {
            ButtonId.VERSION -> {
                val entries = entries(grouping)
                when (val chosen = entries.getOrNull(index) ?: return) {
                    is Entry.Standalone -> entry = chosen
                    Entry.InParts -> {
                        entry = Entry.InParts
                        selectPart(defaultPartNumber(f, grouping))
                    }
                }
            }
            ButtonId.PART -> grouping.parts.getOrNull(index)?.let { selectPart(it.number) }
            ButtonId.PART_VERSION -> grouping.versionsOf(VersionGroup.Part(part ?: return)).getOrNull(index)?.let { partFileId = it.id }
            else -> Unit
        }
        userChose = true
        closeDialog(open.opener)
    }

    fun onDialogDismiss() {
        dialog?.let { closeDialog(it.opener) }
    }

    private fun closeDialog(opener: ButtonId) {
        dialog = null
        focus = opener
        focusVersion++
        publish()
    }

    private fun openDialog(d: ListDialogUi?) {
        dialog = d ?: return
        publish()
    }

    private fun play(file: MediaFile, fromStart: Boolean) {
        _navigation.trySend(NavRequest.Play(PlayRequest("film", filmId, mediaFileId = file.id, fromStart = fromStart)))
    }

    private fun toggleWatched() {
        val watched = film?.progress?.watched == true
        viewModelScope.launch {
            try {
                watch.setWatched(WatchTarget.FILM, filmId, !watched)
                refresh.markStale()
                focus = ButtonId.WATCHED
                focusVersion++
                fetch()
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    private fun load() {
        job?.cancel()
        _state.value = FilmDetailUiState.Loading
        seenGeneration = detailRefresh.generation()
        job = viewModelScope.launch {
            try {
                fetch()
            } catch (e: ApiException) {
                fail(e)
            }
        }
    }

    /**
     * Fetches the detail and recomputes the default choice. An explicit choice is kept while its file
     * still exists; after playback an "in parts" choice follows the default when that is a part, so
     * a finished part moves on to the next one (D10).
     */
    private suspend fun fetch(afterPlayback: Boolean = false) {
        val detail = catalog.film(filmId)
        val caps = capsForFile ?: caps().also { capsForFile = it }
        film = detail
        val files = detail.media_files
        choice = VersionSelector.defaultChoice(files, caps, detail.progress)
        val followParts = afterPlayback && entry == Entry.InParts && choice?.group is VersionGroup.Part
        val keep = userChose && currentFile() != null && !followParts
        if (!keep) {
            userChose = false
            choice?.let { c ->
                when (val group = c.group) {
                    VersionGroup.Standalone -> entry = Entry.Standalone(c.file.id)
                    is VersionGroup.Part -> {
                        entry = Entry.InParts
                        part = group.number
                        partFileId = c.file.id
                    }
                }
            }
        }
        publish()
    }

    private fun fail(e: ApiException) {
        val error = ScreenError.from(e, store.session.value.serverUrl)
        if (error == ScreenError.ItemGone) {
            events.emit(AppEvent.ItemGone("film", filmId))
            refresh.markStale()
        }
        _state.value = FilmDetailUiState.Error(error)
    }

    private fun publish() {
        val f = film ?: return
        val buttons = buttons(f)
        _state.value = FilmDetailUiState.Content(
            film = f,
            meta = meta(f),
            matched = f.match_status == "matched",
            buttons = buttons,
            unplayableMessage = if (choice == null) UiText.Res(R.string.detail_unplayable) else null,
            dialog = dialog,
            focus = focus?.takeIf { id -> buttons.any { it.id == id } } ?: buttons.first().id,
            focusVersion = focusVersion,
        )
    }

    private fun buttons(f: FilmDetail): List<DetailButton> {
        val files = f.media_files
        val grouping = VersionSelector.group(files)
        val file = currentFile()
        val c = choice
        val play = when {
            c == null || file == null -> listOf(DetailButton(ButtonId.PLAY, UiText.Res(R.string.detail_play), enabled = false))
            !userChose && c.startAtZero && c.file.id == file.id ->
                listOf(DetailButton(ButtonId.PLAY_PART, UiText.Res(R.string.detail_play_part, listOf((file.part_number ?: 1).toString()))))
            else -> {
                val start = VersionSelector.startFor(file, files, f.progress)
                if (start.positionSeconds > 0) {
                    listOf(
                        DetailButton(ButtonId.RESUME, UiText.Res(R.string.detail_resume_from, listOf(Format.clock(start.positionSeconds)))),
                        DetailButton(ButtonId.PLAY_FROM_START, UiText.Res(R.string.detail_play_from_start)),
                    )
                } else {
                    listOf(DetailButton(ButtonId.PLAY, UiText.Res(R.string.detail_play)))
                }
            }
        }
        val watched = DetailButton(ButtonId.WATCHED, UiText.Res(if (f.progress?.watched == true) R.string.mark_unwatched else R.string.mark_watched))
        val extra = buildList {
            val entries = entries(grouping)
            val current = entry
            if (c != null && entries.size > 1 && current != null) add(DetailButton(ButtonId.VERSION, UiText.Res(R.string.detail_version, listOf(entryArg(current, grouping)))))
            val p = part
            if (c != null && current == Entry.InParts && p != null) {
                add(DetailButton(ButtonId.PART, UiText.Res(R.string.detail_part_of, listOf(p.toString(), grouping.parts.size.toString()))))
                if (grouping.versionsOf(VersionGroup.Part(p)).size > 1 && file != null) {
                    add(DetailButton(ButtonId.PART_VERSION, UiText.Res(R.string.detail_part_version, listOf(labelArg(file)))))
                }
            }
        }
        return play + watched + extra
    }

    private fun versionDialog(): ListDialogUi? {
        val f = film ?: return null
        val grouping = VersionSelector.group(f.media_files)
        val entries = entries(grouping)
        val items = entries.map { e ->
            when (e) {
                is Entry.Standalone -> labelText(f.media_files.first { it.id == e.fileId })
                Entry.InParts -> inParts(grouping)
            }
        }
        return ListDialogUi(UiText.Res(R.string.detail_versions_title), items, entries.indexOf(entry).coerceAtLeast(0), ButtonId.VERSION)
    }

    private fun partDialog(): ListDialogUi? {
        val f = film ?: return null
        val parts = VersionSelector.group(f.media_files).parts
        val items = parts.map { UiText.Res(R.string.part_n, listOf(it.number.toString())) }
        return ListDialogUi(UiText.Res(R.string.detail_parts_title), items, parts.indexOfFirst { it.number == part }.coerceAtLeast(0), ButtonId.PART)
    }

    private fun partVersionDialog(): ListDialogUi? {
        val f = film ?: return null
        val versions = VersionSelector.group(f.media_files).versionsOf(VersionGroup.Part(part ?: return null))
        return ListDialogUi(
            UiText.Res(R.string.detail_part_versions_title),
            versions.map(::labelText),
            versions.indexOfFirst { it.id == partFileId }.coerceAtLeast(0),
            ButtonId.PART_VERSION,
        )
    }

    private fun selectPart(number: Int) {
        val f = film ?: return
        part = number
        val caps = capsForFile ?: return
        partFileId = (
            VersionSelector.select(f.media_files, caps, f.progress, VersionGroup.Part(number))
                ?: VersionSelector.group(f.media_files).versionsOf(VersionGroup.Part(number)).firstOrNull()
            )?.id
    }

    /** The part of the last-played file when it is a part, else Part 1. */
    private fun defaultPartNumber(f: FilmDetail, grouping: Grouping): Int {
        val lastPart = f.media_files.firstOrNull { it.id == f.progress?.media_file_id }?.part_number
        return lastPart ?: grouping.parts.first().number
    }

    private fun currentFile(): MediaFile? {
        val files = film?.media_files ?: return null
        return when (val e = entry) {
            is Entry.Standalone -> files.firstOrNull { it.id == e.fileId }
            Entry.InParts -> files.firstOrNull { it.id == partFileId }
            null -> null
        }
    }

    private fun entries(grouping: Grouping): List<Entry> =
        grouping.standalone.map { Entry.Standalone(it.id) } + listOfNotNull(Entry.InParts.takeIf { grouping.hasParts })

    private fun entryArg(e: Entry, grouping: Grouping): Any = when (e) {
        is Entry.Standalone -> film?.media_files?.firstOrNull { it.id == e.fileId }?.let(::labelArg) ?: UiText.Res(R.string.version_label_unknown)
        Entry.InParts -> inParts(grouping)
    }

    private fun inParts(grouping: Grouping): UiText = UiText.Res(R.string.detail_in_parts, listOf(grouping.parts.size.toString()))

    private fun labelArg(file: MediaFile): Any = file.label.ifBlank { null } ?: UiText.Res(R.string.version_label_unknown)

    private fun labelText(file: MediaFile): UiText = if (file.label.isBlank()) UiText.Res(R.string.version_label_unknown) else UiText.Data(file.label)

    companion object {
        /** "2021 · 2 h 35 min · ★ 7,8 · Ciencia ficción, Aventura" (missing parts are skipped). */
        fun meta(year: Int?, runtimeMinutes: Int?, rating: Double?, genres: List<String>): UiText = UiText.Joined(
            listOfNotNull(
                year?.let { UiText.Data(it.toString()) },
                runtimeMinutes?.takeIf { it > 0 }?.let { UiText.Value(Format.runtime(it)) },
                rating?.takeIf { it > 0 }?.let { UiText.Res(R.string.rating, listOf(Format.rating(it))) },
                genres.takeIf { it.isNotEmpty() }?.let { g -> UiText.Joined(g.map { UiText.Data(it) }, R.string.separator_comma) },
            ),
        )

        private fun meta(f: FilmDetail) = meta(f.year, f.runtime_minutes, f.vote_average, f.genres.map { it.name })
    }
}
