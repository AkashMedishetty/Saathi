package com.saathi.app.policy

data class Answer(val text: String, val kind: String, val confidence: Float)

/** Extracts literal card text, never invents units, dates, people, or a live/latest claim. */
object Grounded {
    fun extract(question: String, page: List<String>): Answer? {
        val q = norm(question)
        val labels = page.map(String::trim).filter { it.isNotBlank() && it != "null" }
            .fold(mutableListOf<String>()) { out, s -> if (out.lastOrNull() != s) out.add(s); out }
        val start = labels.indexOfFirst { norm(it) in setOf("search results", "खोज नतीजे", "శోధన ఫలితాలు") }
        if (start < 0) return null // launcher/partial capture is not a result page
        val card = labels.drop(start + 1).takeWhile { norm(it) !in setOf("web results", "people also ask", "related results") }
        if (card.isEmpty() || card.any { it.has("ignore (previous|all)|system prompt|<\\|im_start") }) return null
        fun answer(parts: List<String>, kind: String): Answer? {
            if (parts.isEmpty()) return null
            val text = parts.joinToString("\n")
            if (text.length > 600 || Redactor.containsIdentifiedSecret(text)) return null
            return Answer(text, kind, 0.9f) // rule confidence, not confidence that the source is true
        }
        if (q.has("weather|rain|temperature|मौसम|बारिश|वातావరణం|వర్షం")) {
            val now = card.filter { it.has("^Now .{1,50}-?\\d{1,2}°(?:[CF])?$") }.distinct()
            if (now.size != 1) return null
            val rainIndex = card.indexOfFirst { norm(it) == "precipitation" }
            val rain = if (rainIndex >= 0 && card.getOrNull(rainIndex + 1)?.matches(Regex("(?:100|[0-9]{1,2})%")) == true)
                card.subList(rainIndex, rainIndex + 2) else emptyList()
            if (q.has("rain|बारिश|వర్షం") && rain.isEmpty()) return null
            val locations = card.filter { it.matches(Regex("[A-Z][a-z]+(?: [A-Z][a-z]+)*, [A-Z][a-z]+(?: [A-Z][a-z]+)*")) }.distinct()
            if (locations.size != 1 || !q.contains(norm(locations.single().substringBefore(',')))) return null
            return answer(locations + now + rain, "weather")
        }
        if (q.has("sunrise|sunset|सूर्योदय|सूर्यास्त|సూర్యోదయ|సూర్యాస్త")) {
            val name = if (q.has("sunset|सूर्यास्त|సూర్యాస్త")) "sunset" else "sunrise"
            val hits = card.indices.filter { norm(card[it]).startsWith("$name in ") }
            if (hits.size != 1) return null
            val i = hits.single()
            val time = card.getOrNull(i - 2) ?: return null
            val date = card.getOrNull(i - 1) ?: return null
            if (!time.matches(Regex("[0-9]{1,2}:[0-9]{2}\\s*[ap]m", RegexOption.IGNORE_CASE)) &&
                !norm(time).matches(Regex("[0-9]{1,2}:[0-9]{2}\\s*[ap]m"))) return null
            if (!date.has("\\b20[0-9]{2}\\b")) return null
            if (q.has("today|tomorrow|आज|कल|ఈరోజు|రేపు")) return null // no trusted clock/date in this API
            val location = norm(card[i]).substringAfter("$name in ").substringBefore(',')
            if (!q.contains(location)) return null
            return answer(listOf(time, date, card[i]), "time")
        }
        if (q.has("cricket|score|क्रिकेट|स्कोर|క్రికెట్|స్కోరు")) {
            val matches = card.filter { it.has("(?:\\d+ for \\d+ in .+ overs|\\d+/\\d+).*(?:won|Live|live)") }.distinct()
            if (matches.size != 1 || q.has("today|latest|current|आज|ఈరోజు")) return null
            return answer(matches, "score")
        }
        if (q.has("dollar|rupees|currency|डॉलर|రూపాయ|డాలర్")) {
            if (!q.has("(?:^|\\b)1 (?:dollar|usd)|dollar.{0,20}(rate|rupees)|डॉलर|డాలర్")) return null
            val matches = card.filter { it.has("^1 (?:US Dollar|USD).{0,12}(?:equals|=).{0,12}\\d+(?:\\.\\d+)? (?:Indian Rupee|INR)") }.distinct()
            return if (matches.size == 1) answer(matches, "currency") else null
        }
        if (q.has("gold|price|सोना|कीमत|బంగారం|ధర")) {
            val matches = card.filter { it.has("(?i)gold.*(?:22|24)\\s*k.*(?:₹|INR)\\s*[0-9,]+.*(?:gram|/g)") }.distinct()
            return if (matches.size == 1) answer(matches, "price") else null
        }
        if (q.has("diwali|दिवाली|దీపావళి")) {
            val year = Regex("20[0-9]{2}").find(q)?.value ?: return null
            val matches = card.filter { it.has("^Diwali.*$year.*(?:[0-9]{1,2} [A-Za-z]+|[A-Za-z]+ [0-9]{1,2})") }.distinct()
            return if (matches.size == 1) answer(matches, "date") else null
        }
        if (q.has("prime minister of india|भारत.{0,10}प्रधानमंत्री|భారత.{0,10}ప్రధానమంత్రి")) {
            val indices = card.indices.filter { norm(card[it]) == "prime minister of india" }
            if (indices.size != 1) return null
            val i = indices.single()
            val name = card.getOrNull(i - 1) ?: return null
            if (!name.matches(Regex("[A-Z][a-z]+(?: [A-Z][a-z]+){1,3}"))) return null
            return answer(listOf(name, card[i]), "fact")
        }
        return null
    }
}
