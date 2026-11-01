package com.hometv.tv.ui.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hometv.tv.core.data.AppEvent
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.di.AppContainer
import com.hometv.tv.ui.navigation.Route

/**
 * The one root-level rating prompt (ratings prd3 FR-T7), after the `NavHost` in `HomeTvRoot`: takes a
 * pending prompt of `AppContainer.ratingPrompts` for the current profile once the current destination
 * is RESUMED and is not the Player, Setup or the Profile Picker, and shows it as a prompt-mode
 * [RatingDialog] over that screen (one at a time: a newer offer waits until this one closes). Leaving
 * for Profiles/Setup (auth expiry: the modal dialog blocks every other way there) closes it at once,
 * also while saving, with no dismissal; the write keeps running in `appScope` and its outcome reaches
 * no UI. Write failures use [RatingEditor.hostCloses]: 404 closes + `AppEvent.ItemGone`, a gone
 * profile closes, a 401 after the renewal is FAILED.
 */
@Composable
fun RatingPromptHost(container: AppContainer, nav: NavHostController) {
    val pending by container.ratingPrompts.pending.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val lifecycle = entry?.lifecycle?.currentStateFlow?.collectAsState()?.value
    val blocked = entry?.destination?.let(::blocksPrompt) ?: true
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<RatingEditor?>(null) }

    LaunchedEffect(blocked) {
        if (blocked) {
            editor?.detach()
            editor = null
        }
    }
    LaunchedEffect(pending, blocked, lifecycle, editor == null) {
        if (editor != null || pending == null || blocked || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        val taken = container.ratingPrompts.take(container.store.session.value.profileId) ?: return@LaunchedEffect
        val prompt = taken.prompt
        val kind = RatingKind.fromWire(prompt.kind) ?: return@LaunchedEffect
        editor = RatingEditor(
            kind, prompt.id, RatingDialogUi.Mode.PROMPT, prompt.title, prompt.poster?.small, initial = null, container.ratings, scope,
            onGone = { e ->
                // A gone profile: the guard already emitted AuthExpired; the dialog only closes.
                if (RatingEditor.isItemGone(e)) container.events.emit(AppEvent.ItemGone(kind.wire, prompt.id))
            },
            onClosed = { editor = null },
            closesOn = RatingEditor::hostCloses,
        )
    }
    val open = editor ?: return
    val ui by open.ui.collectAsState()
    ui?.let {
        RatingDialog(it, open::step, open::save, open::secondary, open::remove, open::dismiss)
    }
}

/** The prompt never opens over the Player (T-AC-1), Setup or the Profile Picker. */
private fun blocksPrompt(destination: NavDestination): Boolean =
    destination.hasRoute<Route.Player>() || destination.hasRoute<Route.Setup>() || destination.hasRoute<Route.Profiles>()
