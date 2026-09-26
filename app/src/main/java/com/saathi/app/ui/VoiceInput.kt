package com.saathi.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.saathi.app.guide.Lang

/**
 * Inline speech recognition: live words, a level meter, no Google popup.
 * Prefers the ON-DEVICE recogniser; if it lacks the language (hi/te often), retries once with the
 * phone's default recogniser + PREFER_OFFLINE (trap #27). Saathi itself still has no internet permission.
 * Runs inside an Activity, where the mic is reliably allowed (trap #26).
 */
class VoiceInput(private val ctx: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onLevel(level: Float) // 0..1
        fun onFinal(text: String?)
        fun onError(message: String) {}
    }

    private var sr: SpeechRecognizer? = null
    var listening = false; private set

    fun hasPermission() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

    /** One way of listening. The recogniser app does any networking, never Saathi. */
    private data class Try(val onDevice: Boolean, val tag: String, val offline: Boolean)

    private fun prefs() = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE)

    /**
     * Field test: on-device packs for en-IN / te-IN weren't installed (errors 12/13), and switching recognisers every
     * 150 ms made Google's service drop us (error 11). So: start with what worked last time for this language, try
     * each way once with a proper pause between, and let the caller fall back to Google's own voice popup.
     */
    private fun plan(lang: Lang): List<Try> {
        val od = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        val all = buildList {
            if (od) add(Try(true, lang.tag, true))
            add(Try(false, lang.tag, false))
            if (lang == Lang.EN) add(Try(false, "en-US", false))
        }
        val good = prefs().getString("stt_ok_${lang.name}", null)
        return all.sortedByDescending { it.toString() == good }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** True when every inline way failed: the caller should open the system voice popup instead. */
    var exhausted = false; private set

    fun start(lang: Lang, l: Listener, attempt: Int = 0) {
        stop()
        exhausted = false
        val tries = plan(lang)
        val t = tries.getOrNull(attempt) ?: run { exhausted = true; l.onFinal(null); return }
        val onDevice = t.onDevice
        val r = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        }.getOrElse { start(lang, l, attempt + 1); return }
        var lastPartial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { l.onLevel(0f) }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { lastPartial = it; l.onPartial(it) }
            }
            override fun onResults(b: Bundle?) {
                listening = false
                prefs().edit().putString("stt_ok_${lang.name}", t.toString()).apply()
                com.saathi.app.DebugLog.i("voice", "heard \"${b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial}\" via $t")
                l.onFinal(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: lastPartial.ifBlank { null })
            }
            override fun onError(error: Int) {
                listening = false
                Log.w("Saathi", "speech error $error (try $attempt: $t)")
                com.saathi.app.DebugLog.i("voice", "error $error try=$attempt $t partial=${lastPartial.isNotBlank()}")
                // Never restart inside the dying recogniser's callback (gives error 11): post it, with a real pause.
                if (lastPartial.isBlank() && error in RETRYABLE) {
                    if (attempt + 1 < tries.size) { main.postDelayed({ start(lang, l, attempt + 1) }, 450); return }
                    exhausted = true
                }
                if (lastPartial.isNotBlank()) l.onFinal(lastPartial) else l.onFinal(null)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, t.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, t.tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (t.offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        })
        sr = r
        listening = true
    }

    fun stopListening() { runCatching { sr?.stopListening() } }

    fun stop() { runCatching { sr?.destroy() }; sr = null; listening = false }

    /** Android 13+: pre-download the on-device model for a language (do this on Wi-Fi, before airplane mode). */
    fun downloadModel(lang: Lang) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return
        runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).triggerModelDownload(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag))
        }
    }

    private companion object {
        val RETRYABLE = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
    }
}
