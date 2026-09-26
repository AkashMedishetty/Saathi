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
 * Spotify (com.spotify.music): the "learn a new app" test. NO phone dump yet: labels are Spotify's English
 * accessibility labels (tabs Home / Search / Your Library, "What do you want to listen to?", "Play", "Pause",
 * "Enable shuffle", "Add to Liked Songs", "Download"). Unverified.
 */
object SpotifyMap {
    const val PKG = "com.spotify.music"

    private val TAB_SEARCH = lbl("^Search$", clickable = true)
    private val TAB_LIBRARY = lbl("^Your Library$", clickable = true)
    private val SEARCH_BOX = lbl("^What do you want to listen to\\?$|^Search (songs|artists|Spotify).*", clickable = true)
    private val FIELD = Sel(editable = true)
    /** A search result that mentions what they asked for (songs "Song • Artist", artists, playlists); never an ad. */
    private val RESULT = Sel(slot = "query", clickable = true, editable = false, below = 0.1f, pick = Pick.TOP, not = rx("\\b(Sponsored|Advertisement|Ad)\\b"))
    private val PLAYING = lbl("^Pause$", clickable = true)
    private val PLAY = listOf(lbl("^Play$", clickable = true), lbl("^(Play|Shuffle play)( .*)?$", clickable = true))
    private val LIKE = lbl("^(Add to Liked Songs|Like|Save to Your Library|Add to playlist)$", clickable = true)
    private val SHUFFLE = lbl("^(Enable shuffle|Disable shuffle|Shuffle|Shuffle is (on|off).*)$", clickable = true)
    private val DOWNLOAD = lbl("^(Download|Download playlist|Download album)$", clickable = true)

    private val TO_SEARCH = MapStep("sp_home", listOf(TAB_SEARCH),
        say("Tap Search at the bottom.", "नीचे 'Search' दबाइए।", "కింద 'Search' నొక్కండి."), alsoOn = listOf("sp_library", "sp_playlist"))

    val map = AppMap(
        pkg = PKG, name = "Spotify",
        screens = listOf(
            ScreenDef("sp_home", listOf(TAB_SEARCH, TAB_LIBRARY), mustNot = listOf(FIELD, SEARCH_BOX, DOWNLOAD)),
            ScreenDef("sp_search_tab", listOf(TAB_SEARCH, SEARCH_BOX), mustNot = listOf(FIELD)),
            ScreenDef("sp_search", listOf(FIELD)),
            ScreenDef("sp_results", listOf(FIELD, Sel(editable = true, slot = "query"))),
            ScreenDef("sp_library", listOf(TAB_LIBRARY, lbl("^(Playlists|Artists|Albums|Podcasts|Liked Songs)$"))),
            ScreenDef("sp_playlist", listOf(lbl("^(Play|Shuffle play)( .*)?$", clickable = true), DOWNLOAD)),
            ScreenDef("sp_now_playing", listOf(lbl("^(Pause|Play)$", clickable = true), lbl("^(Next|Skip forward|Next track)$", clickable = true),
                lbl("^(Previous|Skip back|Previous track)$", clickable = true))),
        ),
        backHint = say("This is another Spotify page. Tap the back arrow at the top left.",
            "यह Spotify का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Spotify లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "spotify_play", pkg = PKG,
                goals = goals("(play|put on|listen to|hear) .+ (on|in) spotify", "spotify (pe|par|lo|on)? ?.+", ".+ spotify",
                    "स्पॉटिफाई.+", ".+स्पॉटिफाई", "స్పాటిఫై.+", ".+స్పాటిఫై"),
                avoid = listOf(rx("install|download|इंस्टॉल|डाउनलोड|ఇన్‌స్టాల్|shuffle|like (this|the) song|playlist")),
                slots = listOf("query"),
                steps = listOf(
                    TO_SEARCH,
                    MapStep("sp_search_tab", listOf(SEARCH_BOX), say("Tap the white search box at the top.", "ऊपर सफ़ेद खोज बॉक्स दबाइए।", "పైన తెల్లని సెర్చ్ బాక్స్ నొక్కండి.")),
                    MapStep("sp_search", listOf(FIELD), say("Type “{query}”. Or tap Do it and I'll type it.", "“{query}” लिखिए। या 'आप कर दो' दबाइए।",
                        "“{query}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."), fill = "query"),
                    MapStep("sp_results", listOf(RESULT), say("Tap “{query}” in the results to play it.", "चलाने के लिए नतीजों में “{query}” दबाइए।",
                        "ప్లే చేయడానికి ఫలితాల్లో “{query}” నొక్కండి."),
                        why = say("A song shows the singer's name under it. An artist shows a round photo.", "गाने के नीचे गायक का नाम होता है। कलाकार की गोल फोटो होती है।",
                            "పాట కింద గాయకుడి పేరు ఉంటుంది. కళాకారుడికి గుండ్రటి ఫోటో ఉంటుంది.")),
                ),
                done = listOf(PLAYING), doneNeedsLastStep = true,
                doneSay = say("It's playing. Enjoy!", "चल रहा है। आनंद लीजिए!", "ప్లే అవుతోంది. ఆనందించండి!"),
                next = listOf(say("Want me to show you how to like this song so you can find it again?", "क्या इस गाने को लाइक करना सिखाऊँ, ताकि फिर मिल जाए?",
                    "ఈ పాట మళ్ళీ దొరికేలా లైక్ చేయడం చూపించనా?")),
            ),
            Route(
                id = "spotify_playlist", pkg = PKG,
                goals = goals("(open|play|show) (my )?.*playlist", "your library", "मेरी प्लेलिस्ट", "प्लेलिस्ट (खोलो|चलाओ)", "నా ప్లేలిస్ట్", "ప్లేలిస్ట్ (తెరువు|ప్లే)"),
                avoid = listOf(rx("download|offline|डाउनलोड|డౌన్‌లోడ్|youtube|यूट्यूब")),
                slots = listOf("query"),
                steps = listOf(
                    MapStep("sp_home", listOf(TAB_LIBRARY), say("Tap Your Library at the bottom right.", "नीचे दाईं ओर 'Your Library' दबाइए।",
                        "కింద కుడివైపు 'Your Library' నొక్కండి."), alsoOn = listOf("sp_search_tab", "sp_now_playing")),
                    MapStep("sp_library", listOf(Sel(slot = "query", clickable = true, below = 0.15f, pick = Pick.TOP),
                        Sel(label = rx("^(Liked Songs|Playlist)"), clickable = true, pick = Pick.TOP)),
                        say("Tap your playlist.", "अपनी प्लेलिस्ट दबाइए।", "మీ ప్లేలిస్ట్ నొక్కండి."),
                        scrollHint = say("Scroll down to find your playlist.", "नीचे स्क्रॉल करके अपनी प्लेलिस्ट ढूँढिए।", "కిందకు స్క్రోల్ చేసి మీ ప్లేలిస్ట్ వెతకండి.")),
                    MapStep("sp_playlist", PLAY, say("Tap the green Play button.", "हरा 'Play' बटन दबाइए।", "ఆకుపచ్చ 'Play' బటన్ నొక్కండి.")),
                ),
                done = listOf(PLAYING), doneNeedsLastStep = true,
                doneSay = say("Your playlist is playing.", "आपकी प्लेलिस्ट चल रही है।", "మీ ప్లేలిస్ట్ ప్లే అవుతోంది."),
            ),
            Route(
                id = "spotify_like", pkg = PKG,
                goals = goals("like (this|the) song", "save (this|the) song", "(add|put) (this|the) song", "गाना (लाइक|पसंद|सेव)", "ఈ పాట (లైక్|సేవ్)"),
                slots = emptyList(),
                steps = listOf(MapStep("sp_now_playing", listOf(LIKE), say("Tap the plus or heart next to the song name.", "गाने के नाम के पास प्लस या दिल दबाइए।",
                    "పాట పేరు పక్కన ప్లస్ లేదా హార్ట్ నొక్కండి."),
                    why = say("Liked songs are saved in Your Library, so you can find them again.", "लाइक किए गाने 'Your Library' में रहते हैं।",
                        "లైక్ చేసిన పాటలు 'Your Library' లో ఉంటాయి."),
                    scrollHint = say("Tap the small bar at the bottom to open the song.", "गाना खोलने के लिए नीचे की छोटी पट्टी दबाइए।",
                        "పాట తెరవడానికి కింద చిన్న పట్టీ నొక్కండి."))),
                done = listOf(lbl("^(Remove from Liked Songs|Liked|Saved to Your Library|Added to Liked Songs)$")),
                doneSay = say("Saved to your Liked Songs.", "आपके पसंदीदा गानों में सेव हो गया।", "మీ లైక్ చేసిన పాటల్లో సేవ్ అయింది."),
            ),
            Route(
                id = "spotify_shuffle", pkg = PKG,
                goals = goals("shuffle", "(random|mix) (order|songs)", "शफ़ल", "शफल", "गाने मिलाकर", "షఫుల్"),
                slots = emptyList(),
                steps = listOf(MapStep("sp_now_playing", listOf(SHUFFLE), say("Tap the two crossing arrows to turn shuffle on or off.",
                    "शफ़ल चालू या बंद करने के लिए दो क्रॉस तीर दबाइए।", "షఫుల్ ఆన్ లేదా ఆఫ్ చేయడానికి క్రాస్ బాణాలు నొక్కండి."),
                    why = say("Shuffle plays the songs in a mixed-up order.", "शफ़ल गानों को मिले-जुले क्रम में चलाता है।", "షఫుల్ పాటలను కలగలిపి ప్లే చేస్తుంది."),
                    alsoOn = listOf("sp_playlist"))),
                done = emptyList(),
                doneSay = say("Done.", "हो गया।", "అయింది."),
            ),
            Route(
                id = "spotify_download", pkg = PKG,
                goals = goals("download (the |my |this )?(playlist|album|songs)", "(listen|play) offline", "offline (songs|playlist)",
                    "(प्लेलिस्ट|गाने) डाउनलोड", "बिना इंटरनेट", "(ప్లేలిస్ట్|పాటలు) డౌన్‌లోడ్", "ఇంటర్నెట్ లేకుండా"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("sp_home", listOf(TAB_LIBRARY), say("Tap Your Library at the bottom right.", "नीचे दाईं ओर 'Your Library' दबाइए।",
                        "కింద కుడివైపు 'Your Library' నొక్కండి."), alsoOn = listOf("sp_search_tab")),
                    MapStep("sp_library", listOf(Sel(label = rx("^(Liked Songs|Playlist)"), clickable = true, pick = Pick.TOP)),
                        say("Tap the playlist you want on the phone.", "जो प्लेलिस्ट फ़ोन में चाहिए उसे दबाइए।", "ఫోన్‌లో కావాల్సిన ప్లేలిస్ట్ నొక్కండి.")),
                    MapStep("sp_playlist", listOf(DOWNLOAD), say("Tap the down arrow to download it.", "डाउनलोड करने के लिए नीचे वाला तीर दबाइए।",
                        "డౌన్‌లోడ్ చేయడానికి కింది బాణం నొక్కండి."),
                        why = say("Downloaded songs play without internet. Spotify needs Premium for this.", "डाउनलोड गाने बिना इंटरनेट चलते हैं। इसके लिए Spotify Premium चाहिए।",
                            "డౌన్‌లోడ్ చేసిన పాటలు ఇంటర్నెట్ లేకుండా ప్లే అవుతాయి. దీనికి Spotify Premium కావాలి.")),
                ),
                done = listOf(lbl("^(Remove download|Downloaded|Downloading.*)$")),
                doneSay = say("It's downloading. You can listen without internet later.", "डाउनलोड हो रहा है। बाद में बिना इंटरनेट सुन सकते हैं।",
                    "డౌన్‌లోడ్ అవుతోంది. తర్వాత ఇంటర్నెట్ లేకుండా వినవచ్చు."),
            ),
        ),
    )
}
