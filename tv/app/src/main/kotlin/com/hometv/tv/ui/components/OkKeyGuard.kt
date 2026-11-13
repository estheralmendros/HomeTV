package com.hometv.tv.ui.components

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * Drops the OK key events of a press that did not start inside this subtree. tv-material clicks on
 * the OK release without checking that it saw the press, so the release of a long press that opened
 * another screen (or a dialog) would click whatever got focus there. A new [key] starts a new
 * tracker: the navigation host keys it by the current screen; a dialog a long press opens chains it
 * on its content (its own window).
 */
fun Modifier.okKeyGuard(key: Any? = Unit): Modifier = this then OkKeyGuardElement(key)

private data class OkKeyGuardElement(val key: Any?) : ModifierNodeElement<OkKeyGuardNode>() {
    override fun create() = OkKeyGuardNode()

    // Called only when [key] changed: forget the press seen under the previous key.
    override fun update(node: OkKeyGuardNode) {
        node.tracker = OkPressTracker()
    }
}

private class OkKeyGuardNode : Modifier.Node(), KeyInputModifierNode {
    var tracker = OkPressTracker()

    override fun onPreKeyEvent(event: ComposeKeyEvent): Boolean {
        val e = event.nativeKeyEvent
        val drop = tracker.drop(e)
        // TEMPORARY (long-press diagnosis): the guard's decision. Remove once diagnosed.
        if (e.keyCode in OK_KEYS_DIAG) {
            android.util.Log.i("HomeTvKeys", "  guard@${System.identityHashCode(tracker).toString(16)} ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} rc=${e.repeatCount} down=${e.downTime} drop=$drop")
        }
        return drop
    }

    private companion object {
        val OK_KEYS_DIAG = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }

    override fun onKeyEvent(event: ComposeKeyEvent): Boolean = false
}

/** True for the OK events of a press whose first down this tracker did not see. */
internal class OkPressTracker {
    private var pressed = false

    fun drop(event: KeyEvent): Boolean {
        if (event.keyCode !in OkKeyCodes) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) pressed = true
                !pressed
            }
            KeyEvent.ACTION_UP -> (!pressed).also { pressed = false }
            else -> false
        }
    }
}

/** tv-material's click keys (`AcceptableEnterClickKeys`: DPAD_CENTER, ENTER, NUMPAD_ENTER). */
internal val OkKeyCodes = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
