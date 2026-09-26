package com.saathi.app.guide

/**
 * Bridge between the coach (in the accessibility service) and the live TV camera screen.
 * The camera screen shows each instruction big, speaks it, and looks at the TV again after they press the button.
 */
object TvSession {
    interface Screen {
        fun instruct(text: String, question: Boolean)
        fun lookNow()
        fun end(text: String)
    }

    @Volatile var screen: Screen? = null
    val active get() = screen != null

    /** Plain-words names for remote buttons, so anyone can find them on their own remote. */
    fun buttonWords(key: String, lang: Lang): String = when (key.uppercase()) {
        "HOME" -> say("Home — the button with a little house", "होम — छोटे घर वाला बटन", "హోమ్ — చిన్న ఇల్లు గుర్తు ఉన్న బటన్")
        "OK", "ENTER", "SELECT" -> say("OK — the big button in the middle of the arrows", "OK — तीरों के बीच वाला बड़ा बटन", "OK — బాణాల మధ్యలో ఉన్న పెద్ద బటన్")
        "UP" -> say("the up arrow ▲", "ऊपर वाला तीर ▲", "పైకి బాణం ▲")
        "DOWN" -> say("the down arrow ▼", "नीचे वाला तीर ▼", "కిందకి బాణం ▼")
        "LEFT" -> say("the left arrow ◀", "बाएँ वाला तीर ◀", "ఎడమ బాణం ◀")
        "RIGHT" -> say("the right arrow ▶", "दाएँ वाला तीर ▶", "కుడి బాణం ▶")
        "BACK" -> say("Back — the curved arrow", "बैक — मुड़ा हुआ तीर", "బ్యాక్ — వంకర బాణం")
        "POWER" -> say("the red power button", "लाल पावर बटन", "ఎరుపు పవర్ బటన్")
        "VOL_UP" -> say("Volume +", "आवाज़ +", "వాల్యూమ్ +")
        "VOL_DOWN" -> say("Volume −", "आवाज़ −", "వాల్యూమ్ −")
        "MUTE" -> say("Mute — the speaker with a line through it", "म्यूट — कटे स्पीकर वाला बटन", "మ్యూట్ — గీత ఉన్న స్పీకర్ బటన్")
        "SOURCE", "INPUT" -> say("Input or Source", "इनपुट या सोर्स", "ఇన్‌పుట్ లేదా సోర్స్")
        "MENU" -> say("Menu", "मेनू", "మెనూ")
        else -> say(key, key, key)
    }.pick(lang)
}
