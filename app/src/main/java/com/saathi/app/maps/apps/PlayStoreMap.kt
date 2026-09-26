package com.saathi.app.maps.apps

import com.saathi.app.guide.say
import com.saathi.app.maps.AppMap
import com.saathi.app.maps.MapStep
import com.saathi.app.maps.Pick
import com.saathi.app.maps.Route
import com.saathi.app.maps.ScreenDef
import com.saathi.app.maps.Sel
import com.saathi.app.maps.goals
import com.saathi.app.maps.lbl
import com.saathi.app.maps.rx

/**
 * Play Store (com.android.vending): "get a new app and learn it".
 *   search → type the app's name → the RIGHT listing (the known developer first; never an ad) → Install (risky:
 *   glow only, the person taps) → Wait while it installs → Open → hand over to learning (route.next).
 * Also "update my apps" and "uninstall X" (risky). NO phone dump yet: labels are the Play Store's English ones.
 */
object PlayStoreMap {
    const val PKG = "com.android.vending"

    /** Real developer names for the apps people ask for, so a look-alike listing is never the first choice. */
    val DEVELOPERS = mapOf(
        "whatsapp" to "WhatsApp LLC", "spotify" to "Spotify AB", "uber" to "Uber Technologies, Inc.", "ola" to "ANI Technologies",
        "google docs" to "Google LLC", "docs" to "Google LLC", "google maps" to "Google LLC", "youtube" to "Google LLC",
        "google photos" to "Google LLC", "gmail" to "Google LLC", "google pay" to "Google LLC", "instagram" to "Instagram",
        "facebook" to "Meta Platforms, Inc.", "phonepe" to "PhonePe", "paytm" to "Paytm - One97 Communications Ltd.",
        "irctc" to "IRCTC Official", "zoom" to "zoom.us", "telegram" to "Telegram FZ-LLC", "truecaller" to "Truecaller",
        "jiocinema" to "Viacom18 Media PVT LTD", "hotstar" to "Novi Digital Entertainment Pvt. Ltd.", "netflix" to "Netflix, Inc.",
        "digilocker" to "National eGovernance Division", "umang" to "National eGovernance Division", "aarogya setu" to "National Informatics Centre.",
    )

    fun developerOf(app: String?): String? = app?.lowercase()?.trim()?.let { a -> DEVELOPERS[a] ?: DEVELOPERS.entries.firstOrNull { a.startsWith(it.key) }?.value }

    /** App names said in Hindi / Telugu → the name to type into the Play Store. */
    val ALIASES = listOf(
        rx("व्हाट्सएप|व्हाट्सऐप|वॉट्सऐप|वाट्सएप|వాట్సాప్|వాట్సప్") to "whatsapp",
        rx("स्पॉटिफाई|स्पोटिफाई|స్పాటిఫై") to "spotify",
        rx("उबर|ऊबर|ఉబర్") to "uber", rx("ओला|ఓలా") to "ola",
        rx("इंस्टाग्राम|ఇన్‌?స్టాగ్రామ్") to "instagram", rx("फेसबुक|ఫేస్‌?బుక్") to "facebook",
        rx("यूट्यूब|యూట్యూబ్") to "youtube", rx("गूगल पे|గూగుల్ పే") to "google pay", rx("फोनपे|ఫోన్‌?పే") to "phonepe",
        rx("पेटीएम|పేటీఎం") to "paytm", rx("डिजीलॉकर|డిజిలాకర్") to "digilocker", rx("आईआरसीटीसी|ఐఆర్‌సీటీసీ") to "irctc",
    )

    fun latinName(app: String): String = ALIASES.firstOrNull { it.first.containsMatchIn(app) }?.second ?: app

    private val SEARCH_BAR = listOf(lbl("^Search (apps & games|for apps & games|Google Play|apps|games)$"), lbl("^Search$", clickable = true))
    private val FIELD = Sel(editable = true)
    private val NOT_AD = rx("\\b(Sponsored|Ad)\\b|Ad ·|· Ad\\b")
    /** A result row naming the app AND its real developer, never an ad. */
    private val RESULT_DEV = Sel(slot = "developer", clickable = true, editable = false, not = NOT_AD, pick = Pick.TOP, below = 0.12f)
    /** Otherwise the top non-ad row that names the app. */
    private val RESULT_NAME = Sel(slot = "app", clickable = true, editable = false, not = NOT_AD, pick = Pick.TOP, below = 0.12f)
    private val INSTALL = lbl("^Install$", clickable = true)
    private val OPEN = lbl("^Open$", clickable = true)
    private val UNINSTALL = lbl("^Uninstall$", clickable = true)
    private val PROGRESS = lbl("^(Pending|Installing|Downloading|Waiting for download|Waiting for Wi-?Fi|Verifying).*|.*\\d+% of .*|^\\d+%$")

    private val START_SEARCH = MapStep("ps_home", SEARCH_BAR,
        say("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన సెర్చ్ బార్ నొక్కండి."),
        alsoOn = listOf("ps_details", "ps_installed"))
    private val TYPE = MapStep("ps_search", listOf(FIELD),
        say("Type “{app}”. Or tap Do it and I'll type it. Then press the search key.", "“{app}” लिखिए। या 'आप कर दो' दबाइए। फिर खोज बटन दबाइए।",
            "“{app}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి. తర్వాత సెర్చ్ బటన్ నొక్కండి."), fill = "app")
    private val PICK = MapStep("ps_results", listOf(RESULT_DEV, RESULT_NAME),
        say("Tap “{app}”[ by {developer}].", "[{developer} वाला ]“{app}” दबाइए।", "[{developer} వారి ]“{app}” నొక్కండి."),
        why = say("Check the company name under the app. Fake apps copy famous names. I skipped the ads.",
            "ऐप के नीचे कंपनी का नाम देखिए। नकली ऐप मशहूर नाम की नकल करते हैं। विज्ञापन मैंने छोड़ दिए।",
            "యాప్ కింద కంపెనీ పేరు చూడండి. నకిలీ యాప్‌లు పేరున్న పేర్లను కాపీ చేస్తాయి. ప్రకటనలు వదిలేశాను."),
        scrollHint = say("Slowly scroll down to find “{app}”.", "धीरे से नीचे “{app}” तक स्क्रॉल कीजिए।", "నెమ్మదిగా “{app}” వరకు కిందకు స్క్రోల్ చేయండి."))

    val map = AppMap(
        pkg = PKG, name = "Play Store",
        screens = listOf(
            ScreenDef("ps_home", listOf(SEARCH_BAR.first(), lbl("^(Games|Apps|For you|Top charts|Kids)$", clickable = true)), mustNot = listOf(FIELD)),
            ScreenDef("ps_search", listOf(FIELD)),
            ScreenDef("ps_results", listOf(Sel(slot = "app", above = 0.15f), lbl("\\d(\\.\\d)? star|^Install$|^Installed$|^Update$")),
                mustNot = listOf(lbl("^About this app$"))),
            ScreenDef("ps_details", listOf(INSTALL, lbl("^(About this app|Ratings and reviews|Data safety)$")), mustNot = listOf(FIELD)),
            ScreenDef("ps_installing", listOf(PROGRESS, lbl("^Cancel$", clickable = true)),
                wait = say("It's installing. This takes a minute. Keep the phone on.", "इंस्टॉल हो रहा है। एक मिनट लगेगा। फ़ोन चालू रखिए।",
                    "ఇన్‌స్టాల్ అవుతోంది. ఒక నిమిషం పడుతుంది. ఫోన్ ఆన్‌లో ఉంచండి.")),
            ScreenDef("ps_installed", listOf(OPEN, UNINSTALL), mustNot = listOf(FIELD)),
            ScreenDef("ps_uninstall_confirm", listOf(lbl("^Do you want to uninstall this app\\?$|^Uninstall .*\\?$"), UNINSTALL, lbl("^Cancel$"))),
            ScreenDef("ps_account", listOf(lbl("^Manage apps (&|and) device$", clickable = true))),
            ScreenDef("ps_manage", listOf(lbl("^(Overview|Manage)$", clickable = true), lbl("^(Update all|Updates available|All apps up to date|Check for updates|See details)$"))),
        ),
        backHint = say("This is another Play Store page. Tap the back arrow at the top left.",
            "यह Play Store का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Play Store లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "playstore_install", pkg = PKG,
                goals = goals("(install|download) (the |a |new )?.+", "get (the |a |new )?.+ app", "(new|an?) app (for|to)", "play ?store", "google play",
                    ".+ (इंस्टॉल|डाउनलोड)", "ऐप (डालो|चाहिए|इंस्टॉल)", ".+ (ఇన్‌స్టాల్|డౌన్‌లోడ్)", "యాప్ (వేయి|కావాలి)"),
                avoid = listOf(rx("uninstall|remove|delete|update|अनइंस्टॉल|हटाओ|अपडेट|తొలగించు|అప్‌డేట్|download (the |this |a )?(video|song|photo|document|playlist)|" +
                    "(video|song|photo|गाना|फोटो|वीडियो|పాట|ఫోటో).*(download|डाउनलोड|డౌన్‌లోడ్)")),
                slots = listOf("app", "developer"),
                steps = listOf(
                    START_SEARCH, TYPE, PICK,
                    MapStep("ps_details", listOf(INSTALL),
                        say("Check the name and the company. If it's right, tap the green Install button yourself.",
                            "नाम और कंपनी देखिए। सही हो तो हरा 'Install' बटन ख़ुद दबाइए।",
                            "పేరు, కంపెనీ చూడండి. సరైనదైతే ఆకుపచ్చ 'Install' బటన్ మీరే నొక్కండి."), risky = true),
                    MapStep("ps_installed", listOf(OPEN),
                        say("It's installed. Tap Open.", "इंस्टॉल हो गया। 'Open' दबाइए।", "ఇన్‌స్టాల్ అయింది. 'Open' నొక్కండి.")),
                ),
                done = emptyList(),
                doneSay = say("{app} is ready.", "{app} तैयार है।", "{app} సిద్ధంగా ఉంది."),
                next = listOf(say("Want me to show you how to use {app}?", "क्या मैं आपको {app} चलाना सिखाऊँ?", "{app} ఎలా వాడాలో చూపించనా?")),
            ),
            Route(
                id = "playstore_update_all", pkg = PKG,
                goals = goals("update (all )?(my )?apps", "apps? updates?", "ऐप (अपडेट|अपडेट करो)", "सारे ऐप अपडेट", "యాప్‌లు అప్‌డేట్", "యాప్ అప్‌డేట్"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("ps_home", listOf(lbl("^(Signed in as|Account and settings|Google Account).*", clickable = true)),
                        say("Tap your round picture at the top right.", "ऊपर दाईं ओर अपनी गोल तस्वीर दबाइए।", "పైన కుడివైపు మీ గుండ్రటి బొమ్మ నొక్కండి.")),
                    MapStep("ps_account", listOf(lbl("^Manage apps (&|and) device$", clickable = true)),
                        say("Tap Manage apps & device.", "'Manage apps & device' दबाइए।", "'Manage apps & device' నొక్కండి.")),
                    MapStep("ps_manage", listOf(lbl("^Update all$", clickable = true)),
                        say("Tap Update all.", "'Update all' दबाइए।", "'Update all' నొక్కండి."),
                        scrollHint = say("All your apps are up to date. Nothing to do.", "आपके सारे ऐप नए हैं। कुछ नहीं करना।",
                            "మీ యాప్‌లన్నీ తాజాగా ఉన్నాయి. ఏమీ చేయనక్కర్లేదు.")),
                ),
                done = listOf(lbl("^All apps up to date$")),
                doneSay = say("All your apps are up to date.", "आपके सारे ऐप अपडेट हैं।", "మీ యాప్‌లన్నీ అప్‌డేట్ అయ్యాయి."),
            ),
            Route(
                id = "playstore_uninstall", pkg = PKG,
                goals = goals("uninstall .+", "(remove|delete) (the )?.+ app", ".+ (अनइंस्टॉल|हटाओ)", "ऐप हटाओ", ".+ (అన్‌ఇన్‌స్టాల్|తొలగించు)"),
                slots = listOf("app", "developer"),
                steps = listOf(
                    START_SEARCH, TYPE, PICK,
                    MapStep("ps_installed", listOf(UNINSTALL),
                        say("If you're sure, tap Uninstall yourself.", "पक्का हो तो 'Uninstall' ख़ुद दबाइए।", "ఖచ్చితంగా అయితే 'Uninstall' మీరే నొక్కండి."), risky = true),
                    MapStep("ps_uninstall_confirm", listOf(UNINSTALL),
                        say("Tap Uninstall again to confirm, or Cancel to keep it.", "पक्का करने के लिए फिर 'Uninstall' दबाइए, या रखने के लिए 'Cancel'।",
                            "నిర్ధారించడానికి మళ్ళీ 'Uninstall' నొక్కండి, లేదా ఉంచడానికి 'Cancel'."), risky = true),
                ),
                done = listOf(INSTALL), doneNeedsLastStep = true,
                doneSay = say("{app} is removed.", "{app} हटा दिया गया।", "{app} తొలగించబడింది."),
            ),
        ),
    )
}
