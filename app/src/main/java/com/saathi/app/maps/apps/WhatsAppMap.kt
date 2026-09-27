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
 * WhatsApp (com.whatsapp, also Business com.whatsapp.w4b). NO phone dump yet (the demo phone's WhatsApp isn't
 * registered): resource-ids and labels are WhatsApp's long-standing ones (menuitem_search, search_input,
 * conversations_row_contact_name, conversation_contact_name, entry, send, input_attach_button). UNVERIFIED.
 * Calls and Send are always risky: Saathi glows them, the person presses.
 */
object WhatsAppMap {
    const val PKG = "com.whatsapp"

    private val SEARCH = listOf(id("menuitem_search"), lbl("^(Search|Ask Meta AI or Search|Search or ask Meta AI)$", clickable = true))
    private val SEARCH_FIELD = listOf(id("search_input"), id("search_src_text"), Sel(editable = true, above = 0.2f))
    /** The contact's row in the list / search results. */
    private val CONTACT = listOf(
        Sel(resId = "conversations_row_contact_name", slot = "contact", pick = Pick.TOP),
        Sel(resId = "contact_row_container", slot = "contact", pick = Pick.TOP),
        Sel(slot = "contact", clickable = true, editable = false, below = 0.12f, pick = Pick.TOP),
    )
    private val CHAT_TITLE = listOf(id("conversation_contact_name"), id("conversation_contact"))
    private val ENTRY = listOf(id("entry"), lbl("^(Message|Type a message)$"))
    private val SEND = listOf(id("send"), lbl("^Send$", clickable = true))
    // Top bar only: a "Video call · No answer" bubble in the chat is also labelled "Video call" (field, 01:55).
    private val VIDEO = lbl("^Video call$", clickable = true).copy(above = 0.15f)
    private val VOICE = lbl("^(Voice call|Call)$", clickable = true).copy(above = 0.15f)
    private val TABS = lbl("^(Chats|Updates|Communities|Calls)$", clickable = true)

    private val FIND_CONTACT = listOf(
        MapStep("wa_home", SEARCH, say("Tap the search at the top.", "ऊपर खोज दबाइए।", "పైన సెర్చ్ నొక్కండి.")),
        MapStep("wa_search", SEARCH_FIELD.take(2) + Sel(editable = true),
            say("Type {contact}. Or tap Do it and I'll type it.", "{contact} लिखिए। या 'आप कर दो' दबाइए।", "{contact} టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."),
            fill = "contact"),
        MapStep("wa_search", CONTACT, say("Tap {contact}.", "{contact} को दबाइए।", "{contact} ని నొక్కండి."), alsoOn = listOf("wa_home")),
    )

    private fun inChat(vararg steps: MapStep): List<MapStep> = FIND_CONTACT + steps.toList()

    val map = AppMap(
        pkg = PKG, name = "WhatsApp", alsoPkgs = listOf("com.whatsapp.w4b"),
        screens = listOf(
            // The search icon (menuitem_search, labelled "Search") or the newer "Ask Meta AI or Search" bar.
            ScreenDef("wa_home", listOf(TABS, SEARCH.last().copy(clickable = null)), mustNot = listOf(ENTRY.first())),
            ScreenDef("wa_search", listOf(Sel(editable = true, above = 0.2f)), mustNot = listOf(ENTRY.first())),
            ScreenDef("wa_chat", listOf(CHAT_TITLE.first(), ENTRY.first())),
            // Their chat (WhatsApp reopens on the LAST chat, often someone else's): the in-chat steps live only here, so
            // another person's chat is a wrong screen → "tap the back arrow" (field: it glowed a call in the wrong chat).
            ScreenDef("wa_chat_them", listOf(CHAT_TITLE.first(), ENTRY.first(), CHAT_TITLE.first().copy(slot = "contact"))),
            ScreenDef("wa_chat_label", listOf(lbl("^(Message|Type a message)$"), lbl("^(Attach|Camera|Emoji|Voice message|Video call)$", clickable = true))),
            ScreenDef("wa_call_confirm", listOf(lbl("^(Start (video|voice) call\\?|Video call .*\\?|Voice call .*\\?|Call .*\\?)$"), lbl("^(Call|Start)$", clickable = true))),
            ScreenDef("wa_attach", listOf(lbl("^(Gallery|Document|Camera|Location|Contact|Audio|Poll)$", clickable = true), lbl("^(Gallery|Document)$"))),
            ScreenDef("wa_gallery", listOf(lbl("^(Recents|Recent|All media|Gallery|Photos)$"), lbl("^(Photo|Image|Video),? .*", clickable = true))),
            ScreenDef("wa_preview", listOf(lbl("^(Add a caption.*|Add caption.*)$"), SEND.last())),
            ScreenDef("wa_camera", listOf(Sel(resId = "shutter"))),
            ScreenDef("wa_settings", listOf(lbl("^(Account|Privacy|Chats|Notifications|Storage and data|Help)$", clickable = true), lbl("^Settings$"))),
            ScreenDef("wa_chats_settings", listOf(lbl("^Chat backup$", clickable = true))),
            ScreenDef("wa_backup", listOf(lbl("^(Back up|Back Up|BACK UP)$", clickable = true), lbl("^(Last backup|Google Account|Backup to Google).*"))),
            ScreenDef("wa_menu", listOf(lbl("^Settings$", clickable = true), lbl("^(New group|Linked devices|Starred( messages)?|New broadcast)$", clickable = true))),
        ),
        backHint = say("This is another WhatsApp page. Tap the back arrow at the top left.",
            "यह WhatsApp का दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।", "ఇది WhatsApp లో వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి."),
        routes = listOf(
            // "Akash sent me a photo, how do I see it?": their chat → the newest photo → it opens big.
            Route(
                id = "wa_see_photo", pkg = PKG,
                goals = goals("(sent|send|shared|forwarded) me (a |an |the |some )?(photo|image|picture|pic|pics|photos)",
                    "(see|open|view|look at) (the |a |an |that )?(photo|image|picture|pic)", "(photo|image|picture|pic) (from|by) ",
                    "(received|got) (a |an )?(photo|image|picture)",
                    "(फोटो|फ़ोटो|तस्वीर).*(भेजी|भेजा)", "(फोटो|फ़ोटो|तस्वीर) (कैसे )?(देख|खोल)", "ఫోటో (పంపా|పంపి)", "ఫోటో (ఎలా )?(చూడ|తెరవ)"),
                avoid = listOf(rx("\\b(send|share) (my|this|the|a) (photo|picture|pic) to\\b|screenshot|स्क्रीनशॉट|aadhaa?r|आधार|ఆధార్")),
                slots = listOf("contact"),
                steps = inChat(MapStep("wa_chat_them", listOf(Sel(resId = "image", label = rx("^(Enlarge photo|View photo|Photo)$"), pick = Pick.BOTTOM),
                    lbl("^(Enlarge photo|View photo)$").copy(pick = Pick.BOTTOM)),
                    say("Tap the photo to see it big.", "फ़ोटो को बड़ा देखने के लिए उसे दबाइए।", "ఫోటోను పెద్దగా చూడటానికి దాన్ని నొక్కండి."),
                    why = say("Pictures in a chat are small; one tap opens them full screen.", "चैट में फ़ोटो छोटी दिखती है; एक बार दबाने से पूरी खुलती है।",
                        "చాట్‌లో ఫోటోలు చిన్నగా ఉంటాయి; ఒక్క నొక్కుతో పూర్తిగా తెరుచుకుంటాయి."),
                    scrollHint = say("Slowly scroll up in the chat to find the photo.", "चैट में धीरे से ऊपर स्क्रॉल करके फ़ोटो ढूँढिए।",
                        "చాట్‌లో నెమ్మదిగా పైకి స్క్రోల్ చేసి ఫోటో వెతకండి."), alsoOn = listOf("wa_chat_label"))),
                // The viewer: the picture full screen, Reply at the bottom, Save / Forward at the top.
                done = listOf(lbl("^Reply$"), lbl("^(Save|Forward)$")), doneNeedsLastStep = true,
                doneSay = say("Here it is, big. Pinch with two fingers to zoom. Tap the back arrow to return to the chat.",
                    "यह रही, बड़ी। दो उँगलियों से फैलाकर और बड़ा कीजिए। चैट पर लौटने के लिए पीछे वाला तीर दबाइए।",
                    "ఇదిగో, పెద్దగా. రెండు వేళ్లతో విడదీసి ఇంకా పెద్దది చేయండి. చాట్‌కి తిరిగి రావడానికి వెనక్కి బాణం నొక్కండి."),
            ),
            Route(
                id = "wa_video_call", pkg = PKG,
                // "see the photo my grandson sent on whatsapp" is a photo, not a call (field: "see .+ on whatsapp" won).
                avoid = listOf(rx("photo|picture|image|\\bpic|फोटो|फ़ोटो|तस्वीर|ఫోటో")),
                goals = goals("video ?call( to)? .+", ".+ (ko|को) (video|वीडियो) (call|कॉल)", "वीडियो कॉल", "వీడియో కాల్", "(see|talk to) .+ on (video|whatsapp)"),
                slots = listOf("contact"),
                steps = inChat(MapStep("wa_chat_them", listOf(VIDEO), say("Tap the camera icon at the top to video call {contact}.",
                    "{contact} को वीडियो कॉल के लिए ऊपर कैमरे वाला निशान दबाइए।", "{contact} కి వీడియో కాల్ చేయడానికి పైన కెమెరా గుర్తు నొక్కండి."),
                    risky = true, alsoOn = listOf("wa_chat_label")),
                    MapStep("wa_call_confirm", listOf(lbl("^(Call|Start)$", clickable = true)), say("Tap Call.", "'Call' दबाइए।", "'Call' నొక్కండి."), risky = true)),
                done = listOf(lbl("^(End call|Leave call|Turn camera off|Ringing|Calling).*")),
                doneSay = say("Calling {contact}. Hold the phone in front of your face.", "{contact} को कॉल जा रही है। फ़ोन चेहरे के सामने रखिए।",
                    "{contact} కి కాల్ వెళ్తోంది. ఫోన్‌ను మీ ముఖం ముందు పట్టుకోండి."),
            ),
            Route(
                id = "wa_voice_call", pkg = PKG,
                goals = goals("whatsapp call", "call .+ on whatsapp", "(voice|audio) call", "व्हाट्सएप (पर )?कॉल", "వాట్సాప్ కాల్"),
                avoid = listOf(rx("video|वीडियो|వీడియో")),
                slots = listOf("contact"),
                steps = inChat(MapStep("wa_chat_them", listOf(VOICE), say("Tap the phone icon at the top to call {contact}.", "{contact} को कॉल के लिए ऊपर फ़ोन वाला निशान दबाइए।",
                    "{contact} కి కాల్ చేయడానికి పైన ఫోన్ గుర్తు నొక్కండి."), risky = true, alsoOn = listOf("wa_chat_label")),
                    MapStep("wa_call_confirm", listOf(lbl("^(Call|Start)$", clickable = true)), say("Tap Call.", "'Call' दबाइए।", "'Call' నొక్కండి."), risky = true)),
                done = listOf(lbl("^(End call|Ringing|Calling).*")),
                doneSay = say("Calling {contact}.", "{contact} को कॉल जा रही है।", "{contact} కి కాల్ వెళ్తోంది."),
            ),
            Route(
                id = "wa_message", pkg = PKG,
                goals = goals("(message|text|msg|write to) .+", "send (a )?message", "मैसेज (भेजो|करो)", ".+ (को|ko) (मैसेज|msg|message)", "మెసేజ్ (పంపు|చేయి)"),
                avoid = listOf(rx("photo|picture|फोटो|ఫోటో|location|लोकेशन|లొకేషన్|\\bsms\\b|size|bigger|smaller|font|अक्षर|అక్షర|voice (note|message|msg)|audio (note|message)|वॉयस|వాయిస్")),
                slots = listOf("contact", "text"),
                steps = inChat(
                    MapStep("wa_chat_them", ENTRY, say("Tap the box at the bottom and type your message.[ I can type: “{text}”.]",
                        "नीचे बॉक्स दबाकर संदेश लिखिए।[ मैं लिख सकता हूँ: “{text}”।]", "కింద బాక్స్ నొక్కి సందేశం టైప్ చేయండి.[ నేను టైప్ చేయగలను: “{text}”.]"),
                        fill = "text", alsoOn = listOf("wa_chat_label")),
                    MapStep("wa_chat_them", SEND, say("Read it once. Then tap the green send arrow.", "एक बार पढ़ लीजिए। फिर हरा भेजें वाला तीर दबाइए।",
                        "ఒకసారి చదవండి. తర్వాత ఆకుపచ్చ పంపు బాణం నొక్కండి."), risky = true, alsoOn = listOf("wa_chat_label"), needsReached = 3),
                ),
                done = emptyList(),
                doneSay = say("Sent.", "भेज दिया।", "పంపబడింది."),
            ),
            // "Akash sent me a flight ticket, open it": his chat → the newest document (or picture) → Saathi reads it (Guide).
            Route(
                id = "wa_open_doc", pkg = PKG,
                goals = goals("open (the )?(ticket|document|pdf|file) from .+"),
                slots = listOf("contact"),
                steps = inChat(MapStep("wa_chat_them", listOf(Sel(label = rx("\\.(pdf|docx?|jpe?g|png)\\b|\\bPDF$"), pick = Pick.BOTTOM),
                    lbl("^(Enlarge photo|View photo)$").copy(pick = Pick.BOTTOM)),
                    say("Tap the ticket to open it. I'll read it for you.", "टिकट खोलने के लिए उसे दबाइए। मैं पढ़कर बताऊँगा।",
                        "టికెట్ తెరవడానికి దాన్ని నొక్కండి. నేను చదివి చెప్తాను."),
                    scrollHint = say("Slowly scroll up in the chat to find the ticket.", "चैट में धीरे से ऊपर स्क्रॉल करके टिकट ढूँढिए।",
                        "చాట్‌లో నెమ్మదిగా పైకి స్క్రోల్ చేసి టికెట్ వెతకండి."), alsoOn = listOf("wa_chat_label"))),
                done = emptyList(),
                doneSay = say("The ticket is open.", "टिकट खुल गया।", "టికెట్ తెరుచుకుంది."),
            ),
            // "Take a photo of this paper and send it to my son": his chat → the camera in the message box → shutter → send.
            Route(
                id = "wa_camera", pkg = PKG,
                avoid = listOf(rx("posted|\\bsent\\b|send me|look at|see it|received|video ?call")),
                goals = goals("(take|click|capture|snap) (a |the )?(photo|picture|pic|snap)", "photo of (this|the|my) .+ (to|and send|send)",
                    "(फोटो|फ़ोटो) (खींच|ले) .*(भेज)", "ఫోటో (తీసి|తీయి).*(పంపు)"),
                slots = listOf("contact"),
                steps = inChat(
                    MapStep("wa_chat_them", listOf(id("camera_btn"), lbl("^Camera$", clickable = true)),
                        say("Tap the camera icon in the message box. Hold the paper flat, in good light.", "संदेश बॉक्स में कैमरे का निशान दबाइए। कागज़ सीधा, रोशनी में रखिए।",
                            "మెసేజ్ బాక్స్‌లో కెమెరా గుర్తు నొక్కండి. కాగితాన్ని వెలుతురులో సమంగా పట్టుకోండి."), alsoOn = listOf("wa_chat_label")),
                    MapStep("wa_camera", listOf(id("shutter"), lbl("^(Take photo|Take picture|Shutter|Capture|Take a photo).*", clickable = true)),
                        say("Keep the whole paper in the frame, then tap the big round button.", "पूरा कागज़ फ़्रेम में रखिए, फिर बड़ा गोल बटन दबाइए।",
                            "కాగితం మొత్తం ఫ్రేమ్‌లో ఉంచి, పెద్ద గుండ్రటి బటన్ నొక్కండి."), risky = true),
                    MapStep("wa_preview", SEND, say("Check the photo is clear. Then tap the green send arrow.", "देख लीजिए फोटो साफ़ है। फिर हरा भेजें वाला तीर दबाइए।",
                        "ఫోటో స్పష్టంగా ఉందో చూడండి. తర్వాత ఆకుపచ్చ పంపు బాణం నొక్కండి."), risky = true),
                ),
                done = emptyList(),
                doneSay = say("Photo sent.", "फोटो भेज दी।", "ఫోటో పంపబడింది."),
            ),
            // "Send a voice note to Akash": his chat → press and hold the mic. Saathi never records or sends for them.
            Route(
                id = "wa_voice_note", pkg = PKG,
                goals = goals("voice (note|message|msg|record)", "audio (note|message|msg)", "record (a )?(voice|message)", "वॉयस (नोट|मैसेज|मेसेज)",
                    "आवाज़ (में )?(भेज|मैसेज)", "వాయిస్ (నోట్|మెసేజ్)"),
                slots = listOf("contact"),
                steps = inChat(
                    MapStep("wa_chat_them", listOf(id("voice_note_btn"), lbl("^Voice message$", clickable = true)),
                        say("Press and hold the microphone at the bottom right. Keep holding while you speak. Let go to send.",
                            "नीचे दाईं ओर माइक को दबाकर रखिए। दबाए रखते हुए बोलिए। छोड़ते ही भेज दिया जाएगा।",
                            "కింద కుడి వైపు మైక్‌ని నొక్కి పట్టుకోండి. పట్టుకునే మాట్లాడండి. వదిలితే పంపబడుతుంది."),
                        risky = true, alsoOn = listOf("wa_chat_label")),
                ),
                done = emptyList(),
                doneSay = say("Voice note sent.", "वॉयस नोट भेज दिया।", "వాయిస్ నోట్ పంపబడింది."),
            ),
            Route(
                id = "wa_photo", pkg = PKG,
                // Sending theirs, never seeing one someone sent ("my grandson posted a picture… I want to look at it").
                avoid = listOf(rx("posted|\\bsent\\b|send me|look at|see it|received|got a|how (do i|to|can i) (see|open|view)|\\b(take|click|capture|snap|खींच|తీసి|తీయి)\\b")),
                goals = goals("send (a |the |my )?(photo|picture|pic) (to|on) .+", "(photo|picture) .*whatsapp", "(फोटो|फ़ोटो) .*(भेजो|व्हाट्सएप)", "ఫోటో .*(పంపు|వాట్సాప్)"),
                slots = listOf("contact"),
                steps = inChat(
                    MapStep("wa_chat_them", listOf(id("input_attach_button"), lbl("^Attach$", clickable = true)),
                        say("Tap the paper clip to attach.", "जोड़ने के लिए पेपर-क्लिप दबाइए।", "జత చేయడానికి పేపర్ క్లిప్ నొక్కండి."), alsoOn = listOf("wa_chat_label")),
                    MapStep("wa_attach", listOf(lbl("^Gallery$", clickable = true)), say("Tap Gallery.", "'Gallery' दबाइए।", "'Gallery' నొక్కండి.")),
                    MapStep("wa_gallery", listOf(Sel(label = rx("^(Photo|Image),? "), clickable = true, pick = Pick.TOP)),
                        say("Tap the photo you want to send.", "जो फोटो भेजनी है उसे दबाइए।", "పంపాలనుకునే ఫోటో నొక్కండి.")),
                    MapStep("wa_preview", SEND, say("Tap the green send arrow.", "हरा भेजें वाला तीर दबाइए।", "ఆకుపచ్చ పంపు బాణం నొక్కండి."), risky = true),
                ),
                done = emptyList(),
                doneSay = say("Photo sent.", "फोटो भेज दी।", "ఫోటో పంపబడింది."),
            ),
            Route(
                id = "wa_backup", pkg = PKG,
                goals = goals("(whatsapp|chat) backup", "back ?up (my )?(whatsapp|chats)", "व्हाट्सएप बैकअप", "चैट बैकअप", "వాట్సాప్ బ్యాకప్", "చాట్ బ్యాకప్"),
                slots = emptyList(),
                steps = listOf(
                    MapStep("wa_home", listOf(lbl("^More options$", clickable = true)), say("Tap the three dots at the top right.", "ऊपर दाईं ओर तीन बिंदु दबाइए।",
                        "పైన కుడివైపు మూడు చుక్కలు నొక్కండి.")),
                    MapStep("wa_menu", listOf(lbl("^Settings$", clickable = true)), say("Tap Settings.", "'Settings' दबाइए।", "'Settings' నొక్కండి.")),
                    MapStep("wa_settings", listOf(lbl("^Chats$", clickable = true)), say("Tap Chats.", "'Chats' दबाइए।", "'Chats' నొక్కండి.")),
                    MapStep("wa_chats_settings", listOf(lbl("^Chat backup$", clickable = true)), say("Tap Chat backup.", "'Chat backup' दबाइए।", "'Chat backup' నొక్కండి."),
                        scrollHint = say("Scroll down to Chat backup.", "नीचे 'Chat backup' तक स्क्रॉल कीजिए।", "కిందకు 'Chat backup' వరకు స్క్రోల్ చేయండి.")),
                    MapStep("wa_backup", listOf(lbl("^(Back up|Back Up|BACK UP)$", clickable = true)), say("Tap the green Back up button.", "हरा 'Back up' बटन दबाइए।",
                        "ఆకుపచ్చ 'Back up' బటన్ నొక్కండి."),
                        why = say("This saves your chats and photos to Google Drive.", "इससे आपकी बातें और फोटो Google Drive में सुरक्षित रहती हैं।",
                            "మీ చాట్లు, ఫోటోలు Google Drive లో సురక్షితంగా ఉంటాయి.")),
                ),
                done = listOf(lbl("^(Backing up|Back up complete|Uploading).*")),
                doneSay = say("Your chats are being backed up.", "आपकी बातों का बैकअप हो रहा है।", "మీ చాట్ల బ్యాకప్ అవుతోంది."),
            ),
            Route(
                id = "wa_open_location", pkg = PKG,
                goals = goals("(location|address|pin) .+ (sent|shared)", "(open|go to|directions to) the location", "location (in|on) whatsapp",
                    "(भेजी|भेजा) (हुई|हुआ)? ?(लोकेशन|जगह)", "लोकेशन खोलो", "(పంపిన )?లొకేషన్ (తెరువు|చూపించు)"),
                slots = listOf("contact"),
                steps = inChat(MapStep("wa_chat_them", listOf(Sel(label = rx("location|maps\\.google|goo\\.gl/maps|maps\\.app\\.goo\\.gl"), clickable = true, pick = Pick.TOP,
                    not = rx("^(Location|Attach)$"))),
                    say("Tap the map in the chat. It opens in Google Maps.", "चैट में नक्शा दबाइए। यह Google Maps में खुलेगा।",
                        "చాట్‌లో మ్యాప్ నొక్కండి. ఇది Google Maps లో తెరుచుకుంటుంది."),
                    scrollHint = say("Scroll up in the chat to find the map they sent.", "भेजा हुआ नक्शा ढूँढने के लिए चैट में ऊपर स्क्रॉल कीजिए।",
                        "వారు పంపిన మ్యాప్ కోసం చాట్‌లో పైకి స్క్రోల్ చేయండి."), alsoOn = listOf("wa_chat_label"))),
                done = emptyList(),
                doneSay = say("Here is the place in Google Maps.", "Google Maps में यह रही जगह।", "Google Maps లో ఇదిగో ఆ ప్రదేశం."),
                next = listOf(say("Want directions, or a cab to get there?", "रास्ता चाहिए या वहाँ जाने के लिए कैब?", "దారి కావాలా, లేక అక్కడికి క్యాబ్ కావాలా?")),
            ),
        ),
    )
}
