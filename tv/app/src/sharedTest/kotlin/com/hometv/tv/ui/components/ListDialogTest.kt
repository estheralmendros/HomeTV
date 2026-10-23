package com.hometv.tv.ui.components

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.testing.UiTestBase
import com.hometv.tv.ui.theme.HomeTvTheme
import org.junit.Test
import org.junit.runner.RunWith

/** The single-choice list dialog: the current entry is checked; `selected = -1` (no current entry) checks none. */
@RunWith(AndroidJUnit4::class)
class ListDialogTest : UiTestBase() {
    private fun show(selected: Int) {
        rule.setContent { HomeTvTheme { ListDialog("Versiones", listOf("2160p", "1080p"), selected, onSelect = {}, onDismiss = {}) } }
        settle()
    }

    @Test
    fun theCurrentEntryIsSelectedAndFocused() {
        show(1)
        node("dialog_item_1").assertIsSelected()
        node("dialog_item_0").assertIsNotSelected()
        assertFocused("dialog_item_1")
    }

    @Test
    fun withoutACurrentEntryNothingIsSelectedAndTheFirstEntryHasFocus() {
        show(-1)
        node("dialog_item_0").assertIsNotSelected()
        node("dialog_item_1").assertIsNotSelected()
        assertFocused("dialog_item_0")
    }
}
