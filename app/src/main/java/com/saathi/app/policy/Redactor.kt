package com.saathi.app.policy

import com.saathi.app.guide.Lang
import com.saathi.app.guide.pick
import com.saathi.app.guide.say

/** No storage or logging. Memory drops the whole entry when any private value is detected. */
object Redactor {
    private val number = "[0-9](?:[0-9 \\-]{1,30}[0-9])?"
    private val secretLabel = "otp|one.time password|verification code|password|passcode|upi\\s*pin|pin|cvv|cvc|ओटीपी|पासवर्ड|पिन|सीवीवी|ఓటీపీ|పాస్‌వర్డ్|పిన్|సీవీవీ"
    private val accountLabel = "account(?: number| no)?|a/c|acct|card(?: number| no)?|aadhaar|aadhar|खाता(?: संख्या)?|कार्ड(?: नंबर)?|आधार|ఖాతా(?: సంఖ్య)?|కార్డు(?: నంబర్)?|ఆధార్"
    private val patterns = listOf(
        Regex("(?<![A-Za-z0-9])[A-Z]{5}[0-9]{4}[A-Z](?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[A-Z]{4}0[A-Z0-9]{6}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE),
        Regex("(?<![0-9])[0-9](?:[ \\-]?[0-9]){11,18}(?![0-9])"),
        Regex("(?m)^.*(?<![\\p{L}\\p{M}])(?:$secretLabel)(?![\\p{L}\\p{M}]).*$", RegexOption.IGNORE_CASE),
        Regex("$number\\s*(?:is your|is the|आपका|మీ)?\\s*(?<![\\p{L}\\p{M}])(?:$secretLabel)(?![\\p{L}\\p{M}])\\b", RegexOption.IGNORE_CASE),
        Regex("(?<![\\p{L}\\p{M}])(?:$accountLabel)(?![\\p{L}\\p{M}])\\s*(?:number|no\\.?|ending|ends in|संख्या|నంబర్)?\\s*(?:is|:|=|-)?\\s*[Xx*•]*$number", RegexOption.IGNORE_CASE),
    )
    private fun asciiDigits(s: String): String = buildString {
        s.forEach { c -> append(if (c.isDigit()) Character.digit(c, 10).toString() else c.toString()) }
    }
    private fun ranges(s: String): List<IntRange> {
        val text = asciiDigits(s)
        return patterns.flatMap { p -> p.findAll(text).map { it.range }.toList() }
            .sortedBy { it.first }.fold(mutableListOf()) { merged, r ->
                if (merged.isNotEmpty() && r.first <= merged.last().last + 1) {
                    val last = merged.removeAt(merged.lastIndex)
                    merged.add(last.first..maxOf(last.last, r.last))
                } else merged.add(r)
                merged
            }
    }
    internal fun containsIdentifiedSecret(s: String): Boolean = ranges(s).isNotEmpty()
    internal fun containsSensitive(s: String): Boolean = containsIdentifiedSecret(s) ||
        asciiDigits(s).trim().matches(Regex("[0-9][0-9 \\-]{1,28}[0-9]"))
    private fun mask(s: String, replacement: String): String {
        if (containsSensitive(s) && !containsIdentifiedSecret(s)) return replacement
        var result = s
        ranges(s).asReversed().forEach { result = result.replaceRange(it, replacement) }
        return result
    }
    fun forLog(s: String): String = mask(s, "[PRIVATE]")
    fun forMemory(s: String): String = if (containsSensitive(s)) "" else s
    fun forSpeech(s: String): String = forSpeech(s, Lang.EN)
    fun forSpeech(s: String, lang: Lang): String = mask(s, say("private information", "निजी जानकारी", "వ్యక్తిగత సమాచారం").pick(lang))
}
