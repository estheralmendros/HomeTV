package com.hometv.tv.ui

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.testing.UiTestPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search is a lens icon at the left end of the top nav (before
 * "Inicio"), and the keyboard's search key closes the keyboard.
 */
@RunWith(AndroidJUnit4::class)
class SearchTabTest : UiTestBase() {
    private fun inSearchTab(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        rule.onAllNodes(matcher and hasAnyAncestor(hasTestTag("tab_SEARCH")), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun searchIsALensIconAtTheLeftEndBeforeInicio() {
        useLibrary()
        launch()
        val search = node("tab_SEARCH").fetchSemanticsNode().boundsInRoot
        val home = node("tab_HOME").fetchSemanticsNode().boundsInRoot
        assertTrue("the Search tab ($search) is left of Inicio ($home)", search.right <= home.left)
        assertTrue("no 'Buscar' text label on the Search tab", inSearchTab(hasText("Buscar")).isEmpty())
        assertTrue("the lens icon is described as 'Buscar'", inSearchTab(hasContentDescription("Buscar")).isNotEmpty())

        // Up from the rows lands on the current screen's tab; Left reaches the lens; OK opens Search.
        press(Key.DirectionUp)
        assertFocused("tab_HOME")
        press(Key.DirectionLeft)
        assertFocused("tab_SEARCH")
        press(Key.DirectionCenter)
        assertFocused("search_field")
    }

    @Test
    fun theKeyboardSearchKeyClosesTheKeyboard() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertFocused("search_field")
        node("search_field").performTextInput("dune")
        settle()
        assertTrue("typing in the field shows the keyboard", UiTestPlatform.isSoftKeyboardVisible())

        node("search_field").performImeAction()
        settle()
        assertFalse("the keyboard's search key closes the keyboard", UiTestPlatform.isSoftKeyboardVisible())
        assertFocused("search_field")
    }

    /** The view the system keyboard connects to (the Compose host view while a text field has input). */
    private fun textEditorView(root: View): View? {
        if (root.onCheckIsTextEditor()) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) textEditorView(root.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun theSearchFieldAsksTheKeyboardForSpanish() {
        useLibrary()
        launch()
        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertFocused("search_field")
        node("search_field").performTextInput("españa")
        settle()
        // The field's EditorInfo.hintLocales asks the IME for its Spanish layout (with "ñ").
        val info = EditorInfo()
        rule.runOnIdle {
            val view = requireNotNull(textEditorView(rule.activity.window.decorView)) { "no view is accepting text input" }
            requireNotNull(view.onCreateInputConnection(info)) { "no input connection for the search field" }
        }
        assertEquals("es-ES", info.hintLocales?.get(0)?.toLanguageTag())
    }
}
