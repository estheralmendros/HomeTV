package com.hometv.tv.testing

import androidx.test.platform.app.InstrumentationRegistry

/** Emulator: real time passes; wait it out and let the main thread settle. */
object UiTestPlatform {
    fun advance(millis: Long) {
        Thread.sleep(millis)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
