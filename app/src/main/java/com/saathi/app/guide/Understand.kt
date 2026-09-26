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

    suspend fun parse(goal: String): Intent2? {
        if (!LlmManager.isReady) return null
        val out = LlmManager.generate(SYSTEM, "$goal ->") ?: return null
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
