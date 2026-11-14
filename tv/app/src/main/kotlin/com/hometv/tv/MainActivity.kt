package com.hometv.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hometv.tv.ui.HomeTvRoot
import com.hometv.tv.ui.KeyDispatch
import com.hometv.tv.ui.home.focusLog

/**
 * Single activity. Every key event is first offered to [keyDispatch] (the Player registers its
 * handler there via `KeyEventInterceptor`), then to Compose focus as usual.
 * `ON_START` runs the proactive session refresh (at launch it also replaces an expired token).
 */
class MainActivity : ComponentActivity() {
    val keyDispatch = KeyDispatch()

    private val container get() = (application as HomeTvApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focusLog("activity onCreate savedState=${savedInstanceState != null} instance=${System.identityHashCode(this)}")
        setContent { HomeTvRoot(container, onExit = ::finish, keyDispatch = keyDispatch) }
    }

    override fun onStart() {
        super.onStart()
        focusLog("activity onStart")
        container.onActivityStart()
    }

    // TEMPORARY (Home entry focus diagnosis, `adb logcat -s HomeTvFocus`): the activity's lifecycle and window focus.
    override fun onResume() {
        super.onResume()
        focusLog("activity onResume")
    }

    override fun onPause() {
        focusLog("activity onPause")
        super.onPause()
    }

    override fun onStop() {
        focusLog("activity onStop")
        super.onStop()
    }

    override fun onDestroy() {
        focusLog("activity onDestroy finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focusLog("activity windowFocus=$hasFocus")
    }

    // `Activity.dispatchKeyEvent` is public framework API; androidx.core's ComponentActivity only marks
    // its own override as library-restricted, which lint reports for any subclass override.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = keyDispatch.dispatch(event) || super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) focusLog("key ${KeyEvent.keyCodeToString(event.keyCode)}")
        // TEMPORARY (long-press diagnosis): every OK event as the app receives it. Remove once diagnosed.
        if (event.keyCode in DIAG_OK_KEYS) {
            android.util.Log.i(
                "HomeTvKeys",
                "${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else if (event.action == KeyEvent.ACTION_UP) "UP" else "A${event.action}"} " +
                    "code=${event.keyCode} rc=${event.repeatCount} flags=0x${Integer.toHexString(event.flags)} " +
                    "down=${event.downTime} t=${event.eventTime} dev=${event.deviceId} src=0x${Integer.toHexString(event.source)} handled=$handled",
            )
        }
        return handled
    }

    private companion object {
        val DIAG_OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT)
    }
}
