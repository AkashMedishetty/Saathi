package com.saathi.app.guide

/**
 * What kind of screen this is, beyond "a list of buttons". Lets the guide handle the situations that made it
 * wander in field testing: sign-in / setup walls, ads and popups, and places it should never lead people to.
 */
object ScreenKinds {

    /** Stable identity of a screen: its tappable titles, ignoring positions, counters and animations. */
    fun fingerprint(s: Screen): Int =
        (s.pkg + "|" + s.elements.filter { it.role != "text" }.map { it.title.replace(Regex("\\d+"), "#").take(30) }.sorted().joinToString("|")).hashCode()

    // ── Sign-in / setup walls ──
    private val WALL = Regex(
        "(?i)\\b(sign in|log ?in|sign up|create (an )?account|get started|agree and continue|welcome to|enter (your )?(phone|mobile) number|" +
            "verify your (phone )?number|enter (the )?otp|choose an account|continue with google)\\b")
    private val WALL_BUTTON = rx("^Sign in", "^SIGN IN", "^Log ?in", "^LOGIN", "^Get started", "^AGREE AND CONTINUE", "^Agree and continue", "^Continue with Google", "^Next$")

    data class Wall(val button: UiElement?, val setup: Boolean)

    /** A login / first-run screen that only the person (or family) should complete. */
    fun wall(s: Screen): Wall? {
        if (!WALL.containsMatchIn(s.allText)) return null
        val hasPassword = s.elements.any { it.password }
        val setup = Regex("(?i)welcome to|agree and continue|enter (your )?(phone|mobile) number|verify your").containsMatchIn(s.allText)
        val btn = s.find(WALL_BUTTON)
        if (btn == null && !hasPassword && !setup) return null
        return Wall(btn, setup)
    }

    // ── Ads and interrupting popups ──
    private val AD_TEXT = Regex("(?i)\\b(sponsored|advertisement|\\bad\\b ?[·•:]|ad \\d+ of \\d+|skip ad|install now|rate us|enjoying .* \\?)")
    private val AD_CLOSE = rx("^Skip ad", "^Skip Ads?$", "^Skip$", "^Close ad", "^Dismiss", "^No,? thanks", "^Not now$", "^Close$", "^✕$", "^×$", "^X$", "^Maybe later", "^Later$")

    /** An ad or a nag popup with a way out: point at the way out. */
    fun ad(s: Screen): UiElement? = if (AD_TEXT.containsMatchIn(s.allText)) s.find(AD_CLOSE) else null

    // ── Places a guide should not take people unless they asked for them ──
    private val AVOID = Regex("(?i)^(help|help cent(er|re)|need help\\??|about|about (us|this app)|privacy|privacy policy|terms|terms of (service|use)|" +
        "feedback|send feedback|faq|learn more|legal|licen[cs]es?|report( a problem)?|rate (us|this app)|contact us|accessibility|" +
        "open source|version|cookie)")

    fun avoid(label: String, goal: String): Boolean {
        val t = label.substringBefore(" · ").trim()
        if (!AVOID.containsMatchIn(t)) return false
        // Asked for it on purpose? ("open privacy settings", "help me contact support") then it's fine.
        val first = t.lowercase().split(' ').first()
        return !goal.lowercase().contains(first)
    }
}
