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

    @Test fun photos() = check(listOf(
        "show my latest photo" to "photos_open_latest",
        "open my photos" to "photos_open_latest",
        "आख़िरी फोटो दिखाओ" to "photos_open_latest",
        "how do I crop a photo" to "photos_crop",
        "cut the photo" to "photos_crop",
        "फोटो काटना है" to "photos_crop",
        "ఫోటో కట్ చేయి" to "photos_crop",
        "make the photo brighter" to "photos_brightness",
        "photo is too dark" to "photos_brightness",
        "फोटो की रोशनी बढ़ाओ" to "photos_brightness",
        "ఫోటో వెలుతురు పెంచు" to "photos_brightness",
        "share this photo" to "photos_share",
        "send the photo to my son" to "wa_photo",
        "फोटो भेजो" to "photos_share",
        "ఫోటో పంపు" to "photos_share",
    ))

    @Test fun playStore() = check(listOf(
        "install whatsapp" to "playstore_install",
        "download spotify from play store" to "playstore_install",
        "get the uber app" to "playstore_install",
        "व्हाट्सएप इंस्टॉल करो" to "playstore_install",
        "స్పాటిఫై ఇన్‌స్టాల్ చేయి" to "playstore_install",
        "open play store" to "playstore_install",
        "update my apps" to "playstore_update_all",
        "सारे ऐप अपडेट करो" to "playstore_update_all",
        "యాప్‌లు అప్‌డేట్ చేయి" to "playstore_update_all",
        "uninstall candy crush" to "playstore_uninstall",
        "यह ऐप हटाओ" to "playstore_uninstall",
    ))

    @Test fun spotify() = check(listOf(
        "play kishore kumar on spotify" to "spotify_play",
        "play old songs on spotify" to "spotify_play",
        "spotify pe lata mangeshkar" to "spotify_play",
        "स्पॉटिफाई पर भजन चलाओ" to "spotify_play",
        "స్పాటిఫై లో ఘంటసాల పాటలు" to "spotify_play",
        "open my playlist" to "spotify_playlist",
        "मेरी प्लेलिस्ट चलाओ" to "spotify_playlist",
        "like this song" to "spotify_like",
        "यह गाना लाइक करो" to "spotify_like",
        "turn on shuffle" to "spotify_shuffle",
        "గానాలు షఫుల్ చేయి" to "spotify_shuffle",
        "download my playlist" to "spotify_download",
        "listen offline" to "spotify_download",
        "प्लेलिस्ट डाउनलोड करो" to "spotify_download",
        "download spotify" to "playstore_install",
    ))

    @Test fun docs() = check(listOf(
        "create a new document" to "docs_new",
        "write a letter" to "docs_new",
        "नया दस्तावेज़ बनाओ" to "docs_new",
        "కొత్త డాక్యుమెంట్ తయారు చేయి" to "docs_new",
        "save it as a word file" to "docs_save_docx",
        "send the document as docx" to "docs_save_docx",
        "वर्ड फ़ाइल बनाओ" to "docs_save_docx",
        "వర్డ్ ఫైల్ గా సేవ్ చేయి" to "docs_save_docx",
        "open my last document" to "docs_open_recent",
        "मेरे दस्तावेज़ दिखाओ" to "docs_open_recent",
    ))

    @Test fun whatsapp() = check(listOf(
        "video call my son" to "wa_video_call",
        "video call Rahul" to "wa_video_call",
        "बेटे को वीडियो कॉल करो" to "wa_video_call",
        "కొడుకుకి వీడియో కాల్ చేయి" to "wa_video_call",
        "call Rahul on whatsapp" to "wa_voice_call",
        "व्हाट्सएप पर कॉल करो" to "wa_voice_call",
        "message Rahul" to "wa_message",
        "send a message to my daughter" to "wa_message",
        "बेटी को मैसेज भेजो" to "wa_message",
        "send a photo to Rahul" to "wa_photo",
        "फोटो व्हाट्सएप पर भेजो" to "wa_photo",
        "whatsapp backup" to "wa_backup",
        "चैट बैकअप" to "wa_backup",
        "open the location Ravi sent" to "wa_open_location",
        "रवि की भेजी हुई लोकेशन खोलो" to "wa_open_location",
    ))

    @Test fun noCrossMatches() = check(listOf(
        "what time is it" to null,
        "" to null,
        "make the text bigger" to "settings_font",
        "watch a video about gardening on youtube" to "yt_search",
    ))
}
