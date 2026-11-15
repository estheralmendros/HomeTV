package com.hometv.tv.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hometv.tv.R

/** Starts the system speech recognizer; null when the device has none (the mic button is hidden). */
fun interface VoiceInput {
    fun start()
}

object VoiceIntents {
    /** Free-form recognition in es-ES (the UI language), prompt "Buscar películas y series". */
    fun recognize(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    const val LANGUAGE = "es-ES"
}

@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput? {
    val context = LocalContext.current
    val available = remember {
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }
    val prompt = stringResource(R.string.search_voice_prompt)
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { callback(it) }
        }
    }
    if (!available) return null
    return remember(launcher, prompt) { VoiceInput { runCatching { launcher.launch(VoiceIntents.recognize(prompt)) } } }
}
