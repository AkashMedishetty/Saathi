package com.saathi.app.forms

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure logic behind the form screens (FormActivity / ProfileActivity), so it is unit-tested on the JVM. */
object FormUi {

    // ───────────────────────── is the photo usable? ─────────────────────────

    enum class Frame { OK, DARK, BLANK }

    /** Mean luminance below this = too dark to read. */
    const val DARK_MEAN = 25.0
    /**
     * Luminance spread below this = nothing on it (lens covered, a wall, a blown-out white frame). A sparse printed
     * page can come close, so FormActivity only uses BLANK to explain an empty OCR result, never to refuse a photo.
     */
    const val FLAT_STD = 8.0

    /** [luma] = luminance 0..255 of the pixels of a small downscaled copy of the photo (e.g. 160 px wide). */
    fun frame(luma: DoubleArray): Frame {
        if (luma.isEmpty()) return Frame.BLANK
        var sum = 0.0; var sq = 0.0
        for (y in luma) { sum += y; sq += y * y }
        val mean = sum / luma.size
        val std = sqrt(max(0.0, sq / luma.size - mean * mean))
        return when {
            mean < DARK_MEAN -> Frame.DARK
            std < FLAT_STD -> Frame.BLANK
            else -> Frame.OK
        }
    }

    /** ARGB pixels (Bitmap.getPixels) → luminance. */
    fun luma(argb: IntArray) = DoubleArray(argb.size) { luminance(argb[it]) }

    /** Rec. 601 luma, 0..255. */
    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    fun frameSay(f: Frame): Say = when (f) {
        Frame.DARK -> say("It's too dark. Hold the paper in the light and try again.",
            "बहुत अँधेरा है। काग़ज़ को रोशनी में रखकर फिर कोशिश कीजिए।",
            "చాలా చీకటిగా ఉంది. కాగితాన్ని వెలుతురులో పెట్టి మళ్ళీ ప్రయత్నించండి.")
        Frame.BLANK -> say("I can't see any writing. Hold the phone straight above the paper and try again.",
            "मुझे कोई लिखावट नहीं दिख रही। फ़ोन को काग़ज़ के ठीक ऊपर रखकर फिर कोशिश कीजिए।",
            "నాకు ఏ రాతా కనిపించట్లేదు. ఫోన్‌ను కాగితానికి నేరుగా పైన పట్టుకుని మళ్ళీ ప్రయత్నించండి.")
        Frame.OK -> say("", "", "")
    }

    // ───────────────────────── walking through the fields ─────────────────────────

    /** Back / Next over the fields. Immutable: each move returns a new state. */
    data class Walk(val fields: List<PaperField>, val index: Int = 0) {
        val current: PaperField? get() = fields.getOrNull(index)
        val isFirst get() = index <= 0
        val isLast get() = index >= fields.lastIndex
        fun next() = if (isLast) this else copy(index = index + 1)
        fun back() = if (isFirst) this else copy(index = index - 1)

        /** "Field 3 of 11" / "11 में से ख़ाना 3" / "11 లో గడి 3". */
        fun progress(l: Lang): String = say("Field ${index + 1} of ${fields.size}", "${fields.size} में से ख़ाना ${index + 1}",
            "${fields.size} లో గడి ${index + 1}").pick(l)

        /** What goes in the big "copy this" box: never anything for a secret. */
        val bigValue: String? get() = current?.takeUnless { it.sensitive }?.value
    }

    fun lastFieldSay(): Say = say(
        "That was the last box. Read the form once more before you hand it in.",
        "यह आख़िरी ख़ाना था। देने से पहले फ़ॉर्म एक बार फिर पढ़ लीजिए।",
        "ఇదే చివరి గడి. ఇచ్చే ముందు ఫారం ఒకసారి మళ్ళీ చదవండి.")

    fun introSay(): Say = say(
        "Hold the phone above the form so the whole page fits, then tap the big button.",
        "फ़ोन को फ़ॉर्म के ऊपर रखिए ताकि पूरा पन्ना दिखे, फिर बड़ा बटन दबाइए।",
        "పేజీ మొత్తం కనిపించేలా ఫోన్‌ను ఫారం పైన పట్టుకుని, పెద్ద బటన్ నొక్కండి.")

    fun foundSay(n: Int): Say = say(
        "I found $n boxes. I'll show you one at a time.",
        "मुझे $n ख़ाने मिले। मैं एक-एक करके दिखाऊँगा।",
        "నాకు $n గడులు కనిపించాయి. ఒక్కొక్కటిగా చూపిస్తాను.")

    fun profileEmptySay(): Say = say(
        "Tell me your details once in Settings, My details. Then I can help with forms.",
        "एक बार सेटिंग में “मेरी जानकारी” भर दीजिए। फिर मैं फ़ॉर्म भरने में मदद कर सकूँगा।",
        "ఒకసారి సెట్టింగ్స్‌లో “నా వివరాలు” నింపండి. అప్పుడు ఫారాలు నింపడంలో సహాయం చేస్తాను.")

    /** Nothing the forms could use. */
    fun profileIsEmpty(p: FormProfile) = p.sanitized().let {
        it.fullName.isBlank() && it.dob == null && it.mobile.isBlank() && it.fullAddress() == null
    }

    // ───────────────────────── the zoomed photo ─────────────────────────

    /**
     * The part of the photo to show for one field: the label and its write box with room around them, at least
     * [minFrac] of the photo wide (so the person still sees where on the page they are), in the view's aspect ratio,
     * kept inside the photo. Returns the whole photo when the field is too big to zoom.
     */
    fun viewport(label: Box, write: Box, photoW: Int, photoH: Int, viewW: Int, viewH: Int, minFrac: Float = 0.6f): Box {
        val whole = Box(0, 0, photoW, photoH)
        if (photoW <= 0 || photoH <= 0 || viewW <= 0 || viewH <= 0) return whole
        val l = min(label.l, write.l); val t = min(label.t, write.t)
        val r = max(label.r, write.r); val b = max(label.b, write.b)
        val pad = max((b - t) * 0.8f, photoW * 0.04f)
        var w = max((r - l) + 2 * pad, photoW * minFrac)
        var h = (b - t) + 2 * pad
        val aspect = viewW.toFloat() / viewH
        if (w / h > aspect) h = w / aspect else w = h * aspect
        if (w >= photoW || h >= photoH) {
            // Too big to zoom: fit the whole photo, but still at the view's aspect ratio around it.
            return whole
        }
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val x0 = (cx - w / 2).coerceIn(0f, photoW - w)
        val y0 = (cy - h / 2).coerceIn(0f, photoH - h)
        return Box(x0.toInt(), y0.toInt(), (x0 + w).toInt(), (y0 + h).toInt())
    }

    // ───────────────────────── saving "My details" ─────────────────────────

    /** Profile fields as shown in "My details", in screen order. */
    enum class Detail(val label: Say) {
        FULL_NAME(say("Full name", "पूरा नाम", "పూర్తి పేరు")),
        SURNAME(say("Surname (family name)", "उपनाम (सरनेम)", "ఇంటి పేరు")),
        FATHER(say("Father's name", "पिता का नाम", "తండ్రి పేరు")),
        SPOUSE(say("Husband's or wife's name", "पति या पत्नी का नाम", "భర్త లేదా భార్య పేరు")),
        ADDRESS1(say("House number and street", "मकान नंबर और गली", "ఇంటి నంబరు, వీధి")),
        ADDRESS2(say("Area / locality", "इलाका / मोहल्ला", "ప్రాంతం")),
        CITY(say("City or town", "शहर या कस्बा", "ఊరు / నగరం")),
        DISTRICT(say("District", "ज़िला", "జిల్లా")),
        STATE(say("State", "राज्य", "రాష్ట్రం")),
        PIN(say("PIN code", "पिन कोड", "పిన్ కోడ్")),
        MOBILE(say("Mobile number", "मोबाइल नंबर", "మొబైల్ నంబర్")),
        EMAIL(say("Email", "ईमेल", "ఈమెయిల్")),
        OCCUPATION(say("Occupation", "काम या व्यवसाय", "వృత్తి")),
        NOMINEE(say("Nominee's name", "नामांकित व्यक्ति का नाम", "నామినీ పేరు")),
        NOMINEE_REL(say("Nominee's relation to you", "नामांकित व्यक्ति से रिश्ता", "నామినీతో బంధుత్వం")),
    }

    fun get(p: FormProfile, d: Detail): String = when (d) {
        Detail.FULL_NAME -> p.fullName; Detail.SURNAME -> p.surname; Detail.FATHER -> p.fatherName
        Detail.SPOUSE -> p.spouseName; Detail.ADDRESS1 -> p.address1; Detail.ADDRESS2 -> p.address2
        Detail.CITY -> p.city; Detail.DISTRICT -> p.district; Detail.STATE -> p.state; Detail.PIN -> p.pinCode
        Detail.MOBILE -> p.mobile; Detail.EMAIL -> p.email; Detail.OCCUPATION -> p.occupation
        Detail.NOMINEE -> p.nomineeName; Detail.NOMINEE_REL -> p.nomineeRelation
    }

    fun set(p: FormProfile, d: Detail, v: String): FormProfile = when (d) {
        Detail.FULL_NAME -> p.copy(fullName = v); Detail.SURNAME -> p.copy(surname = v); Detail.FATHER -> p.copy(fatherName = v)
        Detail.SPOUSE -> p.copy(spouseName = v); Detail.ADDRESS1 -> p.copy(address1 = v); Detail.ADDRESS2 -> p.copy(address2 = v)
        Detail.CITY -> p.copy(city = v); Detail.DISTRICT -> p.copy(district = v); Detail.STATE -> p.copy(state = v)
        Detail.PIN -> p.copy(pinCode = v); Detail.MOBILE -> p.copy(mobile = v); Detail.EMAIL -> p.copy(email = v)
        Detail.OCCUPATION -> p.copy(occupation = v); Detail.NOMINEE -> p.copy(nomineeName = v)
        Detail.NOMINEE_REL -> p.copy(nomineeRelation = v)
    }

    /** Why the sanitiser emptied a box: it looked like a secret number, or it isn't a valid mobile / PIN / email. */
    enum class Dropped { SECRET, INVALID }

    /** The boxes the person typed something into that [FormProfile.sanitized] emptied. */
    fun dropped(raw: FormProfile): Map<Detail, Dropped> {
        val clean = raw.sanitized()
        return Detail.entries.filter { get(raw, it).isNotBlank() && get(clean, it).isBlank() }.associateWith { d ->
            val v = get(raw, d)
            if (FormProfile.looksSecret(v) && !(d == Detail.MOBILE && FormProfile.normaliseMobile(v).isNotEmpty())) Dropped.SECRET
            else Dropped.INVALID
        }
    }

    fun droppedSay(d: Map<Detail, Dropped>): Say? {
        if (d.isEmpty()) return null
        val secret = d.values.any { it == Dropped.SECRET }
        val invalid = d.filterValues { it == Dropped.INVALID }.keys
        val lines = mutableListOf<Say>()
        if (secret) lines += say("Saathi never stores Aadhaar, PAN or bank numbers, so I left that out.",
            "साथी आधार, पैन या बैंक नंबर कभी नहीं रखता, इसलिए वह नहीं रखा।",
            "సాథీ ఆధార్, పాన్, బ్యాంక్ నంబర్లు ఎప్పుడూ దాచదు, అందుకే అది వదిలేశాను.")
        if (invalid.isNotEmpty()) {
            fun names(l: Lang) = invalid.joinToString(", ") { it.label.pick(l) }
            lines += say("Please check: ${names(Lang.EN)}.", "कृपया जाँचिए: ${names(Lang.HI)}।", "దయచేసి చూడండి: ${names(Lang.TE)}.")
        }
        return Lang.entries.associateWith { l -> lines.joinToString(" ") { it.pick(l) } }
    }
}
