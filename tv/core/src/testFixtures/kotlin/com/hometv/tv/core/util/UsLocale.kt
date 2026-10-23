package com.hometv.tv.core.util

import java.util.Locale
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Runs a test class with the JVM default locale set to en-US (an English TV): output must stay es-ES. */
class UsLocale : BeforeAllCallback, AfterAllCallback {
    private var previous: Locale? = null

    override fun beforeAll(context: ExtensionContext) {
        previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    override fun afterAll(context: ExtensionContext) {
        previous?.let(Locale::setDefault)
    }
}
