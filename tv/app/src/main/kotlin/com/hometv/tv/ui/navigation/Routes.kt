package com.hometv.tv.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes (navigation-compose 2.8). */
sealed interface Route {
    /** (1) Server setup. [initial] = first launch (Back exits the app); false = from Settings or an error screen (Back returns). */
    @Serializable
    data class Setup(val initial: Boolean = true) : Route

    /** (2) Profile picker. [switching] = opened via "Cambiar de perfil" (Back returns to Home); [expired] shows the session banner. */
    @Serializable
    data class Profiles(val switching: Boolean = false, val expired: Boolean = false) : Route

    @Serializable
    data object Home : Route

    /** [library] = `films` | `shows` ([Library.wire]). */
    @Serializable
    data class Browse(val library: String) : Route

    @Serializable
    data object Search : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class FilmDetail(val id: Long) : Route

    @Serializable
    data class ShowDetail(val id: Long) : Route

    /**
     * (9) Player.
     * - [launchId]: UUID registered in `AppContainer.playerLaunches` when the route is pushed; an id
     *   that is not registered (process death + back-stack restore) → pop at once with no request.
     *   The registry also holds the optional Home episode payload (`PlayerLaunches.payload`).
     * - [itemType]: `film` | `episode`; [itemId]: the film or episode id.
     * - [mediaFileId]: the file the viewer chose on Film detail (version / part / part version), or
     *   null to let `VersionSelector` pick (`defaultChoice` for films, `select`/`candidates` with
     *   `progress` for episodes).
     * - [fromStart]: "Reproducir desde el principio" (start at 0, no "different version" chip);
     *   false = resume per `VersionSelector.startFor` (D10/D11).
     */
    @Serializable
    data class Player(
        val launchId: String,
        val itemType: String,
        val itemId: Long,
        val mediaFileId: Long? = null,
        val fromStart: Boolean = false,
    ) : Route
}
