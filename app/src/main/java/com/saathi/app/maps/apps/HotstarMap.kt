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
 * JioHotstar (in.startv.hotstar). Verified against fixtures/trees/hotstar/ (home, search, results, player = the
 * "Log in to watch" wall when signed out). Search is instant: typing shows the results page under the box, and the
 * top result has a "Latest Episode" / "Watch" button that plays directly.
 */
object HotstarMap {
    const val PKG = "in.startv.hotstar"

    /** Bottom bar items are Views described "Search" / "Home" / "My Space" (all one id). */
    private val TAB_SEARCH = Sel(resId = "tag_bottom_menu_item_list", label = rx("^Search$"))
    private val TAB_HOME = Sel(resId = "tag_bottom_menu_item_list", label = rx("^Home$"))
    private val SEARCH_BAR = Sel(resId = "tag_search_bar")
    private val RESULTS = Sel(resId = "tag_search_results_page")
    /** The top result's play button ("Latest Episode 26 Sep", "Watch Now", "Watch Free"); else the result itself. */
    private val WATCH = listOf(Sel(resId = "tag_search_hero_cta_watch_button"), Sel(resId = "tag_search_hero_result"),
        Sel(resId = "tag_image_content_poster", pick = Pick.TOP))

    val map = AppMap(
        pkg = PKG, name = "JioHotstar",
        screens = listOf(
            ScreenDef("hs_home", listOf(TAB_HOME, TAB_SEARCH), mustNot = listOf(SEARCH_BAR)),
            ScreenDef("hs_search", listOf(SEARCH_BAR), mustNot = listOf(RESULTS)),
            ScreenDef("hs_results", listOf(SEARCH_BAR, RESULTS)),
        ),
        backHint = say("This is another JioHotstar page. Tap the back arrow at the top left.",
            "यह JioHotstar का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।",
            "ఇది JioHotstar లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "hs_watch", pkg = PKG,
                goals = goals("(jio ?)?hot ?star", "हॉटस्टार", "హాట్‌?స్టార్"),
                avoid = listOf(rx("install|download|uninstall|subscri|recharge|plan|इंस्टॉल|డౌన్‌లోడ్")),
                slots = listOf("query"),
                steps = listOf(
                    MapStep("hs_home", listOf(TAB_SEARCH),
                        say("Tap Search at the bottom left.", "नीचे बाईं ओर 'Search' दबाइए।", "కింద ఎడమవైపు 'Search' నొక్కండి."),
                        why = say("That's where you find any serial or film by its name.", "वहाँ किसी भी सीरियल या फ़िल्म को नाम से ढूँढ सकते हैं।",
                            "అక్కడ ఏ సీరియల్ లేదా సినిమానైనా పేరుతో వెతకవచ్చు.")),
                    MapStep("hs_search", listOf(SEARCH_BAR),
                        say("Type the name of the serial or film[: “{query}”].", "सीरियल या फ़िल्म का नाम लिखिए[: “{query}”]।",
                            "సీరియల్ లేదా సినిమా పేరు టైప్ చేయండి[: “{query}”]."), fill = "query"),
                    MapStep("hs_results", WATCH,
                        say("Found it. Tap the glowing button to start watching.", "मिल गया। देखने के लिए चमकता बटन दबाइए।",
                            "దొరికింది. చూడటానికి మెరుస్తున్న బటన్ నొక్కండి."),
                        why = say("“Latest Episode” plays the newest one.", "“Latest Episode” सबसे नया एपिसोड चलाता है।",
                            "“Latest Episode” తాజా ఎపిసోడ్ ప్లే చేస్తుంది.")),
                ),
                // Playing (the player's controls). Signed out, the "Log in to watch" page comes instead: not mapped, so
                // Saathi's sign-in wall card explains it (their number, their OTP; Saathi never types them).
                done = listOf(lbl("^(Pause|Skip intro|Audio & Subtitles|Episodes)$")),
                doneNot = listOf(SEARCH_BAR, TAB_HOME), doneNeedsLastStep = true,
                doneSay = say("It's starting. Enjoy!", "शुरू हो रहा है। आनंद लीजिए!", "మొదలవుతోంది. ఆనందించండి!"),
            ),
        ),
    )
}
