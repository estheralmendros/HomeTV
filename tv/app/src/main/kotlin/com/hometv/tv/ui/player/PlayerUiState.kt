package com.hometv.tv.ui.player

import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerAction
import com.hometv.tv.core.playback.PreparingEstimate
import com.hometv.tv.core.util.EpisodeCode
import com.hometv.tv.ui.text.UiText

/**
 * Player session states (the single source of truth for HLS session ownership), plus the
 * full-screen [Error] and [Exited]. `Loading` covers every `POST .../playback` (and the item
 * load before the first one) until its response is attached; a direct → HLS audio switch is `Loading`
 * with a `switchBase` (the direct media keeps playing).
 */
enum class SessionState { Loading, Playing, Paused, Released, Background, EndedResolving, Countdown, EndedIdle, EndScreen, Error, Exited }

enum class OverlayMode {
    HIDDEN,

    /** Title, progress bar and button row (focus in the button row). */
    FULL,

    /** Only the progress bar (D-pad seek while the overlay was hidden). */
    PROGRESS,
}

enum class NextKind { EPISODE, PART }

/** The countdown / part prompt card ("A continuación" / "Continuando con la parte N"). */
data class NextUi(
    val kind: NextKind,
    /** Episode: "T2 · E4"; null for a part. */
    val code: EpisodeCode?,
    /** Episode title or "Episodio N"; for a part, null. */
    val title: UiText?,
    /** Relative artwork path (`/api/artwork/...`) of the episode still. */
    val still: String?,
    val partNumber: Int?,
    /** Seconds left; null when the card is stopped (`EndedIdle`). */
    val seconds: Int?,
)

sealed interface PlayerScreenUi {
    /** Item load / playback request: spinner, or "Preparando el vídeo… quedan unos N min" when [preparing] is set. */
    data class Loading(val preparing: PreparingEstimate?) : PlayerScreenUi

    /** The video (attached, released, or a request that keeps the last frame) with the overlay on top. */
    data object Video : PlayerScreenUi

    data class Countdown(val next: NextUi) : PlayerScreenUi

    /** "El siguiente episodio no se puede reproducir…" (+ code/title + reason) or "No se ha podido cargar el siguiente episodio". */
    data class EndScreen(val message: UiText, val detail: UiText?, val canRetry: Boolean) : PlayerScreenUi

    /** A full-screen player error: message, optional reason line, buttons (first focused). */
    data class Error(val message: UiText, val reason: UiText?, val actions: List<PlayerAction>) : PlayerScreenUi
}

/** One entry of the track dialog; [key] is the audio index or the subtitle id (`off` for Desactivados). */
data class TrackOption(val key: String, val label: UiText, val selected: Boolean, val pending: Boolean = false)

data class TrackDialogUi(val audio: List<TrackOption>, val subtitles: List<TrackOption>)

sealed interface PlayerDialog {
    data class Tracks(val tracks: TrackDialogUi) : PlayerDialog

    /** [Probar otra versión]: the playable labels. */
    data class Versions(val labels: List<String>) : PlayerDialog
}

data class PlayerUiState(
    val session: SessionState = SessionState.Loading,
    val screen: PlayerScreenUi = PlayerScreenUi.Loading(null),
    val title: UiText? = null,
    val subtitle: UiText? = null,
    val overlay: OverlayMode = OverlayMode.HIDDEN,
    val positionMs: Long = 0,
    /** Effective duration; null while unknown (elapsed time only, no bar fill). */
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    /** The pending `SeekAccumulator` target shown on the bar. */
    val pendingSeekMs: Long? = null,
    val playing: Boolean = false,
    /** Spinner over the video (buffering, a request that keeps the last frame). */
    val spinner: Boolean = false,
    val nextButton: NextKind? = null,
    /** Top-right status chip: "Preparando los subtítulos…", "Reproduciendo otra versión desde el principio". */
    val chip: UiText? = null,
    /** The audio switch chip ("Cambiando el audio…" / "Preparando el audio… quedan unos N min") with [Cancelar]. */
    val switchChip: UiText? = null,
    val toast: UiText? = null,
    val dialog: PlayerDialog? = null,
    val keepScreenOn: Boolean = false,
    /** Bumped whenever the overlay should put focus on the play/pause button. */
    val focusPlayPause: Int = 0,
    /**
     * The engine of the active controller: null until the first attach creates one, then changes only on an
     * engine switch. The video surface is keyed on it; no visible text depends on it (the choice is invisible).
     */
    val engine: PlaybackEngine? = null,
)

/** One-off effects for the screen. */
sealed interface PlayerEffect {
    /** Leave the Player (`AppNavigator.exitPlayer`). */
    data object Exit : PlayerEffect
}
