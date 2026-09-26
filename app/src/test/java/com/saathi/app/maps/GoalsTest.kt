package com.saathi.app.maps

import org.junit.Assert.assertEquals
import org.junit.Test

/** Goal phrases → route, in EN / HI / TE, including vague forms, and the cross-matches that must NOT happen. */
class GoalsTest {
    private fun check(cases: List<Pair<String, String?>>) {
        val wrong = cases.filter { (g, want) -> AppMaps.route(g)?.id != want }.map { (g, want) -> "\"$g\" → ${AppMaps.route(g)?.id} (want $want)" }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun youtube() = check(listOf(
        "play hanuman chalisa on youtube" to "yt_search",
        "search old telugu songs on YouTube" to "yt_search",
        "youtube pe sai baba bhajan" to "yt_search",
        "हनुमान चालीसा लगाओ यूट्यूब पर" to "yt_search",
        "यूट्यूब पर मुकेश के गाने" to "yt_search",
        "యూట్యూబ్ లో ఘంటసాల పాటలు" to "yt_search",
        "put on some bhajan songs" to "yt_search",
        "show my subscriptions" to "yt_subscriptions",
        "सब्सक्रिप्शन दिखाओ" to "yt_subscriptions",
        "సబ్‌స్క్రిప్షన్లు చూపించు" to "yt_subscriptions",
        "youtube subscriptions" to "yt_subscriptions",
        "channels I follow" to "yt_subscriptions",
        "show my watch history" to "yt_history",
        "videos I watched yesterday" to "yt_history",
        "देखे हुए वीडियो" to "yt_history",
        "యూట్యూబ్ హిస్టరీ" to "yt_history",
        "like this video" to "yt_like",
        "इस वीडियो को लाइक करो" to "yt_like",
        "share this video on whatsapp" to "yt_share_whatsapp",
        "यह वीडियो व्हाट्सएप पर भेजो" to "yt_share_whatsapp",
        "ఈ వీడియో షేర్ చేయి" to "yt_share_whatsapp",
    ))

    @Test fun settings() = check(listOf(
        "change my ringtone" to "settings_ringtone",
        "रिंगटोन बदलो" to "settings_ringtone",
        "రింగ్‌టోన్ మార్చు" to "settings_ringtone",
        "make the text bigger" to "settings_font",
        "letters are too small" to "settings_font",
        "अक्षर बड़े करो" to "settings_font",
        "అక్షరాలు పెద్దవి చేయి" to "settings_font",
        "screen is too dark" to "settings_brightness",
        "स्क्रीन की रोशनी बढ़ाओ" to "settings_brightness",
        "స్క్రీన్ వెలుతురు పెంచు" to "settings_brightness",
        "change the wallpaper" to "settings_wallpaper",
        "वॉलपेपर बदलो" to "settings_wallpaper",
        "connect to wifi" to "settings_wifi",
        "pair my earphones" to "settings_bluetooth",
        "turn on dark mode" to "settings_dark_mode",
        "डार्क मोड चालू करो" to "settings_dark_mode",
        "change the phone language" to "settings_language",
        "फ़ोन की भाषा बदलो" to "settings_language",
        "my screen turns off too fast" to "settings_timeout",
        "स्क्रीन जल्दी बंद हो जाती है" to "settings_timeout",
        "how much storage is used" to "settings_storage",
        "open the settings for vibration" to "settings_search",
    ))

    @Test fun noCrossMatches() = check(listOf(
        "video call my son" to null,
        "बेटे को वीडियो कॉल करो" to null,
        "what time is it" to null,
        "" to null,
    ))
}
