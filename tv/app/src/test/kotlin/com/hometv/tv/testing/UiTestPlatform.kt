package com.hometv.tv.testing

import android.content.Context
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/** Robolectric: advance the paused main looper's clock (ViewModel `delay`s, debounces). */
object UiTestPlatform {
    fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    /** Whether the on-screen keyboard is shown (Robolectric's `InputMethodManager` records show/hide). */
    fun isSoftKeyboardVisible(): Boolean =
        shadowOf(ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)).isSoftInputVisible
}
