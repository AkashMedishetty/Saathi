package com.saathi.app.guide

/**
 * Telugu / Hindi names in English letters, the way contacts are saved: "ఆకాష్" → "akash", "అక్షయ్" → "akshay",
 * "आकाश" → "akash" (field 10:30: WhatsApp search got "ఆకాష్" and found nobody). Rule-based, instant, no model.
 */
object Translit {
    private val CONS = mapOf(
        'క' to "k", 'ఖ' to "kh", 'గ' to "g", 'ఘ' to "gh", 'ఙ' to "n", 'చ' to "ch", 'ఛ' to "chh", 'జ' to "j", 'ఝ' to "jh", 'ఞ' to "n",
        'ట' to "t", 'ఠ' to "th", 'డ' to "d", 'ఢ' to "dh", 'ణ' to "n", 'త' to "t", 'థ' to "th", 'ద' to "d", 'ధ' to "dh", 'న' to "n",
        'ప' to "p", 'ఫ' to "ph", 'బ' to "b", 'భ' to "bh", 'మ' to "m", 'య' to "y", 'ర' to "r", 'ల' to "l", 'వ' to "v", 'శ' to "sh",
        'ష' to "sh", 'స' to "s", 'హ' to "h", 'ళ' to "l", 'ఱ' to "r",
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
    )
    private val VOWEL = mapOf(
        'అ' to "a", 'ఆ' to "a", 'ఇ' to "i", 'ఈ' to "ee", 'ఉ' to "u", 'ఊ' to "oo", 'ఋ' to "ru", 'ఎ' to "e", 'ఏ' to "e", 'ఐ' to "ai",
        'ఒ' to "o", 'ఓ' to "o", 'ఔ' to "au",
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )
    private val SIGN = mapOf(
        'ా' to "a", 'ి' to "i", 'ీ' to "ee", 'ు' to "u", 'ూ' to "oo", 'ృ' to "ru", 'ె' to "e", 'ే' to "e", 'ై' to "ai", 'ొ' to "o", 'ో' to "o", 'ౌ' to "au",
        'ा' to "a", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
    )
    private const val VIRAMA_TE = '్'
    private const val VIRAMA_HI = '्'

    fun needed(s: String) = s.any { it.code in 0x900..0xC7F }

    fun latin(s: String): String = s.split(Regex("\\s+")).joinToString(" ") { word(it) }.trim()

    private fun word(w: String): String {
        val out = StringBuilder()
        var i = 0
        val hindi = w.any { it.code in 0x900..0x97F }
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            when {
                c in CONS -> {
                    out.append(CONS[c])
                    when {
                        next == VIRAMA_TE || next == VIRAMA_HI -> i++                  // no vowel
                        next != null && next in SIGN -> { out.append(SIGN[next]); i++ }
                        next == 'ం' || next == 'ं' || next == 'ँ' -> out.append("a")
                        // Hindi drops the last "a" (आकाश → akash); Telugu keeps it (రమ → rama).
                        next == null && hindi -> {}
                        else -> out.append("a")
                    }
                }
                c in VOWEL -> out.append(VOWEL[c])
                c == 'ం' || c == 'ं' || c == 'ँ' -> out.append(if (next != null && next in "పబమपबम") "m" else "n")
                c == 'ః' || c == 'ः' -> out.append("h")
                c.code < 0x250 -> out.append(c)
                else -> {}
            }
            i++
        }
        return out.toString()
    }
}
