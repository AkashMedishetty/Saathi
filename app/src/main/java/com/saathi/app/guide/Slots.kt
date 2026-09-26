package com.saathi.app.guide

/** What we pull out of a request: "video call Rahul on WhatsApp" → contact = Rahul. Pure (unit-testable). */
data class Slots(
    val raw: String,
    val contact: String?,
    val text: String?,
    val query: String?,
    val place: String?,
    val amount: String?,
    val hour: Int?,
    val minute: Int?,
)

object SlotExtractor {
    private val STOP = setOf(
        "on", "in", "via", "using", "whatsapp", "saying", "that", "and", "a", "an", "the", "my", "video", "call",
        "message", "msg", "hello", "hi", "about", "now", "please", "to", "for", "at", "from", "photo", "picture", "me",
    )
    /** Relations resolve to the registered family contact when there is one. */
    val FAMILY = setOf("son", "daughter", "beta", "beti", "wife", "husband", "grandson", "granddaughter", "family",
        "बेटा", "बेटे", "बेटी", "पोता", "पोती", "కొడుకు", "కూతురు", "మనవడు", "మనవరాలు")
    private val IC = RegexOption.IGNORE_CASE

    /** Words that aren't part of what to search for: app names, verbs and HI/TE particles. */
    private val FILLER = Regex("(?i)(^|\\s)(youtube|यूट्यूब|యూట్యూబ్|on|in|play|watch|search|for|please|me|a|the|" +
        "पर|पे|में|को|का|की|लगाओ|लगा दो|चलाओ|चला दो|बजाओ|सुनाओ|दिखाओ|सुनना है|देखना है|" +
        "లో|పెట్టు|పెట్టండి|వినిపించు|చూపించు|ప్లే|చేయి)(?=\\s|$)")

    fun searchPhrase(text: String): String {
        var t = " $text "
        repeat(3) { t = FILLER.replace(t, " ") }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun from(goal: String, family: String = ""): Slots {
        val g = goal.trim()
        var contact = Regex("(?:to|call|message|msg|text|with|ping|ring)\\s+((?:[\\p{L}\\p{M}.]+\\s*){1,4})", IC).find(g)
            ?.groupValues?.get(1)?.split(Regex("\\s+"))
            ?.dropWhile { it.lowercase() in STOP }
            ?.takeWhile { it.lowercase() !in STOP && it.isNotBlank() }
            ?.take(2)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        // Hindi/Telugu: "बेटे को वीडियो कॉल करो", "కొడుకుకి వీడియో కాల్ చేయి"
        if (contact == null) contact = FAMILY.firstOrNull { f -> f.any { it.code > 0x900 } && g.contains(f) }
        contact = contact?.let { c -> if (c.lowercase() in FAMILY) family.ifBlank { c } else c }
        val text = (Regex("\"([^\"]+)\"").find(g)?.groupValues?.get(1)
            ?: Regex("(?:saying|that says|say|tell (?:him|her|them)|:)\\s+(.+)$", IC).find(g)?.groupValues?.get(1)
            // Hindi: "बेटे को मैसेज भेजो कि मैं घर पहुँच गया" (field: Hindi/Telugu messages were typed empty).
            ?: Regex("(?:मैसेज|संदेश|message)\\s+(?:भेजो|भेज दो|करो|लिखो)\\s+(?:कि\\s+)?(.+)$", IC).find(g)?.groupValues?.get(1)
            ?: Regex("\\sकि\\s+(.+)$").find(g)?.groupValues?.get(1)
            // Telugu puts the words first: "అబ్బాయికి నేను ఇంటికి చేరుకున్నాను అని మెసేజ్ పంపు".
            ?: Regex("(?:కి|కు)\\s+(.+?)\\s+అని(?:\\s|$)").find(g)?.groupValues?.get(1)
            ?: Regex("^(.+?)\\s+అని\\s+(?:మెసేజ్|సందేశం|చెప్పు|పంపు|రాయి)").find(g)?.groupValues?.get(1)
        )?.replace(Regex("^(?:మెసేజ్|సందేశం)\\s+(?:పంపు|పంపించు|చేయి)\\s+"), "")?.trim()?.ifBlank { null }
        val query = Regex("(?:search(?: for)?|play|watch|find|show me|put on|learn)\\s+(.+?)(?:\\s+(?:on|in)\\s+(?:youtube|netflix|prime(?: video)?|hotstar|jiohotstar|zee5|sony ?liv)\\b.*)?$", IC)
            .find(g)?.groupValues?.get(1)?.trim()
        val place = Regex("(?:navigate|directions|route|way|take me|go|drive)\\s+(?:to\\s+)?(?:the\\s+)?(.+)$", IC).find(g)?.groupValues?.get(1)?.trim()
        val amount = Regex("(?:₹|rs\\.?|rupees?\\s*)?\\b(\\d{1,6})\\b(?!\\s*(?:am|pm|:|\\.\\d|baje|बजे))", IC).find(g.replace(",", ""))?.groupValues?.get(1)
        val t = Regex("\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|baje|बजे|గంటలకు)", IC).find(g)
        var hour = t?.groupValues?.get(1)?.toIntOrNull()
        val minute = t?.groupValues?.get(2)?.toIntOrNull() ?: hour?.let { 0 }
        val ampm = t?.groupValues?.get(3)?.lowercase() ?: ""
        if (hour != null && ampm.startsWith("p") && hour < 12) hour += 12
        if (hour != null && ampm.startsWith("a") && hour == 12) hour = 0
        // "सुबह 8 बजे" / "रात 9 बजे": morning/night words fix am/pm for बजे.
        if (hour != null && hour < 12 && Regex("रात|शाम|evening|night|రాత్రి|సాయంత్రం", IC).containsMatchIn(g)) hour += 12
        return Slots(g, contact, text, query, place, amount, hour, minute)
    }
}
