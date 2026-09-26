package com.saathi.app.scam

import com.saathi.app.guide.Lang
import org.junit.Assert.*
import org.junit.Test

class ScamShieldTest {
    private fun message(text: String, family: Boolean = false) = ScamShield.onMessage(MsgEvent("com.whatsapp", "", text, family))
    // Each row is EN, Hindi, Telugu, Hinglish. Positive and near-miss pairs exercise every rule.
    private val positives = linkedMapOf(
        "apk" to listOf("SBI YONO update.apk", "शादी का कार्ड.apk", "పెళ్లి పత్రిక.xapk", "PM Kisan.apk kholo"),
        "remote" to listOf("Install AnyDesk", "TeamViewer खोलें", "QuickSupport ఇన్‌స్టాల్ చేయండి", "RustDesk install karo"),
        "upi" to listOf("Enter your UPI PIN to receive money", "पैसे पाने के लिए पिन डालें", "డబ్బు పొందడానికి పిన్ నమోదు చేయండి", "paise pane ke liye UPI PIN dalo"),
        "otp" to listOf("Send me your OTP", "ओटीपी बताओ", "ఓటీపీ చెప్పండి", "OTP bhejo"),
        "kyc" to listOf("KYC pending, account will be blocked", "केवाईसी के कारण खाता बंद होगा", "కేవైసీ వల్ల ఖాతా బ్లాక్ అవుతుంది", "KYC karo warna account band ho jayega"),
        "electricity" to listOf("Electricity will disconnect today, pay now", "बिजली कट जाएगी, भुगतान करें", "కరెంట్ కట్ అవుతుంది చెల్లించండి", "bijli kat jayegi bill bharo"),
        "prize" to listOf("Claim your lottery prize at this link", "इनाम के लिए शुल्क भेजें", "బహుమతి కోసం రుసుము పంపండి", "inaam ke liye fee bhejo"),
        "job" to listOf("Deposit money to unlock your next task", "नौकरी के लिए पैसे जमा करें", "ఉద్యోగం కోసం డిపాజిట్ చేయండి", "task ke liye jama karo"),
        "parcel" to listOf("Customs held your parcel, pay a fee", "पार्सल रोक दिया, भुगतान करें", "పార్సిల్ నిలిపి ఉంచాం చెల్లించండి", "parcel pakda gaya fee bharo"),
        "arrest" to listOf("You are under digital arrest", "आपकी डिजिटल गिरफ्तारी होगी", "మీరు డిజిటల్ అరెస్ట్ లో ఉన్నారు", "digital giraftar karenge"),
        "loan" to listOf("Pay loan or we leak your photos", "लोन भरो वरना बदनाम करेंगे", "లోన్ కట్టకపోతే ఫోటోలు పంపుతాం", "loan bharo warna badnaam karenge"),
        "link" to listOf("Open https://bit.ly/abc", "यह लिंक https://tinyurl.com/abc", "ఇది చూడండి http://192.168.1.2/pay", "ye dekho sbi-kyc-update.in"),
    )
    private val negatives = linkedMapOf(
        "apk" to listOf("Wedding card.pdf", "शादी का कार्ड भेजा है", "పెళ్లి పత్రిక.pdf", "PM Kisan payment aaya"),
        "remote" to listOf("AnyDesk was mentioned in the news", "TeamViewer मत खोलें", "QuickSupport ఇన్‌స్టాల్ చేయవద్దు", "RustDesk install mat karo"),
        "upi" to listOf("Enter UPI PIN to send money", "पैसे पाने के लिए पिन नहीं चाहिए", "డబ్బు పొందడానికి పిన్ అవసరం లేదు", "paise pane ke liye PIN mat dalo"),
        "otp" to listOf("Your OTP is valid for five minutes", "ओटीपी किसी को मत बताओ", "ఓటీపీ చెప్పకండి", "OTP mat bhejo"),
        "kyc" to listOf("KYC completed successfully", "केवाईसी हो गई", "కేవైసీ పూర్తయింది", "KYC ho gaya"),
        "electricity" to listOf("Electricity cut today during maintenance", "बिजली कट गई", "కరెంట్ కట్ అయింది", "bijli kat gayi ghar par"),
        "prize" to listOf("Your refund reached your account", "इनाम मिला है", "బహుమతి వచ్చింది", "inaam mila mujhe"),
        "job" to listOf("Your task is to water the plants", "नौकरी मिल गई", "ఉద్యోగం వచ్చింది", "task complete ho gaya"),
        "parcel" to listOf("Your parcel was delivered", "पार्सल आ गया", "పార్సిల్ వచ్చింది", "parcel ghar aa gaya"),
        "arrest" to listOf("Police station is near the bank", "पुलिस स्टेशन कहाँ है", "పోలీసు స్టేషన్ ఎక్కడ", "police station ke paas aao"),
        "loan" to listOf("Your loan statement is ready", "लोन भर दिया", "లోన్ కట్టాను", "loan bhar diya"),
        "link" to listOf("Visit https://www.onlinesbi.sbi", "यह https://www.hdfcbank.com", "ఇది https://sbi.co.in", "ye dekho https://notbit.ly.example.com"),
    )

    @Test fun multilingualRulePairs() {
        assertEquals(positives.keys, negatives.keys)
        positives.forEach { (id, texts) -> texts.forEach { text ->
            val hit = message(text)
            assertEquals(text, id, hit?.id)
            val stop = id in setOf("apk", "remote", "upi", "otp", "arrest", "loan")
            assertEquals(text, if (stop) Level.STOP else Level.CAUTION, hit?.level)
            assertEquals(text, Lang.entries.toSet(), hit?.say?.keys)
            assertTrue(text, hit!!.say.values.all { it.isNotBlank() })
        } }
        negatives.forEach { (_, texts) -> texts.forEach { assertNull(it, message(it)) } }
    }

    @Test fun apkVariantsAndChannels() {
        listOf("RTO challan.apk", "APK", "update.XAPK", "https://x.example/a.apk?download=1",
            "Install this app", "यह ऐप इंस्टॉल करें", "ఈ యాప్ ఇన్‌స్టాల్ చేయండి", "ye app install karo").forEach {
            assertEquals(it, "apk", message(it)?.id)
        }
        listOf("com.whatsapp", "org.telegram.messenger", "com.google.android.apps.messaging", "com.android.mms").forEach { app ->
            assertEquals("apk", ScamShield.onMessage(MsgEvent(app, "", "update.apk"))?.id)
            assertEquals("apk", ScamShield.onScreen(ScreenEvent(app, null, listOf("update.apk"), null))?.id)
        }
        listOf("Don't install this app", "यह ऐप इंस्टॉल मत करें", "ఈ యాప్ ఇన్‌స్టాల్ చేయవద్దు", "ye app install mat karo").forEach { assertNull(it, message(it)) }
        assertNull(message("snapkeeper and apks are words"))
        assertNull(ScamShield.onScreen(ScreenEvent("com.android.chrome", null, listOf("APK"), null)))
    }

    @Test fun installersRequireTrustedOriginClassificationAndIgnoreLanguage() {
        val labels = listOf("Install", "इंस्टॉल करें", "ఇన్‌స్టాల్ చేయండి", "install karo")
        val installers = listOf("com.android.packageinstaller", "com.google.android.packageinstaller", "com.vivo.packageinstaller")
        val sources = listOf("com.whatsapp", "org.telegram.messenger", "com.android.chrome", "com.google.android.apps.nbu.files")
        labels.forEach { label -> installers.forEach { pkg -> sources.forEach { prev ->
            val hit = ScamShield.onScreen(ScreenEvent(pkg, prev, listOf(label), null, true))
            assertEquals("installer", hit?.id)
            assertEquals(Level.STOP, hit?.level)
            assertEquals("back", hit?.safeAction)
        } } }
        labels.forEach { label ->
            assertNull(ScamShield.onScreen(ScreenEvent("com.android.packageinstaller", "com.android.vending", listOf(label), null)))
            assertNull(ScamShield.onScreen(ScreenEvent("com.android.packageinstaller", null, listOf(label), null)))
            assertNull(ScamShield.onScreen(ScreenEvent("com.example.app", "com.whatsapp", listOf(label), null)))
        }
    }

    @Test fun remoteVariantsAndStoreContext() {
        listOf("AnyDesk", "TeamViewer", "QuickSupport", "RustDesk", "AirDroid", "screen share").forEach { name ->
            assertEquals("remote", message("Open $name")?.id)
            assertNull(message("$name is a name"))
            val e = ScreenEvent("com.android.vending", "com.google.android.dialer", listOf(name), null)
            assertEquals("remote", ScamShield.onScreen(e)?.id)
            assertNull(ScamShield.onScreen(e.copy(prevPkg = null)))
            assertNull(ScamShield.onScreen(e.copy(pkg = "com.android.chrome")))
            assertEquals(Level.CAUTION, ScamShield.onScreen(e.copy(isSavedFamily = true))?.level)
        }
        listOf("स्क्रीन शेयर करें", "స్క్రీన్ షేర్ చేయండి", "screen share karo").forEach { assertEquals("remote", message(it)?.id) }
        assertNull(message("Install AnyDesktop"))
    }

    @Test fun upiVariantsAndSafetyAdvice() {
        listOf("Collect request", "कलेक्ट रिक्वेस्ट", "కలెక్ట్ రిక్వెస్ట్", "collect request accept karo",
            "₹500 received, enter PIN", "पैसे आए, पिन डालें", "డబ్బు వచ్చింది పిన్ నమోదు చేయండి", "paise aaye PIN dalo").forEach {
            assertEquals(it, "upi", message(it)?.id)
        }
        listOf("Never accept a collect request", "कलेक्ट रिक्वेस्ट स्वीकार मत करें", "కలెక్ట్ రిక్వెస్ట్ చేయవద్దు", "collect request accept mat karo",
            "₹500 received", "Enter PIN to pay ₹500").forEach { assertNull(it, message(it)) }
    }

    @Test fun linksUseHostBoundariesAndValidIpv4() {
        listOf("https://hdfc-bank-verify.com", "https://sbi-kyc-update.in/x", "http://1.2.3.4:80/x",
            "https://rb.gy/abc", "tinyurl.com/abc").forEach { assertEquals(it, "link", message(it)?.id) }
        listOf("https://bit.ly.evil.example/a", "https://example.com/bit.ly", "http://999.1.1.1/x",
            "https://example.com/hdfc-bank-verify.com", "https://sbi.co.in", "https://www.hdfcbank.com").forEach { assertNull(it, message(it)) }
        assertEquals("apk", message("https://example.com/a.apk")?.id)
        assertEquals("link", ScamShield.onMessage(MsgEvent("com.example.mail", "", "https://example.com/a.apk"))?.id)
    }

    @Test fun familyCapAndPriorityAndNoState() {
        positives.forEach { (id, texts) ->
            assertEquals(id, if (id == "apk") Level.STOP else Level.CAUTION, message(texts.first(), true)?.level)
        }
        assertEquals("apk", message("Send OTP to open wedding.apk", true)?.id)
        assertEquals("otp", message("KYC blocked. Send OTP")?.id)
        assertEquals("otp", message("Never share OTP. Send me your OTP now")?.id)
        assertNull(message("See you at dinner"))
        assertNull(message(""))
    }

    @Test fun screenKeepsMessageBoundariesAndUsesClickLabel() {
        val e = ScreenEvent("com.whatsapp", null, listOf("Send the photo", "Your OTP expires soon"), null)
        assertNull(ScamShield.onScreen(e))
        assertEquals("apk", ScamShield.onScreen(e.copy(clickedText = "wedding.apk"))?.id)
        assertEquals("apk", ScamShield.onScreen(e.copy(texts = listOf("KYC blocked", "a.apk")))?.id)
        assertEquals(Level.CAUTION, ScamShield.onScreen(e.copy(texts = listOf("Send OTP"), isSavedFamily = true))?.level)
    }

    @Test fun paymentScreensInAllFourLanguages() {
        listOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp").forEach { pkg ->
            positives.getValue("upi").forEach { text ->
                assertEquals("upi", ScamShield.onScreen(ScreenEvent(pkg, null, listOf(text), null))?.id)
            }
            negatives.getValue("upi").forEach { text ->
                assertNull(text, ScamShield.onScreen(ScreenEvent(pkg, null, listOf(text), null)))
            }
            assertNull(ScamShield.onScreen(ScreenEvent(pkg, null, listOf("Send money", "PIN", "Received ₹500"), null)))
        }
    }

    @Test fun refundAndPoliceDemandVariants() {
        listOf("Refund claim fee", "रिफंड के लिए शुल्क", "రీఫండ్ కోసం రుసుము", "refund fee bhejo").forEach {
            assertEquals(it, "prize", message(it)?.id)
        }
        listOf("CBI will arrest you, transfer money", "पुलिस गिरफ्तार करेगी पैसे भेजो", "పోలీసు అరెస్ట్ చేస్తారు డబ్బు పంపండి", "police giraftar karegi paise bhejo").forEach {
            assertEquals(it, "arrest", message(it)?.id)
        }
        listOf("Police arrest reported in the news", "पुलिस ने गिरफ्तार किया", "పోలీసు అరెస్ట్ చేశారు", "police ne giraftar kiya").forEach {
            assertNull(it, message(it))
        }
    }

    @Test fun capturedHomeScreensDoNotWarn() {
        listOf("messages" to "com.google.android.apps.messaging", "chrome" to "com.android.chrome").forEach { (folder, pkg) ->
            val relative = "fixtures/trees/$folder/home.txt"
            val file = listOf(java.io.File(relative), java.io.File("../$relative")).first { it.isFile }
            val labels = TreeFixture.labels(file.readText())
            assertTrue(labels.isNotEmpty())
            assertNull(ScamShield.onScreen(ScreenEvent(pkg, null, labels, null)))
        }
    }

    @Test fun fixtureParserReadsOnlyTextAndDescription() {
        val fixture = """
            TextView t=శుభోదయం d=null id=message CF ri=null acts=16 Rect(1, 2 - 30, 40)
              TextView t=null d=RTO challan.apk id=file CF ri=null acts=16 Rect(4, 5 - 60, 70)
        """.trimIndent()
        val labels = TreeFixture.labels(fixture)
        assertEquals(listOf("శుభోదయం", "RTO challan.apk"), labels)
        assertEquals("apk", ScamShield.onScreen(ScreenEvent("com.whatsapp", null, labels, null))?.id)
        assertEquals(emptyList<String>(), TreeFixture.labels("invalid line"))
    }
}

/** Parser for the handoff tree format; Android bounds are deliberately not needed by this detector. */
internal object TreeFixture {
    fun labels(tree: String): List<String> = tree.lineSequence().flatMap { line ->
        val match = Regex(" t=(.*?) d=(.*?) id=").find(line)
        match?.groupValues?.drop(1)?.filter { it != "null" && it.isNotBlank() }.orEmpty().asSequence()
    }.toList()
}
