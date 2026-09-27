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
        // The Play Store is already signed in; its pages are ads whose text says "log in", "code", "PIN" (field 08:59: a
        // false "log in with your phone number" wall on "install JuiceSSH"). Approved by Akash 09:12.
        if (s.pkg == "com.android.vending") return null
        if (!WALL.containsMatchIn(s.allText)) return null
        val hasPassword = s.elements.any { it.password }
        val setup = Regex("(?i)welcome to|agree and continue|enter (your )?(phone|mobile) number|verify your").containsMatchIn(s.allText) || secretEntry(s)
        val btn = s.find(WALL_BUTTON)
        if (btn == null && !hasPassword && !setup) return null
        return Wall(btn, setup)
    }

    /**
     * A box for a phone number, OTP, password or PIN is on screen (field: JioHotstar's "Log in to watch" page — once
     * the keyboard hid its Log in button, it stopped counting as a wall and the planner asked for their number).
     * Only the person types these; Saathi never plans or types here.
     */
    fun secretEntry(s: Screen): Boolean = s.elements.any { it.password } ||
        (s.elements.any { it.role == "input" } && Regex("(?i)mobile number|phone number|\\botp\\b|one.time (password|code)|verification code|enter (the )?code|\\bpin\\b|password|cvv|card number|upi pin")
            .containsMatchIn(s.allText))

    // ── Ads and interrupting popups ──
    private val AD_TEXT = Regex("(?i)\\b(sponsored|advertisement|\\bad\\b ?[·•:]|ad \\d+ of \\d+|skip ad|install now|rate us|enjoying .* \\?)")
    private val AD_CLOSE = rx("^Skip ad", "^Skip Ads?$", "^Skip$", "^Close ad", "^Dismiss", "^No,? thanks", "^Not now$", "^Close$", "^✕$", "^×$", "^X$", "^Maybe later", "^Later$")

    /** An ad or a nag popup with a way out: point at the way out. */
    fun ad(s: Screen): UiElement? = if (AD_TEXT.containsMatchIn(s.allText)) s.find(AD_CLOSE) else null

    /** Buttons that only ever decline a nag ("Allow notifications?", "Rate us", "Try Premium"): always safe to point at. */
    private val NAG_DECLINE = rx("^Maybe later$", "^Not now$", "^No,? thanks$", "^Later$", "^Skip for now$", "^Remind me later$", "^Dismiss$", "^Don.t allow$")

    /** A nag popup covering the page (field: JioHotstar's "Allow Notifications / Maybe Later" sheet hid the Search tab). */
    fun nag(s: Screen): UiElement? {
        // On screen only (field: Photos keeps a "Not now" card off to the right, x = 1921 on a 1440-wide phone).
        val m = android.content.res.Resources.getSystem().displayMetrics
        return s.find(NAG_DECLINE)?.takeIf { e -> e.bounds.left >= 0 && e.bounds.right <= m.widthPixels && e.bounds.top >= 0 && e.bounds.bottom <= m.heightPixels }
    }

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
