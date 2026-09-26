package com.saathi.app.guide

/**
 * Decides what to tap when no scripted step matches. P0-2: keyword heuristic only.
 * P0-4 puts the on-device LLM in front of it (the heuristic stays as the fallback).
 */
object Planner {
    data class Decision(val targetId: Int?, val say: String, val done: Boolean, val fromLlm: Boolean)

    suspend fun decide(goal: String, screen: Screen, history: List<String>, lang: Lang): Decision = heuristic(goal, screen, lang)

    fun heuristic(goal: String, screen: Screen, lang: Lang): Decision {
        val words = goal.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }
        val best = screen.elements.filter { it.role != "text" && it.enabled }
            .maxByOrNull { e -> words.count { it in e.label.lowercase() } }
            ?.takeIf { e -> words.any { it in e.label.lowercase() } }
        if (best != null) return Decision(best.id, tapSay(best.title, lang), done = false, fromLlm = false)
        return Decision(null, say(
            "I can't see it yet. Slowly scroll down to see more.",
            "मुझे अभी नहीं दिख रहा। धीरे से नीचे स्क्रॉल कीजिए।",
            "ఇంకా కనిపించట్లేదు. నెమ్మదిగా కిందకు స్క్రోల్ చేయండి.").pick(lang), done = false, fromLlm = false)
    }

    fun tapSay(label: String, lang: Lang) = say(
        "Tap \"${label.take(40)}\".", "\"${label.take(40)}\" दबाइए।", "\"${label.take(40)}\" నొక్కండి.").pick(lang)

    private val STOP = setOf("the", "and", "for", "with", "how", "open", "please", "want", "can", "you", "help", "use", "make",
        "text", "bigger", "smaller", "change", "phone", "turn", "set", "show", "this", "that", "my")
}
