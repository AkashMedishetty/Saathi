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
 * Phone + Contacts. The vivo dialer (bottom tabs Dial / Contacts / Favorites, dialpad, `dialButton`) is verified
 * against fixtures/trees/contacts/home.txt. Its package on the iQOO is taken to be com.android.contacts (the dump
 * does not record it); Google's dialer is covered by label. Contact search / detail screens: no dump, unverified.
 * Calling is risky: glow only.
 */
object PhoneMap {
    const val PKG = "com.android.contacts"

    // The bottom tab, not the "Contacts" page title in the header (field 09:20: the glow sat on the top bar).
    private val TAB_CONTACTS = lbl("^Contacts$", clickable = true).copy(below = 0.6f)
    private val TAB_DIAL = lbl("^(Dial|Keypad|Phone)$")
    private val SEARCH = listOf(lbl("^(Search contacts|Search|Search \\d+ contacts)$", clickable = true), id("search_box"))
    private val FIELD = Sel(editable = true, above = 0.25f)
    private val CONTACT = listOf(Sel(slot = "contact", clickable = true, editable = false, below = 0.1f, pick = Pick.TOP))
    private val CALL = listOf(lbl("^(Call|Voice call|Call mobile|Dial|Call .*)$", clickable = true), id("dialButton"))

    val map = AppMap(
        pkg = PKG, name = "Phone",
        alsoPkgs = listOf("com.google.android.dialer", "com.android.dialer", "com.vivo.dialer", "com.google.android.contacts"),
        screens = listOf(
            ScreenDef("ph_dial", listOf(id("dialButton"), TAB_CONTACTS)),
            ScreenDef("ph_dial_generic", listOf(TAB_DIAL, TAB_CONTACTS), mustNot = listOf(FIELD)),
            ScreenDef("ph_contacts", listOf(TAB_CONTACTS, SEARCH.first().copy(clickable = null)), mustNot = listOf(FIELD, id("dialButton"))),
            ScreenDef("ph_search", listOf(FIELD)),
            ScreenDef("ph_contact_detail", listOf(lbl("^(Call|Voice call|Call mobile)$", clickable = true), lbl("^(Message|Text|SMS|Video call|Edit|Share)$", clickable = true)),
                mustNot = listOf(FIELD)),
        ),
        routes = listOf(
            Route(
                id = "phone_call", pkg = PKG,
                goals = goals("^(call|phone|ring) .+", "(make|place) a (phone )?call", ".+ (को )?(फ़ोन|फोन|कॉल) (करो|लगाओ|मिलाओ)", ".+ (కి|కు) (ఫోన్|కాల్) (చేయి|చెయ్యి)"),
                avoid = listOf(rx("video|whatsapp|वीडियो|व्हाट्सएप|వీడియో|వాట్సాప్|call log|history")),
                slots = listOf("contact"),
                steps = listOf(
                    MapStep("ph_dial", listOf(TAB_CONTACTS), say("Tap Contacts at the bottom.", "नीचे 'Contacts' दबाइए।", "కింద 'Contacts' నొక్కండి."),
                        alsoOn = listOf("ph_dial_generic")),
                    MapStep("ph_contacts", SEARCH, say("Tap the search box at the top.", "ऊपर खोज बॉक्स दबाइए।", "పైన సెర్చ్ బాక్స్ నొక్కండి.")),
                    MapStep("ph_search", listOf(FIELD), say("Type {contact}. Or tap Do it and I'll type it.", "{contact} लिखिए। या 'आप कर दो' दबाइए।",
                        "{contact} టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."), fill = "contact"),
                    MapStep("ph_search", CONTACT, say("Tap {contact}.", "{contact} को दबाइए।", "{contact} ని నొక్కండి."), alsoOn = listOf("ph_contacts")),
                    MapStep("ph_contact_detail", CALL, say("Tap the green call button to call {contact}.", "{contact} को कॉल करने के लिए हरा बटन दबाइए।",
                        "{contact} కి కాల్ చేయడానికి ఆకుపచ్చ బటన్ నొక్కండి."), risky = true),
                ),
                done = listOf(lbl("^(End call|Hang up|Calling.*|Dialling.*|Dialing.*)$")),
                doneSay = say("Calling {contact}.", "{contact} को कॉल जा रही है।", "{contact} కి కాల్ వెళ్తోంది."),
            ),
        ),
    )
}

/**
 * Messages: Google Messages (com.google.android.apps.messaging; its first-run "Continue as …" screen is verified
 * against fixtures/trees/messages/home.txt) and vivo Messages (com.android.mms). Conversation list / thread: no dump.
 */
object MessagesMap {
    const val PKG = "com.google.android.apps.messaging"

    private val CONVERSATION = Sel(resId = "conversation_list_item", pick = Pick.TOP)
    private val ROW = Sel(label = rx(".+"), clickable = true, editable = false, below = 0.12f, pick = Pick.TOP,
        not = rx("^(Start chat|New message|Search|Menu|More options|Search conversations|Messages|Profile|Account)"))
    private val COMPOSE = lbl("^(Text message|RCS message|Message|Type message|Send message)$")

    val map = AppMap(
        pkg = PKG, name = "Messages", alsoPkgs = listOf("com.android.mms", "com.vivo.mms"),
        screens = listOf(
            ScreenDef("msg_welcome", listOf(lbl("^Welcome!$"), id("continue_as_button"))),
            ScreenDef("msg_list", listOf(lbl("^(Start chat|New message|New conversation)$", clickable = true)), mustNot = listOf(COMPOSE)),
            ScreenDef("msg_thread", listOf(COMPOSE)),
            ScreenDef("msg_details", listOf(lbl("^(Block (&|and) report spam|Block number|Block)$", clickable = true))),
        ),
        routes = listOf(
            Route(
                id = "messages_read_latest", pkg = PKG,
                goals = goals("(read|show|open) (my )?(latest|last|new|recent) (sms|message|text)", "(read|show) (my )?(sms|messages)", "who (sent|messaged) me",
                    "(नया|आख़िरी|आखिरी) (मैसेज|एसएमएस|संदेश)", "मैसेज (पढ़ो|दिखाओ)", "(కొత్త|చివరి) (మెసేజ్|సందేశం)", "మెసేజ్ (చదువు|చూపించు)"),
                avoid = listOf(rx("whatsapp|व्हाट्सएप|వాట్సాప్|send|भेजो|పంపు|block")),
                slots = emptyList(),
                steps = listOf(
                    MapStep("msg_welcome", listOf(id("continue_as_button")), say("Messages is starting for the first time. Tap the blue Continue button.",
                        "Messages पहली बार खुल रहा है। नीला 'Continue' बटन दबाइए।", "Messages మొదటిసారి తెరుచుకుంటోంది. నీలి 'Continue' బటన్ నొక్కండి.")),
                    MapStep("msg_list", listOf(CONVERSATION, ROW), say("Tap the message at the top. It's the newest.", "सबसे ऊपर वाला संदेश दबाइए। वह सबसे नया है।",
                        "పైన ఉన్న సందేశం నొక్కండి. అదే కొత్తది.")),
                ),
                done = listOf(COMPOSE),
                doneSay = say("Here it is. I can read it to you if you like.", "यह रहा। चाहें तो मैं पढ़कर सुना दूँ।", "ఇదిగో. కావాలంటే చదివి వినిపిస్తాను."),
            ),
            Route(
                id = "messages_block", pkg = PKG,
                goals = goals("block (this|the|a) (number|sender|person)", "block .+ (number|messages)", "(नंबर|नम्बर) ब्लॉक", "ब्लॉक करो", "(నంబర్ )?బ్లాక్ (చేయి|చెయ్యి)"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("msg_thread", listOf(lbl("^(More options|Details|Options)$", clickable = true)), say("Tap the three dots at the top right.",
                        "ऊपर दाईं ओर तीन बिंदु दबाइए।", "పైన కుడివైపు మూడు చుక్కలు నొక్కండి.")),
                    MapStep("msg_details", listOf(lbl("^(Block (&|and) report spam|Block number|Block)$", clickable = true)), say("Tap Block.", "'Block' दबाइए।",
                        "'Block' నొక్కండి."),
                        why = say("They can't message or call you after this.", "इसके बाद वे आपको मैसेज या कॉल नहीं कर पाएँगे।", "దీని తర్వాత వారు మీకు మెసేజ్ లేదా కాల్ చేయలేరు.")),
                ),
                done = listOf(lbl("^(Unblock|Blocked).*")),
                doneSay = say("Blocked.", "ब्लॉक हो गया।", "బ్లాక్ అయింది."),
            ),
        ),
    )
}

/**
 * Clock: vivo Clock (package taken to be com.android.BBKClock; ids alarms_list, alarm_item_content, bar_onoff,
 * bt_add_alarm) verified against fixtures/trees/clock/home.txt; Google Clock (com.google.android.deskclock) by label.
 */
object ClockMap {
    const val PKG = "com.android.BBKClock"

    private val NEW_ALARM = listOf(id("bt_add_alarm"), lbl("^(New alarm|Add alarm)$", clickable = true))
    /** The on/off switch inside the alarm card whose description names the asked time ("On,8:00  AM,…"). */
    private val ALARM_SWITCH = listOf(
        Sel(resId = "bar_onff_layout", inside = Sel(resId = "alarm_item_content", slot = "time")),
        Sel(resId = "bar_onoff", inside = Sel(resId = "alarm_item_content", slot = "time")),
        Sel(label = rx("^(On|Off),?Switch$|^Switch$"), inside = Sel(slot = "time")),
    )

    val map = AppMap(
        pkg = PKG, name = "Clock", alsoPkgs = listOf("com.google.android.deskclock", "com.android.deskclock", "com.vivo.clock"),
        screens = listOf(
            ScreenDef("clk_alarms", listOf(NEW_ALARM.last().copy(clickable = null), lbl("^Alarm$"))),
            ScreenDef("clk_alarms_vivo", listOf(id("alarms_list"), id("bt_add_alarm"))),
            ScreenDef("clk_other_tab", listOf(lbl("^(World Clock|Stopwatch|Timer|Clock|Bedtime)$", clickable = true), lbl("^Alarm$", clickable = true)),
                mustNot = listOf(id("alarms_list"), NEW_ALARM.last())),
            ScreenDef("clk_edit", listOf(lbl("^(Save|Done|OK)$", clickable = true), lbl("^(Repeat|Ringtone|Label|Alarm name|Snooze).*"))),
        ),
        routes = listOf(
            Route(
                id = "clock_alarm_off", pkg = PKG,
                goals = goals("(turn|switch) off (the )?.*alarm", "(stop|cancel|remove) (the )?.*alarm", "alarm (off|band|बंद)", "अलार्म बंद", "అలారం (ఆఫ్|ఆపు)"),
                slots = listOf("time"),
                steps = listOf(
                    MapStep("clk_other_tab", listOf(lbl("^Alarm$", clickable = true)), say("Tap Alarm at the bottom.", "नीचे 'Alarm' दबाइए।", "కింద 'Alarm' నొక్కండి.")),
                    MapStep("clk_alarms_vivo", ALARM_SWITCH, say("Tap the switch next to the {time} alarm to turn it off.", "{time} वाले अलार्म के पास का स्विच दबाकर बंद कीजिए।",
                        "{time} అలారం పక్కన స్విచ్ నొక్కి ఆఫ్ చేయండి."), alsoOn = listOf("clk_alarms"),
                        scrollHint = say("Scroll to find the {time} alarm.", "{time} वाला अलार्म ढूँढने के लिए स्क्रॉल कीजिए।", "{time} అలారం కోసం స్క్రోల్ చేయండి.")),
                ),
                done = emptyList(),
                doneSay = say("The alarm is off.", "अलार्म बंद हो गया।", "అలారం ఆఫ్ అయింది."),
            ),
            Route(
                id = "clock_alarm_new", pkg = PKG,
                goals = goals("(add|new|create) (an )?alarm (in|on) (the )?clock", "show me how to (set|add) an alarm", "अलार्म लगाना सिखाओ", "అలారం పెట్టడం నేర్పు"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("clk_other_tab", listOf(lbl("^Alarm$", clickable = true)), say("Tap Alarm at the bottom.", "नीचे 'Alarm' दबाइए।", "కింద 'Alarm' నొక్కండి.")),
                    MapStep("clk_alarms_vivo", NEW_ALARM, say("Tap the big plus button to add an alarm.", "अलार्म जोड़ने के लिए बड़ा प्लस बटन दबाइए।",
                        "అలారం పెట్టడానికి పెద్ద ప్లస్ బటన్ నొక్కండి."), alsoOn = listOf("clk_alarms")),
                    MapStep("clk_edit", listOf(lbl("^(Save|Done|OK)$", clickable = true)), say("Turn the wheels to your time. Then tap Save.",
                        "पहियों को घुमाकर समय चुनिए। फिर 'Save' दबाइए।", "చక్రాలను తిప్పి సమయం ఎంచుకోండి. తర్వాత 'Save' నొక్కండి.")),
                ),
                done = listOf(id("alarms_list")), doneNeedsLastStep = true,
                doneSay = say("Your alarm is set.", "आपका अलार्म लग गया।", "మీ అలారం సెట్ అయింది."),
            ),
        ),
    )
}

/** Instagram (com.instagram.android). NO dump yet (the owner logs in): Instagram's tab labels. Posting = Share: risky. */
object InstagramMap {
    const val PKG = "com.instagram.android"

    private val TAB_PROFILE = lbl("^(Profile|Your profile)$", clickable = true)
    private val TAB_REELS = lbl("^Reels$", clickable = true)
    private val TAB_NEW = lbl("^(New post|Create|Camera|Create new)$", clickable = true)
    private val TAB_HOME = lbl("^Home$", clickable = true)

    val map = AppMap(
        pkg = PKG, name = "Instagram",
        screens = listOf(
            ScreenDef("ig_home", listOf(TAB_HOME, TAB_PROFILE)),
            ScreenDef("ig_new", listOf(lbl("^(New post|Post|Recents|Gallery)$"), lbl("^(Next|Continue)$", clickable = true))),
            ScreenDef("ig_caption", listOf(lbl("^(Write a caption.*|Add a caption.*)$"), lbl("^(Share|Post)$", clickable = true))),
        ),
        routes = listOf(
            Route(id = "instagram_profile", pkg = PKG, goals = goals("my (instagram )?profile", "instagram profile", "(मेरी )?इंस्टाग्राम प्रोफ़ाइल", "ఇన్‌స్టాగ్రామ్ ప్రొఫైల్"),
                slots = emptyList(),
                steps = listOf(MapStep("ig_home", listOf(TAB_PROFILE), say("Tap your small round picture at the bottom right.", "नीचे दाईं ओर अपनी छोटी गोल तस्वीर दबाइए।",
                    "కింద కుడివైపు మీ చిన్న గుండ్రటి బొమ్మ నొక్కండి."))),
                done = listOf(lbl("^(Edit profile|Share profile)$", clickable = true)),
                doneSay = say("This is your profile.", "यह आपकी प्रोफ़ाइल है।", "ఇది మీ ప్రొఫైల్.")),
            Route(id = "instagram_reels", pkg = PKG, goals = goals("reels?", "रील", "రీల్స్"),
                avoid = listOf(rx("post|share|upload|डालो|పోస్ట్")),
                slots = emptyList(),
                steps = listOf(MapStep("ig_home", listOf(TAB_REELS), say("Tap Reels at the bottom. Swipe up for the next one.", "नीचे 'Reels' दबाइए। अगली के लिए ऊपर स्वाइप कीजिए।",
                    "కింద 'Reels' నొక్కండి. తర్వాతి దానికి పైకి స్వైప్ చేయండి."))),
                done = emptyList(),
                doneSay = say("Enjoy the reels.", "रील देखिए।", "రీల్స్ ఆనందించండి.")),
            Route(id = "instagram_post", pkg = PKG, goals = goals("post (a )?(photo|picture) on instagram", "instagram (par|pe|lo)? ?(post|photo)", "इंस्टाग्राम पर फोटो", "ఇన్‌స్టాగ్రామ్ లో ఫోటో"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("ig_home", listOf(TAB_NEW), say("Tap the plus at the bottom.", "नीचे प्लस दबाइए।", "కింద ప్లస్ నొక్కండి.")),
                    MapStep("ig_new", listOf(lbl("^(Next|Continue)$", clickable = true)), say("Choose a photo, then tap Next.", "फोटो चुनिए, फिर 'Next' दबाइए।",
                        "ఫోటో ఎంచుకుని 'Next' నొక్కండి.")),
                    MapStep("ig_caption", listOf(lbl("^(Share|Post)$", clickable = true)), say("Write a few words if you like. When you're sure, tap Share yourself.",
                        "चाहें तो कुछ शब्द लिखिए। पक्का हो तो 'Share' ख़ुद दबाइए।", "కావాలంటే కొన్ని మాటలు రాయండి. ఖచ్చితంగా అయితే 'Share' మీరే నొక్కండి."), risky = true),
                ),
                done = emptyList(),
                doneSay = say("Posted.", "पोस्ट हो गया।", "పోస్ట్ అయింది.")),
        ),
    )
}

/**
 * Camera (vivo com.android.camera; Google com.google.android.GoogleCamera). NO dump: shutter / switch labels as the
 * existing camera skill uses them. Unverified.
 */
object CameraMap {
    const val PKG = "com.android.camera"

    private val SHUTTER = lbl("^(Shutter|Take photo|Take picture|Capture|Shutter button|Take a photo|Photo)$", clickable = true)
    private val SWITCH = lbl("^(Switch to front camera|Switch camera|Flip camera|Front camera)$", clickable = true)

    val map = AppMap(
        pkg = PKG, name = "Camera", alsoPkgs = listOf("com.google.android.GoogleCamera", "com.vivo.camera"),
        screens = listOf(
            ScreenDef("cam_viewfinder", listOf(SHUTTER)),
            // After switching, the button offers the way back: the front camera is on.
            ScreenDef("cam_front", listOf(SHUTTER, lbl("^Switch to (rear|back) camera$", clickable = true))),
        ),
        routes = listOf(
            Route(id = "camera_photo", pkg = PKG, goals = goals("take a (photo|picture|pic)", "(फोटो|फ़ोटो|तस्वीर) (खींचो|लो)", "ఫోటో తీయి"),
                avoid = listOf(rx("selfie|सेल्फी|సెల్ఫీ|send|share|भेज|పంపు")),
                slots = emptyList(),
                steps = listOf(MapStep("cam_viewfinder", listOf(SHUTTER), say("Hold the phone still. Tap the big round button.", "फ़ोन स्थिर रखिए। बड़ा गोल बटन दबाइए।",
                    "ఫోన్ కదలకుండా పట్టుకోండి. పెద్ద గుండ్రటి బటన్ నొక్కండి."))),
                done = emptyList(),
                doneSay = say("Photo taken. It's in your Photos.", "फोटो खिंच गई। यह Photos में है।", "ఫోటో తీశారు. ఇది Photos లో ఉంది.")),
            Route(id = "camera_selfie", pkg = PKG, goals = goals("selfie", "(photo|picture) of (me|myself)", "सेल्फी", "సెల్ఫీ"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("cam_viewfinder", listOf(SWITCH), say("Tap the turning arrows to see yourself.", "ख़ुद को देखने के लिए घूमते तीर दबाइए।",
                        "మిమ్మల్ని మీరు చూడటానికి తిరిగే బాణాలు నొక్కండి.")),
                    MapStep("cam_front", listOf(SHUTTER), say("Smile! Tap the big round button.", "मुस्कुराइए! बड़ा गोल बटन दबाइए।", "నవ్వండి! పెద్ద గుండ్రటి బటన్ నొక్కండి.")),
                ),
                done = emptyList(),
                doneSay = say("Nice selfie!", "बढ़िया सेल्फी!", "చక్కని సెల్ఫీ!")),
        ),
    )
}
