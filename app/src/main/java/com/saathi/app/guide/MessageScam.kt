package com.saathi.app.guide

/** Scam patterns in incoming SMS / WhatsApp text (India-specific). Pure and unit-tested. */
object MessageScam {
    data class Hit(val id: String, val say: Say)

    private fun r(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val rules = listOf(
        r("(share|tell|send|give|forward).{0,20}\\b(otp|one time password)|\\botp\\b.{0,30}(share|tell|send|call|executive)") to Hit("otp", say(
            "This message asks for your OTP. Never share an OTP with anyone, not even the bank.",
            "यह संदेश OTP माँग रहा है। OTP किसी को मत बताइए, बैंक को भी नहीं।",
            "ఈ సందేశం OTP అడుగుతోంది. OTP ఎవరికీ చెప్పకండి, బ్యాంకుకు కూడా.")),
        r("kyc.{0,40}(block|suspend|expire|update|pending|deactivat)|(block|suspend|deactivat).{0,40}kyc") to Hit("kyc", say(
            "This says your KYC will be blocked. That's a common scam. Don't click the link; visit your bank branch.",
            "इसमें लिखा है कि KYC बंद होगा। यह आम धोखा है। लिंक मत खोलिए; बैंक शाखा जाइए।",
            "KYC బ్లాక్ అవుతుందని ఉంది. ఇది సాధారణ మోసం. లింక్ తెరవకండి; బ్యాంకు శాఖకు వెళ్ళండి.")),
        r("(electricity|power|bijli|बिजली).{0,60}(disconnect|cut|कट).{0,40}(tonight|today|आज)") to Hit("electricity", say(
            "This says your electricity will be cut tonight. Electricity boards don't send such messages. Don't call that number.",
            "इसमें लिखा है कि आज रात बिजली कटेगी। बिजली विभाग ऐसे संदेश नहीं भेजता। उस नंबर पर फ़ोन मत कीजिए।",
            "ఈ రాత్రి కరెంట్ కట్ అవుతుందని ఉంది. విద్యుత్ శాఖ ఇలా పంపదు. ఆ నంబర్‌కు ఫోన్ చేయకండి.")),
        r("(lottery|you (have )?won|lucky draw|prize|cash ?back|refund|reward|kbc).{0,80}(click|link|http|call|claim|whatsapp)") to Hit("prize", say(
            "This promises a prize or refund. It's a trick to take your money. Don't reply or click.",
            "यह इनाम या रिफ़ंड का लालच है। यह पैसे लूटने की चाल है। जवाब मत दीजिए, लिंक मत खोलिए।",
            "ఇది బహుమతి లేదా రీఫండ్ ఆశ చూపిస్తోంది. ఇది మోసం. జవాబు ఇవ్వకండి, లింక్ నొక్కకండి.")),
        r("(bit\\.ly|tinyurl|t\\.ly|cutt\\.ly|rb\\.gy|is\\.gd|\\.apk\\b)") to Hit("link", say(
            "This message has a hidden link. Don't open it unless you know exactly who sent it.",
            "इस संदेश में छुपा हुआ लिंक है। भेजने वाले को पक्का न जानें तो मत खोलिए।",
            "ఈ సందేశంలో దాచిన లింక్ ఉంది. పంపినవారు ఎవరో ఖచ్చితంగా తెలియకపోతే తెరవకండి.")),
        r("(anydesk|teamviewer|quicksupport|rustdesk|screen ?share)") to Hit("remote", say(
            "This asks you to install a screen-sharing app. Never do that for someone you don't know.",
            "यह स्क्रीन दिखाने वाला ऐप डालने को कह रहा है। अनजान के कहने पर कभी मत डालिए।",
            "ఇది స్క్రీన్ షేర్ యాప్ వేయమంటోంది. తెలియని వారి కోసం ఎప్పుడూ వేయకండి.")),
    )

    fun check(text: String): Hit? = rules.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.second
}
