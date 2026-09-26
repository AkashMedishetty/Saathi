package com.saathi.app.guide

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.saathi.app.llm.LlmManager

/**
 * Understanding, not clicking (field test: a 2B model can't navigate unknown apps step by step, and nobody should
 * have to phrase exact commands). Gemma turns a vague request into a small structured intent; Android intents /
 * deep links jump straight to the right screen; the glow only teaches the last taps.
 */
object Understand {
    data class Intent2(
        val intent: String,     // weather | lookup | watch | music | call | video_call | message | photo | alarm | reminder |
                                // directions | open_app | setting | question | tv | other
        val query: String? = null,
        val app: String? = null,
        val device: String = "phone",
        val person: String? = null,
    )

    private const val SYSTEM =
        "You understand what an elderly person in India wants from their phone, even when they say it vaguely or in Hindi/Telugu. " +
        "Reply with exactly one line of key=value pairs separated by ' | ', nothing else.\n" +
        "Keys: INTENT (one of: book, weather, lookup, watch, music, call, video_call, message, photo, alarm, reminder, directions, open_app, setting, question, tv, other), " +
        "QUERY (what to search/play/find, in English, short), APP (app name if they said one, else none), DEVICE (tv or phone), PERSON (who, if any, else none).\n" +
        "lookup = live facts from the internet (cricket score, gold price, news, train status, prices). question = general knowledge or advice. " +
        "watch = a movie/serial/video. music = songs/bhajans. book = booking tickets (train, bus, flight, movie).\n" +
        "Examples:\n" +
        "will it rain today -> INTENT=weather | QUERY=will it rain today | APP=none | DEVICE=phone | PERSON=none\n" +
        "guntur karam movie on tv -> INTENT=watch | QUERY=Guntur Kaaram | APP=none | DEVICE=tv | PERSON=none\n" +
        "India match score -> INTENT=lookup | QUERY=India cricket score | APP=none | DEVICE=phone | PERSON=none\n" +
        "बेटे से बात करनी है -> INTENT=video_call | QUERY=none | APP=none | DEVICE=phone | PERSON=son\n" +
        "hanuman chalisa sunao -> INTENT=music | QUERY=Hanuman Chalisa | APP=none | DEVICE=phone | PERSON=none\n" +
        "how to make upma -> INTENT=question | QUERY=how to make upma | APP=none | DEVICE=phone | PERSON=none"

    /** Which brain understood the last request ("NPU" = Gemma 3 1B on the Hexagon NPU, "GPU" = Gemma 4). */
    @Volatile var lastBrain = ""

    /** Which intents a keyword-matched skill is compatible with (used to trust the fast NPU answer). */
    private val COMPATIBLE = mapOf(
        "font" to "setting", "volume" to "setting", "wifi" to "setting", "bluetooth" to "setting", "brightness" to "setting",
        "battery" to "setting", "storage" to "setting", "storage_view" to "setting", "dark_mode" to "setting", "internet" to "setting",
        "torch" to "setting|other", "camera" to "photo|other", "call" to "call", "wa_video" to "video_call", "wa_message" to "message",
        "wa_photo" to "photo|message", "youtube" to "music|watch", "ott" to "watch", "maps" to "directions", "alarm" to "alarm|reminder",
        "medicine" to "alarm|reminder", "irctc_tatkal" to "book", "tv" to "tv|watch")

    /**
     * Hybrid brain. The NPU (Gemma 3 1B, ≈0.25–0.6 s) answers first; its answer is used when it agrees with the keyword
     * skill or is a clear lookup/watch/music/directions intent. Otherwise (disagreement, or "question", which the 1B
     * model over-uses) Gemma 4 on the GPU double-checks. Measured: NPU alone 83%, GPU alone 94%.
     */
    private val MEDIA = Regex("(?i)\\b(videos?|songs?|movies?|films?|serials?|bhajans?|cartoons?)\\b|वीडियो|गाने|गाना|फ़िल्म|फिल्म|భజన|పాటలు|పాట|సినిమా|వీడియోలు")
    private val CALLING = Regex("(?i)\\bcall|\\btalk|\\bspeak|कॉल|बात|కాల్|మాట్లాడ")

    /** "show me minecraft videos" is watching, not a video call (the 1B model mixed them up in the field). */
    private fun fixMedia(goal: String, r: Intent2?): Intent2? {
        if (r == null || !MEDIA.containsMatchIn(goal) || CALLING.containsMatchIn(goal)) return r
        if (r.intent !in setOf("video_call", "photo", "message", "other", "open_app", "question")) return r
        val music = Regex("(?i)songs?|bhajans?|गाने|गाना|పాట").containsMatchIn(goal)
        val q = goal.replace(Regex("(?i)^\\s*(please\\s+)?(show me|play|put on|watch|i want to (watch|see|hear))\\s+"), "").trim()
        return r.copy(intent = if (music) "music" else "watch", query = q.ifBlank { r.query }, person = null)
    }

    suspend fun parse(goal: String, ctx: Context? = null): Intent2? = fixMedia(goal, parseRaw(goal, ctx))

    private suspend fun parseRaw(goal: String, ctx: Context? = null): Intent2? {
        val npu = parseLine(ctx?.let { com.saathi.app.llm.FastBrain.generate(it, SYSTEM, "$goal ->") })
        if (npu != null) {
            val kw = Skills.match(goal)?.id
            val agrees = kw != null && COMPATIBLE[kw]?.split('|')?.contains(npu.intent) == true
            val clear = kw == null && npu.intent in setOf("weather", "lookup", "watch", "music", "directions", "book") && npu.query != null
            if (agrees || clear) { lastBrain = "NPU"; return npu }
        }
        if (!LlmManager.isReady) { lastBrain = if (npu != null) "NPU" else ""; return npu }
        lastBrain = "NPU→GPU"
        return parseLine(LlmManager.generate(SYSTEM, "$goal ->")) ?: npu
    }

    private fun parseLine(out: String?): Intent2? {
        out ?: return null
        val kv = out.lines().firstOrNull { it.contains("INTENT", true) }?.split("|")?.mapNotNull {
            val p = it.split("=", limit = 2); if (p.size == 2) p[0].trim().uppercase() to p[1].trim() else null
        }?.toMap() ?: return null
        fun v(k: String) = kv[k]?.takeIf { it.isNotBlank() && !it.equals("none", true) }
        val intent = v("INTENT")?.lowercase() ?: return null
        return Intent2(intent, v("QUERY"), v("APP"), if (v("DEVICE").equals("tv", true)) "tv" else "phone", v("PERSON"))
    }

    // ── Deep links: straight to the right screen ──

    /** Google search results for live facts (weather, scores, prices). */
    fun webSearch(ctx: Context, q: String): Intent =
        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).let { i ->
            if (i.resolveActivity(ctx.packageManager) != null) i
            else Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        }

    /** YouTube's own search results for [q] (no taps needed). */
    fun youtubeSearch(ctx: Context, q: String): Intent? =
        Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
            .takeIf { it.resolveActivity(ctx.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).setPackage("com.google.android.youtube")
                .takeIf { it.resolveActivity(ctx.packageManager) != null }

    /** Netflix search (falls back to the app if its deep link isn't handled). */
    fun netflixSearch(ctx: Context, q: String): Intent? =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/search?q=" + Uri.encode(q))).setPackage("com.netflix.mediaclient")
            .takeIf { it.resolveActivity(ctx.packageManager) != null }
}
