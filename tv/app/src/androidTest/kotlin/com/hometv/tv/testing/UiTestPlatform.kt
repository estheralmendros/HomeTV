package com.hometv.tv.testing

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Emulator: real time passes; wait it out and let the main thread settle. */
object UiTestPlatform {
    fun advance(millis: Long) {
        Thread.sleep(millis)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Whether the on-screen keyboard is shown, from the resumed activity's window insets. */
    fun isSoftKeyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            val insets = activity?.window?.decorView?.let(ViewCompat::getRootWindowInsets)
            visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }
}
