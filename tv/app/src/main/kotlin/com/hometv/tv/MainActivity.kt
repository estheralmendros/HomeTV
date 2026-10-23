package com.hometv.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hometv.tv.ui.HomeTvRoot
import com.hometv.tv.ui.KeyDispatch

/**
 * Single activity. Every key event is first offered to [keyDispatch] (the Player registers its
 * handler there via `KeyEventInterceptor`), then to Compose focus as usual.
 * `ON_START` runs the proactive session refresh (prd3 §2.5; at launch it also replaces an expired token).
 */
class MainActivity : ComponentActivity() {
    val keyDispatch = KeyDispatch()

    private val container get() = (application as HomeTvApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeTvRoot(container, onExit = ::finish, keyDispatch = keyDispatch) }
    }

    override fun onStart() {
        super.onStart()
        container.onActivityStart()
    }

    // `Activity.dispatchKeyEvent` is public framework API; androidx.core's ComponentActivity only marks
    // its own override as library-restricted, which lint reports for any subclass override.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyDispatch.dispatch(event) || super.dispatchKeyEvent(event)
}
