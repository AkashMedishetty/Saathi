package com.saathi.app.guide

import com.saathi.app.llm.LlmManager
import com.saathi.app.llm.Templates

/**
 * Talk, don't drive (the Clicky lesson): questions, greetings and chit-chat get a short spoken answer written for
 * the ear, with the last few exchanges as context, instead of being forced into a screen-navigation task.
 */
object Conversation {
    private val history = ArrayDeque<Pair<String, String>>()

    @Synchronized fun remember(user: String, saathi: String) {
        history.addLast(user.take(200) to saathi.take(300))
        while (history.size > 6) history.removeFirst()
    }

    @Synchronized fun recent(): List<Pair<String, String>> = history.toList()

    private fun system(lang: Lang, name: String) =
        "You are Saathi, a warm, patient companion for an elderly person in India${if (name.isNotBlank()) " named $name" else ""}. " +
            "You speak out loud, so write for the ear: 1 to 3 short, simple sentences, no lists, no markdown, no emojis. " +
            "Be kind and practical. For recipes or how-to questions, give the 2 or 3 key steps only. " +
            "Never give medical dosage or financial advice; suggest asking a doctor or family. " +
            "If you don't know, say so simply. " +
            when (lang) {
                Lang.EN -> "Answer in simple English."
                Lang.HI -> "Answer in simple Hindi, written in Devanagari script."
                Lang.TE -> "Answer in simple Telugu, written in Telugu script."
            }

    /** Returns a spoken answer, or a gentle template if the model is unavailable or answers badly. */
    suspend fun answer(q: String, lang: Lang, name: String, app: android.content.Context? = null): String {
        val ctx = recent().takeLast(4).joinToString("\n") { (u, s) -> "Person: $u\nSaathi: $s" }
        val prompt = (if (ctx.isNotBlank()) "Earlier:\n$ctx\n\n" else "") + "Person: $q\nSaathi:"
        // Gemma 4 (GPU) if it's up; otherwise the NPU brain answers now instead of "ask me again" (field test).
        val raw = if (LlmManager.isReady) LlmManager.generate(system(lang, name), prompt)
            else app?.let { com.saathi.app.llm.FastBrain.generate(it, system(lang, name), prompt) }
        val out = raw
            ?.replace(Regex("(?i)^saathi:\\s*"), "")?.replace(Regex("[*#_`]"), "")?.trim()
        val ok = !out.isNullOrBlank() && !Templates.garbled(out) && out.length < 600 && when (lang) {
            Lang.EN -> true
            Lang.HI -> out.any { it in 'ऀ'..'ॿ' }
            Lang.TE -> out.any { it in 'ఀ'..'౿' }
        }
        return if (ok) out!!.split(Regex("(?<=[.!?।])\\s+")).take(4).joinToString(" ") else fallback(q, lang)
    }

    private fun fallback(q: String, lang: Lang): String {
        if (IntentRouter.isGreeting(q)) return say("Namaste! I'm here. Tell me what you'd like to do on your phone.",
            "नमस्ते! मैं यहीं हूँ। बताइए, फ़ोन पर क्या करना है?", "నమస్కారం! నేను ఇక్కడే ఉన్నాను. ఫోన్‌లో ఏం చేయాలో చెప్పండి.").pick(lang)
        return say("I'm still waking up my brain. Ask me again in a moment.", "मैं अभी तैयार हो रहा हूँ। एक पल में फिर पूछिए।",
            "నేను ఇంకా సిద్ధమవుతున్నాను. ఒక్క క్షణంలో మళ్ళీ అడగండి.").pick(lang)
    }
}
