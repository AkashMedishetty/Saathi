package com.saathi.app.maps.apps

import com.saathi.app.guide.say
import com.saathi.app.maps.AppMap
import com.saathi.app.maps.MapStep
import com.saathi.app.maps.Pick
import com.saathi.app.maps.Route
import com.saathi.app.maps.ScreenDef
import com.saathi.app.maps.Sel
import com.saathi.app.maps.goals
import com.saathi.app.maps.id
import com.saathi.app.maps.lbl
import com.saathi.app.maps.rx

/**
 * YouTube (com.google.android.youtube). Verified against fixtures/trees/youtube/ (home, search_empty, search_typed,
 * results with an ad, results_playlist, subscriptions, you). The watch page (like / share) and History have no fixture
 * yet: those selectors are from YouTube's accessibility labels and are marked unverified in the DONE note.
 */
object YouTubeMap {
    const val PKG = "com.google.android.youtube"

    // ── selectors ──
    private val TAB_HOME = lbl("^Home$", clickable = true)
    private val TAB_SUBS = lbl("^Subscriptions$", clickable = true)
    private val TAB_YOU = lbl("^You$", clickable = true)
    private val SEARCH_ICON = listOf(Sel(resId = "menu_item_view", label = rx("^Search$")), lbl("^Search YouTube$", clickable = true))
    private val SEARCH_BOX = Sel(resId = "search_edit_text")
    private val QUERY_BAR = Sel(resId = "search_query")
    /** A search suggestion whose text is exactly what we typed (its row is the clickable parent). */
    private val SUGGESTION = Sel(resId = "text", slot = "query", slotExact = true)
    /**
     * A real result: result rows are clickable Buttons whose description ends with "- play video" (playlists:
     * "Playlist - … - N videos", mixes: "Mix - …"). Ads start with "Sponsored". The top-most visible one wins.
     */
    private val RESULT = Sel(label = rx("( - play video\\s*$|^Playlist - .* - \\d[\\d,]* videos\\s*$|^Mix - )"), not = rx("sponsored"),
        clickable = true, pick = Pick.TOP)
    private val LIKE = lbl("^like this video|^Like$|^I like this", clickable = true)
    private val SHARE = lbl("^Share$", clickable = true)
    /**
     * Only on the Subscriptions tab: the empty state ("New videos right to you" + "Subscribe to X." suggestions) or
     * the channel bar's "Manage" / "All subscriptions". Home's chips ("All", "Music") are deliberately not used.
     */
    private val SUBS_CUE = lbl("^(New videos right to you|Manage|All subscriptions|Subscribe to get the latest videos.*)$|^Subscribe to .+\\.$")

    val map = AppMap(
        pkg = PKG, name = "YouTube",
        screens = listOf(
            // The tab screens all show the bottom bar; the content tells them apart (the dump has no "selected" state).
            ScreenDef("yt_home", listOf(TAB_HOME, Sel(resId = "youtube_logo")), mustNot = listOf(SEARCH_BOX, QUERY_BAR)),
            ScreenDef("yt_subs", listOf(TAB_HOME, TAB_SUBS, SUBS_CUE), mustNot = listOf(SEARCH_BOX, QUERY_BAR)),
            ScreenDef("yt_you", listOf(TAB_HOME, TAB_YOU, lbl("^(Switch account|Your videos|Downloads|Playlists|History)$")),
                mustNot = listOf(SEARCH_BOX, QUERY_BAR)),
            ScreenDef("yt_history", listOf(lbl("^(History|Watch history)$"), lbl("^(Search watch history|Clear all watch history|Manage all history)$")),
                mustNot = listOf(SEARCH_BOX)),
            ScreenDef("yt_search", listOf(SEARCH_BOX)),
            // Results for what we asked, vs results for something else (needs a new search).
            ScreenDef("yt_results_for", listOf(QUERY_BAR.copy(slot = "query"), Sel(resId = "results"))),
            ScreenDef("yt_results", listOf(QUERY_BAR, Sel(resId = "results"))),
            // The watch page hides the bottom tab bar; a Like button next to the tab bar is a Short on Home.
            ScreenDef("yt_watch", listOf(LIKE), mustNot = listOf(SEARCH_BOX, TAB_HOME)),
            ScreenDef("yt_share", listOf(lbl("^(Share|Share to|Send to)$"), lbl("^(WhatsApp|Copy link)$", clickable = true)),
                mustNot = listOf(SEARCH_BOX)),
        ),
        backHint = say("This is another YouTube page. Tap the back arrow at the top left.",
            "यह YouTube का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।",
            "ఇది YouTube లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "yt_search", pkg = PKG,
                goals = goals(
                    "(search|find|play|watch|put on|show me|show|open|listen to|hear).+(on|in) youtube",
                    "youtube (pe|par|mein|me|lo|on)? ?.+", "^youtube .+", ".+ (on|in) youtube",
                    "यूट्यूब.+", ".+यूट्यूब", "యూట్యూబ్.+", ".+యూట్యూబ్",
                    "(play|put on|watch) (a |some |the )?.+ (video|song|bhajan|songs|videos)",
                    ".+ (गाना|गाने|भजन|वीडियो) (लगाओ|चलाओ|बजाओ|सुनाओ|दिखाओ)", ".+ (పాట|పాటలు|వీడియో) (పెట్టు|వినిపించు|చూపించు)",
                ),
                avoid = listOf(rx("video ?call|वीडियो कॉल|వీడియో కాల్|subscription|सब्सक्रिप्शन|సబ్.?స్క్రిప్షన్|history|हिस्ट्री|హిస్టరీ|spotify|स्पॉटिफाई|స్పాటిఫై")),
                slots = listOf("query"),
                steps = listOf(
                    MapStep("yt_home", SEARCH_ICON,
                        say("Tap the magnifying glass at the top to search.", "खोजने के लिए ऊपर आवर्धक काँच दबाइए।", "వెతకడానికి పైన భూతద్దం నొక్కండి."),
                        why = say("The magnifying glass means search.", "आवर्धक काँच का मतलब है खोज।", "భూతద్దం అంటే వెతకడం."),
                        alsoOn = listOf("yt_subs", "yt_you", "yt_history")),
                    // Results for something else: tap the search bar to change the words.
                    MapStep("yt_results", listOf(QUERY_BAR),
                        say("Tap the search bar at the top to search for “{query}”.", "“{query}” खोजने के लिए ऊपर खोज पट्टी दबाइए।",
                            "“{query}” వెతకడానికి పైన సెర్చ్ బార్ నొక్కండి.")),
                    MapStep("yt_search", listOf(SEARCH_BOX),
                        say("Type “{query}”. Or tap Do it and I'll type it.", "“{query}” लिखिए। या 'आप कर दो' दबाइए, मैं लिख दूँगा।",
                            "“{query}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి, నేను టైప్ చేస్తాను."), fill = "query"),
                    MapStep("yt_search", listOf(SUGGESTION),
                        say("Tap “{query}” in the list.", "सूची में “{query}” दबाइए।", "జాబితాలో “{query}” నొక్కండి."),
                        why = say("Or press the search key on the keyboard.", "या कीबोर्ड पर खोज वाला बटन दबाइए।", "లేదా కీబోర్డ్‌లో సెర్చ్ బటన్ నొక్కండి.")),
                    MapStep("yt_results_for", listOf(RESULT),
                        say("Tap the first video to play it.", "चलाने के लिए पहला वीडियो दबाइए।", "ప్లే చేయడానికి మొదటి వీడియో నొక్కండి."),
                        why = say("I skipped the advertisement at the top.", "ऊपर वाला विज्ञापन मैंने छोड़ दिया।", "పైన ఉన్న ప్రకటనను వదిలేశాను."),
                        scrollHint = say("Slowly scroll down to the first video.", "धीरे से नीचे पहले वीडियो तक स्क्रॉल कीजिए।",
                            "నెమ్మదిగా మొదటి వీడియో వరకు కిందకు స్క్రోల్ చేయండి.")),
                ),
                // Done = on a watch page after the result was tapped. Shorts on the Home tab also have a Like button
                // (found on the phone), so the Like button alone is not enough: the last step must be reached too.
                done = listOf(LIKE), doneNot = listOf(TAB_HOME, SEARCH_BOX), doneNeedsLastStep = true,
                doneSay = say("It's playing. Enjoy!", "चल रहा है। आनंद लीजिए!", "ప్లే అవుతోంది. ఆనందించండి!"),
                next = listOf(say("Want me to show you how to like it or share it with family?",
                    "क्या इसे लाइक करना या परिवार को भेजना सिखाऊँ?", "దీన్ని లైక్ చేయడం లేదా కుటుంబానికి పంపడం చూపించనా?")),
            ),
            Route(
                id = "yt_subscriptions", pkg = PKG,
                goals = goals("subscription", "channels i (follow|subscribed)", "my channels", "सब्सक्रिप्शन", "सब्सक्राइब किए",
                    "సబ్.?స్క్రిప్షన్", "సబ్‌స్క్రైబ్"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("yt_home", listOf(TAB_SUBS),
                        say("Tap Subscriptions at the bottom.", "नीचे 'Subscriptions' दबाइए।", "కింద 'Subscriptions' నొక్కండి."),
                        why = say("It shows new videos from the channels you follow.", "इसमें आपके पसंदीदा चैनलों के नए वीडियो होते हैं।",
                            "మీరు ఫాలో అయ్యే ఛానెళ్ల కొత్త వీడియోలు ఇందులో ఉంటాయి."),
                        alsoOn = listOf("yt_you", "yt_results", "yt_results_for")),
                ),
                done = listOf(TAB_SUBS, SUBS_CUE),
                doneSay = say("These are your subscriptions.", "ये आपके सब्सक्रिप्शन हैं।", "ఇవి మీ సబ్‌స్క్రిప్షన్లు."),
                next = listOf(say("Want to watch the newest video?", "नया वीडियो देखें?", "కొత్త వీడియో చూద్దామా?")),
            ),
            Route(
                id = "yt_history", pkg = PKG,
                goals = goals("(watch )?history", "videos i (watched|saw)", "(my )?library", "watched before", "हिस्ट्री",
                    "देखे (हुए|गए) वीडियो", "పాత వీడియోలు", "హిస్టరీ", "చూసిన వీడియోలు"),
                avoid = listOf(rx("clear|delete|हटाओ|తొలగించు")),
                slots = emptyList(),
                steps = listOf(
                    MapStep("yt_home", listOf(TAB_YOU),
                        say("Tap You at the bottom right.", "नीचे दाईं ओर 'You' दबाइए।", "కింద కుడివైపు 'You' నొక్కండి."),
                        alsoOn = listOf("yt_subs", "yt_results", "yt_results_for")),
                    MapStep("yt_you", listOf(lbl("^History$", clickable = true), lbl("^View all$", clickable = true)),
                        say("Tap History.", "'History' दबाइए।", "'History' నొక్కండి."),
                        why = say("History is every video you watched.", "History में आपके देखे सारे वीडियो हैं।",
                            "History లో మీరు చూసిన అన్ని వీడియోలు ఉంటాయి."),
                        scrollHint = say("Slowly scroll down to History.", "धीरे से नीचे 'History' तक स्क्रॉल कीजिए।",
                            "నెమ్మదిగా 'History' వరకు కిందకు స్క్రోల్ చేయండి.")),
                ),
                done = listOf(lbl("^(Search watch history|Clear all watch history|Manage all history)$")),
                doneSay = say("These are the videos you watched.", "ये आपके देखे हुए वीडियो हैं।", "ఇవి మీరు చూసిన వీడియోలు."),
            ),
            Route(
                id = "yt_like", pkg = PKG,
                goals = goals("like (this|the) video", "thumbs up", "वीडियो (को )?लाइक", "पसंद (करो|है)", "లైక్ (చేయి|చెయ్యి)"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("yt_watch", listOf(LIKE),
                        say("Tap the thumbs-up under the video.", "वीडियो के नीचे अँगूठे वाला निशान दबाइए।", "వీడియో కింద బొటనవేలు గుర్తు నొక్కండి."),
                        why = say("A like tells YouTube to show you more like this.", "लाइक से YouTube ऐसे और वीडियो दिखाता है।",
                            "లైక్ చేస్తే YouTube ఇలాంటివి మరిన్ని చూపిస్తుంది."),
                        scrollHint = say("Tap the video once, then look below it.", "वीडियो को एक बार छूइए, फिर उसके नीचे देखिए।",
                            "వీడియోను ఒకసారి తాకి, దాని కింద చూడండి.")),
                ),
                done = listOf(lbl("^(unlike this video|remove like|you liked this).*", clickable = true)),
                doneSay = say("Liked!", "लाइक हो गया!", "లైక్ అయింది!"),
            ),
            Route(
                id = "yt_share_whatsapp", pkg = PKG,
                goals = goals("share (this|the)? ?video", "send (this|the) video", "video.*(to|on) whatsapp", "वीडियो (भेज|शेयर)",
                    "वीडियो.*व्हाट्सएप", "వీడియో (షేర్|పంపు)"),
                slots = listOf("contact"),
                steps = listOf(
                    MapStep("yt_watch", listOf(SHARE),
                        say("Tap Share under the video.", "वीडियो के नीचे 'Share' दबाइए।", "వీడియో కింద 'Share' నొక్కండి."),
                        scrollHint = say("Tap the video once, then look for Share below it.", "वीडियो को एक बार छूइए, फिर नीचे 'Share' ढूँढिए।",
                            "వీడియోను ఒకసారి తాకి, కింద 'Share' వెతకండి.")),
                    MapStep("yt_share", listOf(lbl("^WhatsApp$", clickable = true)),
                        say("Tap WhatsApp.", "'WhatsApp' दबाइए।", "'WhatsApp' నొక్కండి."),
                        scrollHint = say("Slide the row of apps to find WhatsApp.", "ऐप की पंक्ति खिसकाकर WhatsApp ढूँढिए।",
                            "యాప్‌ల వరుసను జరిపి WhatsApp వెతకండి.")),
                ),
                done = emptyList(),
                doneSay = say("Now choose who to send it to in WhatsApp.", "अब WhatsApp में चुनिए किसे भेजना है।",
                    "ఇప్పుడు WhatsApp లో ఎవరికి పంపాలో ఎంచుకోండి."),
            ),
        ),
    )
}
