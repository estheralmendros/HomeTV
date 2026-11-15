package com.hometv.tv.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions

/** Long-press OK on a tv-material Surface/Card (its `onLongClick` semantics action). */
fun SemanticsNodeInteraction.performLongClickCompat(rule: ComposeTestRule): SemanticsNodeInteraction {
    performSemanticsAction(SemanticsActions.OnLongClick)
    rule.waitForIdle()
    return this
}
