package com.hometv.tv.ui.home

import android.util.Log

/**
 * TEMPORARY (Home entry focus diagnosis: a new film is not focused when coming back to the app).
 * Read with `adb logcat -s HomeTvFocus`. Remove once diagnosed.
 */
internal fun focusLog(message: String) {
    runCatching { Log.i("HomeTvFocus", message) }
}
