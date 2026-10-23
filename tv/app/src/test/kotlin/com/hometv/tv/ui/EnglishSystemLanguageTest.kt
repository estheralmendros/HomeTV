package com.hometv.tv.ui

import java.util.Locale
import org.junit.AfterClass
import org.junit.BeforeClass
import org.robolectric.annotation.Config

/** The same screens with the TV (and the JVM) in en-US: texts stay Spanish, formatting stays es-ES. */
@Config(qualifiers = "en-rUS-w960dp-h540dp-land-television-xhdpi")
class EnglishSystemLanguageTest : LanguageAndErrorsTest() {
    companion object {
        private var previous: Locale? = null

        @JvmStatic
        @BeforeClass
        fun english() {
            previous = Locale.getDefault()
            Locale.setDefault(Locale.US)
        }

        @JvmStatic
        @AfterClass
        fun restore() {
            previous?.let(Locale::setDefault)
        }
    }
}
