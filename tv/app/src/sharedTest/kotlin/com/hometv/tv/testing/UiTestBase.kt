package com.hometv.tv.testing

import com.hometv.tv.ui.player.ExoCaptionApplied
import com.hometv.tv.ui.player.LocalExoCaptionProbe
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.hometv.tv.core.api.CastMember
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmDetail
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.Page
import com.hometv.tv.core.api.Profile
import com.hometv.tv.core.api.ProfileList
import com.hometv.tv.core.api.Progress
import com.hometv.tv.core.api.SearchResponse
import com.hometv.tv.core.api.SeasonDetail
import com.hometv.tv.core.api.SeasonSummary
import com.hometv.tv.core.api.ShowDetail
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.data.Session
import com.hometv.tv.core.playback.F
import com.hometv.tv.core.playback.PlaybackEngine
import com.hometv.tv.core.playback.PlayerController
import com.hometv.tv.data.EnginePrefs
import com.hometv.tv.data.InMemoryEnginePrefs
import com.hometv.tv.di.AppContainer
import com.hometv.tv.ui.HomeTvRoot
import com.hometv.tv.ui.player.LocalVideoSurfaceProbe
import com.hometv.tv.ui.theme.HomeTvDimens
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule

/**
 * Compose focus/navigation tests over the whole app ([HomeTvRoot] + a container on a [ScriptedApi]):
 * Robolectric on the JVM (`src/test`), the TV emulator in `androidTest`. Keys go to the focused node.
 */
abstract class UiTestBase {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    protected val api = ScriptedApi()
    protected var exited = false
    protected lateinit var container: AppContainer

    /** The player of the Player screen (no ExoPlayer in tests); its callbacks must run on the main thread. */
    protected val player = FakePlayerController()

    /** The libVLC engine's player (a second fake), used only when [vlcAvailable] and a file plays on libVLC. */
    protected val vlcPlayer = FakePlayerController()

    /** `AppContainer.vlcAvailability` of the launched container (false: every file on ExoPlayer). */
    protected open val vlcAvailable: Boolean = false

    /** `AppContainer.enginePrefs` of the launched container (the device preference "Usar siempre VLC (experimental)"). */
    protected open val enginePrefs: EnginePrefs = InMemoryEnginePrefs()

    /** The licence notice of the launched container (Ajustes → "Licencias de código abierto"). */
    protected open val notices: String = "THIRD PARTY NOTICES"

    /** Calls of the container's `player()` factory (the Player builds its controller lazily, at the first attach). */
    protected var playerBuilds = 0

    /** Calls of the container's `vlcPlayer()` factory. */
    protected var vlcPlayerBuilds = 0

    /** Every (engine, controller) pair the video surface bound, through `LocalVideoSurfaceProbe`. */
    protected val surfaceBindings = mutableListOf<Pair<PlaybackEngine?, PlayerController?>>()

    /** What the Exo surface last applied to its `SubtitleView` (look, bottom padding, overlay clearance), through `LocalExoCaptionProbe`. */
    internal var exoCaption: ExoCaptionApplied? = null

    /** The activity-level key hook (`MainActivity.dispatchKeyEvent` offers keys to it before Compose focus). */
    protected val keyDispatch = com.hometv.tv.ui.KeyDispatch()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    protected val signedIn = Session(serverUrl = TEST_SERVER, profileId = 5, token = "tok", expiresAt = Instant.parse("2030-01-01T00:00:00Z"))

    /** The container's clock (session renewal, ages on the Person screen, Actividad times); its zone is the calendar's. Tests may fix it. */
    protected open val clock: Clock = Clock.systemUTC()

    protected fun launch(session: Session = signedIn) {
        container = testContainer(
            api, scope, session, clock = clock, cacheIo = Dispatchers.Unconfined,
            player = {
                playerBuilds++
                player
            },
            vlcPlayer = {
                vlcPlayerBuilds++
                vlcPlayer
            },
            vlcAvailable = vlcAvailable,
            enginePrefs = enginePrefs,
            notices = notices,
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalVideoSurfaceProbe provides { e, c -> surfaceBindings += e to c },
                LocalExoCaptionProbe provides { applied -> exoCaption = applied },
            ) {
                HomeTvRoot(container, onExit = { exited = true }, keyDispatch = keyDispatch)
            }
        }
        settle()
    }

    protected fun settle() {
        rule.waitForIdle()
        UiTestPlatform.advance(50)
        rule.waitForIdle()
    }

    /**
     * One D-pad / remote key press: offered first to the activity's [keyDispatch] (as `MainActivity`
     * does), then to the focused element of the topmost window (e.g. an open dialog).
     */
    protected fun press(key: Key, times: Int = 1) {
        repeat(times) {
            val code = key.nativeKeyCode
            val down = rule.runOnUiThread<Boolean> { keyDispatch.dispatch(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)) }
            if (down) {
                rule.runOnUiThread { keyDispatch.dispatch(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)) }
            } else {
                rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
            }
            settle()
        }
    }

    /**
     * Holds OK as a remote does: the first down, the repeat that tv-material reads as a long press
     * (`repeatCount == 1`), a later repeat, then the release. Each event goes to the window that has
     * focus at that moment (a long press may open another screen or a dialog before the release).
     */
    protected fun holdOk() {
        val code = android.view.KeyEvent.KEYCODE_DPAD_CENTER
        sendToFocusedWindow(android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, code, 0))
        sendToFocusedWindow(android.view.KeyEvent(0L, 500L, android.view.KeyEvent.ACTION_DOWN, code, 1, 0, 0, 0, android.view.KeyEvent.FLAG_LONG_PRESS))
        sendToFocusedWindow(android.view.KeyEvent(0L, 550L, android.view.KeyEvent.ACTION_DOWN, code, 2))
        sendToFocusedWindow(android.view.KeyEvent(0L, 600L, android.view.KeyEvent.ACTION_UP, code, 0))
    }

    private fun sendToFocusedWindow(event: android.view.KeyEvent) {
        val focused = rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()
        val root = focused?.root ?: rule.onAllNodes(isRoot()).fetchSemanticsNodes().last().root
        checkNotNull(root) { "no window to send $event to" }
        rule.runOnUiThread { root.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(event)) }
        settle()
    }

    /** Runs a player callback (READY, ENDED, a failure) on the main thread, as ExoPlayer does. */
    protected fun onPlayer(block: FakePlayerController.() -> Unit) {
        rule.runOnUiThread { player.block() }
        settle()
    }

    /** [onPlayer] for the libVLC engine's fake. */
    protected fun onVlcPlayer(block: FakePlayerController.() -> Unit) {
        rule.runOnUiThread { vlcPlayer.block() }
        settle()
    }

    /** System / remote Back, delivered to the focused window (a dialog first, else the activity). */
    protected fun back() {
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        settle()
    }

    protected fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true)

    /** No node carries [tag] (e.g. a closed dialog). */
    protected fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    /** The bounds of [tag] in dp, `[left, top, right, bottom]`, in its own window's root (a dialog's window for dialog nodes). */
    protected fun dpBounds(tag: String): FloatArray {
        val n = node(tag).fetchSemanticsNode()
        val d = n.layoutInfo.density.density
        return floatArrayOf(n.positionInRoot.x / d, n.positionInRoot.y / d, (n.positionInRoot.x + n.size.width) / d, (n.positionInRoot.y + n.size.height) / d)
    }

    protected fun assertFocused(tag: String) {
        try {
            node(tag).assertIsFocused()
        } catch (e: AssertionError) {
            throw AssertionError("Expected focus on '$tag' but it is on ${focusedTags()}", e)
        }
    }

    /** Test tags of the focused nodes (and of their ancestors), for failure messages. */
    protected fun focusedTags(): List<String> = rule.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
        generateSequence(node) { it.parent }
            .mapNotNull { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }
            .joinToString(" < ")
            .ifEmpty { "(untagged ${node.config})" }
    }

    /**
     * The node [tag] lies entirely on screen: inside the screen width, its top at or below [minTop] dp
     * and its bottom above the 27 dp bottom safe area.
     */
    protected fun assertFullyVisible(tag: String, minTop: Float = 0f) {
        val node = node(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().size
        val left = node.positionInRoot.x / density
        val top = node.positionInRoot.y / density
        val right = left + node.size.width / density
        val bottom = top + node.size.height / density
        val safeBottom = root.height / density - HomeTvDimens.SafeVertical.value
        assertTrue("'$tag' spans x $left..$right dp, outside the ${root.width / density} dp screen", left >= 0f && right <= root.width / density)
        assertTrue("'$tag' starts at $top dp, above $minTop dp", top >= minTop - 0.5f)
        assertTrue("'$tag' ends at $bottom dp, below the safe area ($safeBottom dp)", bottom <= safeBottom + 0.5f)
    }

    /**
     * The scroll viewport [tag] runs down to the screen's bottom edge (decision C of
     * `specs/tv-improvements/idea.md`): no empty strip under it; the safe area is padding inside it.
     */
    protected fun assertReachesBottomEdge(tag: String) {
        val node = node(tag).fetchSemanticsNode()
        val density = node.layoutInfo.density.density
        val root = rule.onAllNodes(isRoot()).fetchSemanticsNodes().first().size
        val bottom = node.boundsInRoot.bottom / density
        val screen = root.height / density
        assertTrue("'$tag' ends at $bottom dp, ${screen - bottom} dp above the screen's bottom edge ($screen dp)", bottom >= screen - 0.5f)
    }

    /** [assertFullyVisible] with the top safe area too (top ≥ 27 dp). */
    protected fun assertInsideSafeArea(tag: String) = assertFullyVisible(tag, minTop = HomeTvDimens.SafeVertical.value)

    @After
    fun cancelScope() = scope.cancel()

    companion object {
        /** The 24 dp inner padding of the app's dialogs (`ConfirmDialog`, `RatingDialog`). */
        const val DIALOG_PADDING = 24f

        /**
         * Robolectric does not model a TV's dialog-window insets (they added ~77 dp on the emulator), so
         * a dialog must leave that much room inside the 27 dp vertical safe area.
         */
        const val DIALOG_WINDOW_INSETS = 80f
    }

    // ---- A small library ------------------------------------------------------------------

    protected val dune = FilmSummary(12, "Dune", 2021, progress = Progress(position_seconds = 1200.0, duration_seconds = 9360.0, media_file_id = 1))
    protected val alien = FilmSummary(13, "Alien", 1979)
    protected val amelie = FilmSummary(14, "Amélie", 2001)
    protected val office = ShowSummary(7, "The Office", 2005, unwatched_episode_count = 3)
    protected val olympics = EpisodeSummary(
        id = 203, show_id = 7, show_title = "The Office", season_number = 2, episode_number = 3, title = "Office Olympics",
        progress = Progress(position_seconds = 600.0, duration_seconds = 1320.0, media_file_id = 2), media_files = listOf(F.file(2)),
    )

    protected fun useLibrary() {
        api.onProfiles = { ProfileList(listOf(Profile(3, "Esther", "avatar-01"), Profile(5, "Javi", "avatar-02"), Profile(8, "Kids", "avatar-03"))) }
        api.onHome = { HomeResponse(continue_watching = listOf(dune, olympics), recently_added = listOf(office, alien)) }
        api.onFilms = { p, s, _, _ ->
            val all = listOf(dune, alien, amelie) + (20L until 32L).map { FilmSummary(it, "Film $it", 2000) }
            Page(all.drop((p - 1) * s).take(s), p, s, all.size)
        }
        api.onShows = { p, s, _, _ -> Page(listOf(office), p, s, 1) }
        api.onFilm = { id ->
            FilmDetail(id = id, title = "Film $id", year = 2021, runtime_minutes = 155, match_status = "matched", media_files = listOf(F.file(1)),
                cast = listOf(CastMember(1, "Timothée", "Paul")))
        }
        api.onShow = {
            ShowDetail(
                id = 7, title = "The Office", year = 2005, status = "Ended", unwatched_episode_count = 3, next_episode = olympics,
                seasons = listOf(SeasonSummary(1, 1, null, unwatched_episode_count = 1), SeasonSummary(2, 2, null, unwatched_episode_count = 2)),
            )
        }
        api.onSeason = { _, n ->
            SeasonDetail(n.toLong(), 7, "The Office", n, null, episodes = (1..3).map { e ->
                if (n == 2 && e == 3) olympics else EpisodeSummary(id = n * 100L + e, show_id = 7, show_title = "The Office", season_number = n, episode_number = e, title = "Episode $e")
            })
        }
        api.onSearch = { _, _ -> SearchResponse(films = listOf(dune, alien), shows = listOf(office)) }
    }
}
