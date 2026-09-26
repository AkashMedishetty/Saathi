package com.saathi.app.guide

/** Always-on, offline scam detection over whatever text is on screen. */
object ScamGuard {
    data class Alert(val id: String, val say: Say, val safe: List<Regex>)

    /** The buttons we point at instead: the way out. */
    private val SAFE = rx("^Decline$", "^Reject$", "^Cancel$", "^Deny$", "^Not now$", "^Close$", "^Block$")
    private fun r(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val rules = listOf(
        r("(enter|use|type).{0,25}pin.{0,40}(receive|get|claim|credit)") to Alert("pin_to_receive", say(
            "Stop! This is a scam. You never need your PIN to receive money. Tap Decline.",
            "रुकिए! यह धोखा है। पैसे पाने के लिए कभी PIN नहीं डालना पड़ता। 'Decline' दबाइए।",
            "ఆగండి! ఇది మోసం. డబ్బు అందుకోవడానికి PIN అవసరం లేదు. 'Decline' నొక్కండి."), SAFE),
        r("lottery|lucky draw|you (have )?won|\\bKBC\\b|cash ?prize") to Alert("lottery", say(
            "Careful! Nobody gives free prize money. This is a trick to take your money. Tap Decline.",
            "सावधान! कोई मुफ़्त इनाम नहीं देता। यह पैसे लूटने की चाल है। 'Decline' दबाइए।",
            "జాగ్రత్త! ఎవరూ ఉచితంగా బహుమతి ఇవ్వరు. ఇది మోసం. 'Decline' నొక్కండి."), SAFE),
        r("anydesk|teamviewer|quicksupport|rustdesk") to Alert("remote", say(
            "Careful! Never let a stranger see or control your phone. Close this now.",
            "सावधान! किसी अनजान को अपना फ़ोन देखने या चलाने मत दीजिए। इसे अभी बंद कीजिए।",
            "జాగ్రత్త! తెలియని వారికి మీ ఫోన్ చూపించకండి. ఇప్పుడే మూసేయండి."), SAFE),
        r("kyc.{0,30}(expire|block|suspend|update now)") to Alert("kyc", say(
            "Careful! Banks never ask you to update KYC through a link or a call. Close this and call your bank branch.",
            "सावधान! बैंक कभी लिंक या फ़ोन पर KYC अपडेट नहीं करवाता। इसे बंद कीजिए।",
            "జాగ్రత్త! బ్యాంకులు లింక్ ద్వారా KYC అడగవు. దీన్ని మూసేయండి."), SAFE),
    )

    fun check(s: Screen): Alert? = rules.firstOrNull { (re, _) -> re.containsMatchIn(s.allText) }?.second
}
