package com.hometv.tv.ui.navigation

import com.hometv.tv.core.api.EpisodeSummary

/** Browse library (route argument `library`). */
enum class Library(val wire: String) {
    FILMS("films"),
    SHOWS("shows"),
    ;

    companion object {
        fun fromWire(wire: String): Library = entries.firstOrNull { it.wire == wire } ?: FILMS
    }
}

/**
 * What a screen asks the Player to play (turned into [Route.Player] by `AppNavigator.play`).
 * [mediaFileId] null = the Player picks the version (`VersionSelector`, honoring `progress.media_file_id`);
 * [fromStart] = "Ver desde el principio"; [payload] = a Home episode entry, whose `media_files`
 * let the Player send `POST .../playback` without waiting for `GET /api/episodes/{id}`.
 */
data class PlayRequest(
    val itemType: String,
    val itemId: Long,
    val mediaFileId: Long? = null,
    val fromStart: Boolean = false,
    val payload: EpisodeSummary? = null,
)

/** Navigation a ViewModel asks for (collected by its screen). */
sealed interface NavRequest {
    /** Film detail (`type = "film"`) or Show detail (`"show"`). */
    data class Detail(val type: String, val id: Long) : NavRequest

    data class Play(val request: PlayRequest) : NavRequest

    data class Browse(val library: Library) : NavRequest

    /** (10) Person: the actor screen, opened from Film detail's cast row. */
    data class Person(val id: Long) : NavRequest

    /** Close the app (Home's exit dialog, after "Salir" signed the profile out). */
    data object Exit : NavRequest
}
