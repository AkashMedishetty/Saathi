package com.saathi.app.maps

/**
 * Phone-setting topics in the person's words (EN/HI/TE) → the English word to type into Settings search.
 * The demo phone's Settings are in English; the word is what the Settings search index knows.
 */
object SettingsTerms {
    private val TOPICS = listOf(
        rx("ring ?tone|ringer|ring sound|रिंगटोन|रिंग टोन|घंटी की आवाज़|రింగ్ ?టోన్|రింగ్‌టోన్") to "ringtone",
        rx("font|text size|letters? (bigger|smaller|size)|bigger (text|letters)|अक्षर|फ़ॉन्ट|फॉन्ट|లిపి|అక్షరాలు|ఫాంట్") to "font size",
        rx("bright|screen light|रोशनी|चमक|బ్రైట్‌నెస్|వెలుతురు") to "brightness",
        rx("wall ?paper|background (picture|photo)|वॉलपेपर|वालपेपर|వాల్ ?పేపర్|వాల్‌పేపర్") to "wallpaper",
        rx("wi-?fi|wlan|वाई-?फाई|वाईफाई|వై-?ఫై|వైఫై") to "wi-fi",
        rx("bluetooth|ब्लूटूथ|బ్లూటూత్") to "bluetooth",
        rx("dark (mode|theme)|night mode|डार्क मोड|డార్క్ మోడ్") to "dark mode",
        rx("language|भाषा|భాష") to "language",
        rx("screen (timeout|time out|off time|turns off|goes off|sleep)|sleep time|स्क्रीन (जल्दी )?बंद|స్క్రీన్ (త్వరగా )?ఆఫ్") to "screen timeout",
        rx("storage|space|memory full|स्टोरेज|जगह|స్టోరేజ్|స్థలం") to "storage",
        rx("vibrat|कंपन|वाइब्रेशन|వైబ్రేషన్") to "vibration",
        rx("\\bvolume|sound|आवाज़|आवाज|వాల్యూమ్|శబ్దం") to "volume",
        rx("battery|बैटरी|బ్యాటరీ") to "battery",
        rx("\\btime\\b|date and time|clock time|समय|తేదీ|సమయం") to "date & time",
        rx("password|screen lock|lock screen|फ़ोन लॉक|लॉक|స్క్రీన్ లాక్") to "screen lock",
    )

    /** The Settings search word for this goal, or null if it doesn't name a setting Saathi knows. */
    fun termFor(goal: String): String? = TOPICS.firstOrNull { it.first.containsMatchIn(goal) }?.second

    val all: List<String> get() = TOPICS.map { it.second }
}
