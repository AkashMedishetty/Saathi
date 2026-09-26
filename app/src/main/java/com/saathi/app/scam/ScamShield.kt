package com.saathi.app.scam

import com.saathi.app.guide.Say
import com.saathi.app.guide.say
import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class MsgEvent(val app: String, val sender: String, val text: String, val isSavedFamily: Boolean = false)
data class ScreenEvent(
    val pkg: String, val prevPkg: String?, val texts: List<String>, val clickedText: String?,
    val isSavedFamily: Boolean = false,
)
enum class Level { CAUTION, STOP }
data class Warning(val id: String, val level: Level, val say: Say, val safeAction: String?)

/** Pure, stateless, offline rules. Never retains message text or performs the suggested action. */
object ScamShield {
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private fun String.has(s: String) = rx(s).containsMatchIn(this)
    private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\u200B\\uFEFF]"), "")
    private val messaging = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "org.telegram.messenger.web", "com.google.android.apps.messaging", "com.android.mms",
        "com.samsung.android.messaging", "com.vivo.mms", "com.facebook.orca", "org.thoughtcrime.securesms")
    private val browsers = setOf("com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.vivo.browser", "com.android.browser")
    private val files = setOf("com.google.android.documentsui", "com.android.documentsui",
        "com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.android.filemanager",
        "com.vivo.filemanager", "com.mi.android.globalfileexplorer")
    private val payments = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app",
        "net.one97.paytm", "in.org.npci.upiapp")
    private val dialers = setOf("com.google.android.dialer", "com.android.dialer", "com.android.incallui",
        "com.samsung.android.dialer", "com.samsung.android.incallui", "com.vivo.dialer")
    private val remote = "\\b(anydesk|teamviewer|quicksupport|rustdesk|airdroid)\\b|screen[ -]?shar(?:e|ing)|स्क्रीन शेयर|స్క్రీన్ షేర్"
    private val request = "\\b(install|download|open|share|send|tell|give|enter|type|provide|karo|karen|kijiye|bhejo|batao|dalo)\\b|करो|करें|कीजिए|भेजो|भेजें|बताओ|बताएं|बताएँ|डालें|डालो|खोलें|करें|చేయండి|పంపండి|చెప్పండి|నమోదు|డౌన్‌లోడ్|ఇన్‌స్టాల్"
    // Suppress advice only in its own sentence; another sentence can still contain a harmful request.
    private val advice = "\\b(never|don't|do not|dont|not required|no need|mat|nahi|nahin)\\b|मत |नहीं|कभी न |వద్దు|అవసరం లేదు|చేయకండి|ఇవ్వకండి|పంపకండి|చెప్పకండి"
    private data class Rule(val id: String, val level: Level, val matches: (String) -> Boolean)
    private fun both(t: String, a: String, b: String) = t.has(a) && t.has(b)
    private val rules = listOf(
        Rule("remote", Level.STOP) { it.has(remote) && it.replace(rx(remote), "").has(request) },
        Rule("upi", Level.STOP) {
            it.has("\\bcollect request\\b|कलेक्ट रिक्वेस्ट|కలెక్ట్ రిక్వెస్ట్") ||
                (both(it, "\\bpin\\b|पिन|పిన్", "receiv|collect|paise.{0,15}(pane|lene|aaye)|प्राप्त|पैसे.{0,15}(लेने|पाने|आए)|स्वीकार|డబ్బు.{0,15}(రావ|పొంద|వచ్చ|స్వీకర)") && it.has(request))
        },
        Rule("otp", Level.STOP) { both(it, "\\botp\\b|one.time password|ओटीपी|ఓటీపీ", request) &&
            it.has("\\b(share|send|tell|give|forward|provide|bhejo|batao)\\b|भेज|बता|సందేశం పంపండి|పంపండి|చెప్పండి") },
        Rule("kyc", Level.CAUTION) { both(it, "\\bkyc\\b|केवाईसी|కేవైసీ", "block|suspend|deactivat|band ho|बंद|ब्लॉक|నిలిపి|బ్లాక్") },
        Rule("electricity", Level.CAUTION) { both(it, "electricity|\\bpower\\b|bijli|बिजली|కరెంట్|విద్యుత్", "disconnect|cut|kat jay|काट|कट|కట్|నిలిపి") &&
            it.has("pay|call|contact|bharo|करें|भरो|చెల్లించండి|కాల్") },
        Rule("prize", Level.CAUTION) { both(it, "\\b(prize|lottery|refund|reward|kbc)\\b|you (have )?won|inaam|इनाम|लॉटरी|रिफंड|బహుమతి|లాటరీ|రీఫండ్", "click|claim|fee|link|bhejo|भेज|लिंक|शुल्क|లింక్|రుసుము") },
        Rule("job", Level.CAUTION) { both(it, "\\b(task|job)\\b|काम|टास्क|नौकरी|టాస్క్|ఉద్యోగం", "deposit|recharge|registration fee|jama karo|जमा|रिचार्ज|డిపాజిట్|రీచార్జ్") },
        Rule("parcel", Level.CAUTION) { both(it, "customs|parcel|पार्सल|कस्टम|పార్సిల్|కస్టమ్స్", "held|seized|detained|pakda|रोक|जब्त|పట్టుబడ|నిలిపి") &&
            it.has("pay|call|contact|fee|bharo|करें|भरो|చెల్లించండి|కాల్") },
        Rule("arrest", Level.STOP) { it.has("digital arrest|digital giraftar|डिजिटल गिरफ्ता|డిజిటల్ అరెస్ట్") ||
            (both(it, "\\b(police|cbi)\\b|पुलिस|సీబీఐ|పోలీసు", "arrest|giraftar|गिरफ्तार|అరెస్ట్") && it.has("pay|transfer|bhejo|भेज|చెల్లించండి|పంపండి")) },
        Rule("loan", Level.STOP) { both(it, "\\bloan\\b|karz|कर्ज|लोन|రుణం|లోన్", "leak|shame|expose|badnaam|बदनाम|फोटो.{0,20}भेज|ఫోటో.{0,20}పంప|పరువు") },
        Rule("link", Level.CAUTION) { suspiciousLink(it) },
    )

    fun onMessage(e: MsgEvent): Warning? = detect(e.text, e.app in messaging)?.family(e.isSavedFamily)

    fun onScreen(e: ScreenEvent): Warning? {
        if (e.pkg.contains("packageinstaller") && e.prevPkg in messaging + browsers + files)
            return warning("installer", Level.STOP, "back")
        val labels = e.texts + listOfNotNull(e.clickedText)
        // prevPkg=dialer must be supplied only when the integration knows a call is active.
        if (e.pkg == "com.android.vending" && e.prevPkg in dialers && labels.any { normalized(it).has(remote) })
            return warning("remote", Level.STOP).family(e.isSavedFamily)
        if (e.pkg in payments) {
            val upi = rules.first { it.id == "upi" }
            return labels.map(::normalized).firstOrNull { !it.has(advice) && upi.matches(it) }
                ?.let { warning("upi", Level.STOP).family(e.isSavedFamily) }
        }
        if (e.pkg !in messaging) return null
        return labels.mapNotNull { detect(it, true) }.minWithOrNull(
            compareBy<Warning> { if (it.id == "apk") 0 else if (it.level == Level.STOP) 1 else 2 }
        )?.family(e.isSavedFamily)
    }

    private fun detect(raw: String, allowApk: Boolean): Warning? {
        val text = normalized(raw)
        if (allowApk && text.has("(?<![\\p{L}\\p{N}_])[^\\s/]+\\.(?:xapk|apk)(?![\\p{L}\\p{N}_])|(?<![\\p{L}\\p{N}_])(?:apk|xapk)(?![\\p{L}\\p{N}_])"))
            return warning("apk", Level.STOP)
        // Do not split at dots in URLs, filenames or decimal amounts.
        val sentences = text.split(Regex("[!?।\\n]+|\\.(?=\\s|$)"))
        val actionable = sentences.filterNot { it.has(advice) }
        if (allowApk && actionable.any { it.has("install this app|ye(?:h)? app install|यह ऐप इंस्टॉल|यह ऐप डाउनलोड|ఈ యాప్.{0,10}(ఇన్‌స్టాల్|డౌన్‌లోడ్)") })
            return warning("apk", Level.STOP)
        return rules.firstOrNull { rule -> actionable.any(rule.matches) }?.let { warning(it.id, it.level) }
    }

    private fun suspiciousLink(text: String): Boolean {
        val candidates = rx("(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z0-9-]+(?::[0-9]+)?(?:/[^\\s]*)?").findAll(text)
        return candidates.any { match ->
            val token = match.value
            val host = runCatching { URI(if (token.startsWith("http")) token else "https://$token").host }.getOrNull() ?: return@any false
            val shorteners = setOf("bit.ly", "tinyurl.com", "t.ly", "cutt.ly", "rb.gy", "is.gd", "tiny.cc")
            host in shorteners || shorteners.any { host.endsWith(".$it") } ||
                (host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }) ||
                URI(if (token.startsWith("http")) token else "https://$token").path.orEmpty().has("\\.(xapk|apk)$") ||
                host.has("^(sbi|hdfc|icici|axis|pnb)[-](?:[a-z0-9-]*)(kyc|update|verify|bank)[a-z0-9-]*\\.")
        }
    }

    private fun Warning.family(saved: Boolean) = if (saved && id != "apk" && id != "installer") copy(level = Level.CAUTION) else this

    private fun warning(id: String, level: Level, action: String? = null): Warning = Warning(id, level, when (id) {
        "installer" -> say("An app from a message or download is about to install. Go back. Ask someone you trust to check it.", "संदेश या डाउनलोड से आया ऐप इंस्टॉल होने वाला है। वापस जाइए। किसी भरोसेमंद व्यक्ति से जाँच करवाइए।", "సందేశం లేదా డౌన్‌లోడ్ నుంచి వచ్చిన యాప్ ఇన్‌స్టాల్ కాబోతోంది. వెనక్కి వెళ్లండి. నమ్మకమైన వ్యక్తితో తనిఖీ చేయించండి.")
        "apk" -> say("Someone sent an app file. Don't open it. It can steal your money. Go back.", "किसी ने ऐप की फ़ाइल भेजी है। इसे मत खोलिए। इससे आपके पैसे चोरी हो सकते हैं। वापस जाइए।", "ఎవరో యాప్ ఫైల్ పంపారు. దాన్ని తెరవకండి. అది మీ డబ్బును దొంగిలించవచ్చు. వెనక్కి వెళ్లండి.")
        "remote" -> say("This app can let someone see or control your phone. Don't open it for a caller. End the call.", "इस ऐप से कोई आपका फ़ोन देख या चला सकता है। फ़ोन करने वाले के कहने पर इसे मत खोलिए। कॉल बंद कीजिए।", "ఈ యాప్‌తో మరొకరు మీ ఫోన్ చూడవచ్చు లేదా నడపవచ్చు. కాల్ చేసిన వారి కోసం దీన్ని తెరవకండి. కాల్ ముగించండి.")
        "upi" -> say("You never need a PIN to receive money. Don't enter it for this request. Go back.", "पैसे पाने के लिए कभी पिन नहीं चाहिए। इस अनुरोध के लिए पिन मत डालिए। वापस जाइए।", "డబ్బు పొందడానికి పిన్ అవసరం లేదు. ఈ అభ్యర్థన కోసం పిన్ నమోదు చేయకండి. వెనక్కి వెళ్లండి.")
        "otp" -> say("This asks for your secret code. Don't share it. Ask someone you trust for help.", "इसमें आपका गुप्त कोड माँगा गया है। इसे मत बताइए। किसी भरोसेमंद व्यक्ति से मदद माँगिए।", "ఇది మీ రహస్య కోడ్ అడుగుతోంది. దాన్ని చెప్పకండి. నమ్మకమైన వ్యక్తిని సహాయం అడగండి.")
        "kyc" -> say("This threatens to block your account. Don't use its link. Check at your bank branch.", "यह खाता बंद करने की धमकी है। इसका लिंक मत खोलिए। बैंक शाखा में पूछिए।", "ఇది మీ ఖాతాను ఆపేస్తామని బెదిరిస్తోంది. దీని లింక్ తెరవకండి. బ్యాంకు శాఖలో అడగండి.")
        "electricity" -> say("This threatens to cut your electricity. Don't pay through this message. Check your usual electricity bill.", "यह बिजली काटने की धमकी है। इस संदेश से भुगतान मत कीजिए। अपने बिजली के बिल में जानकारी देखिए।", "ఇది కరెంట్ ఆపేస్తామని బెదిరిస్తోంది. ఈ సందేశం ద్వారా చెల్లించకండి. మీ విద్యుత్ బిల్లులో వివరాలు చూడండి.")
        "prize" -> say("This offers a prize or refund. Don't use its link or send money. Ask someone you trust.", "यह इनाम या रिफंड का वादा है। इसका लिंक मत खोलिए। पैसे मत भेजिए। किसी भरोसेमंद व्यक्ति से पूछिए।", "ఇది బహుమతి లేదా రీఫండ్ ఇస్తామంటోంది. దీని లింక్ తెరవకండి. డబ్బు పంపకండి. నమ్మకమైన వ్యక్తిని అడగండి.")
        "job" -> say("This asks for money to get work. Don't send money. Ask someone you trust to check it.", "यह काम देने के लिए पैसे माँग रहा है। पैसे मत भेजिए। किसी भरोसेमंद व्यक्ति से जाँच करवाइए।", "ఇది పని ఇవ్వడానికి డబ్బు అడుగుతోంది. డబ్బు పంపకండి. నమ్మకమైన వ్యక్తితో తనిఖీ చేయించండి.")
        "parcel" -> say("This says a parcel is held. Don't pay through this message. Check with your usual delivery company.", "इसमें पार्सल रोके जाने की बात है। इस संदेश से भुगतान मत कीजिए। अपनी डिलीवरी कंपनी से पूछिए।", "ఇది పార్సిల్ ఆపేశామని చెబుతోంది. ఈ సందేశం ద్వారా చెల్లించకండి. మీ డెలివరీ సంస్థను అడగండి.")
        "arrest" -> say("This threatens arrest. Don't send money. End the call and ask someone you trust for help.", "यह गिरफ्तारी की धमकी है। पैसे मत भेजिए। कॉल बंद कीजिए। किसी भरोसेमंद व्यक्ति से मदद माँगिए।", "ఇది అరెస్ట్ చేస్తామని బెదిరిస్తోంది. డబ్బు పంపకండి. కాల్ ముగించండి. నమ్మకమైన వ్యక్తిని సహాయం అడగండి.")
        "loan" -> say("This loan message threatens you. Don't send money under pressure. Ask someone you trust for help.", "यह लोन का संदेश आपको धमका रहा है। दबाव में पैसे मत भेजिए। किसी भरोसेमंद व्यक्ति से मदद माँगिए।", "ఈ లోన్ సందేశం మిమ్మల్ని బెదిరిస్తోంది. ఒత్తిడితో డబ్బు పంపకండి. నమ్మకమైన వ్యక్తిని సహాయం అడగండి.")
        else -> say("This link may be unsafe. Don't open it. Use the app you normally use instead.", "यह लिंक असुरक्षित हो सकता है। इसे मत खोलिए। अपना रोज़ का ऐप खोलिए।", "ఈ లింక్ సురక్షితం కాకపోవచ్చు. దాన్ని తెరవకండి. మీరు సాధారణంగా వాడే యాప్ తెరవండి.")
    }, action)
}
