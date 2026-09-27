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
 * Google Maps (com.google.android.apps.maps) + Uber (com.ubercab): directions to X, and a cab to X (from Maps'
 * ride tab, or straight in Uber). NO phone dumps yet: Maps ids (search_omnibox_text_box / _edit_text) and labels
 * ("Directions", "Start", "Ride services"), Uber labels ("Where to?", "Confirm …", "Request …"). UNVERIFIED.
 * Choosing the ride and Confirm / Request are risky: Saathi glows them, the person presses.
 */
object MapsMap {
    const val PKG = "com.google.android.apps.maps"

    private val SEARCH_BOX = listOf(id("search_omnibox_text_box"), lbl("^(Search here|Search Google Maps|Search)$", clickable = true))
    private val FIELD = listOf(id("search_omnibox_edit_text"), Sel(editable = true, above = 0.2f))
    private val SUGGESTION = Sel(slot = "place", clickable = true, editable = false, below = 0.1f, pick = Pick.TOP)
    private val DIRECTIONS = lbl("^Directions( to .*)?$", clickable = true)
    private val START = lbl("^(Start|Start navigation)$", clickable = true)
    private val RIDE_TAB = lbl("^(Ride services|Ride-hailing|Cab|Ride services mode|Taxi).*", clickable = true)

    private val TO_PLACE = listOf(
        MapStep("mp_home", SEARCH_BOX, say("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన సెర్చ్ బార్ నొక్కండి.")),
        MapStep("mp_search", FIELD, say("Type “{place}”. Or tap Do it and I'll type it.", "“{place}” लिखिए। या 'आप कर दो' दबाइए।",
            "“{place}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."), fill = "place"),
        MapStep("mp_search", listOf(SUGGESTION), say("Tap “{place}” in the list.", "सूची में “{place}” दबाइए।", "జాబితాలో “{place}” నొక్కండి."),
            scrollHint = say("If you don't see it, press the search key on the keyboard.", "न दिखे तो कीबोर्ड पर खोज बटन दबाइए।",
                "కనిపించకపోతే కీబోర్డ్‌లో సెర్చ్ బటన్ నొక్కండి.")),
        MapStep("mp_place", listOf(DIRECTIONS), say("Tap Directions.", "'Directions' दबाइए।", "'Directions' నొక్కండి."),
            alsoOn = listOf("mp_home")),
    )

    val map = AppMap(
        pkg = PKG, name = "Google Maps",
        screens = listOf(
            ScreenDef("mp_home", listOf(SEARCH_BOX.last().copy(clickable = null), lbl("^(Explore|You|Contribute|Updates|Go)$", clickable = true)),
                mustNot = listOf(FIELD.last())),
            ScreenDef("mp_search", listOf(FIELD.last())),
            ScreenDef("mp_place", listOf(DIRECTIONS, lbl("^(Start|Call|Save|Share|Website|Reviews|Overview)$", clickable = true)),
                mustNot = listOf(FIELD.last(), lbl("^(Driving|Two-wheeler|Transit|Walking)( mode)?.*", clickable = true))),
            ScreenDef("mp_directions", listOf(lbl("^(Driving|Two-wheeler|Transit|Walking)( mode)?.*", clickable = true), START)),
            ScreenDef("mp_directions_tabs", listOf(lbl("^(Driving|Two-wheeler|Transit|Walking)( mode)?.*", clickable = true), RIDE_TAB)),
            ScreenDef("mp_rides", listOf(RIDE_TAB, lbl("^(Uber|Ola|Rapido).*", clickable = true))),
        ),
        backHint = say("This is another Maps page. Tap the back arrow at the top left.",
            "यह Maps का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Maps లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "maps_directions", pkg = PKG,
                goals = goals("(directions|way|route|navigate|take me) to .+", "how (do i|to) (go|get|reach) to .+", ".+ (का|की) रास्ता", ".+ (कैसे जाऊँ|कैसे जाएँ)",
                    ".+ కి దారి", ".+ ఎలా వెళ్ళాలి"),
                avoid = listOf(rx("cab|taxi|uber|ola|rapido|auto|ride|कैब|टैक्सी|ऑटो|క్యాబ్|టాక్సీ|ఆటో|home ?screen|होम स्क्रीन|హోమ్ స్క్రీన్")),
                slots = listOf("place"),
                steps = TO_PLACE + MapStep("mp_directions", listOf(START), say("Tap Start. Maps will speak each turn.", "'Start' दबाइए। मैप हर मोड़ बताएगा।",
                    "'Start' నొక్కండి. ప్రతి మలుపును మ్యాప్స్ చెబుతుంది.")),
                done = listOf(lbl("^(Exit navigation|Close navigation|Re-centre|Recenter|Mute voice guidance).*")),
                doneSay = say("Navigation started. Follow the voice.", "रास्ता शुरू। आवाज़ के हिसाब से चलिए।", "నావిగేషన్ మొదలైంది. గొంతు చెప్పినట్లు వెళ్ళండి."),
            ),
            Route(
                id = "maps_cab", pkg = PKG,
                goals = goals("(cab|taxi|auto|ride) to .+ (in|on|from|using) maps", "maps .*(cab|taxi|ride)"),
                slots = listOf("place"),
                steps = TO_PLACE + listOf(
                    MapStep("mp_directions_tabs", listOf(RIDE_TAB), say("Tap the cab tab (the person waving) at the top.", "ऊपर कैब वाला टैब (हाथ हिलाता व्यक्ति) दबाइए।",
                        "పైన క్యాబ్ ట్యాబ్ (చేయి ఊపుతున్న మనిషి) నొక్కండి."), alsoOn = listOf("mp_directions"),
                        scrollHint = say("Slide the row of travel modes at the top to find the cab tab.", "ऊपर सफ़र के तरीकों की पंक्ति खिसकाकर कैब टैब ढूँढिए।",
                            "పైన ప్రయాణ విధానాల వరుసను జరిపి క్యాబ్ ట్యాబ్ వెతకండి.")),
                    MapStep("mp_rides", listOf(lbl("^Uber.*", clickable = true), lbl("^(Ola|Rapido).*", clickable = true)),
                        say("Tap Uber to open it. It will fill in the place for you.", "Uber दबाइए। वह जगह अपने आप भर देगा।",
                            "Uber నొక్కండి. అది ప్రదేశాన్ని తనే నింపుతుంది.")),
                ),
                done = emptyList(),
                doneSay = say("Uber is open with your place.", "Uber आपकी जगह के साथ खुल गया।", "మీ ప్రదేశంతో Uber తెరుచుకుంది."),
            ),
        ),
    )
}

object UberMap {
    const val PKG = "com.ubercab"

    private val WHERE = lbl("^(Where to\\?|Where to|Enter destination|Search destination)$", clickable = true)
    private val FIELD = Sel(editable = true)
    private val PLACE = Sel(slot = "place", clickable = true, editable = false, below = 0.15f, pick = Pick.TOP)
    /** The cheapest everyday rides first. */
    private val RIDE = listOf(lbl("^(Uber Go|Auto|Moto|Go Sedan|UberGo|Uber Auto).*", clickable = true), lbl("^(Uber|Premier|UberX).*", clickable = true))
    private val CONFIRM = lbl("^(Choose|Confirm|Request|Book) .*|^(Confirm|Request|Book)$", clickable = true)

    val map = AppMap(
        pkg = PKG, name = "Uber",
        screens = listOf(
            ScreenDef("ub_home", listOf(WHERE), mustNot = listOf(FIELD)),
            ScreenDef("ub_search", listOf(FIELD)),
            ScreenDef("ub_rides", listOf(RIDE.last(), CONFIRM)),
            ScreenDef("ub_pickup", listOf(lbl("^Confirm pickup.*", clickable = true))),
            ScreenDef("ub_finding", listOf(lbl("^(Finding|Looking for|Connecting you).*"), lbl("^Cancel.*", clickable = true)),
                wait = say("Uber is finding a driver. Please wait.", "Uber ड्राइवर ढूँढ रहा है। थोड़ा रुकिए।", "Uber డ్రైవర్‌ను వెతుకుతోంది. కొంచెం ఆగండి.")),
        ),
        backHint = say("This is another Uber page. Tap the back arrow at the top left.",
            "यह Uber का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Uber లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "uber_cab", pkg = PKG,
                goals = goals("(book|get|call|order) (a |an )?(cab|taxi|uber|ride|auto|car)", "(cab|taxi|uber|auto) to .+", "(कैब|टैक्सी|ऑटो|गाड़ी) (बुक|बुलाओ|मंगाओ)",
                    ".+ (के लिए|तक) (कैब|टैक्सी|ऑटो)", "(క్యాబ్|టాక్సీ|ఆటో) (బుక్|పిలువు)", ".+ కి (క్యాబ్|టాక్సీ|ఆటో)"),
                avoid = listOf(rx("maps|मैप्स|మ్యాప్స్|install|इंस्टॉल")),
                slots = listOf("place"),
                steps = listOf(
                    MapStep("ub_home", listOf(WHERE), say("Tap “Where to?”.", "“Where to?” दबाइए।", "“Where to?” నొక్కండి.")),
                    MapStep("ub_search", listOf(FIELD), say("Type “{place}”. Or tap Do it and I'll type it.", "“{place}” लिखिए। या 'आप कर दो' दबाइए।",
                        "“{place}” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."), fill = "place"),
                    MapStep("ub_search", listOf(PLACE), say("Tap “{place}” in the list.", "सूची में “{place}” दबाइए।", "జాబితాలో “{place}” నొక్కండి.")),
                    MapStep("ub_rides", RIDE, say("Check the price. Tap the ride you want.", "किराया देखिए। जो गाड़ी चाहिए उसे दबाइए।",
                        "ధర చూడండి. కావాల్సిన వాహనం నొక్కండి."),
                        why = say("Go and Auto cost the least.", "Go और Auto सबसे सस्ते होते हैं।", "Go, Auto తక్కువ ధర."), risky = true),
                    MapStep("ub_rides", listOf(CONFIRM), say("If the price is right, tap the black button at the bottom yourself.",
                        "किराया ठीक हो तो नीचे काला बटन ख़ुद दबाइए।", "ధర సరిగ్గా ఉంటే కింద నల్ల బటన్ మీరే నొక్కండి."), risky = true, needsReached = 3),
                    MapStep("ub_pickup", listOf(lbl("^Confirm pickup.*", clickable = true)), say("Check the pickup spot on the map, then tap Confirm pickup.",
                        "नक्शे पर लेने की जगह देखिए, फिर 'Confirm pickup' दबाइए।", "మ్యాప్‌లో పికప్ స్థలం చూసి, 'Confirm pickup' నొక్కండి."), risky = true),
                ),
                done = listOf(lbl("^(Your driver|Driver is on the way|Meet at|arriving).*")),
                doneSay = say("Your cab is booked. The driver is coming.", "आपकी कैब बुक हो गई। ड्राइवर आ रहा है।", "మీ క్యాబ్ బుక్ అయింది. డ్రైవర్ వస్తున్నారు."),
            ),
        ),
    )
}
