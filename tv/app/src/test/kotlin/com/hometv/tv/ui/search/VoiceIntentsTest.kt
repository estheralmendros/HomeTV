package com.hometv.tv.ui.search

import android.speech.RecognizerIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** prd3 §5.2 / §UI Language rule 8: free-form recognition in es-ES with the catalog prompt. */
@RunWith(AndroidJUnit4::class)
class VoiceIntentsTest {
    @Test
    fun recognizesSpanishFreeFormWithThePrompt() {
        val intent = VoiceIntents.recognize("Buscar películas y series")
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("es-ES", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("Buscar películas y series", intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT))
    }
}
