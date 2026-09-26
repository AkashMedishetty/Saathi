package com.saathi.app.forms

import com.saathi.app.guide.Lang
import java.time.LocalDate

/**
 * Online forms: the editable fields of any app or web page → one step per field, in reading order.
 *
 * Which text decides: the visible [FieldNode.label], else the [FieldNode.hint], else the words of the
 * [FieldNode.resId]. Secrets win from any source that is not a sentence-long hint: a password flag or password
 * inputType, or a resId like `otp_input`, makes the field sensitive even when the label says "Mobile".
 * A sensitive field never gets a value; that is enforced again at the end, whatever the rules said.
 */
object OnlineForm {
    // android.text.InputType, copied so this stays JVM-pure.
    private const val CLASS_MASK = 0x0000000f
    private const val VARIATION_MASK = 0x00000ff0
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2
    private const val TEXT_PASSWORD = 0x80
    private const val TEXT_VISIBLE_PASSWORD = 0x90
    private const val TEXT_WEB_PASSWORD = 0xe0
    private const val NUMBER_PASSWORD = 0x10
    private const val TEXT_EMAIL = 0x20
    private const val TEXT_WEB_EMAIL = 0xd0

    fun isPasswordType(t: Int?): Boolean {
        t ?: return false
        val c = t and CLASS_MASK; val v = t and VARIATION_MASK
        return (c == CLASS_TEXT && v in setOf(TEXT_PASSWORD, TEXT_VISIBLE_PASSWORD, TEXT_WEB_PASSWORD)) ||
            (c == CLASS_NUMBER && v == NUMBER_PASSWORD)
    }

    private fun isEmailType(t: Int?) = t != null && (t and CLASS_MASK) == CLASS_TEXT &&
        (t and VARIATION_MASK).let { it == TEXT_EMAIL || it == TEXT_WEB_EMAIL }

    /** [lang] only picks the word for a gender text box; the spoken [FillPlan.say] carries all three languages. */
    fun plan(fields: List<FieldNode>, p: FormProfile, today: LocalDate = LocalDate.now(), lang: Lang = Lang.EN): List<FillPlan> {
        val ordered = readingOrder(fields)
        val primary = ordered.map { f -> classifyPrimary(f) }
        val postal = primary.any { Labels.isAddress(it) }
        val keys = ordered.mapIndexed { i, f -> keyFor(f, primary[i], postal) }
        val hasMiddle = FieldKey.MIDDLE_NAME in keys
        val profile = p.sanitized()

        return ordered.mapIndexed { i, f ->
            val key = keys[i]
            val sensitive = f.password || isPasswordType(f.inputType) || key?.sensitive == true
            val dates = DateFmt.find(f.hint) ?: DateFmt.find(f.label) ?: DateFmt.DEFAULT
            val value = if (sensitive || key == null) null
                else profile.valueFor(key, today, lang, dates, hasMiddleBox = hasMiddle)
                    ?.takeUnless { key != FieldKey.MOBILE && FormProfile.looksSecret(it) }
            val say = when {
                sensitive -> FormSay.onlineSecret(key?.takeIf { it.sensitive } ?: FieldKey.PASSWORD)
                key == null -> FormSay.onlineUnknown(f.label ?: f.hint)
                key == FieldKey.CAPTCHA -> FormSay.captcha()
                key == FieldKey.USERNAME -> FormSay.username()
                value != null -> FormSay.onlineFill(key, value)
                else -> FormSay.onlineMissing(key)
            }
            // Last line of defence: nothing sensitive ever leaves with a value.
            FillPlan(f, if (sensitive && key?.sensitive != true) FieldKey.PASSWORD else key,
                if (sensitive) null else value, say, sensitive)
        }
    }

    private fun classifyPrimary(f: FieldNode): Labels.Cls? =
        Labels.classify(f.label) ?: Labels.classify(f.hint) ?: f.resId?.let { Labels.classify(Labels.idWords(it)) }

    private fun keyFor(f: FieldNode, primary: Labels.Cls?, postal: Boolean): FieldKey? {
        // Developer ids are terse and say what the field really is: `otp_input`, `et_mpin`, `cvv`.
        val fromId = f.resId?.let { Labels.classify(Labels.idWords(it)) }?.let { Labels.resolve(it, postal, false) }
        if (fromId?.sensitive == true) return fromId
        // A short hint ("Enter OTP") can reveal a secret behind a vague label; a long one ("OTP will be sent to this
        // number") is an explanation, not the field's name.
        val fromHint = f.hint?.takeIf { it.trim().split(Regex("\\s+")).size <= 4 }
            ?.let { Labels.classify(it) }?.let { Labels.resolve(it, postal, false) }
        if (fromHint?.sensitive == true) return fromHint
        val k = primary?.let { Labels.resolve(it, postal, false) }
        if (k != null) return k
        return if (primary == null && isEmailType(f.inputType)) FieldKey.EMAIL else null
    }

    /** Top-to-bottom by row (boxes overlapping half their height share a row), then left-to-right. */
    internal fun <T> readingOrder(items: List<T>, box: (T) -> Box): List<T> {
        val rows = mutableListOf<MutableList<T>>()
        for (it in items.sortedBy { box(it).t }) {
            val row = rows.lastOrNull()?.takeIf { r -> box(r.first()).rowOverlap(box(it)) >= 0.5f }
            if (row != null) row += it else rows += mutableListOf(it)
        }
        return rows.flatMap { r -> r.sortedBy { box(it).l } }
    }

    private fun readingOrder(fields: List<FieldNode>) = readingOrder(fields) { it.box }
}
