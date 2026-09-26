package com.saathi.app.maps.apps

import com.saathi.app.guide.Say
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
 * Settings (vivo OriginOS `com.android.settings`, and its search page, which on AOSP/Pixel lives in
 * `…settings.intelligence`). Every route uses the Settings search (it works on every OEM skin):
 *   the search bar → type the topic → the RESULT ROW ("Incoming call ringtone"), never the history chip ("ringtone").
 * Saathi never taps inside Settings (the phone treats it as a hijack, trap #45): these are glow-only for taps;
 * typing into the search box is the one allowed action.
 *
 * NO phone dump of Settings exists yet: labels come from Saathi's field runs on this phone ("Search settings" /
 * "Search", "Display, brightness & eye protection", "Font size and weight") and AOSP; unverified in the DONE note.
 */
object SettingsMap {
    const val PKG = "com.android.settings"

    private val SEARCH_BAR = listOf(
        Sel(resId = "search_action_bar"), Sel(resId = "search_bar"),
        lbl("^(Search settings|Search Settings|Search|Search for settings)$", clickable = true),
        lbl("^(Search settings|Search)$"),
    )
    private val SEARCH_BAR_ANY = lbl("^(Search settings|Search Settings|Search|Search for settings)$")
    private val MAIN_ROW = lbl("^(Wi-?Fi|WLAN|Bluetooth|Mobile network|SIM.*|Display.*|Battery|Sound.*|Connections)$")
    private val FIELD = Sel(editable = true)
    private val BACK = lbl("^(Navigate up|Back|Go back|Up)$", clickable = true)

    /** A result row that contains the topic AND more ("Incoming call ringtone"): never the bare history chip. */
    private val RESULT = Sel(slot = "term", slotLonger = true, clickable = true, editable = false, below = 0.1f, pick = Pick.TOP,
        not = rx("^(clear( all)?|search history|history|recent searches)$| · (remove|delete|clear)$"))

    private fun topic(id: String, term: String, doneSay: Say, vararg g: String) = searchRoute(id, goals(*g), doneSay, mapOf("term" to term))

    private fun searchRoute(id: String, g: List<Regex>, doneSay: Say, presets: Map<String, String> = emptyMap()) = Route(
        id = id, pkg = PKG, goals = g, slots = listOf("term"), presets = presets,
        steps = listOf(
            MapStep("set_home", SEARCH_BAR,
                say("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన సెర్చ్ బార్ నొక్కండి."),
                why = say("Searching is the quickest way to find any setting.", "कोई भी सेटिंग ढूँढने का सबसे आसान तरीका खोज है।",
                    "ఏ సెట్టింగ్ అయినా వెతకడమే సులువైన దారి.")),
            MapStep("set_search", listOf(FIELD),
                say("Type “{term}”. Or tap Do it and I'll type it.", "“{term}” लिखिए। या 'आप कर दो' दबाइए, मैं लिख दूँगा।",
                    "“{term}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి, నేను టైప్ చేస్తాను."), fill = "term"),
            MapStep("set_search_typed", listOf(RESULT),
                say("Tap the result that says “{term}”.", "“{term}” वाला नतीजा दबाइए।", "“{term}” అని ఉన్న ఫలితం నొక్కండి."),
                why = say("Tap the result below, not the old search word.", "नीचे वाला नतीजा दबाइए, पुराना खोजा हुआ शब्द नहीं।",
                    "కింద ఉన్న ఫలితం నొక్కండి, పాత వెతికిన పదం కాదు."),
                scrollHint = say("Look at the list under the search bar for “{term}”.", "खोज पट्टी के नीचे की सूची में “{term}” देखिए।",
                    "సెర్చ్ బార్ కింద ఉన్న జాబితాలో “{term}” చూడండి.")),
        ),
        done = listOf(Sel(slot = "term", above = 0.3f), BACK),
        doneNot = listOf(FIELD, SEARCH_BAR_ANY),
        doneNeedsLastStep = true,
        doneSay = doneSay,
    )

    private fun choose(what: String, hi: String, te: String) = say("Here it is. $what", hi, te)

    val map = AppMap(
        pkg = PKG, name = "Settings",
        alsoPkgs = listOf("com.android.settings.intelligence", "com.google.android.settings.intelligence", "com.vivo.settings"),
        screens = listOf(
            ScreenDef("set_home", listOf(SEARCH_BAR_ANY, MAIN_ROW), mustNot = listOf(FIELD)),
            ScreenDef("set_search", listOf(FIELD)),
            ScreenDef("set_search_typed", listOf(FIELD, Sel(editable = true, slot = "term"))),
            // Any other Settings page: an old sub-page when a task starts (go back), or the result page at the end.
            ScreenDef("set_page", listOf(BACK), mustNot = listOf(FIELD, SEARCH_BAR_ANY)),
        ),
        backHint = say("This is an older Settings page. Tap the back arrow at the top until you see the main Settings page.",
            "यह पुराना Settings पेज है। ऊपर पीछे वाला तीर दबाइए, जब तक मुख्य Settings पेज न दिखे।",
            "ఇది పాత Settings పేజీ. ప్రధాన Settings పేజీ వచ్చే వరకు పైన వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            topic("settings_ringtone", "ringtone",
                choose("Tap a ringtone to hear it, then choose the one you like.", "यह रहा। सुनने के लिए किसी रिंगटोन को दबाइए, फिर पसंद वाली चुनिए।",
                    "ఇదిగో. వినడానికి ఒక రింగ్‌టోన్ నొక్కండి, నచ్చినది ఎంచుకోండి."),
                "ring ?tone", "ringer", "रिंगटोन", "रिंग टोन", "घंटी की आवाज़", "రింగ్ ?టోన్", "రింగ్‌టోన్"),
            topic("settings_font", "font size",
                choose("Slide the dot to the right to make the letters bigger.", "यह रहा। अक्षर बड़े करने के लिए गोला दाईं ओर खिसकाइए।",
                    "ఇదిగో. అక్షరాలు పెద్దవి చేయడానికి చుక్కను కుడివైపు జరపండి."),
                "font", "text (size|bigger|smaller)", "(letters|text|writing) (are )?(too )?(small|big)", "bigger (text|letters)",
                "अक्षर (बड़े|छोटे)", "फ़ॉन्ट", "फॉन्ट", "అక్షరాలు (పెద్ద|చిన్న)", "ఫాంట్"),
            topic("settings_brightness", "brightness",
                choose("Slide the dot right for a brighter screen, left for darker.", "यह रहा। तेज़ रोशनी के लिए गोला दाईं ओर, कम के लिए बाईं ओर खिसकाइए।",
                    "ఇదిగో. ఎక్కువ వెలుతురుకు చుక్కను కుడివైపు, తక్కువకు ఎడమవైపు జరపండి."),
                "brightness", "screen (is )?(too )?(dark|dim|bright)", "रोशनी", "चमक", "వెలుతురు", "బ్రైట్‌నెస్"),
            topic("settings_wallpaper", "wallpaper",
                choose("Choose a picture you like for the background.", "यह रहा। पीछे के लिए पसंद की तस्वीर चुनिए।",
                    "ఇదిగో. బ్యాక్‌గ్రౌండ్ కోసం నచ్చిన బొమ్మ ఎంచుకోండి."),
                "wall ?paper", "background (picture|photo|image)", "वॉलपेपर", "वालपेपर", "వాల్ ?పేపర్", "వాల్‌పేపర్"),
            topic("settings_wifi", "wi-fi",
                choose("Tap the Wi-Fi switch to turn it on, then tap your home Wi-Fi name.", "यह रहा। Wi-Fi का स्विच चालू कीजिए, फिर घर के Wi-Fi का नाम दबाइए।",
                    "ఇదిగో. Wi-Fi స్విచ్ ఆన్ చేసి, మీ ఇంటి Wi-Fi పేరు నొక్కండి."),
                "wi-?fi (settings|setting|network|password|connect)", "connect (to )?(the )?wi-?fi", "वाई-?फाई (से जोड़ो|कनेक्ट)", "వై-?ఫై (కనెక్ట్|సెట్టింగ్)"),
            topic("settings_bluetooth", "bluetooth",
                choose("Tap the Bluetooth switch to turn it on, then tap your earphones' name.", "यह रहा। ब्लूटूथ का स्विच चालू कीजिए, फिर ईयरफ़ोन का नाम दबाइए।",
                    "ఇదిగో. బ్లూటూత్ స్విచ్ ఆన్ చేసి, మీ ఇయర్‌ఫోన్ పేరు నొక్కండి."),
                "bluetooth (settings|setting|devices|pair)", "pair (my )?(earphones|earbuds|headphones|speaker)", "ब्लूटूथ (सेटिंग|जोड़ो)", "బ్లూటూత్ (సెట్టింగ్|జత)"),
            topic("settings_dark_mode", "dark mode",
                choose("Tap the Dark mode switch.", "यह रहा। 'Dark mode' का स्विच दबाइए।", "ఇదిగో. 'Dark mode' స్విచ్ నొక్కండి."),
                "dark (mode|theme)", "night mode", "डार्क मोड", "డార్క్ మోడ్"),
            topic("settings_language", "language",
                choose("Tap Languages, then choose your language.", "यह रहा। 'Languages' दबाइए, फिर अपनी भाषा चुनिए।",
                    "ఇదిగో. 'Languages' నొక్కి, మీ భాష ఎంచుకోండి."),
                "(phone|system|change( the)?) language", "language (of|on) (the )?phone", "फ़ोन की भाषा", "भाषा बदल", "ఫోన్ భాష", "భాష మార్చు"),
            topic("settings_timeout", "screen timeout",
                choose("Choose a longer time, like 2 minutes.", "यह रहा। लंबा समय चुनिए, जैसे 2 मिनट।", "ఇదిగో. ఎక్కువ సమయం ఎంచుకోండి, ఉదా: 2 నిమిషాలు."),
                "screen (timeout|time out|turns off|goes off|goes black|switches off)", "sleep time", "स्क्रीन (जल्दी )?बंद", "స్క్రీన్ (త్వరగా )?ఆఫ్"),
            topic("settings_storage", "storage",
                choose("This shows what is using the space.", "यह रहा। इसमें दिखता है कि जगह किसने ली है।", "ఇదిగో. స్థలం దేనికి వాడబడిందో ఇది చూపిస్తుంది."),
                "(how much|check|show) .*storage", "storage (used|left|settings)", "कितनी जगह", "స్టోరేజ్ ఎంత"),
            // Any other setting: search for the person's own words.
            searchRoute("settings_search", goals("(change|open|find|show|where is) .*setting", "settings? (for|of) .+", "सेटिंग.+(बदल|खोल|ढूंढ|ढूँढ)", "సెట్టింగ్.+(మార్చు|తెరువు|వెతుకు)"),
                say("Here it is. Choose what you like on this page.", "यह रहा। इस पेज पर जो पसंद हो चुनिए।", "ఇదిగో. ఈ పేజీలో మీకు నచ్చింది ఎంచుకోండి.")),
        ),
    )
}
