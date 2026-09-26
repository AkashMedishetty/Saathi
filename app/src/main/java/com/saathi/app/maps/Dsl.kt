package com.saathi.app.maps

import com.saathi.app.guide.SlotExtractor

/** Small helpers so the per-app maps read like the screens they describe. */
internal fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

/** By resource-id (the id part only: "search_edit_text"). */
internal fun id(resId: String, label: String? = null, clickable: Boolean? = null) =
    Sel(resId = resId, label = label?.let(::rx), clickable = clickable)

/** By label (contentDescription, else text, else a row's text). */
internal fun lbl(p: String, clickable: Boolean? = null, not: String? = null, pick: Pick = Pick.LARGEST) =
    Sel(label = rx(p), clickable = clickable, not = not?.let(::rx), pick = pick)

/** A goal phrase list → regexes (lower-case input). */
internal fun goals(vararg p: String) = p.map { Regex(it) }

/**
 * Runtime slot values for a route, from the person's own words (reuses guide/Slots.kt).
 *  - "query": what to search for ("हनुमान चालीसा लगाओ यूट्यूब पर" → "हनुमान चालीसा")
 *  - "term": a Settings topic ("change my ringtone" → "ringtone"), see [SettingsTerms]
 *  - "contact", "place", "app"
 */
object MapSlots {
    fun of(r: Route, goal: String, family: String = ""): Map<String, String> {
        val s = SlotExtractor.from(goal, family)
        val out = mutableMapOf<String, String>()
        for (k in r.slots) {
            val v = when (k) {
                "query" -> query(goal, s.query)
                "contact" -> s.contact
                "place" -> s.place
                "term" -> SettingsTerms.termFor(goal)
                "app" -> appName(goal)
                else -> null
            }
            v?.trim()?.takeIf { it.isNotEmpty() }?.let { out[k] = it }
        }
        return out
    }

    /** Words that are about the app, not what to search for. "songs" / "गाने" / "పాటలు" stay: they help the search. */
    private val ROUTE_WORDS = rx("(^|\\s)(on|in|at|the|a|an|search|find|open|app|youtube|spotify|" +
        "play store|playstore|google play|please|for|me|and|then|some|to|listen|hear|" +
        "सुनाओ|सुनना|चलाओ|लगाओ|बजाओ|खोजो|ढूंढो|ढूँढो|पर|पे|में|को|" +
        "పెట్టు|వినిపించు|వెతుకు|లో)(?=\\s|$)")

    private fun query(goal: String, fromSlots: String?): String? {
        var q = (fromSlots ?: SlotExtractor.searchPhrase(goal)).let { " $it " }
        repeat(3) { q = ROUTE_WORDS.replace(q, " ") }
        return q.replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }

    private val INSTALL_WORDS = rx("(^|\\s)(install|download|get|find|search|open|learn|use|how|do|i|to|the|app|application|" +
        "new|from|play store|playstore|google play|please|me|a|an|on|my|phone|इंस्टॉल|डाउनलोड|ऐप|करो|चाहिए|सीखना|है|" +
        "ఇన్‌స్టాల్|డౌన్‌లోడ్|యాప్|చేయి|కావాలి)(?=\\s|$)")

    private fun appName(goal: String): String? {
        var q = " ${goal.lowercase()} "
        repeat(3) { q = INSTALL_WORDS.replace(q, " ") }
        return q.replace(Regex("\\s+"), " ").trim().ifBlank { null }
    }
}
