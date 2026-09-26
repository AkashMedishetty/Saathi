package com.saathi.app.policy

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say

data class Check(val ok: Boolean, val reasons: List<String>, val fallback: Say?)

/** Source support, not source truth. Rejects free paraphrases; accepts only normalized source excerpts. */
object AnswerCheck {
    fun verify(question: String, answer: String, source: String?): Check = verify(question, answer, source, language(question))

    /** Use the explicit overload when the selected UI language differs from the question's script. */
    fun verify(question: String, answer: String, source: String?, expectedLanguage: Lang): Check {
        val q = norm(question)
        val a = norm(answer)
        fun reject(reason: String, fallback: Say = Words.unverified) = Check(false, listOf(reason), fallback)
        if (a.isBlank() || a.length > 2000) return reject("empty_or_too_long")
        if (garbled(answer)) return reject("garbled")
        if (!rightScript(answer, expectedLanguage)) return reject("wrong_script")
        if (medical(q) || medical(a)) return reject("medical", Words.doctor)
        if (regulated(q) || regulated(a)) return reject("legal_or_investment", Words.expert)
        if (Redactor.containsIdentifiedSecret(answer)) return reject("private_information", Words.privateField)
        if (source.isNullOrBlank()) {
            if (currentFact(q)) return reject("needs_current_source", Words.internet)
            val group = offlineAdvice.firstOrNull { q.has(it.first) }
            if (group != null && a in group.second.map(::norm)) return Check(true, emptyList(), null)
            return reject("no_supported_offline_advice")
        }
        if (source.has("Ġ|Ċ|à[°±¤¥]|�|ignore (previous|all) instructions|system prompt")) return reject("unusable_source")
        val canonicalSource = canonical(source)
        val excerpt = canonical(answer)
        if (excerpt.isBlank()) return reject("no_content")
        // A whole excerpt must be contiguous, including across newlines. Only the deterministic
        // card extractor may select nonadjacent card fields; arbitrary model fragments cannot be combined.
        val contiguous = " $canonicalSource ".contains(" $excerpt ")
        val card = if (!contiguous) Grounded.extract(question, source.lines()) else null
        if (!contiguous && card?.text?.let(::canonical) != excerpt) return reject("unsupported_claim")
        val answerCurrency = currencies(answer)
        if (!currencies(source).containsAll(answerCurrency)) return reject("unsupported_currency")
        return Check(true, emptyList(), null)
    }

    internal fun language(question: String): Lang = when {
        question.has("in telugu|తెలుగులో") -> Lang.TE
        question.has("in hindi|हिंदी में") -> Lang.HI
        question.has("in english|अंग्रेज़ी में|ఆంగ్లంలో") -> Lang.EN
        question.any { it in '\u0C00'..'\u0C7F' } -> Lang.TE
        question.any { it in '\u0900'..'\u097F' } -> Lang.HI
        else -> Lang.EN
    }
    private fun rightScript(s: String, lang: Lang): Boolean {
        val letters = s.filter(Char::isLetter)
        if (letters.isEmpty()) return true // numbers and units may be script neutral
        val wanted = when (lang) {
            Lang.EN -> letters.count { it in 'A'..'Z' || it in 'a'..'z' }
            Lang.HI -> letters.count { it in '\u0900'..'\u097F' }
            Lang.TE -> letters.count { it in '\u0C00'..'\u0C7F' }
        }
        return wanted.toDouble() / letters.length >= 0.7
    }
    internal fun garbled(s: String): Boolean {
        if (s.has("Ġ|Ċ|à[°±¤¥]|�|<\\|[^>]+\\|>|<start_of_turn>|<end_of_turn>|</?think>")) return true
        val w = norm(s).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotBlank() }
        if (w.size < 12) return false
        val top = w.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return w.count { it.all(Char::isDigit) } > w.size * 0.4 || (top > 6 && top > w.size * .25) || w.toSet().size.toDouble() / w.size < .3
    }
    private fun medical(s: String) = s.has("\\b(dose|dosage|diagnos\\w*|medicine|medication|insulin|tablet|cancer|antibiotic|treatment|symptom|headache|chest pain|fever)\\b|दवा|खुराक|निदान|इलाज|मिलीग्राम|మందు|మందులు|మోతాదు|చికిత్స|నిర్ధారణ")
    private fun regulated(s: String) = s.has("\\b(legal|lawyer|lawsuit|invest\\w*|stock|shares|mutual fund|loan|tax advice)\\b|कानून|कानूनी|वकील|निवेश|शेयर|చట్ట|న్యాయ|పెట్టుబడి|షేర్లు")
    private fun currentFact(s: String) = s.has("\\b(today|tomorrow|now|current|latest|weather|rain|price|score|prime minister|president|exchange rate|dollar|sunrise|sunset|date|diwali)\\b|आज|कल|मौसम|बारिश|कीमत|स्कोर|प्रधानमंत्री|तारीख|दिवाली|ఈరోజు|రేపు|వాతావరణం|వర్షం|ధర|స్కోరు|ప్రధానమంత్రి|తేదీ|దీపావళి")
    private val offlineAdvice = listOf(
        "how.{0,30}(search|find).{0,20}(phone|app)|कैसे.{0,20}खोज|ఎలా.{0,20}వెత" to listOf(
            "Open the app. Use its search box.", "ऐप खोलिए। उसके खोज बॉक्स का उपयोग कीजिए।", "యాప్ తెరవండి. దానిలోని శోధన పెట్టె వాడండి."),
        "how.{0,30}(message|photo)|संदेश.{0,20}कैसे|ఫోటో.{0,20}ఎలా" to listOf(
            "Choose the person. Check the message before sending it yourself.", "व्यक्ति चुनिए। भेजने से पहले संदेश जाँचिए।", "వ్యక్తిని ఎంచుకోండి. పంపే ముందు సందేశాన్ని తనిఖీ చేయండి."),
        "how.{0,30}(upma|cook)|उपमा.{0,20}कैसे|ఉప్మా.{0,20}ఎలా" to listOf(
            "Roast the semolina. Add it to hot water carefully. Stir until cooked.", "सूजी भूनिए। उसे सावधानी से गरम पानी में डालिए। पकने तक चलाइए।", "రవ్వ వేయించండి. జాగ్రత్తగా వేడి నీటిలో వేయండి. ఉడికే వరకు కలపండి."),
    )
    private fun currencies(s: String): Set<String> = buildSet {
        if (s.has("₹|\\binr\\b|rupees?|रुपये|రూపాయ")) add("inr")
        if (s.has("\\$|\\busd\\b|dollars?")) add("usd")
        if (s.has("€|\\beur\\b|euros?")) add("eur")
        if (s.has("£|\\bgbp\\b|pounds?")) add("gbp")
    }
    internal fun canonical(raw: String): String {
        var s = norm(raw).map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("")
        val months = listOf("jan(?:uary)?", "feb(?:ruary)?", "mar(?:ch)?", "apr(?:il)?", "may", "jun(?:e)?", "jul(?:y)?", "aug(?:ust)?", "sep(?:t(?:ember)?)?", "oct(?:ober)?", "nov(?:ember)?", "dec(?:ember)?")
        months.forEachIndexed { index, month ->
            val mm = (index + 1).toString().padStart(2, '0')
            s = s.replace(Regex("\\b([0-9]{1,2}) ($month) ([0-9]{4})\\b")) { m -> "${m.groupValues[3]}-$mm-${m.groupValues[1].padStart(2, '0')}" }
            s = s.replace(Regex("\\b($month) ([0-9]{1,2}),? ([0-9]{4})\\b")) { m -> "${m.groupValues[3]}-$mm-${m.groupValues[2].padStart(2, '0')}" }
        }
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), "")
            .replace("°", " degrees ")
            .replace(Regex("₹|\\binr\\b|\\b(?:indian )?rupees?\\b|रुपये|రూపాయలు"), " inr ")
            .replace(Regex("\\$|\\busd\\b|\\b(?:us )?dollars?\\b"), " usd ")
            .replace(Regex("€|\\beur\\b|\\beuros?\\b"), " eur ")
            .replace(Regex("£|\\bgbp\\b|\\bpounds?\\b"), " gbp ")
        // Keep currency attached to its amount. A different INR amount elsewhere cannot support $→₹ swaps.
        s = s.replace(Regex("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s+(inr|usd|eur|gbp)\\b")) { m -> "${m.groupValues[2]} ${m.groupValues[1]}" }
        // Limited, explicit aliases; no guessed transliteration or inferred names.
        listOf("नरेंद्र मोदी", "నరేంద్ర మోదీ", "నరేంద్ర మోడీ").forEach { s = s.replace(it, "narendra modi") }
        s = s.replace(Regex("[^\\p{L}\\p{M}\\p{N}.%:/+\\-]+"), " ")
        return s.trim().trimEnd('.').replace(Regex("\\s+"), " ")
    }
}
