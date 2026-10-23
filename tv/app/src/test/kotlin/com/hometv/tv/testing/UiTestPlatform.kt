package com.hometv.tv.testing

import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/** Robolectric: advance the paused main looper's clock (ViewModel `delay`s, debounces). */
object UiTestPlatform {
    fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }
}
