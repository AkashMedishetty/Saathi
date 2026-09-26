package com.saathi.app.guide

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Ask family" — help that can't be hijacked (playbook §9.3):
 *  1. Saathi never sends. It prefills a message; the person presses send.
 *  2. Only the registered family contact (changing it needs the screen lock). No free-typed numbers.
 *  3. Redacted: no digits of 4+, amounts, emails or links. From money apps: no screen details at all.
 *  4. One-way: nothing comes back in, no remote control, never screen sharing.
 */
object FamilyHelp {

    /** Pure, unit-tested. */
    fun redact(t: String): String = t
        .replace(Regex("(₹|rs\\.?|inr)\\s*[\\d,]+(\\.\\d+)?", RegexOption.IGNORE_CASE), "[amount]")
        .replace(Regex("[\\d,]+(\\.\\d+)?\\s*(rupees?|rs\\b|रुपये|रुपए|రూపాయలు)", RegexOption.IGNORE_CASE), "[amount]")
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "[email]")
        .replace(Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE), "[link]")
        .replace(Regex("\\d[\\d\\s-]{2,}\\d"), "[number]")
        .replace(Regex("\\s+"), " ").trim()

    /** Cut at a word boundary so we never leave half a "[email]". */
    private fun short(t: String, n: Int) = if (t.length <= n) t else t.take(n).substringBeforeLast(' ').trimEnd(',', '.') + "…"

    /** Pure: the message text. [moneyApp] = true means we say nothing about the screen. */
    fun message(name: String, appLabel: String, screenTitle: String?, goal: String?, moneyApp: Boolean, lang: Lang): String {
        val who = name.ifBlank { say("Your mother/father", "आपके माता/पिता", "మీ అమ్మ/నాన్న").pick(lang) }
        if (moneyApp) return say(
            "$who needs help with a payment app. Please call me. — Saathi",
            "$who को पैसे वाले ऐप में मदद चाहिए। कृपया फ़ोन कीजिए। — Saathi",
            "$who కి పేమెంట్ యాప్‌లో సహాయం కావాలి. దయచేసి ఫోన్ చేయండి. — Saathi").pick(lang)
        val where = listOfNotNull(appLabel.takeIf { it.isNotBlank() }, screenTitle?.let { short(redact(it), 40) }?.takeIf { it.isNotBlank() }).joinToString(" › ")
        val doing = goal?.let { short(redact(it), 60) }
        return say(
            "$who needs help on the phone: in $where${doing?.let { ", trying to \"$it\"" } ?: ""}. Please call when you can. — Saathi",
            "$who को फ़ोन में मदद चाहिए: $where${doing?.let { " में, \"$it\" करना है" } ?: ""}। समय मिले तो फ़ोन कीजिए। — Saathi",
            "$who కి ఫోన్‌లో సహాయం కావాలి: $where${doing?.let { " లో, \"$it\" చేయాలి" } ?: ""}. వీలైనప్పుడు ఫోన్ చేయండి. — Saathi").pick(lang)
    }

    /** WhatsApp chat with the family number, text prefilled (not sent). SMS if there's no WhatsApp. */
    fun intent(ctx: Context, text: String): Intent? {
        val num = Prefs.familyPhone(ctx).filter { it.isDigit() || it == '+' }
        if (num.isBlank()) return null
        val digits = num.removePrefix("+").let { if (it.length == 10) "91$it" else it }
        val wa = AppLauncher.first(ctx, "com.whatsapp", "com.whatsapp.w4b")
        return if (wa != null) Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))).setPackage(wa)
        else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", text)
    }
}
