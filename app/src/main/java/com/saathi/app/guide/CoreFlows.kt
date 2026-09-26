package com.saathi.app.guide

import android.content.Intent
import android.provider.Settings

/** Settings flows. Label patterns are OEM-tolerant (OriginOS, Pixel, One UI). */
object CoreFlows {

    private val FONT_ROW = rx("^Font size", "^Text size", "^Display size and text", "^Font and display", "^Font & display")

    fun fontSize() = Flow(
        id = "font_size",
        // Straight to Display: Saathi never opens the Settings home page itself (trap #45: the phone's protection
        // disables an accessibility app that launches the Settings home page, the way malware self-grants permissions).
        launch = { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        start = say(
            "Let's make the letters bigger. I've opened the screen settings for you.",
            "चलिए अक्षर बड़े करते हैं। मैंने स्क्रीन की Settings खोल दी है।",
            "అక్షరాలు పెద్దవి చేద్దాం. స్క్రీన్ Settings తెరిచాను."),
        steps = listOf(
            Step("display", rx("^Display$", "^Display ?[,&] ?brightness", "^Display and brightness", "^Screen$"), say(
                "Tap Display.", "'Display' को दबाइए।", "'Display' నొక్కండి."),
                tip = say("Everything about how the screen looks lives here.",
                    "स्क्रीन कैसी दिखे, यह सब यहीं है।", "స్క్రీన్ ఎలా కనిపించాలో అన్నీ ఇక్కడే.")),
            Step("font", FONT_ROW, say(
                "Now tap Font size.", "अब 'Font size' दबाइए।", "ఇప్పుడు 'Font size' నొక్కండి.")),
            Step("slider", rx("."), say(
                "Slide the dot to the right to make letters bigger. Stop when it feels comfortable.",
                "अक्षर बड़े करने के लिए गोले को दाईं ओर खिसकाइए। आराम लगे तो रुक जाइए।",
                "అక్షరాలు పెద్దవి చేయడానికి చుక్కను కుడివైపు జరపండి. సౌకర్యంగా ఉంటే ఆపండి."), role = "slider",
                screenHas = Regex("font size|text size|display size and text", RegexOption.IGNORE_CASE),
                unlessVisible = FONT_ROW),
        ),
        isDone = null,
        doneSay = say("Well done! The letters are bigger now.", "शाबाश! अब अक्षर बड़े हो गए।", "భలే! ఇప్పుడు అక్షరాలు పెద్దవయ్యాయి."),
    )

    fun wifi() = Flow(
        id = "wifi",
        launch = { Intent(Settings.ACTION_WIFI_SETTINGS) },
        start = say("Let's turn on Wi-Fi.", "चलिए Wi-Fi चालू करते हैं।", "Wi-Fi ఆన్ చేద్దాం."),
        steps = listOf(
            Step("toggle", rx("Wi-?Fi", "WLAN", "Use Wi"), say(
                "Tap the switch next to Wi-Fi to turn it on.",
                "Wi-Fi के सामने वाले स्विच को दबाकर चालू कीजिए।",
                "Wi-Fi పక్కన ఉన్న స్విచ్ నొక్కి ఆన్ చేయండి."), role = "switch"),
        ),
        isDone = { s -> s.elements.any { it.role == "switch" && it.checked && Regex("Wi-?Fi|WLAN|Use Wi", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } },
        doneSay = say("Wi-Fi is on. Well done!", "Wi-Fi चालू हो गया। बहुत बढ़िया!", "Wi-Fi ఆన్ అయింది. చాలా బాగుంది!"),
    )
}
