package com.saathi.app.policy

import com.saathi.app.guide.Say

enum class Mode { TEACH, DO_IT_ONCE, AUTO }
enum class Kind { TAP, TYPE, SCROLL, BACK, HOME, LAUNCH, GLOBAL }
data class ActionRequest(
    val kind: Kind, val pkg: String, val targetLabel: String?, val targetRole: String?,
    val isPassword: Boolean, val fillText: String?, val screenText: String, val mode: Mode,
    val prevPkg: String? = null,
)
sealed interface Verdict {
    data object Allow : Verdict
    data class Confirm(val ask: Say) : Verdict
    data class GlowOnly(val say: Say) : Verdict
    data class Block(val say: Say) : Verdict
}

/** Called again with a fresh target immediately before execution. No approval is stored here. */
object ActionPolicy {
    private val moneyPackages = setOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user",
        "net.one97.paytm", "in.org.npci.upiapp", "com.whatsapp.pay", "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus", "com.sbi.SBAnywhere", "com.snapwork.hdfc", "com.csam.icici.bank.imobile",
        "com.axis.mobile", "com.infrasofttech.CentralBank", "com.bankofbaroda.mconnect",
        "com.dreamplug.androidapp", "com.paytm.business").map(::norm).toSet()
    private val sensitive = "\\b(password|passcode|otp|pin|cvv|cvc|aadhaar|aadhar|pan|iban)\\b|one.time (password|code)|verification code|security code|card (number|no)|account (number|no)|bank account|पासवर्ड|ओटीपी|पिन|सीवीवी|आधार|पैन|खाता संख्या|कार्ड नंबर|పాస్‌వర్డ్|ఓటీపీ|పిన్|సీవీవీ|ఆధార్|పాన్|ఖాతా సంఖ్య|కార్డు నంబర్"
    private val danger = "unknown sources|install unknown apps|allow from this source|device admin|device administrator|accessibility(?: (service|permission|access))?|use .{0,40}accessibility|screen shar|share (your )?screen|screen cast|cast (your )?screen|start (recording|casting)|अनजान स्रोत|अज्ञात स्रोत|इस स्रोत से अनुमति|डिवाइस एडमिन|सुलभता.{0,20}(अनुमति|सेवा)|स्क्रीन (शेयर|कास्ट)|తెలియని మూల|ఈ మూలం నుండి|డివైస్ అడ్మిన్|యాక్సెసిబిలిటీ|స్క్రీన్ (షేర్|కాస్ట్)"
    private val consequence = "^(send|pay|transfer|install|uninstall|delete|call|dial|buy|purchase|confirm order|book|submit|grant permission|allow|accept terms|remove account|factory reset|erase|subscribe|confirm)(?:\\b|$)|^(भेजें|भेजो|भुगतान|पैसे भेज|इंस्टॉल|अनइंस्टॉल|मिटा|हटाएँ|हटाएं|कॉल|खरीद|बुक|जमा करें|सबमिट|अनुमति|स्वीकार|पुष्टि)|^(పంపు|పంపండి|చెల్లించు|చెల్లింపు|బదిలీ|ఇన్‌స్టాల్|అన్‌ఇన్‌స్టాల్|తొలగించు|కాల్|కొనుగోలు|బుక్|సమర్పించు|అనుమతించు|అంగీకరించు|నిర్ధారించు)"
    // The README is stricter than the Confirm list: these four must always be a physical user tap.
    private val manual = "^(send|pay|install|call|dial)(?:\\b|$)|^(भेजें|भेजो|भुगतान|पैसे भेज|इंस्टॉल|कॉल)|^(పంపు|పంపండి|చెల్లించు|చెల్లింపు|ఇన్‌స్టాల్|కాల్)"
    private val lookalikes = setOf("send feedback", "sender", "senders", "payment history", "payment methods",
        "installed", "installed apps", "call history", "call settings", "book library", "bookmarks", "delete history help",
        "कॉल इतिहास", "भुगतान इतिहास", "కాల్ చరిత్ర", "చెల్లింపు చరిత్ర")

    fun check(r: ActionRequest): Verdict {
        val pkg = norm(r.pkg)
        val label = norm(r.targetLabel.orEmpty()).trimEnd('.', '!', ':')
        val screen = norm(r.screenText)
        val role = norm(r.targetRole.orEmpty())
        if (pkg in moneyPackages || pkg.has("(^|[._])(bank|banking|sbi|hdfc|icici|kotak|axisbank|pnb|bob|canara|unionbank|indusind|idfc|yono)([._]|$)") ||
            screen.has("\\b(upi|netbanking|net banking|payment gateway)\\b|यूपीआई|యూపీఐ") ||
            ((pkg == "cris.org.in.prs.ima" || pkg.contains("irctc") || screen.contains("irctc")) && screen.has("payment|pay now|भुगतान|చెల్లింపు"))) return Verdict.Block(Words.money)
        if (pkg.isBlank()) return Verdict.Block(Words.uncertain)
        if (r.kind == Kind.GLOBAL) return Verdict.Block(Words.uncertain) // caller must classify BACK/HOME explicitly
        if (r.kind !in setOf(Kind.BACK, Kind.HOME) && (label.has(danger) || screen.has(danger)))
            return Verdict.Block(Words.dangerous)
        if (r.kind !in setOf(Kind.BACK, Kind.HOME) && pkg.contains("packageinstaller") &&
            r.prevPkg != null && r.prevPkg != "com.android.vending") return Verdict.Block(Words.dangerous)
        if (r.kind == Kind.TYPE && (r.isPassword || label.has(sensitive) || Redactor.containsSensitive(r.fillText.orEmpty())))
            return Verdict.Block(Words.privateField)
        if (r.kind == Kind.TAP && (r.isPassword || label.has(sensitive))) return Verdict.GlowOnly(Words.privateField)
        if (r.kind == Kind.TYPE && (role != "input" || label.isBlank() || r.fillText.isNullOrBlank()))
            return Verdict.Block(Words.uncertain)
        if (pkg == "com.android.settings") {
            if (r.kind == Kind.TAP) return Verdict.GlowOnly(Words.ownTap)
            if (r.kind == Kind.TYPE && label !in setOf("search", "search settings", "search in settings", "सेटिंग खोजें", "खोजें", "సెట్టింగ్‌లలో వెతకండి", "వెతకండి"))
                return Verdict.Block(Words.uncertain)
        }
        if (r.mode == Mode.TEACH) return Verdict.GlowOnly(Words.ownTap)
        if (r.kind == Kind.TAP && label.isBlank()) return Verdict.GlowOnly(Words.uncertain)
        if (r.kind == Kind.TAP && label.has("^(start )?(voice|video) call")) return Verdict.GlowOnly(Words.ownTap)
        if (r.kind in setOf(Kind.TAP, Kind.LAUNCH) && label !in lookalikes && label.has(consequence)) {
            if (label.has(manual)) return Verdict.GlowOnly(Words.ownTap)
            return Verdict.Confirm(Words.confirm)
        }
        if (r.kind == Kind.LAUNCH && pkg.contains("packageinstaller")) return Verdict.Block(Words.dangerous)
        return Verdict.Allow
    }
}
