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
 * Chrome (com.android.chrome). The notifications prompt ("No thanks" / "Continue") is verified against
 * fixtures/trees/chrome/home.txt. The new-tab page (search_box_text), address bar (url_bar), tab switcher and cookie
 * banners have no dump yet: Chrome's ids / labels, unverified. Popups are answered the privacy-first way:
 * "No thanks", and on cookie banners "Reject all / only necessary" before "Accept".
 */
object ChromeMap {
    const val PKG = "com.android.chrome"

    private val NO_THANKS = listOf(id("negative_button", label = "^(No thanks|No, thanks|Not now)$"), lbl("^(No thanks|No, thanks|Not now)$", clickable = true))
    private val SEARCH_BOX = listOf(id("search_box_text"), id("url_bar"), lbl("^(Search or type (web address|URL)|Search Google or type a URL)$", clickable = true))
    private val FIELD = listOf(id("url_bar"), Sel(editable = true, above = 0.2f))
    private val SUGGESTION = Sel(slot = "query", clickable = true, editable = false, below = 0.08f, pick = Pick.TOP)
    /** Cookie banners: reject / only-necessary first; accept only when there is no other way. */
    private val COOKIES = listOf(
        lbl("^(Reject all|Reject All|Reject non-essential( cookies)?|Only necessary( cookies)?|Necessary cookies only|Decline( all)?|Refuse( all)?|Continue without accepting)$", clickable = true),
        lbl("^(Accept|Accept all|Accept All|I agree|Agree|Got it|OK)$", clickable = true),
    )
    private val TYPING = listOf(lbl("^(Clear input|Clear)$", clickable = true), id("delete_button"))
    private val COOKIE_CUE = lbl("cookie|consent|privacy (settings|choices)")

    private val DISMISS_PROMPT = MapStep("ch_prompt", NO_THANKS, say("Chrome is asking about notifications. Tap No thanks.",
        "Chrome सूचनाओं के बारे में पूछ रहा है। 'No thanks' दबाइए।", "Chrome నోటిఫికేషన్ల గురించి అడుగుతోంది. 'No thanks' నొక్కండి."),
        why = say("You can change this later. It doesn't stop Chrome from working.", "यह बाद में बदल सकते हैं। Chrome इससे बंद नहीं होता।",
            "దీన్ని తర్వాత మార్చవచ్చు. Chrome పనిచేయడం ఆగదు."))

    val map = AppMap(
        pkg = PKG, name = "Chrome",
        screens = listOf(
            ScreenDef("ch_prompt", listOf(NO_THANKS.last(), lbl("^(Continue|Allow|Yes|Turn on)$", clickable = true))),
            // Chrome's url_bar is an EditText even when not focused: typing is told apart by the clear button.
            ScreenDef("ch_home", listOf(SEARCH_BOX.last().copy(clickable = null)), mustNot = TYPING),
            ScreenDef("ch_home_id", listOf(id("search_box_text")), mustNot = TYPING),
            ScreenDef("ch_page", listOf(id("url_bar")), mustNot = TYPING),
            ScreenDef("ch_typing", listOf(FIELD.last(), TYPING[0])),
            ScreenDef("ch_typing_id", listOf(FIELD.last(), TYPING[1])),
            ScreenDef("ch_cookies", listOf(COOKIE_CUE, COOKIES.last())),
            ScreenDef("ch_tabs", listOf(lbl("^(New tab|Add tab)$", clickable = true), lbl("^(Close all tabs|Search your tabs|Incognito tabs|Open tabs)$|tabs? open", clickable = null))),
        ),
        backHint = say("This is another Chrome page. Tap the back arrow at the bottom, or swipe from the left edge.",
            "यह Chrome का दूसरा पेज है। नीचे पीछे वाला बटन दबाइए, या बाएँ किनारे से स्वाइप कीजिए।",
            "ఇది Chrome లో వేరే పేజీ. కింద వెనక్కి బటన్ నొక్కండి, లేదా ఎడమ అంచు నుంచి స్వైప్ చేయండి."),
        routes = listOf(
            Route(
                id = "chrome_search", pkg = PKG,
                goals = goals("(search|google|look up) .+ (on|in) (chrome|google|the internet|internet)", "chrome (pe|par|lo|on)? ?.+", "google (pe|par|lo)? ?.+",
                    "(गूगल|क्रोम|इंटरनेट) (पर|पे|में) .+", ".+ (गूगल|क्रोम) (पर|पे) (खोजो|ढूँढो|ढूंढो)", "(గూగుల్|క్రోమ్)(లో| లో) .+"),
                avoid = listOf(rx("google (maps|photos|pay|docs|play)|youtube|यूट्यूब|యూట్యూబ్")),
                slots = listOf("query"),
                steps = listOf(
                    DISMISS_PROMPT,
                    MapStep("ch_home", SEARCH_BOX, say("Tap the search bar.", "खोज पट्टी दबाइए।", "సెర్చ్ బార్ నొక్కండి."), alsoOn = listOf("ch_home_id", "ch_page")),
                    MapStep("ch_typing", FIELD, say("Type “{query}”. Or tap Do it and I'll type it.", "“{query}” लिखिए। या 'आप कर दो' दबाइए।",
                        "“{query}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."), fill = "query", alsoOn = listOf("ch_typing_id")),
                    MapStep("ch_typing", listOf(SUGGESTION), say("Tap “{query}” in the list, or press the search key.", "सूची में “{query}” दबाइए, या खोज बटन दबाइए।",
                        "జాబితాలో “{query}” నొక్కండి, లేదా సెర్చ్ బటన్ నొక్కండి."), alsoOn = listOf("ch_typing_id")),
                ),
                done = listOf(id("url_bar")), doneNot = TYPING, doneNeedsLastStep = true,
                doneSay = say("Here are the results.", "ये रहे नतीजे।", "ఇవిగో ఫలితాలు."),
            ),
            Route(
                id = "chrome_new_tab", pkg = PKG,
                goals = goals("new tab", "open (another|a new) (page|tab)", "नया टैब", "కొత్త ట్యాబ్"),
                slots = emptyList(),
                steps = listOf(
                    DISMISS_PROMPT,
                    MapStep("ch_page", listOf(id("tab_switcher_button"), lbl("^(Switch or close tabs|Tabs|\\d+ open tabs?).*", clickable = true)),
                        say("Tap the square with a number at the top right.", "ऊपर दाईं ओर नंबर वाला चौकोर दबाइए।", "పైన కుడివైపు సంఖ్య ఉన్న చతురస్రం నొక్కండి."),
                        alsoOn = listOf("ch_home", "ch_home_id")),
                    MapStep("ch_tabs", listOf(lbl("^(New tab|Add tab)$", clickable = true)), say("Tap New tab.", "'New tab' दबाइए।", "'New tab' నొక్కండి.")),
                ),
                done = listOf(id("search_box_text")), doneNeedsLastStep = true,
                doneSay = say("A new tab is open.", "नया टैब खुल गया।", "కొత్త ట్యాబ్ తెరుచుకుంది."),
            ),
            Route(
                id = "chrome_dismiss", pkg = PKG,
                goals = goals("cookie", "(close|remove|get rid of) (this )?(popup|pop-up|banner|box)", "(accept|agree|reject) .*(cookies|this)",
                    "(यह )?(पॉपअप|बॉक्स) (हटाओ|बंद करो)", "కుకీ", "(పాప్‌అప్|బాక్స్) (మూసేయి|తీసేయి)"),
                slots = emptyList(),
                steps = listOf(
                    DISMISS_PROMPT,
                    MapStep("ch_cookies", COOKIES, say("Tap the button that refuses extra cookies.", "अतिरिक्त कुकीज़ मना करने वाला बटन दबाइए।",
                        "అదనపు కుకీలను తిరస్కరించే బటన్ నొక్కండి."),
                        why = say("Refusing extra cookies means less tracking. The website still works.", "कुकीज़ मना करने से आपकी निगरानी कम होती है। वेबसाइट फिर भी चलती है।",
                            "కుకీలను తిరస్కరిస్తే మీపై నిఘా తగ్గుతుంది. వెబ్‌సైట్ పనిచేస్తుంది.")),
                ),
                done = emptyList(),
                doneSay = say("Done. You can read the page now.", "हो गया। अब पेज पढ़ सकते हैं।", "అయింది. ఇప్పుడు పేజీ చదవవచ్చు."),
            ),
        ),
    )
}
