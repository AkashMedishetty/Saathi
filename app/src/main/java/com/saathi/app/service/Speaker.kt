package com.saathi.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs
import java.util.Locale

/** Offline text-to-speech in English / Hindi / Telugu, a little slower for elderly listeners. */
class Speaker(private val ctx: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx.applicationContext, this)
    private var ready = false
    private var pending: Pair<String, Lang>? = null
    /** Called on the TTS thread with true when speech starts, false when it ends. */
    var onSpeaking: ((Boolean) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { onSpeaking?.invoke(true) }
            override fun onDone(id: String?) { onSpeaking?.invoke(false) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { onSpeaking?.invoke(false) }
            override fun onStop(id: String?, interrupted: Boolean) { onSpeaking?.invoke(false) }
        })
        pending?.let { (t, l) -> pending = null; say(t, l) }
    }

    fun say(text: String, lang: Lang) {
        if (text.isBlank()) return
        if (!ready) { pending = text to lang; return }
        tts.setSpeechRate(Prefs.speechRate(ctx))
        val r = tts.setLanguage(lang.locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.forLanguageTag("en-IN"))
        bestVoice(lang)?.let { if (tts.voice != it) tts.voice = it }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saathi")
    }

    private val chosen = HashMap<Lang, android.speech.tts.Voice?>()

    /** The most natural installed offline voice for the language (never a network voice: text stays on the phone). */
    private fun bestVoice(lang: Lang): android.speech.tts.Voice? = chosen.getOrPut(lang) {
        runCatching {
            tts.voices?.filter { v -> v.locale.language == lang.locale.language && !v.isNetworkConnectionRequired &&
                v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == lang.locale.country }.thenByDescending { it.quality }.thenBy { it.latency })
                ?.firstOrNull()
        }.getOrNull()
    }

    fun supports(lang: Lang) = ready && tts.isLanguageAvailable(lang.locale) >= TextToSpeech.LANG_AVAILABLE
    fun stop() { tts.stop() }
    fun shutdown() = tts.shutdown()
}
