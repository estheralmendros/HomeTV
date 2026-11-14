package com.hometv.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hometv.tv.ui.components.requestFocusWhenReady
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `requestFocusWhenReady` waits frames for its target; a newer target meanwhile must win (Home's cold start). */
@RunWith(AndroidJUnit4::class)
class RequestFocusWhenReadyTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var showLate by mutableStateOf(false)

    private fun setContent(stillWanted: () -> Boolean) {
        rule.setContent {
            val current = remember { FocusRequester() }
            val late = remember { FocusRequester() }
            LaunchedEffect(Unit) { current.requestFocus() }
            LaunchedEffect(showLate) { if (showLate) late.requestFocusWhenReady(stillWanted = stillWanted) }
            Column {
                Column(Modifier.size(10.dp).focusRequester(current).focusable().testTag("current")) {}
                if (showLate) Column(Modifier.size(10.dp).focusRequester(late).focusable().testTag("late")) {}
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("current").assertIsFocused()
    }

    @Test
    fun aRequestThatIsNoLongerWantedNeverLands() {
        setContent(stillWanted = { false })
        showLate = true
        rule.waitForIdle()
        rule.onNodeWithTag("late").assertIsNotFocused()
        rule.onNodeWithTag("current").assertIsFocused()
    }

    @Test
    fun aWantedRequestLands() {
        setContent(stillWanted = { true })
        showLate = true
        rule.waitForIdle()
        rule.onNodeWithTag("late").assertIsFocused()
    }
}
