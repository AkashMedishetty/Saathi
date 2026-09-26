package com.saathi.app.policy

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ActionPolicyTest(private val description: String, private val request: ActionRequest, private val expected: String) {
    @Test fun verdict() = assertEquals(description, expected, ActionPolicy.check(request).javaClass.simpleName)

    companion object {
        private fun req(label: String?, mode: Mode, kind: Kind = Kind.TAP, pkg: String = "com.example.chat",
                        role: String = "button", screen: String = "", fill: String? = null, password: Boolean = false) =
            ActionRequest(kind, pkg, label, role, password, fill, screen, mode)

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList {
            fun addCase(name: String, r: ActionRequest, result: String) { add(arrayOf(name, r, result)) }
            Mode.entries.forEach { mode ->
                val manual = listOf("Send", "SEND", "Send message", "Send, button", "Pay", "Pay ₹500", "Install", "Install app",
                    "Call", "Call Alice", "Dial", "Voice call", "Start video call", "भेजें", "भेजो", "भुगतान", "इंस्टॉल", "कॉल",
                    "పంపు", "పంపండి", "చెల్లించు", "చెల్లింపు", "ఇన్‌స్టాల్", "కాల్", "  Send! ")
                manual.forEach { addCase("manual $mode $it", req(it, mode), "GlowOnly") }
                val confirm = listOf("Transfer", "Transfer files", "Uninstall", "Delete", "Delete photo", "Buy", "Buy now",
                    "Confirm order", "Book", "Book tickets", "Submit", "Grant permission", "Allow", "Allow notifications",
                    "Accept terms", "Purchase", "Remove account", "Factory reset", "Erase", "Subscribe", "Confirm",
                    "अनइंस्टॉल", "मिटाएं", "हटाएँ", "खरीदें", "बुक करें", "जमा करें", "अनुमति दें", "स्वीकार करें", "पुष्टि करें",
                    "బదిలీ", "అన్‌ఇన్‌స్టాల్", "తొలగించు", "కొనుగోలు", "బుక్", "సమర్పించు", "అనుమతించు", "అంగీకరించు", "నిర్ధారించు")
                confirm.forEach { addCase("confirmation $mode $it", req(it, mode), if (mode == Mode.TEACH) "GlowOnly" else "Confirm") }
                val safe = listOf("Search", "Sender", "Senders", "Send feedback", "Payment history", "Payment methods", "Installed",
                    "Installed apps", "Call history", "Call settings", "Bookmarks", "Book library", "Display", "Play", "Next",
                    "Open photo", "Cancel", "Back", "कॉल इतिहास", "भुगतान इतिहास", "కాల్ చరిత్ర", "చెల్లింపు చరిత్ర", "खोजें", "వెతకండి")
                safe.forEach { addCase("lookalike $mode $it", req(it, mode), if (mode == Mode.TEACH) "GlowOnly" else "Allow") }
                val sensitive = listOf("Password", "Enter OTP", "PIN", "UPI PIN", "CVV", "CVC", "Card number", "Aadhaar", "PAN",
                    "Bank account number", "Verification code", "पासवर्ड", "ओटीपी", "पिन", "सीवीवी", "आधार", "पैन", "खाता संख्या",
                    "పాస్‌వర్డ్", "ఓటీపీ", "పిన్", "సీవీవీ", "ఆధార్", "పాన్", "ఖాతా సంఖ్య")
                sensitive.forEach {
                    addCase("private type $mode $it", req(it, mode, Kind.TYPE, role = "input", fill = "example"), "Block")
                    addCase("private tap $mode $it", req(it, mode, role = "input"), "GlowOnly")
                }
                val dangerous = listOf("Install unknown apps", "Allow from this source", "Unknown sources", "Device admin",
                    "Device administrator", "Accessibility permission", "Screen sharing", "Share your screen", "Cast screen",
                    "Start recording", "अज्ञात स्रोत", "इस स्रोत से अनुमति", "डिवाइस एडमिन", "स्क्रीन शेयर", "स्क्रीन कास्ट",
                    "తెలియని మూలం", "ఈ మూలం నుండి", "డివైస్ అడ్మిన్", "యాక్సెసిబిలిటీ", "స్క్రీన్ షేర్", "స్క్రీన్ కాస్ట్")
                dangerous.forEach {
                    addCase("danger target $mode $it", req(it, mode), "Block")
                    addCase("danger context $mode $it", req("Allow", mode, screen = it), "Block")
                }
                listOf("com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
                    "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile").forEach { pkg ->
                    Kind.entries.forEach { kind -> addCase("money $mode $kind $pkg", req("Search", mode, kind, pkg, "input", fill = "tea"), "Block") }
                }
                listOf("Payment", "भुगतान", "చెల్లింపు").forEach { context ->
                    Kind.entries.forEach { kind -> addCase("irctc $mode $kind $context", req("Continue", mode, kind, "cris.org.in.prs.ima", screen = context), "Block") }
                }
                listOf("Search", "Wi-Fi", "Allow", "Sounds", "खोजें", "వెతకండి").forEach {
                    addCase("settings tap $mode $it", req(it, mode, pkg = "com.android.settings"), "GlowOnly")
                }
                listOf("Search", "Search settings", "खोजें", "వెతకండి").forEach {
                    addCase("settings search $mode $it", req(it, mode, Kind.TYPE, "com.android.settings", "input", fill = "ringtone"), if (mode == Mode.TEACH) "GlowOnly" else "Allow")
                }
                listOf("Device name", "Wi-Fi name", "Search history", "Name").forEach {
                    addCase("settings other type $mode $it", req(it, mode, Kind.TYPE, "com.android.settings", "input", fill = "new name"), "Block")
                }
                addCase("password bit type $mode", req("Search", mode, Kind.TYPE, role = "input", fill = "hello", password = true), "Block")
                addCase("password bit tap $mode", req("Search", mode, password = true), "GlowOnly")
                addCase("null tap $mode", req(null, mode), "GlowOnly")
                addCase("global $mode", req("Back", mode, Kind.GLOBAL), "Block")
                addCase("blank package $mode", req("Search", mode, pkg = ""), "Block")
                addCase("secret unlabeled fill $mode", req("Search", mode, Kind.TYPE, role = "input", fill = "123456"), "Block")
                addCase("safe text $mode", req("Search", mode, Kind.TYPE, role = "input", fill = "bhajan"), if (mode == Mode.TEACH) "GlowOnly" else "Allow")
                listOf(Kind.BACK, Kind.HOME, Kind.SCROLL, Kind.LAUNCH).forEach {
                    addCase("ordinary $mode $it", req("Photos", mode, it), if (mode == Mode.TEACH) "GlowOnly" else "Allow")
                }
            }
        }
    }
}

class ActionPolicyEdgeTest {
    private fun request(kind: Kind = Kind.TAP, label: String = "Allow", screen: String = "", pkg: String = "com.example.app") =
        ActionRequest(kind, pkg, label, "button", false, null, screen, Mode.AUTO)
    @Test fun installerOriginAndSafeExit() {
        val r = request(pkg = "com.android.packageinstaller",label = "Install")
        assertEquals("Block", ActionPolicy.check(r.copy(prevPkg = "com.android.chrome")).javaClass.simpleName)
        assertEquals("GlowOnly", ActionPolicy.check(r.copy(prevPkg = "com.android.vending")).javaClass.simpleName)
        assertEquals(Verdict.Allow, ActionPolicy.check(r.copy(kind = Kind.BACK,prevPkg = "com.android.chrome")))
    }
    @Test fun irctcBrowserPaymentAndNormalTrainSearch() {
        assertEquals("Block", ActionPolicy.check(request(screen = "IRCTC payment gateway",pkg = "com.android.chrome")).javaClass.simpleName)
        assertEquals(Verdict.Allow, ActionPolicy.check(request(label = "Search trains",pkg = "cris.org.in.prs.ima")))
    }
    @Test fun settingsSearchCannotBypassPrivateOrDangerChecks() {
        val r = request(Kind.TYPE,"Search",pkg = "com.android.settings").copy(targetRole = "input",fillText = "OTP 123456")
        assertEquals("Block",ActionPolicy.check(r).javaClass.simpleName)
        assertEquals("Block",ActionPolicy.check(r.copy(fillText = "hello",screenText = "Accessibility")).javaClass.simpleName)
    }
    @Test fun invalidTypingFailsClosed() {
        val r = request(Kind.TYPE,"Search")
        assertEquals("Block",ActionPolicy.check(r).javaClass.simpleName)
        assertEquals("Block",ActionPolicy.check(r.copy(fillText = "hello")).javaClass.simpleName)
        assertEquals("Block",ActionPolicy.check(r.copy(targetRole = "input",fillText = "hello",targetLabel = null)).javaClass.simpleName)
    }
    @Test fun noSecretInVerdictText() {
        val r = request(Kind.TYPE,"Password 123456").copy(targetRole = "input",fillText = "secret")
        val v = ActionPolicy.check(r) as Verdict.Block
        assertEquals(3,v.say.size)
        org.junit.Assert.assertFalse(v.say.values.any { "123456" in it || "secret" in it })
    }
}
