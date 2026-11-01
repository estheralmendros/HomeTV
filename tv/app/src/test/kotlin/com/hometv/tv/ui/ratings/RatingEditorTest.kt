package com.hometv.tv.ui.ratings

import com.hometv.tv.core.api.ApiException
import com.hometv.tv.core.data.FakeHomeTvApi.Companion.httpError
import com.hometv.tv.core.data.RatingKind
import com.hometv.tv.core.util.Stars
import com.hometv.tv.testing.TestRepos
import com.hometv.tv.ui.ratings.RatingDialogUi.Mode
import com.hometv.tv.ui.ratings.RatingDialogUi.Phase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ratings prd3 FR-T9: the dialog's state holder shared by the root host and the detail screens. */
class RatingEditorTest {
    private val dispatcher = StandardTestDispatcher()
    private val repos = TestRepos(appScope = CoroutineScope(SupervisorJob() + dispatcher))
    private val api = repos.api

    private val saved = mutableListOf<Stars>()
    private val saving = mutableListOf<Stars>()
    private val failed = mutableListOf<Pair<ApiException, Boolean>>()
    private val gone = mutableListOf<Throwable>()
    private var removing = 0
    private var removed = 0
    private var closed = 0

    private fun TestScope.editor(
        mode: Mode = Mode.PROMPT,
        initial: Stars? = null,
        closesOn: (Throwable) -> Boolean = RatingEditor::detailCloses,
    ) = RatingEditor(
        kind = RatingKind.FILM, id = 12, mode = mode, title = "Dune", poster = "/p.jpg", initial = initial,
        ratings = repos.ratings, scope = backgroundScope,
        onSaving = { saving += it }, onSaved = { saved += it }, onRemoving = { removing++ }, onRemoved = { removed++ },
        onFailed = { e, removal -> failed += e to removal }, onGone = { gone += it }, onClosed = { closed++ },
        closesOn = closesOn,
    )

    private fun RatingEditor.phase() = ui.value?.phase

    /** Gates every rating write until [open] completes. */
    private fun gate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { g -> api.onRatingWrite = { _, _, _ -> g.await() } }

    @Test
    fun `opens on the initial value in editing`() = runTest(dispatcher) {
        val e = editor(Mode.MANUAL, initial = Stars(8))
        assertEquals(RatingDialogUi(Mode.MANUAL, "Dune", "/p.jpg", Stars(8), canRemove = true, phase = Phase.EDITING), e.ui.value)
        assertEquals(false, editor(Mode.MANUAL).ui.value?.canRemove)
        assertEquals(false, editor(Mode.PROMPT, initial = Stars(8)).ui.value?.canRemove)
    }

    @Test
    fun `steps are halves, empty gives one half either way, clamped to one half and five`() = runTest(dispatcher) {
        val e = editor()
        e.step(-1)
        assertEquals(Stars(1), e.ui.value?.stars)
        e.step(-1)
        assertEquals(Stars(1), e.ui.value?.stars)
        val f = editor()
        f.step(1)
        assertEquals(Stars(1), f.ui.value?.stars)
        f.step(1)
        assertEquals(Stars(2), f.ui.value?.stars)
        val g = editor(Mode.MANUAL, initial = Stars(10))
        g.step(1)
        assertEquals(Stars(10), g.ui.value?.stars)
    }

    @Test
    fun `save without a value does nothing`() = runTest(dispatcher) {
        val e = editor()
        e.save()
        advanceUntilIdle()
        assertEquals(Phase.EDITING, e.phase())
        assertEquals(0, api.count("PUT /api/ratings/films/12"))
    }

    @Test
    fun `save goes SAVING at once, then SAVED, and closes after one and a half seconds`() = runTest(dispatcher) {
        val e = editor()
        e.step(1)
        e.step(1)
        e.save()
        assertEquals(Phase.SAVING, e.phase())
        assertEquals(listOf(Stars(2)), saving)
        runCurrent()
        assertEquals(Phase.SAVED, e.phase())
        assertEquals(Stars(2), e.ui.value?.stars)
        assertEquals(listOf(Stars(2)), saved)
        advanceTimeBy(RatingEditor.SAVED_MILLIS - 1)
        assertNotNull(e.ui.value)
        advanceTimeBy(2)
        assertNull(e.ui.value)
        assertEquals(1, closed)
        assertEquals(1, api.count("PUT /api/ratings/films/12"))
        assertEquals(0, api.count("POST /api/ratings/films/12/dismiss-prompt"))
    }

    @Test
    fun `Back in SAVED closes at once with no request`() = runTest(dispatcher) {
        val e = editor()
        e.step(1)
        e.save()
        runCurrent()
        e.dismiss()
        assertNull(e.ui.value)
        assertEquals(1, closed)
        advanceUntilIdle()
        assertEquals(1, closed, "the timer was cancelled")
        assertEquals(listOf("PUT /api/ratings/films/12"), api.calls)
    }

    @Test
    fun `a double OK, OK then Guardar, or a second editor of the same title send one PUT`() = runTest(dispatcher) {
        val open = gate()
        val e = editor()
        e.step(1)
        e.save()
        e.save()
        runCurrent()
        val other = editor(Mode.MANUAL, initial = Stars(4))
        other.save()
        other.step(1)
        other.remove()
        assertEquals(Phase.EDITING, other.phase())
        assertEquals(Stars(4), other.ui.value?.stars)
        assertEquals(1, api.calls.size)
        open.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("PUT /api/ratings/films/12"), api.calls)
    }

    @Test
    fun `while SAVING, step, remove, Back and Ahora no do nothing`() = runTest(dispatcher) {
        val open = gate()
        val e = editor()
        e.step(1)
        e.save()
        runCurrent()
        e.step(1)
        e.dismiss()
        e.secondary()
        assertEquals(Phase.SAVING, e.phase())
        assertEquals(Stars(1), e.ui.value?.stars)
        assertEquals(0, closed)
        open.complete(Unit)
        runCurrent()
        assertEquals(Phase.SAVED, e.phase())
        assertEquals(listOf("PUT /api/ratings/films/12"), api.calls)

        val m = editor(Mode.MANUAL, initial = Stars(4))
        val open2 = gate()
        m.save()
        runCurrent()
        m.remove()
        m.dismiss()
        m.secondary()
        assertEquals(Phase.SAVING, m.phase())
        open2.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, api.count("DELETE /api/ratings/films/12"))
    }

    @Test
    fun `a failure keeps the value in FAILED, a retry saves, Ahora no sends one dismissal`() = runTest(dispatcher) {
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        val e = editor()
        e.step(1)
        e.save()
        runCurrent()
        assertEquals(Phase.FAILED, e.phase())
        assertEquals(Stars(1), e.ui.value?.stars)
        assertEquals(1, failed.size)
        assertEquals(false, failed.single().second)
        e.secondary()
        assertNull(e.ui.value)
        runCurrent()
        assertEquals(1, api.count("POST /api/ratings/films/12/dismiss-prompt"))

        api.onRatingWrite = { _, _, _ -> }
        val r = editor()
        r.step(1)
        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        r.save()
        runCurrent()
        api.onRatingWrite = { _, _, _ -> }
        r.save()
        runCurrent()
        assertEquals(Phase.SAVED, r.phase())
    }

    @Test
    fun `prompt Back is Ahora no, manual Back and Cancelar send nothing`() = runTest(dispatcher) {
        val p = editor()
        p.dismiss()
        runCurrent()
        assertNull(p.ui.value)
        assertEquals(1, api.count("POST /api/ratings/films/12/dismiss-prompt"))
        val m = editor(Mode.MANUAL, initial = Stars(4))
        m.dismiss()
        val c = editor(Mode.MANUAL)
        c.secondary()
        runCurrent()
        assertEquals(listOf("POST /api/ratings/films/12/dismiss-prompt"), api.calls)
        assertEquals(3, closed)
    }

    @Test
    fun `remove closes at once and awaits the delete`() = runTest(dispatcher) {
        val open = gate()
        val e = editor(Mode.MANUAL, initial = Stars(8))
        e.remove()
        assertNull(e.ui.value)
        assertEquals(1, closed)
        assertEquals(1, removing)
        runCurrent()
        assertTrue(repos.ratings.isWriting(RatingKind.FILM, 12))
        open.complete(Unit)
        runCurrent()
        assertEquals(1, removed)
        assertEquals(listOf("DELETE /api/ratings/films/12"), api.calls)

        api.onRatingWrite = { _, _, _ -> throw httpError(500, "internal_error") }
        val f = editor(Mode.MANUAL, initial = Stars(8))
        f.remove()
        runCurrent()
        assertEquals(true, failed.single().second)
    }

    @Test
    fun `remove needs a rated manual dialog`() = runTest(dispatcher) {
        editor(Mode.MANUAL).remove()
        editor(Mode.PROMPT, initial = Stars(3)).remove()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `detail policy closes on 404, a 401 after the renewal and a gone profile`() = runTest(dispatcher) {
        api.onRatingWrite = { _, _, _ -> throw httpError(404, "not_found") }
        val notFound = editor().apply { step(1); save() }
        runCurrent()
        assertNull(notFound.ui.value)
        assertTrue((gone.single() as ApiException.Http).status == 404)

        api.onRatingWrite = { _, _, _ -> throw httpError(401, "unauthorized") }
        val unauthorized = editor().apply { step(1); save() }
        runCurrent()
        assertNull(unauthorized.ui.value)
        assertTrue(gone.last() is ApiException.Unauthorized)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `host policy keeps a 401 after the renewal as FAILED and closes on 404 and a gone profile`() = runTest(dispatcher) {
        api.onRatingWrite = { _, _, _ -> throw httpError(401, "unauthorized") }
        val unauthorized = editor(closesOn = RatingEditor::hostCloses).apply { step(1); save() }
        runCurrent()
        assertEquals(Phase.FAILED, unauthorized.phase())
        assertTrue(failed.single().first is ApiException.Unauthorized)
        assertTrue(gone.isEmpty())
        assertEquals(2, api.count("PUT /api/ratings/films/12"), "renewed once, retried once")

        api.onCreateSession = { throw httpError(404, "profile_not_found") }
        val profileGone = editor(closesOn = RatingEditor::hostCloses)
        profileGone.step(1)
        api.calls.clear()
        // A fresh title so the failed one above does not count.
        profileGone.save()
        runCurrent()
        assertNull(profileGone.ui.value)
        assertTrue(gone.single() is ApiException.ProfileGone)

        api.onRatingWrite = { _, _, _ -> throw httpError(404, "not_found") }
        val notFound = editor(closesOn = RatingEditor::hostCloses).apply { step(1); save() }
        runCurrent()
        assertNull(notFound.ui.value)
        assertEquals(2, gone.size)
    }

    @Test
    fun `a detached editor ignores the late outcome`() = runTest(dispatcher) {
        val open = gate()
        val e = editor()
        e.step(1)
        e.save()
        runCurrent()
        e.detach()
        assertNull(e.ui.value)
        open.complete(Unit)
        advanceUntilIdle()
        assertNull(e.ui.value)
        assertTrue(saved.isEmpty())
        assertEquals(0, closed)
        assertEquals(1, api.count("PUT /api/ratings/films/12"), "the write still completes")
    }
}
