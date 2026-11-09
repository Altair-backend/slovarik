package ru.slovarik.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Обёртка над системным синтезатором речи Android. */
class TtsHelper(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
    }

    /** Произносит текст на языке languageTag (например, "en" или "de"). */
    fun speak(text: String, languageTag: String) {
        if (!ready || text.isBlank()) return
        runCatching {
            val locale = Locale.forLanguageTag(languageTag)
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "slovarik")
        }
    }

    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
    }
}

@Composable
fun rememberTts(): TtsHelper {
    val context = LocalContext.current
    val tts = remember { TtsHelper(context) }
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }
    return tts
}
