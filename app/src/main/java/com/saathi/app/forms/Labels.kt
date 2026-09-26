package com.saathi.app.forms

/**
 * Label text → what the box asks for. English + Hindi (Devanagari); a few Telugu words for online forms
 * (ML Kit cannot read Telugu print, so paper forms never produce Telugu).
 *
 * The rules are ORDERED and the first match wins. The order is the safety logic:
 *  - secrets (OTP, password, CVV, PIN) come first, so "Mobile OTP" is an OTP, never a mobile number;
 *  - "Name as per Aadhaar" / "Account holder name" are names, checked before Aadhaar / account;
 *  - "Aadhaar-linked mobile" is a mobile number, checked before Aadhaar;
 *  - "PIN code" is postal, checked before a bare "PIN" (which is resolved by context: see [Kind.BARE_PIN]);
 *  - other people's details ("Mother's name", "Emergency contact", "Bank name") map to no key at all.
 * Hindi patterns are plain substrings: `\b` does not work across Devanagari matras.
 */
object Labels {
    enum class Kind {
        /** A normal label; [Cls.key] may be null (a real box, but nothing Saathi knows, e.g. "Mother's name"). */
        FIELD,
        /** "PIN" on its own: a PIN code on an address form, a secret PIN anywhere else. */
        BARE_PIN,
        /** "Relationship" on its own: the nominee's relation inside a nominee section, else unknown. */
        RELATION,
    }

    data class Cls(val key: FieldKey?, val kind: Kind = Kind.FIELD)

    private class Rule(p: String, val key: FieldKey?, val kind: Kind = Kind.FIELD) {
        val re = Regex(p, RegexOption.IGNORE_CASE)
    }

    private val RULES = listOf(
        // ── Secrets first (the captcha before them: "Enter the code shown in the image" is not an OTP) ──
        Rule("captcha|(text|code|letters|characters) (shown |you see )?(in |on )?(the )?(image|picture)|कैप्चा",
            FieldKey.CAPTCHA),
        Rule("\\botp\\b|one[ -]?time ?(password|pass ?code|pin|code)|verification code|enter (the )?(\\d[ -]?digit )?code\\b|" +
            "sms code|ओटीपी|ఓటీపీ", FieldKey.OTP),
        Rule("security (question|answer)|secret (question|answer)|maiden name|सुरक्षा प्रश्न", FieldKey.SECURITY_ANSWER),
        Rule("pass ?word|passcode|पासवर्ड|పాస్‌?వర్డ్", FieldKey.PASSWORD),
        Rule("\\bcvv\\d?\\b|\\bcvc\\b|card verification|security code", FieldKey.CVV),
        Rule("address.{0,25}(with|including|incl\\.?) (the )?pin|पता.{0,25}पिन", FieldKey.ADDRESS),
        Rule("pin ?code|pincode|postal ?code|post ?code|\\bzip\\b|पिन ?कोड|पिनकोड|పిన్ ?కోడ్", FieldKey.PIN_CODE),
        Rule("\\b(m|upi|atm|card|debit|login|transaction|txn|security|app|secret|wallet|\\d[ -]?digit)[ -]?pin\\b|\\bmpin\\b|\\bt ?pin\\b|enter (your )?pin\\b",
            FieldKey.SECRET_PIN),
        Rule("\\bpin\\b|पिन", null, Kind.BARE_PIN),
        Rule("\\bupi\\b|\\bvpa\\b", FieldKey.UPI_ID),
        Rule("expiry|expiration|valid (thru|through|till|upto)|\\bexp\\.? ?date", FieldKey.EXPIRY),

        // ── Names (before the ID rules, which also contain words like "card" / "account") ──
        Rule("name (as )?(per|in|on) (the )?(aadhaar|aadhar|pan|card|passbook|bank|certificate)|" +
            "(aadhaar|aadhar|card|account|a/c) ?holder'?s? ?name|आधार (के अनुसार|में|पर) नाम|खाताधारक का नाम", FieldKey.FULL_NAME),
        Rule("(bank|branch|company|employer|hospital|doctor|school|college|scheme|department|office|product|shop|firm|" +
            "organi[sz]ation|business|institution|referring)'?s? ?name|name of (the )?(bank|branch|company|employer|hospital|" +
            "doctor|school|college|scheme|department|office|organi[sz]ation|institution)|बैंक का नाम|शाखा", null),
        Rule("mother'?s?( maiden)? ?name|माता का नाम|माँ का नाम|मां का नाम|తల్లి పేరు", null),
        Rule("nominee.{0,12}(address|birth|\\bdob\\b|\\bage\\b|mobile|phone|signature|aadhaar|pan\\b)|" +
            "(address|birth|\\bage\\b|mobile|phone|signature) of (the )?nominee|" +
            "(नामांकित|नामिती|नॉमिनी).{0,20}(पता|जन्म|आयु|उम्र|मोबाइल|हस्ताक्षर)", null),
        Rule("nominee'?s? ?(relation|relationship)|relation(ship)? (with|to) (the )?nominee|" +
            "(नामांकित|नामिती|नॉमिनी).{0,20}(संबंध|रिश्ता)|నామినీ.{0,10}బంధుత్వ", FieldKey.NOMINEE_RELATION),
        Rule("nominee|नामांकित|नामिती|नॉमिनी|నామినీ", FieldKey.NOMINEE_NAME),
        Rule("father'?s? ?(or|/) ?husband|husband'?s? ?(or|/) ?father|\\b[sdw] ?/ ?o\\b|son of|daughter of|wife of|" +
            "guardian|पिता ?(/|या) ?पति|पति ?(/|या) ?पिता|अभिभावक|पुत्र/पुत्री/पत्नी", FieldKey.RELATIVE_NAME),
        Rule("father|पिता|తండ్రి", FieldKey.FATHER_NAME),
        Rule("husband|spouse|wife|पति|पत्नी|భర్త|భార్య", FieldKey.SPOUSE_NAME),
        Rule("first name|given name|fore ?name|पहला नाम|మొదటి పేరు", FieldKey.FIRST_NAME),
        Rule("middle name|बीच का नाम|మధ్య పేరు", FieldKey.MIDDLE_NAME),
        Rule("last name|sur ?name|family name|उपनाम|सरनेम|ఇంటి పేరు", FieldKey.LAST_NAME),
        Rule("user ?(name|id)|login ?(id|name)|(create|choose|make).{0,25}(gmail|e-?mail) address|यूज़र ?नेम|यूजर ?नेम", FieldKey.USERNAME),

        // ── Other ID numbers Saathi does not know (not secrets, but not in the profile) ──
        Rule("\\bration\\b|voter|\\bepic\\b|election|driving|licen[cs]e|passport|\\babha\\b|health id|\\buhid\\b|\\bmrn\\b|" +
            "patient id|registration (no|number)|\\bppo\\b|pension (payment order|id)|राशन|मतदाता|पेंशन भुगतान", null),

        // ── Secret / personal ID numbers ──
        Rule("(aadhaar|aadhar|आधार).{0,20}(linked|registered|से जुड़ा|से लिंक).{0,20}(mobile|phone|मोबाइल)|" +
            "(registered|linked) (mobile|phone)", FieldKey.MOBILE),
        Rule("aadhaar|aadhar|\\buid\\b|आधार|ఆధార్", FieldKey.AADHAAR),
        Rule("\\bpan\\b|permanent account|पैन|పాన్", FieldKey.PAN),
        Rule("\\bifsc?\\b|ifs code|आईएफएससी", FieldKey.IFSC),
        Rule("account ?(no|number|num)|\\ba/?c\\.? ?(no|number)|\\bacc(t)?\\.? ?(no|number)|bank account|" +
            "खाता ?(संख्या|नंबर|सं)|ఖాతా", FieldKey.ACCOUNT_NUMBER),
        Rule("card ?(no|number|num)|(debit|credit|atm) card|कार्ड (नंबर|संख्या)", FieldKey.CARD_NUMBER),
        Rule("signature|\\bsign\\b|हस्ताक्षर|अंगूठ|thumb|సంతకం", FieldKey.SIGNATURE),

        // ── The person's own details ──
        Rule("date of birth|birth ?date|\\bd\\.? ?o\\.? ?b\\b|born on|जन्म ?(की |का )?(तिथि|तारीख|दिनांक)|పుట్టిన తేదీ|జనన తేదీ", FieldKey.DOB),
        Rule("\\bage\\b|आयु|उम्र|వయస్సు", FieldKey.AGE),
        Rule("gender|\\bsex\\b|लिंग|లింగం", FieldKey.GENDER),
        Rule("emergency|alternate|alternative|landline|\\bstd\\b|relative'?s?|friend|reference|attendant|caretaker|" +
            "वैकल्पिक|आपातकाल|रिश्तेदार", null),
        Rule("mobile|phone|\\bcell\\b|contact ?(no|number)|\\bmob\\b|\\bph\\b|\\btel\\b|whats ?app|मोबाइल|फ़ोन|फोन|" +
            "మొబైల్|ఫోన్", FieldKey.MOBILE),
        Rule("e-? ?mail|ईमेल|ई-मेल|ఈమెయిల్", FieldKey.EMAIL),
        Rule("address ?(line)? ?(1|one|i)\\b|house|flat|door|building|street|plot|मकान|गली|ఇంటి నంబర", FieldKey.ADDRESS1),
        Rule("address ?(line)? ?(2|two|ii)\\b|locality|landmark|colony|\\barea\\b|mohalla|मोहल्ला|इलाका|कॉलोनी|ప్రాంతం",
            FieldKey.ADDRESS2),
        Rule("district|जिला|ज़िला|జిల్లా", FieldKey.DISTRICT),
        Rule("\\bcity\\b|\\btown\\b|village|शहर|नगर|गाँव|गांव|ఊరు|నగరం|గ్రామం", FieldKey.CITY),
        Rule("\\bstate\\b|राज्य|రాష్ట్రం", FieldKey.STATE),
        Rule("address|पता|पते|చిరునామా", FieldKey.ADDRESS),
        Rule("occupation|profession|employment|\\bjob\\b|व्यवसाय|पेशा|వృత్తి", FieldKey.OCCUPATION),
        Rule("^(place|स्थान|స్థలం)$", FieldKey.PLACE),
        Rule("^(date|dated|दिनांक|तारीख|తేదీ)$", FieldKey.DATE),
        Rule("relation(ship)?|रिश्ता|संबंध|బంధుత్వం", null, Kind.RELATION),
        Rule("full name|\\bname\\b|नाम|పేరు|^applicant$|^patient$", FieldKey.FULL_NAME),
    )

    /** "2. Father's Name (in capitals) :-" → "father's name". */
    fun normalise(s: String): String = s
        .replace('’', '\'').replace('‘', '\'').replace('`', '\'')
        .replace(Regex("\\([^)]*\\)"), " ")                         // "(in capital letters)"
        .replace(Regex("^\\s*(\\d{1,2}|[a-z]|[ivx]{1,4})\\s*[.)]\\s+", RegexOption.IGNORE_CASE), "") // "2." "(a)" "iv)"
        .replace(Regex("[:：*]+-?"), " ")
        .replace(Regex("\\s+"), " ")
        .trim().lowercase()

    /** "mobileNo", "et_first_name" → "mobile no", "et first name". */
    fun idWords(resId: String): String = resId.substringAfter('/')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[_.\\-]+"), " ")
        .trim().lowercase()

    /** The first rule that matches, or null when the text is not a label Saathi recognises. */
    fun classify(text: String?): Cls? {
        if (text.isNullOrBlank()) return null
        val n = normalise(text)
        if (n.isEmpty()) return null
        val rule = RULES.firstOrNull { it.re.containsMatchIn(n) } ?: return null
        return Cls(rule.key, rule.kind)
    }

    /**
     * Resolves the context-dependent kinds. [postal] = this form also asks for an address, so a bare "PIN" is the PIN
     * code; [nominee] = we are inside a nominee section.
     */
    fun resolve(c: Cls, postal: Boolean, nominee: Boolean): FieldKey? = when (c.kind) {
        Kind.FIELD -> c.key
        Kind.BARE_PIN -> if (postal) FieldKey.PIN_CODE else FieldKey.SECRET_PIN
        Kind.RELATION -> if (nominee) FieldKey.NOMINEE_RELATION else null
    }

    private val ADDRESS_KEYS = setOf(FieldKey.ADDRESS, FieldKey.ADDRESS1, FieldKey.ADDRESS2, FieldKey.CITY,
        FieldKey.DISTRICT, FieldKey.STATE, FieldKey.PIN_CODE)

    fun isAddress(c: Cls?) = c?.kind == Kind.FIELD && c.key in ADDRESS_KEYS
}
