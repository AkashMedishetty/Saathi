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
 * Google Photos (com.google.android.apps.photos). NO phone dump yet: selectors are Photos' accessibility labels
 * (tiles "Photo taken on …", the viewer's Edit / Share, the editor's Crop / Adjust / Save copy). Unverified.
 */
object PhotosMap {
    const val PKG = "com.google.android.apps.photos"

    private val TILE = Sel(label = rx("^(Photo|Image|Portrait photo|Screenshot) (taken )?(on|·|,)|^Photo taken|^Image taken"), clickable = true, pick = Pick.TOP)
    // Newer Photos (Compose) doesn't mark its bottom bar clickable (field, 03:09): match the label alone.
    private val EDIT = lbl("^Edit$")
    private val SHARE = lbl("^Share$")
    private val CROP_TAB = lbl("^Crop$", clickable = true)
    private val ADJUST_TAB = lbl("^(Adjust|Tools)$", clickable = true)
    private val SAVE = listOf(lbl("^Save copy$", clickable = true), lbl("^Save$", clickable = true))
    private val CROP_CUE = lbl("^(Rotate|Aspect ratio|Free|Reset|Auto|Flip)$")
    private val BRIGHTNESS = lbl("^Brightness$", clickable = true)
    private val SLIDER = Sel(cls = rx("SeekBar|Slider"))

    private val SAVE_SAY = say("Tap Save copy. Your original photo stays safe too.", "'Save copy' दबाइए। आपकी असली फोटो भी सुरक्षित रहेगी।",
        "'Save copy' నొక్కండి. మీ అసలు ఫోటో కూడా సురక్షితంగా ఉంటుంది.")

    private val OPEN_LATEST = MapStep("ph_grid", listOf(TILE),
        say("Tap your newest photo at the top.", "ऊपर सबसे नई फोटो दबाइए।", "పైన మీ కొత్త ఫోటో నొక్కండి."),
        scrollHint = say("Scroll up to the top to see your newest photos.", "नई फोटो देखने के लिए ऊपर तक स्क्रॉल कीजिए।",
            "కొత్త ఫోటోలు చూడటానికి పైకి స్క్రోల్ చేయండి."))
    private val OPEN_EDIT = MapStep("ph_viewer", listOf(EDIT),
        say("Tap Edit at the bottom.", "नीचे 'Edit' दबाइए।", "కింద 'Edit' నొక్కండి."),
        scrollHint = say("Tap the photo once to show the buttons.", "बटन दिखाने के लिए फोटो को एक बार छूइए।", "బటన్లు కనిపించడానికి ఫోటోను ఒకసారి తాకండి."))

    val map = AppMap(
        pkg = PKG, name = "Google Photos",
        screens = listOf(
            ScreenDef("ph_grid", listOf(TILE, lbl("^(Photos|Collections|Library|Search|Ask)$", clickable = true))),
            ScreenDef("ph_viewer", listOf(EDIT, SHARE), mustNot = listOf(CROP_TAB)),
            ScreenDef("ph_editor", listOf(CROP_TAB, ADJUST_TAB)),
            ScreenDef("ph_crop", listOf(CROP_CUE, lbl("^Done$", clickable = true))),
            // The editor keeps its tabs visible under a tool panel: the panels need more `must`s to win.
            ScreenDef("ph_adjust", listOf(ADJUST_TAB, BRIGHTNESS, lbl("^Contrast$"))),
            ScreenDef("ph_adjust_slider", listOf(ADJUST_TAB, BRIGHTNESS, lbl("^Contrast$"), SLIDER)),
            ScreenDef("ph_share", listOf(lbl("^(Share to apps|Send in Photos|Share to)$"), lbl("^WhatsApp$", clickable = true))),
        ),
        backHint = say("This is another Photos page. Tap the back arrow at the top left.",
            "यह Photos का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది Photos లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            Route(
                id = "photos_open_latest", pkg = PKG,
                goals = goals("(show|open|see) (my )?(latest|last|newest|recent) (photo|picture|pic)", "(my|the) photos", "gallery",
                    "(नई|आख़िरी|आखिरी|पिछली) (फोटो|फ़ोटो|तस्वीर)", "फोटो दिखाओ", "తాజా ఫోటో", "ఫోటోలు చూపించు"),
                avoid = listOf(rx("crop|edit|bright|share|send|काट|एडिट|रोशनी|भेज|కట్|ఎడిట్|వెలుతురు|పంపు")),
                slots = emptyList(),
                steps = listOf(OPEN_LATEST),
                done = listOf(EDIT, SHARE),
                doneSay = say("Here is your newest photo.", "यह रही आपकी सबसे नई फोटो।", "ఇదిగో మీ కొత్త ఫోటో."),
                next = listOf(say("Want me to show you how to make it brighter or cut it?", "क्या इसे चमकदार बनाना या काटना सिखाऊँ?",
                    "దీన్ని ప్రకాశవంతంగా చేయడం లేదా కత్తిరించడం చూపించనా?")),
            ),
            Route(
                id = "photos_crop", pkg = PKG,
                goals = goals("crop", "cut (the |a |my )?(photo|picture|pic)", "(photo|picture).*(smaller|cut)", "फोटो (काटना|काटो|छोटी)",
                    "फ़ोटो (काटना|काटो)", "ఫోటో (కట్|కత్తిరించు)"),
                slots = emptyList(),
                steps = listOf(
                    OPEN_LATEST, OPEN_EDIT,
                    MapStep("ph_editor", listOf(CROP_TAB), say("Tap Crop.", "'Crop' दबाइए।", "'Crop' నొక్కండి."),
                        why = say("Crop means cutting away the edges you don't want.", "Crop यानी फालतू किनारे काटना।",
                            "Crop అంటే వద్దనుకున్న అంచులు కత్తిరించడం.")),
                    MapStep("ph_crop", listOf(lbl("^Done$", clickable = true)),
                        say("Drag the white corners to cut the edges. Then tap Done.", "सफ़ेद कोनों को खींचकर किनारे काटिए। फिर 'Done' दबाइए।",
                            "తెల్లని మూలలను లాగి అంచులు కత్తిరించండి. తర్వాత 'Done' నొక్కండి.")),
                    MapStep("ph_editor", SAVE, SAVE_SAY, needsReached = 3),
                ),
                done = listOf(EDIT, SHARE), doneNeedsLastStep = true,
                doneSay = say("Saved! The cut photo is next to the original.", "सेव हो गया! कटी हुई फोटो असली के पास है।",
                    "సేవ్ అయింది! కత్తిరించిన ఫోటో అసలు దాని పక్కనే ఉంది."),
                next = listOf(say("Want to send it to your family?", "क्या इसे परिवार को भेजें?", "దీన్ని కుటుంబానికి పంపుదామా?")),
            ),
            Route(
                id = "photos_brightness", pkg = PKG,
                goals = goals("(photo|picture|pic).*(bright|light|dark)", "(bright|lighter|brighten).*(photo|picture|pic)",
                    "फोटो.*(रोशनी|चमक|साफ़|अंधेरी)", "(रोशनी|चमक).*फोटो", "ఫోటో.*(వెలుతురు|ప్రకాశ|చీకటి)"),
                slots = emptyList(),
                steps = listOf(
                    OPEN_LATEST, OPEN_EDIT,
                    MapStep("ph_editor", listOf(ADJUST_TAB), say("Tap Adjust.", "'Adjust' दबाइए।", "'Adjust' నొక్కండి."),
                        scrollHint = say("Slide the row at the bottom to find Adjust.", "नीचे की पंक्ति खिसकाकर 'Adjust' ढूँढिए।",
                            "కింద వరుసను జరిపి 'Adjust' వెతకండి.")),
                    MapStep("ph_adjust", listOf(BRIGHTNESS), say("Tap Brightness.", "'Brightness' दबाइए।", "'Brightness' నొక్కండి.")),
                    MapStep("ph_adjust_slider", listOf(SLIDER),
                        say("Slide right to make the photo brighter. Then tap Done.", "फोटो चमकदार करने के लिए दाईं ओर खिसकाइए। फिर 'Done' दबाइए।",
                            "ఫోటో ప్రకాశవంతం చేయడానికి కుడివైపు జరపండి. తర్వాత 'Done' నొక్కండి.")),
                    MapStep("ph_editor", SAVE, SAVE_SAY, needsReached = 4),
                ),
                done = listOf(EDIT, SHARE), doneNeedsLastStep = true,
                doneSay = say("Saved! The brighter photo is next to the original.", "सेव हो गया! चमकदार फोटो असली के पास है।",
                    "సేవ్ అయింది! ప్రకాశవంతమైన ఫోటో అసలు దాని పక్కనే ఉంది."),
            ),
            Route(
                id = "photos_share", pkg = PKG,
                // Theirs to send, never one someone sent them ("my grandson posted a picture in whatsapp, I want to look at it").
                avoid = listOf(rx("posted|\\bsent\\b|send me|look at|see it|received|got a")),
                goals = goals("(share|send) (my |the |this |a )?(photo|picture|pic)", "(photo|picture|pic).*(whatsapp|to my|family)",
                    "फोटो (भेजो|भेजना|शेयर)", "फ़ोटो भेजो", "ఫోటో (పంపు|షేర్)"),
                slots = listOf("contact"),
                steps = listOf(
                    OPEN_LATEST,
                    MapStep("ph_viewer", listOf(SHARE), say("Tap Share at the bottom.", "नीचे 'Share' दबाइए।", "కింద 'Share' నొక్కండి.")),
                    MapStep("ph_share", listOf(lbl("^WhatsApp$", clickable = true)), say("Tap WhatsApp.", "'WhatsApp' दबाइए।", "'WhatsApp' నొక్కండి."),
                        scrollHint = say("Slide the row of apps to find WhatsApp.", "ऐप की पंक्ति खिसकाकर WhatsApp ढूँढिए।", "యాప్‌ల వరుసను జరిపి WhatsApp వెతకండి.")),
                ),
                done = emptyList(),
                doneSay = say("Now choose who to send it to in WhatsApp.", "अब WhatsApp में चुनिए किसे भेजना है।", "ఇప్పుడు WhatsApp లో ఎవరికి పంపాలో ఎంచుకోండి."),
            ),
        ),
    )
}
