package com.saathi.app.forms

import android.content.Context
import com.saathi.app.guide.Lang
import java.time.LocalDate

enum class Gender(val en: String, val hi: String, val te: String) {
    MALE("Male", "पुरुष", "పురుషుడు"),
    FEMALE("Female", "महिला", "స్త్రీ"),
    OTHER("Other", "अन्य", "ఇతర");

    fun word(l: Lang) = when (l) { Lang.EN -> en; Lang.HI -> hi; Lang.TE -> te }
}

/**
 * The only data forms may use. Lives on the phone, in app-private SharedPreferences (`saathi_profile`).
 *
 * There is deliberately no field for Aadhaar, PAN, bank account, IFSC, card, OTP, PIN, password or signature, and
 * [sanitized] (run on every load and save) empties any field that looks like one of those numbers, so a number typed
 * into the wrong box in Settings is never stored or filled.
 */
data class FormProfile(
    val fullName: String = "",
    /** Optional family name, for forms with separate first/last boxes. Many Telugu names put it first. */
    val surname: String = "",
    val fatherName: String = "",
    val spouseName: String = "",
    val dob: LocalDate? = null,
    val gender: Gender? = null,
    val address1: String = "",
    val address2: String = "",
    val city: String = "",
    val district: String = "",
    val state: String = "",
    val pinCode: String = "",
    val mobile: String = "",
    val email: String = "",
    val occupation: String = "",
    val nomineeName: String = "",
    val nomineeRelation: String = "",
) {
    data class NameParts(val first: String?, val middle: String?, val last: String?)

    /**
     * Splits [fullName] for first/middle/last boxes. With a saved [surname] that starts or ends the name, that is the
     * last name. Otherwise the last word is. Without a middle box, the middle words stay with the first name
     * ("Ramesh Kumar" / "Sharma").
     */
    fun nameParts(hasMiddleBox: Boolean): NameParts {
        val words = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .dropWhile { it.trimEnd('.').lowercase() in TITLES }
        if (words.isEmpty()) return NameParts(null, null, null)
        val sur = surname.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val (rest, last) = when {
            sur.isNotEmpty() && words.size > sur.size && words.takeLast(sur.size).map { it.lowercase() } == sur.map { it.lowercase() } ->
                words.dropLast(sur.size) to words.takeLast(sur.size)
            sur.isNotEmpty() && words.size > sur.size && words.take(sur.size).map { it.lowercase() } == sur.map { it.lowercase() } ->
                words.drop(sur.size) to words.take(sur.size)
            words.size == 1 -> words to emptyList()
            else -> words.dropLast(1) to words.takeLast(1)
        }
        val lastS = last.joinToString(" ").ifBlank { null }
        return if (hasMiddleBox && rest.size > 1) NameParts(rest.first(), rest.drop(1).joinToString(" "), lastS)
        else NameParts(rest.joinToString(" "), null, lastS)
    }

    /** "12-4-56, Gandhi Road, Ameerpet, Hyderabad, Telangana - 500016" (district left out when it's the city). */
    fun fullAddress(): String? {
        val parts = listOf(address1, address2, city, district.takeUnless { it.equals(city, ignoreCase = true) } ?: "", state)
            .map { it.trim() }.filter { it.isNotEmpty() }
        val a = parts.joinToString(", ")
        return when {
            a.isEmpty() && pinCode.isEmpty() -> null
            pinCode.isEmpty() -> a
            a.isEmpty() -> pinCode
            else -> "$a - $pinCode"
        }
    }

    /**
     * The value for a box, or null when unknown or when the key is sensitive (never has a value).
     * [lang] picks the gender word; [dates] the date layout; [hasMiddleBox] the name split.
     */
    fun valueFor(key: FieldKey, today: LocalDate, lang: Lang = Lang.EN, dates: DateFmt = DateFmt.DEFAULT,
                 hasMiddleBox: Boolean = false, spacedDates: Boolean = false): String? {
        if (key.sensitive) return null
        fun d(x: LocalDate) = if (spacedDates) dates.spaced(x) else dates.format(x)
        val v = when (key) {
            FieldKey.FULL_NAME -> fullName
            FieldKey.FIRST_NAME -> nameParts(hasMiddleBox).first
            FieldKey.MIDDLE_NAME -> nameParts(true).middle
            FieldKey.LAST_NAME -> nameParts(hasMiddleBox).last
            FieldKey.FATHER_NAME -> fatherName
            FieldKey.SPOUSE_NAME -> spouseName
            FieldKey.RELATIVE_NAME -> fatherName.ifBlank { spouseName }
            FieldKey.DOB -> dob?.let(::d)
            FieldKey.AGE -> dob?.let { DateFmt.age(it, today).takeIf { a -> a in 1..130 }?.toString() }
            FieldKey.GENDER -> gender?.word(lang)
            FieldKey.ADDRESS -> fullAddress()
            FieldKey.ADDRESS1 -> address1
            FieldKey.ADDRESS2 -> address2
            FieldKey.CITY, FieldKey.PLACE -> city
            FieldKey.DISTRICT -> district
            FieldKey.STATE -> state
            FieldKey.PIN_CODE -> pinCode
            FieldKey.MOBILE -> mobile
            FieldKey.EMAIL -> email
            FieldKey.OCCUPATION -> occupation
            FieldKey.NOMINEE_NAME -> nomineeName
            FieldKey.NOMINEE_RELATION -> nomineeRelation
            FieldKey.DATE -> d(today)
            // The person chooses / reads these; Saathi never has a value.
            FieldKey.USERNAME, FieldKey.CAPTCHA -> null
            else -> null
        }
        return v?.trim()?.ifBlank { null }
    }

    /** A copy with anything that looks like a secret number, or an invalid mobile / PIN code / email, emptied. */
    fun sanitized(): FormProfile {
        fun safe(s: String) = s.trim().takeUnless { looksSecret(it) } ?: ""
        return copy(
            fullName = safe(fullName), surname = safe(surname), fatherName = safe(fatherName), spouseName = safe(spouseName),
            address1 = safe(address1), address2 = safe(address2), city = safe(city), district = safe(district),
            state = safe(state), occupation = safe(occupation), nomineeName = safe(nomineeName),
            nomineeRelation = safe(nomineeRelation),
            pinCode = pinCode.filter { it.isDigit() }.takeIf { PIN.matches(it) } ?: "",
            mobile = normaliseMobile(mobile),
            email = email.trim().takeIf { EMAIL.matches(it) } ?: "",
        )
    }

    fun toMap(): Map<String, String> = sanitized().run {
        mapOf(
            "full_name" to fullName, "surname" to surname, "father" to fatherName, "spouse" to spouseName,
            "dob" to (dob?.toString() ?: ""), "gender" to (gender?.name ?: ""),
            "addr1" to address1, "addr2" to address2, "city" to city, "district" to district, "state" to state,
            "pin" to pinCode, "mobile" to mobile, "email" to email, "occupation" to occupation,
            "nominee" to nomineeName, "nominee_rel" to nomineeRelation,
        )
    }

    /** Writes the (sanitized) profile to app-private storage. */
    fun save(ctx: Context) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        toMap().forEach { (k, v) -> if (v.isNotEmpty()) e.putString(k, v) }
        e.apply()
    }

    companion object {
        const val PREFS = "saathi_profile"
        private val TITLES = setOf("mr", "mrs", "ms", "miss", "shri", "sri", "smt", "kum", "dr", "श्री", "श्रीमती", "कुमारी", "डॉ")
        private val PIN = Regex("[1-9]\\d{5}")
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+")
        private val PAN_LIKE = Regex("(?i)\\b[a-z]{5}\\s?\\d{4}\\s?[a-z]\\b")
        private val IFSC_LIKE = Regex("(?i)\\b[a-z]{4}0[a-z0-9]{6}\\b")
        /** 9+ digits in a row, allowing single spaces/hyphens: Aadhaar (12), card (13-19), bank account (9-18). */
        private val LONG_NUMBER = Regex("\\d(?:[ -]?\\d){8,}")

        fun looksSecret(s: String) = LONG_NUMBER.containsMatchIn(s) || PAN_LIKE.containsMatchIn(s) || IFSC_LIKE.containsMatchIn(s)

        /** "+91 98765-43210" / "098765 43210" → "9876543210"; anything that isn't an Indian mobile → "". */
        fun normaliseMobile(s: String): String {
            var d = s.filter { it.isDigit() }
            if (d.length == 12 && d.startsWith("91")) d = d.drop(2)
            if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
            return if (Regex("[6-9]\\d{9}").matches(d)) d else ""
        }

        fun fromMap(m: Map<String, String?>): FormProfile {
            fun g(k: String) = m[k]?.trim() ?: ""
            return FormProfile(
                fullName = g("full_name"), surname = g("surname"), fatherName = g("father"), spouseName = g("spouse"),
                dob = runCatching { LocalDate.parse(g("dob")) }.getOrNull(),
                gender = runCatching { Gender.valueOf(g("gender")) }.getOrNull(),
                address1 = g("addr1"), address2 = g("addr2"), city = g("city"), district = g("district"), state = g("state"),
                pinCode = g("pin"), mobile = g("mobile"), email = g("email"), occupation = g("occupation"),
                nomineeName = g("nominee"), nomineeRelation = g("nominee_rel"),
            ).sanitized()
        }

        fun load(ctx: Context): FormProfile {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return fromMap(sp.all.mapValues { it.value as? String })
        }
    }
}
