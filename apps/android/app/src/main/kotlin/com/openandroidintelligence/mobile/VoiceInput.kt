package com.openandroidintelligence.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import java.util.Locale

/** Launching an Activity does not require package-query visibility on Android 11+. */
internal fun launchVoiceInput(launch: (Intent) -> Unit, onUnavailable: () -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出要发送的内容")
    }
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
    }
}
