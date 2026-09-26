package com.saathi.app.forms

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say
import com.saathi.app.guide.pick
import com.saathi.app.guide.say

/** Every sentence the form helper says. Short, one instruction per sentence, EN / HI / TE. */
internal object FormSay {
    private fun w(k: FieldKey, l: Lang) = k.what.pick(l)

    /** Mobile numbers are read out in two groups so they can be copied: "98765 43210". */
    fun spoken(k: FieldKey, v: String) = if (k == FieldKey.MOBILE && v.length == 10) v.take(5) + " " + v.drop(5) else v

    // ── Paper ──

    fun paperWrite(k: FieldKey, v: String) = spoken(k, v).let { s ->
        say("Write ${w(k, Lang.EN)} here: $s.", "यहाँ ${w(k, Lang.HI)} लिखिए: $s।", "ఇక్కడ ${w(k, Lang.TE)} రాయండి: $s.")
    }

    fun paperMissing(k: FieldKey) = say(
        "Write ${w(k, Lang.EN)} here. I don't have it saved.",
        "यहाँ ${w(k, Lang.HI)} लिखिए। यह मेरे पास सेव नहीं है।",
        "ఇక్కడ ${w(k, Lang.TE)} రాయండి. ఇది నా దగ్గర సేవ్ అవ్వలేదు.")

    fun tick(option: String) = say(
        "Tick “$option”.", "“$option” पर सही का निशान लगाइए।", "“$option” దగ్గర టిక్ పెట్టండి.")

    fun tickYours() = say("Tick your gender.", "अपने लिंग पर सही का निशान लगाइए।", "మీ లింగం దగ్గర టిక్ పెట్టండి.")

    fun sign() = say("Sign here.", "यहाँ हस्ताक्षर कीजिए।", "ఇక్కడ సంతకం చేయండి.")

    /** Aadhaar / PAN / account / IFSC / anything secret on paper. */
    fun paperSecret(k: FieldKey): Say = when (k) {
        FieldKey.SIGNATURE -> sign()
        FieldKey.AADHAAR, FieldKey.PAN -> say(
            "Write ${w(k, Lang.EN)} yourself. Copy it from your card. Don't tell anyone.",
            "${w(k, Lang.HI)} ख़ुद लिखिए। अपने कार्ड से देखकर लिखिए। किसी को मत बताइए।",
            "${w(k, Lang.TE)} మీరే రాయండి. మీ కార్డు చూసి రాయండి. ఎవరికీ చెప్పకండి.")
        FieldKey.ACCOUNT_NUMBER, FieldKey.IFSC -> say(
            "Write ${w(k, Lang.EN)} yourself. Copy it from your passbook. Don't tell anyone.",
            "${w(k, Lang.HI)} ख़ुद लिखिए। पासबुक से देखकर लिखिए। किसी को मत बताइए।",
            "${w(k, Lang.TE)} మీరే రాయండి. పాస్‌బుక్ చూసి రాయండి. ఎవరికీ చెప్పకండి.")
        else -> say("Write this yourself. Don't tell anyone.", "यह ख़ुद लिखिए। किसी को मत बताइए।",
            "ఇది మీరే రాయండి. ఎవరికీ చెప్పకండి.")
    }

    fun unknown(label: String) = q(label).let { l ->
        say("I'm not sure what goes here: $l. Ask your family if you're unsure.",
            "मुझे पक्का नहीं पता कि यहाँ क्या लिखना है: $l। शक हो तो परिवार से पूछिए।",
            "ఇక్కడ ఏమి రాయాలో నాకు తెలియదు: $l. సందేహం ఉంటే కుటుంబాన్ని అడగండి.")
    }

    fun forNominee(label: String) = q(label).let { l ->
        say("This box is for your nominee: $l. Write their details here.",
            "यह ख़ाना नामांकित व्यक्ति के लिए है: $l। इसमें उनकी जानकारी लिखिए।",
            "ఈ గడి మీ నామినీ కోసం: $l. ఇందులో వారి వివరాలు రాయండి.")
    }

    fun forOtherPerson(label: String) = q(label).let { l ->
        say("This box is about another person, not you: $l. Fill it with their details.",
            "यह ख़ाना किसी और व्यक्ति के बारे में है: $l। इसमें उनकी जानकारी लिखिए।",
            "ఈ గడి వేరే వ్యక్తి గురించి: $l. ఇందులో వారి వివరాలు రాయండి.")
    }

    /** Nothing found on the photo. Telugu print is the common reason: ML Kit cannot read it. */
    fun nothingFound() = say(
        "I couldn't find any boxes to fill on this paper. Hold the phone straight above it and try again. I can't read Telugu print yet.",
        "इस काग़ज़ पर भरने के ख़ाने नहीं मिले। फ़ोन को काग़ज़ के ठीक ऊपर रखकर फिर कोशिश कीजिए। मैं अभी तेलुगु छपाई नहीं पढ़ सकता।",
        "ఈ కాగితంలో నింపాల్సిన గడులు కనిపించలేదు. ఫోన్‌ను కాగితానికి నేరుగా పైన పట్టుకుని మళ్ళీ ప్రయత్నించండి. నేను ఇంకా తెలుగు అచ్చు చదవలేను.")

    // ── Online ──

    fun onlineFill(k: FieldKey, v: String) = spoken(k, v).let { s ->
        say("This box is for ${w(k, Lang.EN)}: $s. Press “Do it for me” and I'll type it.",
            "इस ख़ाने में ${w(k, Lang.HI)} लिखना है: $s। “आप कर दो” दबाइए, मैं लिख दूँगा।",
            "ఈ గడిలో ${w(k, Lang.TE)} రాయాలి: $s. “మీరే చేయండి” నొక్కండి, నేను టైప్ చేస్తాను.")
    }

    fun onlineMissing(k: FieldKey) = say(
        "This box is for ${w(k, Lang.EN)}. I don't have it saved, so please type it.",
        "इस ख़ाने में ${w(k, Lang.HI)} लिखना है। यह मेरे पास सेव नहीं है, कृपया ख़ुद लिखिए।",
        "ఈ గడిలో ${w(k, Lang.TE)} రాయాలి. ఇది నా దగ్గర లేదు, దయచేసి మీరే టైప్ చేయండి.")

    fun captcha() = say("Type the letters you see in the picture.", "तस्वीर में दिख रहे अक्षर लिखिए।",
        "బొమ్మలో కనిపించే అక్షరాలు టైప్ చేయండి.")

    fun username() = say("Choose a user name and type it here. Write it in your diary too.",
        "एक यूज़र नेम चुनकर यहाँ लिखिए। इसे अपनी डायरी में भी लिख लीजिए।",
        "ఒక యూజర్ నేమ్ ఎంచుకుని ఇక్కడ టైప్ చేయండి. దాన్ని మీ డైరీలో కూడా రాసుకోండి.")

    fun onlineSecret(k: FieldKey): Say = when (k) {
        FieldKey.OTP -> say("Type the OTP yourself. Never tell it to anyone, even on a call.",
            "OTP ख़ुद लिखिए। इसे किसी को मत बताइए, फ़ोन पर भी नहीं।",
            "OTP మీరే టైప్ చేయండి. ఫోన్‌లో కూడా ఎవరికీ చెప్పకండి.")
        FieldKey.AADHAAR, FieldKey.PAN -> say(
            "Type ${w(k, Lang.EN)} yourself. Copy it from your card. Don't tell anyone.",
            "${w(k, Lang.HI)} ख़ुद लिखिए। अपने कार्ड से देखकर लिखिए। किसी को मत बताइए।",
            "${w(k, Lang.TE)} మీరే టైప్ చేయండి. మీ కార్డు చూసి రాయండి. ఎవరికీ చెప్పకండి.")
        FieldKey.SIGNATURE -> say("Sign this yourself.", "यह हस्ताक्षर ख़ुद कीजिए।", "ఈ సంతకం మీరే చేయండి.")
        else -> say("Type ${w(k, Lang.EN)} yourself. Don't tell anyone.",
            "${w(k, Lang.HI)} ख़ुद लिखिए। किसी को मत बताइए।",
            "${w(k, Lang.TE)} మీరే టైప్ చేయండి. ఎవరికీ చెప్పకండి.")
    }

    fun onlineUnknown(label: String?) = if (label.isNullOrBlank()) say(
        "I can't tell what this box is for. Ask your family if you're unsure.",
        "मुझे नहीं पता यह ख़ाना किस लिए है। शक हो तो परिवार से पूछिए।",
        "ఈ గడి దేనికో నాకు తెలియదు. సందేహం ఉంటే కుటుంబాన్ని అడగండి.")
    else q(label).let { l ->
        say("I'm not sure what this box is for: $l. Please type it yourself.",
            "मुझे पक्का नहीं पता यह ख़ाना किस लिए है: $l। कृपया ख़ुद लिखिए।",
            "ఈ గడి దేనికో నాకు సరిగ్గా తెలియదు: $l. దయచేసి మీరే టైప్ చేయండి.")
    }

    private fun q(label: String) = "“" + label.trim().trimEnd(':', '-', ' ', '*').take(40) + "”"
}
