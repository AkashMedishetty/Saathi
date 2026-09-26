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
                "text" -> s.text
                "time" -> timeFor(goal, s.hour, s.minute)
                "place" -> placeFor(goal) ?: s.place
                "term" -> SettingsTerms.termFor(goal) ?: settingsWords(goal)
                "app" -> appName(goal)
                "developer" -> com.saathi.app.maps.apps.PlayStoreMap.developerOf(appName(goal))
                else -> null
            }
            v?.trim()?.takeIf { it.isNotEmpty() }?.let { out[k] = it }
        }
        // A route's own fixed words win ("make the text bigger" → search "font size", never "text bigger").
        for ((k, v) in r.presets) out[k] = v
        return out
    }

    /** Words that are about the app, not what to search for. "songs" / "गाने" / "పాటలు" stay: they help the search. */
    private val ROUTE_WORDS = rx("(^|\\s)(on|in|at|the|a|an|search|find|open|app|youtube|spotify|" +
        "play store|playstore|google play|please|for|me|and|then|some|to|listen|hear|my|watch|serial|jio ?hotstar|hotstar|netflix|" +
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

    private val PLACE_EN = rx("(?:directions|way|route|navigate|take me|go|get|reach|cab|taxi|auto|ride|uber|ola)\\s+(?:to|till|upto)\\s+(?:the\\s+)?(.+?)(?:\\s+(?:in|on|from|using)\\s+(?:google\\s+)?maps)?\\s*$")
    private val PLACE_HI = Regex("^(.+?)\\s+(?:का रास्ता|की रास्ता|कैसे जाऊँ|कैसे जाएँ|के लिए|तक)")
    private val PLACE_TE = Regex("^(.+?)(?:కి|కు)\\s+(?:దారి|ఎలా|క్యాబ్|టాక్సీ|ఆటో)")

    /** "book a cab to Charminar" → "Charminar"; "चारमीनार का रास्ता" → "चारमीनार"; "చార్మినార్ కి దారి" → "చార్మినార్". */
    fun placeFor(goal: String): String? {
        val g = goal.trim()
        return (PLACE_EN.find(g) ?: PLACE_HI.find(g) ?: PLACE_TE.find(g))?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    private val CLOCK_TIME = Regex("\\b(\\d{1,2})[:.](\\d{2})\\b")

    /** "the 8 am alarm" → "8:00"; "the 8:30 alarm" → "8:30" (12-hour, as the Clock app shows it). */
    fun timeFor(goal: String, hour: Int? = null, minute: Int? = null): String? {
        val m = CLOCK_TIME.find(goal)
        val h = hour ?: m?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val min = if (hour != null) minute ?: 0 else m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59) return null
        return "%d:%02d".format(if (h % 12 == 0) 12 else h % 12, min)
    }

    private val SETTINGS_FILLER = rx("(^|\\s)(change|open|find|show|where|is|the|my|a|an|of|for|in|on|phone|setting|settings|" +
        "please|how|do|i|to|turn|set|make|सेटिंग|फ़ोन|फोन|की|का|के|में|बदलो|खोलो|दिखाओ|సెట్టింగ్|ఫోన్|లో|మార్చు|తెరువు|చూపించు)(?=\\s|$)")

    /** Any other setting: the person's own key words ("change the ringtone for calls" → "ringtone calls"). */
    private fun settingsWords(goal: String): String? {
        var q = " ${goal.lowercase()} "
        repeat(3) { q = SETTINGS_FILLER.replace(q, " ") }
        return q.replace(Regex("\\s+"), " ").trim().split(' ').takeLast(2).joinToString(" ").ifBlank { null }
    }

    private fun appName(goal: String): String? {
        var q = " ${goal.lowercase()} "
        repeat(3) { q = INSTALL_WORDS.replace(q, " ") }
        return q.replace(Regex("\\s+"), " ").trim().ifBlank { null }?.let { com.saathi.app.maps.apps.PlayStoreMap.latinName(it) }
    }
}
