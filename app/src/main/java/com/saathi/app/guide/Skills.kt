package com.saathi.app.guide

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.saathi.app.R

/** Home-screen groups, everyday life first, money last. */
enum class Cat(val label: Say, val icon: Int) {
    DAILY(say("Everyday help", "रोज़ के काम", "రోజువారీ పనులు"), R.drawable.ic_light_mode),
    CHAT(say("Family & friends", "परिवार और दोस्त", "కుటుంబం & స్నేహితులు"), R.drawable.ic_family_restroom),
    WATCH(say("Watch & listen", "देखें और सुनें", "చూడండి & వినండి"), R.drawable.ic_smart_display),
    LEARN(say("Learn something new", "कुछ नया सीखें", "కొత్తది నేర్చుకోండి"), R.drawable.ic_school),
    FIX(say("Fix my phone", "फ़ोन ठीक करें", "ఫోన్ సరిచేయండి"), R.drawable.ic_settings),
    MONEY(say("Money safety", "पैसों की सुरक्षा", "డబ్బు భద్రత"), R.drawable.ic_shield),
}

/**
 * Something Saathi knows how to help with. [keywords] are matched against the request (EN/HI/TE);
 * longer keywords weigh more. [example] is what a Home row sends when tapped.
 */
class Skill(
    val id: String,
    val cat: Cat,
    val icon: Int,
    val title: Say,
    val keywords: List<String>,
    val example: Say,
    val build: (Context, Slots) -> Flow,
)

private fun s3(en: String, hi: String, te: String) = say(en, hi, te)
private val NONE = s3("", "", "")

object Skills {

    private val SEARCH_BTN = rx("^Search$", "^Search ", "Search YouTube", "^Find$", "^Search contacts")
    private val SEARCH_BOX = rx("Search", "Find")
    private val SAVE = rx("^Save( copy)?$", "^Done$", "^OK$", "^Apply$", "^Set$")

    private fun searchSteps(query: String, pick: Say, youtube: Boolean = false) = listOf(
        Step("search", SEARCH_BTN, s3("Tap the magnifying glass to search.", "खोजने के लिए आवर्धक काँच (खोज) दबाइए।", "వెతకడానికి భూతద్దం నొక్కండి.")),
        Step("type", SEARCH_BOX, s3("Type \"$query\". Or tap Do it and I'll type it.", "\"$query\" लिखिए। या 'आप कर दो' दबाइए, मैं लिख दूँगा।", "\"$query\" టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి, నేను టైప్ చేస్తాను."),
            role = "input", fill = query),
        // Typed: the matching suggestion row runs the search (as does the keyboard's search key).
        Step("go", listOf(Regex("^" + Regex.escape(query) + "$", RegexOption.IGNORE_CASE)),
            s3("Now tap “$query” in the list, or the search key on the keyboard.", "अब सूची में “$query” दबाइए, या कीबोर्ड पर खोज वाला बटन।",
                "ఇప్పుడు జాబితాలో “$query” నొక్కండి, లేదా కీబోర్డ్‌లో వెతుకు బటన్."), role = "button",
            screenHas = Regex("Edit suggestion|Search for", RegexOption.IGNORE_CASE)), // only while suggestions show
        // A real result: never the search box, a suggestion or an ad (field test: "Tap the video you like" pointed at a
        // history suggestion, then at the search box itself).
        // A real video row: YouTube describes each one as "<title> - <length> - … - <views> - play video" (titles may be in
        // Hindi for an English search, so we don't match the query words). Never an ad, a channel card or a suggestion.
        Step("pick", listOf(if (youtube) Regex("(?i)^(?!.*\\bSponsored\\b)(.*\\bplay (video|short)\\s*$|(Playlist|Mix) - .*)")
            else Regex("^(?=.{${query.length + 12},})(?!.*(Edit suggestion|Sponsored|Search for)).*" +
                Regex.escape(query.split(" ").maxByOrNull { it.length } ?: query), RegexOption.IGNORE_CASE)), pick,
            role = "button", unlessVisible = listOf(Regex("^Search YouTube$", RegexOption.IGNORE_CASE), Regex("Edit suggestion", RegexOption.IGNORE_CASE))),
    )

    val all: List<Skill> = listOf(

        // ───────────── FAMILY & FRIENDS ─────────────
        Skill("wa_video", Cat.CHAT, R.drawable.ic_videocam, s3("Video call family", "परिवार को वीडियो कॉल", "కుటుంబానికి వీడియో కాల్"),
            listOf("video call", "videocall", "video chat", "face to face", "वीडियो कॉल", "विडियो कॉल", "వీడియో కాల్"),
            s3("Video call my son on WhatsApp", "बेटे को वीडियो कॉल करो", "కొడుకుకి వీడియో కాల్ చేయి")) { ctx, s -> whatsapp(ctx, s, video = true) },

        Skill("wa_message", Cat.CHAT, R.drawable.ic_chat, s3("Send a WhatsApp message", "WhatsApp संदेश भेजें", "WhatsApp సందేశం పంపండి"),
            listOf("whatsapp", "message", "msg", "text my", "text to", "chat with", "संदेश", "मैसेज", "సందేశం", "మెసేజ్"),
            s3("Send a WhatsApp message to my son", "बेटे को मैसेज भेजो", "కొడుకుకి మెసేజ్ పంపు")) { ctx, s -> whatsapp(ctx, s, video = false) },

        Skill("wa_photo", Cat.CHAT, R.drawable.ic_add_photo_alternate, s3("Send a photo on WhatsApp", "WhatsApp पर फोटो भेजें", "WhatsApp లో ఫోటో పంపండి"),
            listOf("send photo", "send a photo", "send picture", "share photo", "send the photo", "share this picture", "share the picture", "share a picture", "send this picture", "फोटो भेज", "फ़ोटो भेज", "ఫోటో పంపు"),
            s3("Send a photo to my son on WhatsApp", "बेटे को फोटो भेजो", "కొడుకుకి ఫోటో పంపు")) { ctx, s ->
            val base = whatsapp(ctx, s, video = false)
            Flow("wa_photo", base.launch,
                base.steps.filter { it.key in setOf("search", "type", "pick") } + listOf(
                    Step("attach", rx("^Attach", "^Camera$"), s3("Tap the paper clip to attach.", "जोड़ने के लिए पेपर-क्लिप दबाइए।", "జత చేయడానికి పేపర్ క్లిప్ నొక్కండి.")),
                    Step("gallery", rx("^Gallery$", "^Photos"), s3("Tap Gallery.", "'Gallery' दबाइए।", "'Gallery' నొక్కండి.")),
                    Step("photo", rx("^Photo", "^Image", "^Video"), s3("Tap the photo you want to send.", "जो फोटो भेजनी है उसे दबाइए।", "పంపాలనుకునే ఫోటో నొక్కండి.")),
                    Step("send", rx("^Send$"), s3("Tap the green send arrow.", "हरा भेजें वाला तीर दबाइए।", "ఆకుపచ్చ పంపు బాణం నొక్కండి.")),
                ), null, s3("Photo sent!", "फोटो चली गई!", "ఫోటో వెళ్ళింది!"),
                s3("Let's send a photo on WhatsApp.", "चलिए WhatsApp पर फोटो भेजते हैं।", "WhatsApp లో ఫోటో పంపుదాం."), llmGoal = s.raw)
        },

        Skill("call", Cat.CHAT, R.drawable.ic_call, s3("Call someone", "किसी को फ़ोन करें", "ఎవరికైనా కాల్ చేయండి"),
            listOf("call ", "phone my", "phone to", "ring ", "फ़ोन कर", "फोन कर", "कॉल कर", "फ़ोन लगा", "फोन लगा", "ఫోన్ చేయి", "కాల్ చేయి", "ఫోన్ చెయ్"),
            s3("Call my son", "बेटे को फ़ोन करो", "కొడుకుకి ఫోన్ చేయి")) { ctx, s ->
            val num = Prefs.familyPhone(ctx)
            val fam = Prefs.family(ctx)
            val name = s.contact ?: fam.ifBlank { "" }
            val useFamily = num.isNotBlank() && (s.contact == null || s.contact.equals(fam, true))
            Flow("call",
                { if (useFamily) Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")) else Intent(Intent.ACTION_DIAL) },
                if (useFamily) listOf(
                    Step("dial", rx("^Call$", "^Dial$", "voice call", "^Call button", "^Dial button"), s3("Tap the green call button.", "हरा कॉल बटन दबाइए।", "ఆకుపచ్చ కాల్ బటన్ నొక్కండి.")),
                ) else listOf(
                    Step("contacts", rx("^Contacts$"), s3("Tap Contacts.", "'Contacts' दबाइए।", "'Contacts' నొక్కండి.")),
                    Step("search", SEARCH_BTN + rx("^Search contacts"), s3("Tap search.", "खोज दबाइए।", "వెతుకు నొక్కండి.")),
                    Step("type", SEARCH_BOX, s3("Type $name.", "$name लिखिए।", "$name టైప్ చేయండి."), role = "input", fill = name.ifBlank { null }),
                    Step("pick", if (name.isNotBlank()) listOf(Regex(Regex.escape(name), RegexOption.IGNORE_CASE)) else rx("^\\u0000$"),
                        s3("Tap $name.", "$name दबाइए।", "$name నొక్కండి.")),
                    Step("dial", rx("^Call ", "^Call$", "voice call", "^Mobile"), s3("Tap the call button.", "कॉल बटन दबाइए।", "కాల్ బటన్ నొక్కండి.")),
                ),
                { sc -> Regex("End call|Hang up|Calling|Dialing|Dialling", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
                s3("Calling now. Speak after they pick up!", "कॉल लग रहा है। उठाने पर बात कीजिए!", "కాల్ వెళ్తోంది. వాళ్ళు ఎత్తాక మాట్లాడండి!"),
                if (name.isBlank()) s3("Let's make a call.", "चलिए फ़ोन करते हैं।", "కాల్ చేద్దాం.")
                else s3("Let's call $name.", "चलिए $name को फ़ोन करते हैं।", "$name కి కాల్ చేద్దాం."),
                llmGoal = "call ${name.ifBlank { "someone" }}")
        },

        // ───────────── WATCH & LISTEN ─────────────
        Skill("youtube", Cat.WATCH, R.drawable.ic_play_circle, s3("Watch on YouTube", "YouTube पर देखें", "YouTube లో చూడండి"),
            listOf("youtube", "song", "bhajan", "kirtan", "news", "video of", "recipe", "गाना", "गाने", "भजन", "यूट्यूब", "పాట", "భజన", "యూట్యూబ్"),
            s3("Play Hanuman Chalisa on YouTube", "हनुमान चालीसा लगाओ", "హనుమాన్ చాలీసా పెట్టు")) { _, s ->
            val q = SlotExtractor.searchPhrase(s.query?.takeIf { it.length > 1 } ?: s.raw).ifBlank { "bhajan" }
            Flow("youtube", { AppLauncher.launch(it, "com.google.android.youtube") },
                searchSteps(q, youtube = true, pick = s3("Tap the video you like. The picture shows what it is.", "जो वीडियो पसंद हो उसे दबाइए। तस्वीर से पता चलता है।", "నచ్చిన వీడియోను నొక్కండి. బొమ్మ చూస్తే తెలుస్తుంది.")),
                { sc -> sc.elements.any { Regex("^(Pause|Play) video|^Minimi[sz]e|Enter fullscreen|^Full screen", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } },
                s3("Enjoy! Tap the video once to see the pause and full-screen buttons.", "आनंद लीजिए! रोकने के लिए वीडियो को एक बार छुइए।", "ఆనందించండి! ఆపడానికి వీడియోను ఒకసారి తాకండి."),
                s3("Let's find \"$q\" on YouTube.", "YouTube पर \"$q\" ढूँढते हैं।", "YouTube లో \"$q\" వెతుకుదాం."), teach = true, llmGoal = "play $q on YouTube",
                appPkg = "com.google.android.youtube")
        },

        Skill("tv", Cat.WATCH, R.drawable.ic_tv, s3("TV remote", "टीवी रिमोट", "టీవీ రిమోట్"),
            listOf("tv", "television", "tv remote", "tv volume", "tv off", "tv on", "tv go", "tv channel", "set top box",
                "टीवी", "टीवी की आवाज़", "टीवी बंद", "टीवी चैनल", "టీవీ", "టీవీ ఛానెల్"),
            s3("Turn the TV volume up", "टीवी की आवाज़ बढ़ाओ", "టీవీ సౌండ్ పెంచు")) { _, s ->
            val digits = IrRemote.digitsFor(s.raw)
            val key = if (digits == null) IrRemote.keyFor(s.raw) else null
            Flow("tv", null, emptyList(), null, NONE, NONE, quiet = key == null && digits == null, action = { ctx ->
                if (digits != null && IrRemote.sendDigits(ctx, digits)) s3("Changing to channel ${digits.joinToString("") { it.name.drop(1) }}.",
                    "चैनल ${digits.joinToString("") { it.name.drop(1) }} लगा रहा हूँ।", "ఛానెల్ ${digits.joinToString("") { it.name.drop(1) }} పెడుతున్నాను.")
                else if (key != null && IrRemote.send(ctx, key)) when (key) {
                    IrRemote.Key.POWER -> s3("Done. I pressed the TV power button.", "टीवी का पावर बटन दबा दिया।", "టీవీ పవర్ బటన్ నొక్కాను.")
                    IrRemote.Key.VOL_UP -> s3("Louder.", "आवाज़ बढ़ा दी।", "సౌండ్ పెంచాను.")
                    IrRemote.Key.VOL_DOWN -> s3("Softer.", "आवाज़ कम कर दी।", "సౌండ్ తగ్గించాను.")
                    IrRemote.Key.MUTE -> s3("Muted.", "आवाज़ बंद कर दी।", "మ్యూట్ చేశాను.")
                    IrRemote.Key.CH_UP, IrRemote.Key.CH_DOWN -> s3("Changed the channel.", "चैनल बदल दिया।", "ఛానెల్ మార్చాను.")
                    IrRemote.Key.HOME -> s3("TV home screen. Now use up, down, left, right and OK to choose.", "टीवी का होम। अब ऊपर, नीचे, बाएँ, दाएँ और OK से चुनिए।", "టీవీ హోమ్. ఇప్పుడు పైకి, కిందకి, ఎడమ, కుడి, OK తో ఎంచుకోండి.")
                    IrRemote.Key.SOURCE -> s3("Changed the input. Press again for the next one.", "इनपुट बदल दिया। अगले के लिए फिर कहिए।", "ఇన్‌పుట్ మార్చాను. తర్వాతిదానికి మళ్ళీ చెప్పండి.")
                    else -> s3("Pressed.", "दबा दिया।", "నొక్కాను.")
                } else {
                    ctx.startActivity(Intent(ctx, com.saathi.app.ui.RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    s3("Here's your TV remote.", "यह रहा आपका टीवी रिमोट।", "ఇదిగో మీ టీవీ రిమోట్.")
                }
            })
        },

        Skill("ott", Cat.WATCH, R.drawable.ic_movie, s3("Watch a movie or serial", "फ़िल्म या सीरियल देखें", "సినిమా లేదా సీరియల్ చూడండి"),
            listOf("netflix", "prime video", "hotstar", "jiohotstar", "zee5", "sonyliv", "movie", "film", "serial", "series", "फ़िल्म", "फिल्म", "सीरियल", "సినిమా", "సీరియల్"),
            s3("Watch my serial on Hotstar", "सीरियल देखना है", "సీరియల్ చూడాలి")) { ctx, s ->
            val g = s.raw.lowercase()
            val pkg = when {
                "netflix" in g -> "com.netflix.mediaclient"
                "prime" in g -> "com.amazon.avod.thirdpartyclient"
                "zee" in g -> "com.graymatrix.did"
                "sony" in g -> "com.sonyliv"
                "hotstar" in g || "jio" in g -> "in.startv.hotstar"
                else -> AppLauncher.first(ctx, "in.startv.hotstar", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.graymatrix.did", "com.sonyliv")
                    ?: "com.google.android.youtube"
            }
            val q = s.query?.replace(Regex("(?i)\\b(a|the|my)?\\s*(movie|film|serial|series|show)\\b"), "")?.trim()?.takeIf { it.length > 1 }
            // No title ("watch my serial"): point at Search and ask for the name, never "scroll and look for Play".
            val steps = if (q != null && !Regex("(?i)^(my|watch|serial|series|show|movie|film|something|\\s)+$").matches(q))
                searchSteps(q, s3("Tap the one you want to watch.", "जो देखना है उसे दबाइए।", "చూడాలనుకున్నది నొక్కండి."))
            else listOf(Step("search", rx("^Search$", "^Find$", "^Search .*"), s3("Tap Search, then type the name of your serial or film.",
                "'Search' दबाइए, फिर अपने सीरियल या फ़िल्म का नाम लिखिए।", "'Search' నొక్కి, మీ సీరియల్ లేదా సినిమా పేరు టైప్ చేయండి.")))
            Flow("ott", { AppLauncher.launch(it, pkg) },
                steps + Step("play", rx("^Play", "^Resume", "^Watch", "^Continue watching"), s3("Tap Play.", "'Play' दबाइए।", "'Play' నొక్కండి.")),
                { sc -> Regex("^Pause|Skip intro|Audio & Subtitles|Episodes", RegexOption.IGNORE_CASE).let { r -> sc.elements.any { r.containsMatchIn(it.label) } } },
                s3("It's playing. Enjoy!", "चल गया। आनंद लीजिए!", "ప్లే అవుతోంది. ఆనందించండి!"),
                s3("Opening ${AppLauncher.labelOf(ctx, pkg)}.", "${AppLauncher.labelOf(ctx, pkg)} खोल रहा हूँ।", "${AppLauncher.labelOf(ctx, pkg)} తెరుస్తున్నాను."),
                teach = true, llmGoal = s.raw, appPkg = pkg)
        },

        // ───────────── EVERYDAY ─────────────
        Skill("medicine", Cat.DAILY, R.drawable.ic_medication, s3("Medicine reminder", "दवा की याद", "మందుల గుర్తు"),
            listOf("medicine", "tablet", "pill", "remind me", "reminder", "दवा", "दवाई", "गोली", "याद दिला", "మందు", "మాత్ర", "గుర్తు చేయి"),
            s3("Remind me to take my BP tablet at 8 am", "सुबह 8 बजे दवा की याद दिलाओ", "ఉదయం 8 గంటలకు మందు గుర్తు చేయి")) { _, s ->
            val h = s.hour ?: 8; val m = s.minute ?: 0
            val what = Regex("(?i)(?:to take|take|to)\\s+(?:my\\s+)?(.+?)\\s+(?:at|every|daily)").find(s.raw)?.groupValues?.get(1) ?: "medicine"
            alarmFlow("medicine", h, m, "Take $what",
                s3("I'll remind you every day at ${fmt(h, m)} to take $what.", "मैं रोज़ ${fmt(h, m)} बजे दवा की याद दिलाऊँगा।", "రోజూ ${fmt(h, m)}కి మందు గుర్తు చేస్తాను."),
                onDone = { c -> Routines.add(c, h, m, "take $what", "remind") })
        },

        Skill("alarm", Cat.DAILY, R.drawable.ic_alarm, s3("Set an alarm", "अलार्म लगाएँ", "అలారం పెట్టండి"),
            listOf("alarm", "wake me", "wake up", "अलार्म", "जगा देना", "जगाना", "అలారం", "లేపు"),
            s3("Set an alarm for 6 am", "सुबह 6 बजे का अलार्म लगाओ", "ఉదయం 6 గంటలకు అలారం పెట్టు")) { _, s ->
            val h = s.hour ?: 6; val m = s.minute ?: 0
            alarmFlow("alarm", h, m, "Saathi alarm",
                s3("Alarm set for ${fmt(h, m)}.", "${fmt(h, m)} का अलार्म लग गया।", "${fmt(h, m)}కి అలారం పెట్టాను."))
        },

        Skill("maps", Cat.DAILY, R.drawable.ic_directions, s3("Directions to a place", "किसी जगह का रास्ता", "ఒక చోటికి దారి"),
            listOf("navigate", "directions", "route to", "take me to", "how to go", "way to", "रास्ता", "कैसे जाऊँ", "దారి", "ఎలా వెళ్ళాలి", "maps"),
            s3("Take me to the nearest hospital", "अस्पताल का रास्ता दिखाओ", "ఆసుపత్రికి దారి చూపించు")) { _, s ->
            val place = s.place?.takeIf { it.length > 1 } ?: "hospital"
            Flow("maps", { Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps") },
                listOf(
                    Step("pick", rx("^Directions$", "^Directions to"), s3("Tap Directions.", "'Directions' दबाइए।", "'Directions' నొక్కండి."),
                        tip = s3("Maps shows the best road in blue.", "नीली लाइन सबसे अच्छा रास्ता दिखाती है।", "నీలి గీత ఉత్తమ దారి చూపిస్తుంది.")),
                    Step("start", rx("^Start$", "^Start navigation"), s3("Tap Start. Maps will speak each turn.", "'Start' दबाइए। मैप हर मोड़ बताएगा।", "'Start' నొక్కండి. ప్రతి మలుపు మ్యాప్స్ చెబుతుంది.")),
                ),
                { sc -> Regex("Exit navigation|^Re-center|Re-centre", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
                s3("You're on your way. Follow the voice. Safe travels!", "चल पड़िए, आवाज़ के हिसाब से चलिए। शुभ यात्रा!", "బయలుదేరండి, వాయిస్ ప్రకారం వెళ్ళండి. శుభ ప్రయాణం!"),
                s3("Finding the way to $place.", "$place का रास्ता ढूँढ रहा हूँ।", "$place కి దారి వెతుకుతున్నాను."), llmGoal = "get directions to $place",
                appPkg = "com.google.android.apps.maps")
        },

        Skill("camera", Cat.DAILY, R.drawable.ic_photo_camera, s3("Take a photo", "फोटो खींचें", "ఫోటో తీయండి"),
            listOf("take a photo", "take photo", "take a picture", "take picture", "selfie", "camera", "फोटो खींच", "फ़ोटो खींच", "सेल्फी", "कैमरा", "ఫోటో తీయి", "ఫోటో తీ", "సెల్ఫీ", "కెమెరా"),
            s3("Take a selfie", "सेल्फी लो", "సెల్ఫీ తీయి")) { _, s ->
            val selfie = Regex("(?i)selfie|सेल्फी|సెల్ఫీ").containsMatchIn(s.raw)
            Flow("camera", { Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA) },
                (if (selfie) listOf(Step("flip", rx("Switch to front", "^Switch camera", "^Flip", "front camera", "^Switch"),
                    s3("Tap the turning arrows to see yourself.", "ख़ुद को देखने के लिए घूमते तीर दबाइए।", "మిమ్మల్ని చూడటానికి తిరిగే బాణాలు నొక్కండి."))) else emptyList()) +
                    Step("shoot", rx("^Shutter", "^Take photo", "^Take picture", "^Capture", "^Photo$", "shutter button", "^Take a photo"),
                        s3("Hold the phone still and tap the big round button.", "फ़ोन स्थिर रखिए और बड़ा गोल बटन दबाइए।", "ఫోన్ కదలకుండా పట్టుకుని పెద్ద గుండ్రటి బటన్ నొక్కండి."),
                        tip = s3("Tip: tap on a face first so it's sharp.", "सुझाव: पहले चेहरे पर छुइए ताकि फोटो साफ़ आए।", "సూచన: ముందు ముఖం మీద తాకితే ఫోటో స్పష్టంగా వస్తుంది.")),
                null, s3("Lovely photo!", "बढ़िया फोटो!", "అందమైన ఫోటో!"),
                s3("Opening the camera.", "कैमरा खोल रहा हूँ।", "కెమెరా తెరుస్తున్నాను."), teach = true)
        },

        Skill("read_this", Cat.DAILY, R.drawable.ic_document_scanner, s3("Read this for me", "मेरे लिए पढ़ो", "నా కోసం చదువు"),
            listOf("read this", "read it", "read the", "what does this say", "what is written", "what's written", "written here", "this paper", "this letter", "paper say", "magnifier", "zoom in", "can't see", "बड़ा करके दिखाओ",
                "पढ़ो", "पढ़कर सुनाओ", "क्या लिखा है", "చదువు", "చదివి వినిపించు", "ఏం రాసి ఉంది"),
            s3("Read this letter for me", "यह काग़ज़ पढ़कर सुनाओ", "ఈ కాగితం చదివి వినిపించు")) { _, _ ->
            Flow("read_this", null, emptyList(), null, NONE, NONE, action = { ctx ->
                ctx.startActivity(Intent(ctx, com.saathi.app.ui.ReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                s3("Opening the reader.", "पढ़ने वाला कैमरा खोल रहा हूँ।", "చదివే కెమెరా తెరుస్తున్నాను.")
            }, quiet = true)
        },

        Skill("scan_medicine", Cat.DAILY, R.drawable.ic_medication_liquid, s3("Medicine strip → reminder", "दवा का पत्ता → याद", "మందుల స్ట్రిప్ → గుర్తు"),
            listOf("medicine strip", "scan medicine", "scan my medicine", "scan the medicine", "tablet strip", "read my medicine", "दवा का पत्ता", "दवा स्कैन", "మందుల స్ట్రిప్", "మందు స్కాన్"),
            s3("Scan my medicine strip", "दवा का पत्ता स्कैन करो", "మందుల స్ట్రిప్ స్కాన్ చేయి")) { _, _ ->
            Flow("scan_medicine", null, emptyList(), null, NONE, NONE, action = { ctx ->
                ctx.startActivity(Intent(ctx, com.saathi.app.ui.ReadActivity::class.java)
                    .putExtra(com.saathi.app.ui.ReadActivity.EXTRA_MODE, com.saathi.app.ui.ReadActivity.MODE_MEDICINE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                s3("Show me the medicine strip.", "दवा का पत्ता दिखाइए।", "మందుల స్ట్రిప్ చూపించండి.")
            }, quiet = true)
        },

        Skill("torch", Cat.DAILY, R.drawable.ic_flashlight_on, s3("Torch on / off", "टॉर्च चालू / बंद", "టార్చ్ ఆన్ / ఆఫ్"),
            listOf("torch", "flashlight", "flash light", "टॉर्च", "टार्च", "बत्ती", "టార్చ్", "లైట్"),
            s3("Turn on the torch", "टॉर्च जलाओ", "టార్చ్ ఆన్ చేయి")) { _, s ->
            val off = Regex("(?i)\\boff\\b|बंद|बुझा|ఆఫ్|ఆపు").containsMatchIn(s.raw)
            Flow("torch", null, emptyList(), null, NONE, NONE, action = { ctx ->
                runCatching {
                    val cm = ctx.getSystemService(CameraManager::class.java)
                    val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    cm.setTorchMode(id, !off)
                }
                if (off) s3("Torch is off.", "टॉर्च बंद कर दी।", "టార్చ్ ఆఫ్ చేశాను.") else s3("Torch is on.", "टॉर्च जला दी।", "టార్చ్ ఆన్ చేశాను.")
            })
        },

        Skill("home", Cat.DAILY, R.drawable.ic_home, s3("Take me to the home screen", "होम स्क्रीन पर ले चलो", "హోమ్ స్క్రీన్‌కి తీసుకెళ్ళు"),
            listOf("home screen", "the home screen", "go home", "take me home", "main screen", "होम", "घर ले चलो", "शुरू में", "హోమ్", "మొదటికి"),
            s3("Take me to the home screen", "होम स्क्रीन पर ले चलो", "హోమ్ స్క్రీన్‌కి తీసుకెళ్ళు")) { _, _ ->
            Flow("home", null, emptyList(), null, NONE, NONE, action = { ctx ->
                ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                s3("You're on the home screen.", "आप होम स्क्रीन पर हैं।", "మీరు హోమ్ స్క్రీన్‌లో ఉన్నారు.")
            })
        },

        // ───────────── LEARN ─────────────
        Skill("phone_school", Cat.LEARN, R.drawable.ic_school, s3("Phone School", "फ़ोन पाठशाला", "ఫోన్ బడి"),
            listOf("phone school", "lesson", "lessons", "teach me the phone", "पाठशाला", "पाठ", "పాఠం", "బడి"),
            s3("Open Phone School", "फ़ोन पाठशाला खोलो", "ఫోన్ బడి తెరువు")) { _, _ ->
            Flow("phone_school", null, emptyList(), null, NONE, NONE, quiet = true, action = { ctx ->
                ctx.startActivity(Intent(ctx, com.saathi.app.ui.SchoolActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                NONE
            })
        },

        Skill("learn_photo", Cat.LEARN, R.drawable.ic_brush, s3("Learn photo editing", "फोटो एडिटिंग सीखें", "ఫోటో ఎడిటింగ్ నేర్చుకోండి"),
            listOf("edit photo", "photo edit", "edit a photo", "edit picture", "crop", "photo editing", "फोटो एडिट", "फोटो ठीक", "ఫోటో ఎడిట్"),
            s3("Teach me to edit a photo", "फोटो एडिट करना सिखाओ", "ఫోటో ఎడిట్ చేయడం నేర్పు")) { ctx, _ -> editLesson(ctx, video = false) },

        Skill("learn_video", Cat.LEARN, R.drawable.ic_movie_edit, s3("Learn video editing", "वीडियो एडिटिंग सीखें", "వీడియో ఎడిటింగ్ నేర్చుకోండి"),
            listOf("edit video", "video edit", "edit a video", "trim", "cut video", "video editing", "वीडियो एडिट", "వీడియో ఎడిట్"),
            s3("Teach me to trim a video", "वीडियो काटना सिखाओ", "వీడియో కత్తిరించడం నేర్పు")) { ctx, _ -> editLesson(ctx, video = true) },

        Skill("learn_app", Cat.LEARN, R.drawable.ic_menu_book, s3("Teach me any app", "कोई भी ऐप सिखाइए", "ఏ యాప్ అయినా నేర్పండి"),
            listOf("teach me", "how do i use", "how to use", "how does this work", "सिखाओ", "सिखा दो", "कैसे चलाते", "कैसे चलाऊँ", "कैसे इस्तेमाल",
                "నేర్పు", "నేర్పించు", "ఎలా వాడాలి", "ఎలా ఉపయోగించాలి"),
            s3("Teach me how to use Google Photos", "यह ऐप सिखाओ", "ఈ యాప్ నేర్పు")) { ctx, s ->
            val app = AppLauncher.findInGoal(ctx, s.raw)
            // No app named ("how do I use this", "यह कैसे चलाते हैं") → it's a thing in front of them: use the camera.
            // …but never "this mobile / phone / app / screen / website" (field 07:56: "how to access my VPS from this mobile" opened the camera).
            val notAThing = Regex("(?i)\\b(this|that|my|the) (mobile|phone|app|screen|page|website|site|laptop|computer|vps|server)\\b|\\b(ssh|vps|server|website|wifi|internet|account|email|password)\\b").containsMatchIn(s.raw)
            if (app == null && !notAThing && !Regex("(?i)\\bapp\\b|ऐप|యాప్").containsMatchIn(s.raw)) return@Skill Flow("object_help", null, emptyList(), null, NONE, NONE, quiet = true, action = { c ->
                c.startActivity(Intent(c, com.saathi.app.ui.ReadActivity::class.java)
                    .putExtra(com.saathi.app.ui.ReadActivity.EXTRA_MODE, com.saathi.app.ui.ReadActivity.MODE_OBJECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                NONE
            })
            Flow("learn_app", app?.let { a -> { c: Context -> AppLauncher.launch(c, a.pkg) } }, emptyList(), null, NONE,
                s3("Let's learn ${app?.label ?: "this app"} together. I'll explain each part.", "साथ में ${app?.label ?: "यह ऐप"} सीखते हैं।", "కలిసి ${app?.label ?: "ఈ యాప్"} నేర్చుకుందాం."),
                teach = true, llmGoal = s.raw)
        },

        // ───────────── FIX MY PHONE ─────────────
        Skill("font", Cat.FIX, R.drawable.ic_format_size, s3("Make letters bigger", "अक्षर बड़े करें", "అక్షరాలు పెద్దవి చేయండి"),
            listOf("font", "letter", "text bigger", "bigger text", "text size", "small text", "big text", "can't read", "cannot read", "hard to read",
                "अक्षर", "फॉन्ट", "फ़ॉन्ट", "लिखावट", "అక్షరాలు", "అక్షరం", "ఫాంట్"),
            s3("Make the text bigger", "अक्षर बड़े करो", "అక్షరాలు పెద్దవి చేయి")) { _, _ -> CoreFlows.fontSize() },

        Skill("volume", Cat.FIX, R.drawable.ic_volume_up, s3("I can't hear the phone", "फ़ोन की आवाज़ नहीं आ रही", "ఫోన్ శబ్దం వినబడట్లేదు"),
            listOf("volume", "can't hear", "cant hear", "cannot hear", "silent", "no sound", "louder", "आवाज़", "आवाज", "सुनाई नहीं", "సౌండ్", "శబ్దం", "వినబడట్లేదు"),
            s3("My phone is silent", "आवाज़ नहीं आ रही", "సౌండ్ రావట్లేదు")) { _, _ ->
            Flow("volume", null, emptyList(), null, NONE, NONE, action = { ctx ->
                val am = ctx.getSystemService(AudioManager::class.java)
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                for (stream in listOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC, AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_NOTIFICATION)) {
                    runCatching { am.setStreamVolume(stream, (am.getStreamMaxVolume(stream) * 0.85f).toInt(), AudioManager.FLAG_SHOW_UI) }
                }
                s3("I've turned the sound up. Calls and videos will be loud now.",
                    "मैंने आवाज़ बढ़ा दी है। अब कॉल और वीडियो ज़ोर से सुनाई देंगे।",
                    "శబ్దం పెంచాను. ఇప్పుడు కాల్స్, వీడియోలు గట్టిగా వినిపిస్తాయి.")
            })
        },

        Skill("wifi", Cat.FIX, R.drawable.ic_wifi, s3("Turn on Wi-Fi", "Wi-Fi चालू करें", "Wi-Fi ఆన్ చేయండి"),
            listOf("wifi", "wi-fi", "wi fi", "वाईफाई", "वाई-फाई", "वाई फाई", "వైఫై", "వై-ఫై"),
            s3("Turn on Wi-Fi", "वाईफाई चालू करो", "వైఫై ఆన్ చేయి")) { _, s ->
            if (wantsOff(s.raw)) toggleFlow("wifi", Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-?Fi|WLAN|Use Wi", NONE, NONE, off = true) else CoreFlows.wifi() },

        Skill("dark_mode", Cat.FIX, R.drawable.ic_light_mode, s3("Dark screen / light screen", "डार्क मोड", "డార్క్ మోడ్"),
            listOf("dark mode", "dark theme", "night mode", "light mode", "डार्क मोड", "డార్క్ మోడ్"),
            s3("Turn on dark mode", "डार्क मोड चालू करो", "డార్క్ మోడ్ ఆన్ చేయి")) { _, s ->
            val off = wantsOff(s.raw) || Regex("(?i)light mode|लाइट|లైట్").containsMatchIn(s.raw)
            Flow("dark_mode", { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
                listOf(Step("mode", if (off) rx("^Light mode", "^Light$") else rx("^Dark mode$", "^Dark theme", "^Dark$"),
                    if (off) s3("Tap Light mode.", "'Light mode' दबाइए।", "'Light mode' నొక్కండి.") else s3("Tap Dark mode.", "'Dark mode' दबाइए।", "'Dark mode' నొక్కండి."))),
                null, s3("Done!", "हो गया!", "అయింది!"), s3("Opening display settings.", "डिस्प्ले सेटिंग खोल रहा हूँ।", "డిస్‌ప్లే సెట్టింగ్స్ తెరుస్తున్నాను."),
                llmGoal = if (off) "turn on light mode" else "turn on dark mode")
        },

        Skill("internet", Cat.FIX, R.drawable.ic_signal_cellular_alt, s3("Internet not working", "इंटरनेट नहीं चल रहा", "ఇంటర్నెట్ పనిచేయట్లేదు"),
            listOf("internet", "net not", "no internet", "mobile data", "इंटरनेट", "नेट नहीं", "ఇంటర్నెట్", "నెట్ రావట్లేదు"),
            s3("Internet is not working", "इंटरनेट नहीं चल रहा", "ఇంటర్నెట్ రావట్లేదు")) { _, _ ->
            Flow("internet", { Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) },
                listOf(Step("airplane", rx("^Airplane mode", "^Aeroplane mode", "^Flight mode"), s3("Airplane mode must be OFF. Tap it if it's on.", "हवाई जहाज़ मोड बंद होना चाहिए।", "ఎయిర్‌ప్లేన్ మోడ్ ఆఫ్‌లో ఉండాలి."), role = "switch"),
                    Step("data", rx("Mobile data", "^SIM", "Cellular"), s3("Turn on Mobile data.", "'Mobile data' चालू कीजिए।", "'Mobile data' ఆన్ చేయండి."), role = "switch")),
                { sc -> sc.elements.any { it.role == "switch" && it.checked && Regex("Mobile data|Wi-?Fi", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } &&
                    sc.elements.none { it.role == "switch" && it.checked && Regex("Airplane|Aeroplane|Flight", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } },
                s3("Internet should work now. Try again!", "अब इंटरनेट चलना चाहिए। फिर से कोशिश कीजिए!", "ఇప్పుడు ఇంటర్నెట్ పనిచేయాలి. మళ్ళీ ప్రయత్నించండి!"),
                s3("Let's check your internet.", "चलिए इंटरनेट जाँचते हैं।", "ఇంటర్నెట్ చెక్ చేద్దాం."), llmGoal = "turn on mobile data and turn off airplane mode")
        },

        Skill("bluetooth", Cat.FIX, R.drawable.ic_bluetooth, s3("Connect earphones (Bluetooth)", "ब्लूटूथ चालू करें", "బ్లూటూత్ ఆన్ చేయండి"),
            listOf("bluetooth", "earphone", "earbuds", "headphone", "ब्लूटूथ", "ईयरफ़ोन", "బ్లూటూత్", "ఇయర్‌ఫోన్"),
            s3("Connect my earphones", "ब्लूटूथ चालू करो", "బ్లూటూత్ ఆన్ చేయి")) { _, s ->
            toggleFlow("bluetooth", Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth|Use Bluetooth",
                s3("Tap the Bluetooth switch to turn it on.", "ब्लूटूथ का स्विच दबाकर चालू कीजिए।", "బ్లూటూత్ స్విచ్ నొక్కి ఆన్ చేయండి."),
                s3("Bluetooth is on. Now tap your earphones' name to connect.", "ब्लूटूथ चालू है। अब अपने ईयरफ़ोन का नाम दबाइए।", "బ్లూటూత్ ఆన్. ఇప్పుడు మీ ఇయర్‌ఫోన్ పేరు నొక్కండి."), off = wantsOff(s.raw))
        },

        Skill("battery", Cat.FIX, R.drawable.ic_battery_saver, s3("Battery runs out fast", "बैटरी जल्दी ख़त्म होती है", "బ్యాటరీ త్వరగా అయిపోతోంది"),
            listOf("battery", "charge", "power saving", "बैटरी", "चार्ज", "బ్యాటరీ", "ఛార్జ్"),
            s3("Save battery", "बैटरी बचाओ", "బ్యాటరీ ఆదా చేయి")) { _, s ->
            toggleFlow("battery", Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "Battery saver|Power saving|Use Battery Saver|Low power",
                s3("Turn on the battery saver switch.", "बैटरी सेवर का स्विच चालू कीजिए।", "బ్యాటరీ సేవర్ స్విచ్ ఆన్ చేయండి."),
                s3("Battery saver is on. Your charge will last longer.", "बैटरी सेवर चालू। अब चार्ज ज़्यादा चलेगा।", "బ్యాటరీ సేవర్ ఆన్. ఛార్జ్ ఎక్కువసేపు ఉంటుంది."), off = wantsOff(s.raw))
        },

        Skill("brightness", Cat.FIX, R.drawable.ic_light_mode, s3("Screen too dark or bright", "स्क्रीन की रोशनी", "స్క్రీన్ వెలుతురు"),
            listOf("bright", "dark screen", "screen is dark", "dim", "रोशनी", "चमक", "अंधेरा", "వెలుతురు", "చీకటి"),
            s3("Make the screen brighter", "स्क्रीन की रोशनी बढ़ाओ", "స్క్రీన్ వెలుతురు పెంచు")) { _, _ ->
            Flow("brightness", { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
                listOf(Step("slider", rx("."), s3("Slide the dot right for brighter, left for darker.",
                    "रोशनी बढ़ाने के लिए गोला दाईं ओर, कम करने के लिए बाईं ओर खिसकाइए।", "ఎక్కువ వెలుతురు కోసం కుడివైపు, తక్కువ కోసం ఎడమవైపు జరపండి."), role = "slider",
                    screenHas = Regex("brightness", RegexOption.IGNORE_CASE))),
                null, s3("Screen looks good now!", "अब स्क्रीन ठीक है!", "ఇప్పుడు స్క్రీన్ బాగుంది!"),
                s3("Let's fix the screen light.", "स्क्रीन की रोशनी ठीक करते हैं।", "స్క్రీన్ వెలుతురు సరిచేద్దాం."), llmGoal = "change screen brightness")
        },

        Skill("storage", Cat.FIX, R.drawable.ic_cleaning_services, s3("Free up space (storage full)", "जगह ख़ाली करें (स्टोरेज भरा)", "స్థలం ఖాళీ చేయండి (స్టోరేజ్ నిండింది)"),
            listOf("storage full", "storage is full", "is full", "no space", "space full", "memory full", "phone full", "phone is slow", "clean", "free up", "जगह भर", "स्टोरेज भर", "मेमोरी भर", "స్టోరేజ్ నిండ", "స్థలం లేదు"),
            s3("My phone storage is full", "फ़ोन की जगह भर गई", "స్టోరేజ్ నిండిపోయింది")) { ctx, _ ->
            // vivo/iQOO phones: i Manager does the cleaning; others: Files by Google, else Settings › Storage.
            // NOT vivo i Manager: opening it kills accessibility services (field test: Saathi was destroyed on launch).
            val cleaner = AppLauncher.first(ctx, "com.google.android.apps.nbu.files")
            Flow("storage",
                { c -> AppLauncher.launch(c, cleaner) ?: Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
                listOf(
                    Step("clean", rx("^Clean$", "^Free up space", "^Clean up", "^Cleanup", "^Space clean", "^Storage clean", "^One-tap clean", "^Optimi[sz]e", "^Clean now"),
                        s3("Tap Clean up.", "'Clean up' दबाइए।", "'Clean up' నొక్కండి."),
                        tip = s3("It finds junk files and copies you don't need.", "यह बेकार फ़ाइलें और दोहरी फोटो ढूँढता है।", "ఇది అనవసర ఫైళ్ళు, డూప్లికేట్లు వెతుకుతుంది.")),
                    Step("junk", rx("^Junk files", "Clean .* junk", "^Confirm and free up", "^Free up"), s3("Tap to clear the junk files. Your photos are safe.", "बेकार फ़ाइलें हटाइए। आपकी फोटो सुरक्षित हैं।", "జంక్ ఫైళ్ళు తొలగించండి. మీ ఫోటోలు సురక్షితం.")),
                ),
                null, s3("Space freed up!", "जगह ख़ाली हो गई!", "స్థలం ఖాళీ అయింది!"),
                s3("Let's free up some space.", "चलिए थोड़ी जगह ख़ाली करते हैं।", "కొంత స్థలం ఖాళీ చేద్దాం."), teach = true, llmGoal = "free up storage space by cleaning junk files")
        },

        Skill("storage_view", Cat.FIX, R.drawable.ic_cloud_upload, s3("See how much space is used", "कितनी जगह भरी है देखें", "ఎంత స్థలం వాడారో చూడండి"),
            listOf("how much storage", "storage used", "see storage", "check storage", "show storage", "how much space", "कितनी जगह", "స్టోరేజ్ ఎంత"),
            s3("Show me how much storage is used", "कितनी जगह भरी है दिखाओ", "ఎంత స్టోరేజ్ వాడారో చూపించు")) { _, _ ->
            Flow("storage_view", { Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) }, emptyList(), { _ -> true },
                s3("This shows how much space is used and what's using it.", "यहाँ दिखता है कितनी जगह भरी है और किससे।", "ఎంత స్థలం వాడారో, దేనితో వాడారో ఇక్కడ కనిపిస్తుంది."),
                s3("Opening storage.", "स्टोरेज खोल रहा हूँ।", "స్టోరేజ్ తెరుస్తున్నాను."))
        },

        Skill("backup", Cat.FIX, R.drawable.ic_cloud_upload, s3("Back up my phone", "फ़ोन का बैकअप लें", "ఫోన్ బ్యాకప్ తీసుకోండి"),
            listOf("backup", "back up", "save my photos", "backup whatsapp", "whatsapp backup", "chat backup", "back up my whatsapp", "backup my whatsapp", "whatsapp chats backup", "बैकअप", "బ్యాకప్"),
            s3("Back up my WhatsApp chats", "WhatsApp का बैकअप लो", "WhatsApp బ్యాకప్ తీసుకో")) { _, s ->
            if (Regex("(?i)whatsapp|chat|व्हाट्सएप|వాట్సాప్").containsMatchIn(s.raw)) whatsappBackup()
            else Flow("backup", { Intent(Settings.ACTION_SYNC_SETTINGS) }, // never the Settings home page (trap #45)
                listOf(
                    Step("google", rx("^Google$", "^Accounts", "^Cloud", "^Backup and restore", "^Back up and restore", "^System$"), s3("Tap Google.", "'Google' दबाइए।", "'Google' నొక్కండి."),
                        tip = s3("Backup keeps a copy of your photos and contacts safe, even if the phone is lost.", "बैकअप से फ़ोन खोने पर भी फोटो और नंबर सुरक्षित रहते हैं।", "ఫోన్ పోయినా బ్యాకప్ వల్ల ఫోటోలు, నంబర్లు సురక్షితం.")),
                    Step("backup", rx("^Backup$", "^Back up$", "^Google One backup", "^Backup & restore"), s3("Tap Backup.", "'Backup' दबाइए।", "'Backup' నొక్కండి.")),
                    Step("now", rx("^Back up now$", "^Backup now$"), s3("Tap Back up now.", "'Back up now' दबाइए।", "'Back up now' నొక్కండి.")),
                ),
                { sc -> Regex("Backing up|Backup complete|Last backup: (Just now|0 minutes)", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
                s3("Backup has started. Keep Wi-Fi on and the phone charging.", "बैकअप शुरू हो गया। Wi-Fi और चार्जर लगा रहने दीजिए।", "బ్యాకప్ మొదలైంది. Wi-Fi, ఛార్జర్ ఉంచండి."),
                s3("Let's back up your phone.", "चलिए फ़ोन का बैकअप लेते हैं।", "ఫోన్ బ్యాకప్ తీసుకుందాం."), teach = true, llmGoal = "back up the phone to Google")
        },

        // ───────────── MONEY SAFETY ─────────────
        Skill("real_upi", Cat.MONEY, R.drawable.ic_account_balance, s3("Open GPay / PhonePe safely", "GPay / PhonePe सुरक्षित खोलें", "GPay / PhonePe సురక్షితంగా తెరవండి"),
            listOf("gpay", "google pay", "phonepe", "phone pe", "paytm", "bhim", "upi", "गूगल पे", "फोनपे", "ఫోన్‌పే", "గూగుల్ పే"),
            s3("Open Google Pay", "गूगल पे खोलो", "గూగుల్ పే తెరువు")) { ctx, s ->
            val g = s.raw.lowercase()
            val pkg = when {
                "phonepe" in g || "phone pe" in g || "फोनपे" in g || "ఫోన్‌పే" in g -> "com.phonepe.app"
                "paytm" in g -> "net.one97.paytm"
                "bhim" in g -> "in.org.npci.upiapp"
                else -> "com.google.android.apps.nbu.paisa.user"
            }
            val label = AppLauncher.labelOf(ctx, pkg)
            Flow("real_upi", { AppLauncher.launch(it, pkg) }, emptyList(), null, NONE,
                s3("Opening $label. I'm watching for scams while you pay.", "$label खोल रहा हूँ। आप पैसे भेजें, मैं धोखे पर नज़र रखूँगा।", "$label తెరుస్తున్నాను. మీరు చెల్లిస్తుంటే మోసాలపై నేను కాపలా ఉంటాను."),
                llmGoal = s.raw)
        },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun matches(sk: Skill, goal: String): Boolean { val g = " ${goal.lowercase()} "; return sk.keywords.any { g.contains(it.lowercase()) } }

    /** Best skill by keyword hits; longer keywords weigh more, so "video call" beats "call ". */
    fun match(goal: String): Skill? {
        val g = " ${goal.lowercase()} "
        return all.map { sk -> sk to sk.keywords.sumOf { k -> if (g.contains(k.lowercase())) 1 + k.length / 5 else 0 } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    // ───────────── builders ─────────────

    private fun whatsapp(ctx: Context, s: Slots, video: Boolean): Flow {
        val who = s.contact ?: Prefs.family(ctx).ifBlank { "your family" }
        val text = s.text
        val pkg = AppLauncher.first(ctx, "com.whatsapp", "com.whatsapp.w4b") ?: "com.whatsapp"
        val nameRx = Regex("(^|\\s)" + Regex.escape(who), RegexOption.IGNORE_CASE)
        val open = listOf(
            Step("search", rx("^Search$", "^Ask Meta AI or Search", "^Search or ask", "^Ask Meta AI"), s3("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన వెతుకు పట్టీ నొక్కండి.")),
            Step("type", rx("Search", "Ask Meta AI"), s3("Type $who.", "$who लिखिए।", "$who టైప్ చేయండి."), role = "input", fill = who),
            Step("pick", listOf(nameRx), s3("Tap $who.", "$who को दबाइए।", "$who ని నొక్కండి.")),
        )
        val finish = if (video) listOf(
            Step("video", rx("^Video call$", "^Video call "), s3("Tap the camera icon at the top to video call.", "वीडियो कॉल के लिए ऊपर कैमरा वाला निशान दबाइए।", "వీడియో కాల్‌కి పైన కెమెరా గుర్తు నొక్కండి.")),
            Step("confirm", rx("^Call$", "^Start video call", "^Video call$"), s3("Tap Call.", "'Call' दबाइए।", "'Call' నొక్కండి.")),
        ) else listOf(
            Step("write", rx("^Message$", "^Type a message"), s3(if (text != null) "Tap the box and type: $text" else "Tap the box at the bottom and type your message.",
                if (text != null) "नीचे डिब्बे में लिखिए: $text" else "नीचे डिब्बे में अपना संदेश लिखिए।",
                if (text != null) "కింద బాక్స్‌లో టైప్ చేయండి: $text" else "కింద బాక్స్‌లో మీ సందేశం టైప్ చేయండి."), role = "input", fill = text,
                tip = s3("Tip: hold the microphone to send a voice message instead.", "सुझाव: माइक दबाकर रखें और बोलकर भेजें।", "సూచన: మైక్ నొక్కి పట్టుకుని మాట్లాడి పంపవచ్చు.")),
            Step("send", rx("^Send$"), s3("Tap the green send arrow.", "हरा भेजें वाला तीर दबाइए।", "ఆకుపచ్చ పంపు బాణం నొక్కండి.")),
        )
        return Flow(if (video) "wa_video" else "wa_message", { AppLauncher.launch(it, pkg) }, open + finish,
            if (video) { sc -> Regex("Ringing|Calling|End call|Connecting", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) }
            else if (text != null) { sc -> sc.allText.contains(text, true) && sc.elements.any { it.role == "input" && Regex("^Message$").matches(it.label) } }
            else null,
            if (video) s3("Calling $who. Hold the phone in front of your face!", "$who को कॉल लग रहा है। फ़ोन चेहरे के सामने रखिए!", "$who కి కాల్ వెళ్తోంది. ఫోన్ ముఖం ముందు పట్టుకోండి!")
            else s3("Message sent to $who!", "$who को संदेश चला गया!", "$who కి సందేశం వెళ్ళింది!"),
            if (video) s3("Let's video call $who on WhatsApp.", "WhatsApp पर $who को वीडियो कॉल करते हैं।", "WhatsApp లో $who కి వీడియో కాల్ చేద్దాం.")
            else s3("Let's send $who a WhatsApp message.", "$who को WhatsApp संदेश भेजते हैं।", "$who కి WhatsApp సందేశం పంపుదాం."),
            llmGoal = s.raw, appPkg = "com.whatsapp")
    }

    private fun whatsappBackup() = Flow("wa_backup", { AppLauncher.launch(it, "com.whatsapp") },
        listOf(
            Step("menu", rx("^More options$"), s3("Tap the three dots at the top right.", "ऊपर दाईं ओर तीन बिंदु दबाइए।", "పైన కుడివైపు మూడు చుక్కలు నొక్కండి.")),
            Step("settings", rx("^Settings$"), s3("Tap Settings.", "'Settings' दबाइए।", "'Settings' నొక్కండి.")),
            Step("chats", rx("^Chats$"), s3("Tap Chats.", "'Chats' दबाइए।", "'Chats' నొక్కండి.")),
            Step("backup", rx("^Chat backup$", "^Backup$"), s3("Tap Chat backup.", "'Chat backup' दबाइए।", "'Chat backup' నొక్కండి."),
                tip = s3("This saves your chats and photos to Google Drive.", "इससे आपकी बातें और फोटो Google Drive में सुरक्षित रहती हैं।", "మీ చాట్లు, ఫోటోలు Google Drive లో భద్రంగా ఉంటాయి.")),
            Step("now", rx("^Back up$"), s3("Tap the green Back up button.", "हरा 'Back up' बटन दबाइए।", "ఆకుపచ్చ 'Back up' బటన్ నొక్కండి.")),
        ),
        { sc -> Regex("Backing up|Uploading|Preparing backup", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
        s3("WhatsApp backup started!", "WhatsApp बैकअप शुरू हो गया!", "WhatsApp బ్యాకప్ మొదలైంది!"),
        s3("Let's back up your WhatsApp chats.", "चलिए WhatsApp का बैकअप लेते हैं।", "WhatsApp బ్యాకప్ తీసుకుందాం."), teach = true, llmGoal = "back up WhatsApp chats")

    /** On/off both work: "turn off Bluetooth" waits for the switch to be OFF. */
    fun wantsOff(raw: String) = Regex("(?i)\\b(off|disable|stop)\\b|बंद|ऑफ|ఆఫ్|ఆపు").containsMatchIn(raw)

    private fun toggleFlow(id: String, intent: Intent, label: String, stepSay: Say, done: Say, off: Boolean = false): Flow {
        val r = Regex(label, RegexOption.IGNORE_CASE)
        val name = label.substringBefore('|')
        return Flow(id, { intent }, listOf(Step("toggle", listOf(r), if (!off) stepSay else s3("Tap the $name switch to turn it off.", "$name का स्विच दबाकर बंद कीजिए।", "$name స్విచ్ నొక్కి ఆఫ్ చేయండి."), role = "switch")),
            { sc -> sc.elements.any { it.role == "switch" && it.checked != off && r.containsMatchIn(it.label) } },
            if (!off) done else s3("$name is off.", "$name बंद हो गया।", "$name ఆఫ్ అయింది."),
            s3("Opening settings for you.", "आपके लिए सेटिंग खोल रहा हूँ।", "మీ కోసం సెట్టింగ్స్ తెరుస్తున్నాను."), llmGoal = "turn ${if (off) "off" else "on"} $name")
    }

    /** SET_ALARM opens the Clock app prefilled; the person confirms (needs the SET_ALARM permission, trap #16). */
    private fun alarmFlow(id: String, h: Int, m: Int, message: String, done: Say, onDone: ((Context) -> Unit)? = null) = Flow(id,
        {
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                .apply { if (id == "medicine") putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(1, 2, 3, 4, 5, 6, 7)) }
        },
        listOf(Step("save", SAVE, s3("Check the time and tap Save.", "समय देखकर 'Save' दबाइए।", "సమయం చూసి 'Save' నొక్కండి."))),
        // Some clocks save without a confirm screen: done when our label shows up (trap #17).
        { sc -> sc.allText.contains(message, ignoreCase = true) ||
            Regex("Alarm set for|Alarm for .* set|hours and|minutes from now", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
        done, s3("Setting it for ${fmt(h, m)}.", "${fmt(h, m)} के लिए लगा रहा हूँ।", "${fmt(h, m)}కి పెడుతున్నాను."), llmGoal = "save the alarm",
        memo = "$message · ${fmt(h, m)}${if (id == "medicine") " daily" else ""}", onDone = onDone)

    private fun editLesson(ctx: Context, video: Boolean): Flow {
        val pkg = AppLauncher.first(ctx, "com.google.android.apps.photos", "com.vivo.gallery", "com.android.gallery3d")
        val steps = mutableListOf(
            Step("open", if (video) rx("^Video taken", "^Video,", "^Video ·") else rx("^Photo taken", "^Photo,", "^Photo ·", "^Image taken", "^Portrait photo"),
                if (video) s3("Tap any video.", "कोई भी वीडियो दबाइए।", "ఏదైనా వీడియో నొక్కండి.") else s3("Tap any photo you like.", "कोई भी फोटो दबाइए।", "నచ్చిన ఫోటో నొక్కండి."),
                tip = s3("Editing never spoils your original. We'll save a copy.", "असली फोटो ख़राब नहीं होगी, हम कॉपी सेव करेंगे।", "అసలు ఫోటో పాడవదు, కాపీ సేవ్ చేస్తాం.")),
            Step("edit", rx("^Edit$", "^Edit "), s3("Tap Edit at the bottom.", "नीचे 'Edit' दबाइए।", "కింద 'Edit' నొక్కండి."),
                tip = s3("Edit opens the tools, like a little studio.", "Edit से औज़ार खुलते हैं, एक छोटे स्टूडियो जैसे।", "Edit తో టూల్స్ తెరుచుకుంటాయి, చిన్న స్టూడియోలా.")),
        )
        if (video) steps += listOf(
            Step("trim", rx("^Video$", "^Trim$", "^Cut$"), s3("Tap Trim.", "'Trim' दबाइए।", "'Trim' నొక్కండి."),
                tip = s3("Drag the white handles at both ends to cut the start or end.", "दोनों तरफ़ के सफ़ेद हैंडल खींचकर शुरू या अंत काटिए।", "రెండు వైపుల తెల్ల హ్యాండిల్స్ లాగి మొదలు/చివర కత్తిరించండి.")),
            Step("stabilize", rx("^Stabili[sz]e$"), s3("Tap Stabilize to remove shaking.", "हिलना हटाने के लिए 'Stabilize' दबाइए।", "వణుకు తగ్గించడానికి 'Stabilize' నొక్కండి.")),
        ) else steps += listOf(
            Step("crop", rx("^Crop$"), s3("Tap Crop.", "'Crop' दबाइए।", "'Crop' నొక్కండి."),
                tip = s3("Crop cuts away edges. Drag the white corners inward.", "Crop किनारे काटता है। सफ़ेद कोनों को अंदर खींचिए।", "Crop అంచులు కత్తిరిస్తుంది. తెల్ల మూలలను లోపలికి లాగండి.")),
            Step("adjust", rx("^Adjust$", "^Tools$", "^Tune"), s3("Now tap Adjust.", "अब 'Adjust' दबाइए।", "ఇప్పుడు 'Adjust' నొక్కండి.")),
            Step("bright", rx("^Brightness$"), s3("Tap Brightness and slide to make it lighter.", "'Brightness' दबाकर खिसकाइए।", "'Brightness' నొక్కి జరపండి.")),
        )
        steps += Step("save", rx("^Save copy$", "^Save$", "^Done$"), s3("Tap Save copy. Your original stays safe too!", "'Save copy' दबाइए। असली फोटो भी रहेगी!", "'Save copy' నొక్కండి. అసలు కూడా ఉంటుంది!"))
        return Flow(if (video) "learn_video" else "learn_photo", { c -> AppLauncher.launch(c, pkg) }, steps,
            { sc -> Regex("Saving|Saved|Copy saved", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
            s3("Beautiful! You just edited it yourself.", "सुंदर! आपने ख़ुद एडिट कर लिया।", "అద్భుతం! మీరే ఎడిట్ చేశారు."),
            if (video) s3("Let's learn video editing, step by step.", "चलिए क़दम-क़दम वीडियो एडिटिंग सीखते हैं।", "అడుగు అడుగునా వీడియో ఎడిటింగ్ నేర్చుకుందాం.")
            else s3("Let's learn photo editing, step by step.", "चलिए क़दम-क़दम फोटो एडिटिंग सीखते हैं।", "అడుగు అడుగునా ఫోటో ఎడిటింగ్ నేర్చుకుందాం."),
            teach = true, llmGoal = if (video) "trim a video and save a copy" else "crop a photo, adjust brightness and save a copy")
    }

    fun fmt(h: Int, m: Int): String {
        val hh = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(hh, m, if (h < 12) "AM" else "PM")
    }
}
