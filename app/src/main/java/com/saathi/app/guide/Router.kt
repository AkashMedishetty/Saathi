package com.saathi.app.guide

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Installed apps, for "open <app>" and for naming the app we pause on. */
object AppLauncher {
    data class App(val label: String, val pkg: String)

    @Volatile private var cache: List<App>? = null

    fun installed(ctx: Context): List<App> = cache ?: run {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .also { cache = it }
    }

    fun isInstalled(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    fun first(ctx: Context, vararg pkgs: String): String? = pkgs.firstOrNull { isInstalled(ctx, it) }
    fun launch(ctx: Context, pkg: String?): Intent? = pkg?.let {
        // "Open Settings": the Settings search page, never the home page (trap #45).
        if (it == "com.android.settings") Intent("android.settings.APP_SEARCH_SETTINGS").takeIf { i -> i.resolveActivity(ctx.packageManager) != null }
            ?: Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
        else ctx.packageManager.getLaunchIntentForPackage(it)
    }
    fun labelOf(ctx: Context, pkg: String): String = installed(ctx).firstOrNull { it.pkg == pkg }?.label ?: pkg

    /** "open calculator" / "how do I use Instagram" → that app; the longest label match wins. */
    /** App names people say in Hindi / Telugu script → the English label the phone uses. */
    private val INDIC_APPS = listOf(
        "instagram" to Regex("ఇన్‌?స్టా|ఇంస్టా|ఇన్స్టా|इंस्टा|इन्स्टा"),
        "whatsapp" to Regex("వాట్స(ా)?ప్|వాట్సప్|व्हाट्स|वॉट्स|वाट्स"),
        "youtube" to Regex("యూట్యూబ్|యూట్యుబ్|यूट्यूब|युट्यूब"),
        "facebook" to Regex("ఫేస్‌?బుక్|फेसबुक|फ़ेसबुक"),
        "chrome" to Regex("క్రోమ్|क्रोम"),
        "gmail" to Regex("జీమెయిల్|जीमेल"),
        "maps" to Regex("మ్యాప్స్|మ్యాప్|मैप्स|मैप"),
        "camera" to Regex("కెమెరా|कैमरा"),
        "calculator" to Regex("క్యాలిక్యులేటర్|కాలిక్యులేటర్|कैलकुलेटर"),
        "phonepe" to Regex("ఫోన్‌?పే|फोनपे|फ़ोनपे"),
        "google pay" to Regex("గూగుల్ ?పే|गूगल ?पे"),
        "paytm" to Regex("పేటీఎం|पेटीएम"),
        "netflix" to Regex("నెట్‌?ఫ్లిక్స్|नेटफ्लिक्स"),
        "play store" to Regex("ప్లే ?స్టోర్|प्ले ?स्टोर"),
        "telegram" to Regex("టెలిగ్రామ్|टेलीग्राम"),
    )
    fun latinAppNames(goal: String): String {
        var g = goal.replace("\u200C", "").replace("\u200D", "")
        INDIC_APPS.forEach { (en, rx) -> g = rx.replace(g) { " $en " } }
        return g
    }

    fun findInGoal(ctx: Context, goal: String): App? {
        val g = " ${latinAppNames(goal).lowercase()} "
        // An app named after "in / on / using / open" is the one they mean ("weather … in chrome" → Chrome, not Weather).
        // Check the words after EVERY such word (the last one usually names the app).
        val words = g.trim().split(Regex("\\s+"))
        val apps = installed(ctx).filter { it.pkg != ctx.packageName && it.label.length >= 3 }
        for (i in words.indices.reversed()) {
            if (words[i] !in setOf("in", "on", "using", "with", "open", "launch", "start", "से", "में", "पर", "లో")) continue
            for (n in 3 downTo 1) {
                val name = words.drop(i + 1).take(n).joinToString(" ").trim('.', ',', '?', '!')
                if (name.length < 3) continue
                apps.firstOrNull { it.label.lowercase() == name }?.let { return it }
            }
        }
        return installed(ctx)
            .filter { it.pkg != ctx.packageName && it.label.length >= 3 }
            .filter { g.contains(" ${it.label.lowercase()}") || g.contains(it.label.lowercase().replace(" ", "")) }
            .maxByOrNull { it.label.length }
    }
}

/** Goal text → Flow. P0-3 adds the full skills registry in front of the app fallback. */
object IntentRouter {
    private fun String.has(vararg w: String) = w.any { it in this }

    fun isExplain(goal: String) = goal.lowercase().has(
        "what is on", "what's on", "explain", "what is this", "where am i", "i'm lost", "i am lost", "samjhao",
        "समझाओ", "यह क्या है", "मैं कहाँ हूँ", "कहाँ हूँ", "ఏమిటి", "వివరించు", "ఏముంది", "ఎక్కడ ఉన్నాను")

    fun isFamilyHelp(goal: String) = goal.lowercase().has(
        "ask family", "ask my son", "ask my daughter", "ask for help", "help from family", "call for help", "tell my son", "tell my daughter",
        "परिवार से", "बेटे से पूछो", "बेटी से पूछो", "मदद मांगो", "मदद माँगो", "కుటుంబాన్ని అడుగు", "సహాయం అడుగు", "కొడుకుని అడుగు", "కూతురుని అడుగు")

    fun isReadMessages(goal: String) = goal.lowercase().has(
        "read my messages", "read messages", "any messages", "new messages", "who messaged", "read my whatsapp",
        "मेरे संदेश", "संदेश पढ़ो", "मैसेज पढ़ो", "मैसेज सुनाओ", "సందేశాలు చదువు", "మెసేజ్‌లు చదువు", "మెసేజ్ చదువు")

    fun isSos(goal: String) = goal.lowercase().trim().let { g ->
        Regex("^(help|help me|help!|sos|emergency|save me)\\W*$").matches(g) || g.has("emergency", "call ambulance", "i fell", "i have fallen",
            "बचाओ", "आपातकाल", "एम्बुलेंस", "मैं गिर गया", "मैं गिर गई", "కాపాడండి", "అత్యవసరం", "ఆపద", "అంబులెన్స్")
    }

    fun isRecall(goal: String) = goal.lowercase().has(
        "what is my", "what's my", "when is my", "when do i", "do you remember", "what did i", "what did i tell", "what did i do", "yesterday", "last time", "which tablet", "which medicine",
        "क्या है मेरी", "मेरी दवा क्या", "मेरा क्या", "याद है", "क्या बताया था", "గుర్తుందా", "నా మందు ఏమిటి", "ఏం చెప్పాను")

    fun isBriefing(goal: String) = goal.lowercase().trim().let { g ->
        Regex("^(good morning|what'?s today|what is today|today'?s plan|my day)\\W*$").matches(g) || g.has("आज क्या है", "आज का दिन", "సుప్రభాతం", "ఈరోజు ఏమిటి")
    }

    private val SETTINGS_TOPIC = Regex("(?i)ringtone|ring tone|wallpaper|language|date|time zone|password|screen lock|lock screen|fingerprint|face unlock|" +
        "notification sound|vibrat|hotspot|mobile data|data usage|location|gps|software update|system update|about phone|keyboard|auto.?rotate|" +
        "do not disturb|airplane|flight mode|sim|nfc|default app|app permission|eye (protection|comfort)|blue light|night light|reading mode|screen timeout|auto.?lock|रिंगटोन|वॉलपेपर|भाषा|पासवर्ड|रिंगटోన్|రింగ్‌టోన్|వాల్‌పేపర్|భాష|పాస్‌వర్డ్")

    /** Starts like a real question ("what / why / how …", or ends with "?"), not a command like "make the text bigger". */
    fun phrasedAsQuestion(g: String) = QUESTION.containsMatchIn(g.trim())

    /** "Teach me …", "how do I …", "show me how …": learning, so no shortcuts. */
    fun wantsToLearn(g: String) = Regex("(?i)\\b(teach me|show me how|how (do|can|should) i|how to|help me learn|i want to learn)\\b|सिखा|कैसे करते|नेर्प|నేర్ప|ఎలా చేయాలి|ఎలా వాడాలి").containsMatchIn(g)

    /** Is this about the phone / an app / a setting (then it's a task to guide, never a "video" or a chat answer)? */
    fun aboutPhone(ctx: Context, g: String) = PHONE_WORDS.containsMatchIn(g) || SETTINGS_TOPIC.containsMatchIn(g) ||
        AppLauncher.findInGoal(ctx, g) != null || Skills.match(g) != null

    private val SETTINGS_VERB = Regex("(?i)change|set|put|turn|switch|how (do|to|can)|बदल|लगा|మార్చ|పెట్ట")

    /** "How do I change my ringtone" / "set a photo as wallpaper": a settings task, whatever the model calls it. */
    fun settingsTask(goal: String): Flow? =
        if (SETTINGS_TOPIC.containsMatchIn(goal) && SETTINGS_VERB.containsMatchIn(goal)) settingsSearch(goal) else null

    private val HOW_TO = Regex("(?i)^\\W*(please\\s+)?(teach me|show me how|help me (to )?(use|learn|change|set)|how (do|can|should) i|how to|how does|i want to learn)\\b|सिखा|कैसे|నేర్ప|ఎలా")

    /**
     * "Teach me to edit a photo", "how do I use Instagram", "how do I change my ringtone": a thing ON the phone → guide it
     * with the glow. Only general knowledge ("how to make upma") gets a spoken answer. Decided before the model, which
     * labels all of these "question" (field test: everything came back as text).
     */
    fun phoneHowTo(ctx: Context, goal: String): Flow? {
        if (!HOW_TO.containsMatchIn(goal)) return null
        settingsTask(goal)?.let { return it }
        val slots = SlotExtractor.from(goal)
        // The specific lessons first (trim a video, edit a photo), then any other matching skill.
        Skills.all.firstOrNull { it.id in setOf("learn_video", "learn_photo") && Skills.matches(it, goal) }?.let { return it.build(ctx, slots) }
        Skills.match(goal)?.takeIf { it.id != "learn_app" }?.let { return it.build(ctx, slots) }
        AppLauncher.findInGoal(ctx, goal)?.let { app -> return Skills.byId("learn_app")?.build(ctx, SlotExtractor.from("how do I use ${app.label}")) }
        if (PHONE_WORDS.containsMatchIn(goal)) return Skills.byId("learn_app")?.build(ctx, slots)
        return null
    }

    /** Phone-settings goals: open Settings search and type the topic (works on every OEM skin). */
    fun settingsSearch(goal: String): Flow? {
        val m = SETTINGS_TOPIC.find(goal) ?: return null
        val term = m.value.lowercase()
        return Flow("settings_search", { Intent("android.settings.APP_SEARCH_SETTINGS") },
            listOf(Step("open_search", rx("^Search settings", "^Search$", "^Search "), say("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన వెతుకు పట్టీ నొక్కండి.")),
                Step("type", rx("Search"), say("Type “$term”. Or tap Do it and I'll type it.", "“$term” लिखिए। या 'आप कर दो' दबाइए।", "“$term” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి."),
                role = "input", fill = term),
                // After typing: point at the matching result (never back at the search bar; field test).
                // A real result row ("Incoming call ringtone"), not the search-history chip that says just "ringtone".
                Step("result", listOf(Regex("(?i)^[^·]*(\\S+\\s+" + Regex.escape(term) + "|" + Regex.escape(term) + "\\s+\\S+)")), say("Now tap the result that matches.", "अब मिलता हुआ नतीजा दबाइए।", "ఇప్పుడు సరిపోయే ఫలితం నొక్కండి."),
                    role = "button")),
            null, say("Here it is. Choose what you like on this page.", "यह रहा। इस पेज पर जो पसंद हो चुनिए।", "ఇదిగో. ఈ పేజీలో మీకు నచ్చింది ఎంచుకోండి."),
            say("Let's find “$term” in Settings.", "Settings में “$term” ढूँढते हैं।", "Settings లో “$term” వెతుకుదాం."),
            llmGoal = goal)
    }

    sealed interface Route {
        data class Skill(val flow: Flow?) : Route
        data object Question : Route
    }

    /**
     * Model-first routing (field test: "show me how much storage is used" hit the cleanup skill on a keyword).
     * Gemma picks the helper from the list; keywords are only the fallback while it loads.
     */
    suspend fun smartRoute(ctx: Context, goal: String): Route {
        val kw = route(ctx, goal)
        if (!com.saathi.app.llm.LlmManager.isReady) return Route.Skill(kw)
        val list = Skills.all.joinToString("\n") { "${it.id}: ${it.title.pick(Lang.EN)} (e.g. \"${it.example.pick(Lang.EN)}\")" }
        val out = com.saathi.app.llm.LlmManager.generate(
            "You route an elderly person's request to the right helper on their Android phone. Reply with ONE word: a helper id from the list, " +
                "or SETTINGS (a phone setting to find or change), or APP (open or use a specific app not in the list), or QUESTION (they want an answer, not a phone action), or OTHER.",
            "Helpers:\n$list\n\nRequest: \"$goal\"\nAnswer:")?.trim()?.split(Regex("[^A-Za-z_]+"))?.firstOrNull { it.isNotBlank() } ?: return Route.Skill(kw)
        com.saathi.app.DebugLog.i("route", "\"$goal\" → model=$out keyword=${kw?.id}")
        val namedApp = AppLauncher.findInGoal(ctx, goal) != null
        return when {
            // The model may only call it a question if it's phrased as one and names no app.
            out.equals("QUESTION", true) && !namedApp && isQuestion(ctx, goal) -> Route.Question
            out.equals("QUESTION", true) -> Route.Skill(kw)
            out.equals("SETTINGS", true) -> Route.Skill(settingsSearch(goal) ?: kw ?: settingsFlow(goal))
            Skills.byId(out) != null -> Route.Skill(Skills.byId(out)!!.build(ctx, SlotExtractor.from(goal, Prefs.family(ctx))))
            else -> Route.Skill(kw)
        }
    }

    fun settingsFlowPublic(goal: String) = settingsFlow(goal)

    /** Any phone-setting goal: open Settings search with the goal's key words. */
    private fun settingsFlow(goal: String): Flow {
        val term = SlotExtractor.searchPhrase(goal).split(Regex("\\s+")).filter { it.length > 2 && it.lowercase() !in setOf("change", "my", "the", "show", "how", "much", "turn", "set", "make") }
            .takeLast(2).joinToString(" ").ifBlank { goal }
        return Flow("settings_search", { Intent("android.settings.APP_SEARCH_SETTINGS") },
            listOf(Step("open_search", rx("^Search settings", "^Search$", "^Search "), say("Tap the search bar at the top.", "ऊपर खोज पट्टी दबाइए।", "పైన వెతుకు పట్టీ నొక్కండి.")),
                Step("type", rx("Search"), say("Type “$term”.", "“$term” लिखिए।", "“$term” టైప్ చేయండి."), role = "input", fill = term)),
            null, say("Done!", "हो गया!", "అయింది!"), say("Let's find “$term” in Settings.", "Settings में “$term” ढूँढते हैं।", "Settings లో “$term” వెతుకుదాం."),
            llmGoal = goal)
    }

    fun isGreeting(goal: String) = Regex("(?i)^\\W*(hi|hello|hey|hlo|namaste|namaskar|namaskaram|good (morning|afternoon|evening|night)|thank you|thanks|how are you|" +
        "नमस्ते|नमस्कार|हैलो|धन्यवाद|शुक्रिया|आप कैसे हैं|హలో|హాయ్|నమస్కారం|ధన్యవాదాలు|బాగున్నారా)\\W*(saathi|साथी|సాథీ)?\\W*$").matches(goal.trim())

    private val PHONE_WORDS = Regex("(?i)phone|mobile|app\\b|screen|setting|button|whatsapp|call|message|wifi|wi-fi|bluetooth|youtube|netflix|hotstar|tv\\b|" +
        "photo|camera|alarm|remind|torch|volume|internet|battery|storage|ticket|irctc|upi|pay|notification|keyboard|font|letters|" +
        "फ़ोन|फोन|ऐप|स्क्रीन|सेटिंग|व्हाट्सएप|कॉल|मैसेज|वीडियो|फोटो|कैमरा|अलार्म|टीवी|ఫోన్|యాప్|స్క్రీన్|సెట్టింగ్|వాట్సాప్|కాల్|మెసేజ్|వీడియో|ఫోటో|టీవీ")
    private val QUESTION = Regex("(?i)^\\W*(how|what|why|who|when|where|which|can you|could you|tell me|explain|is it|should i|do you|are you|what's|whats)\\b|\\?\\s*$|" +
        "^(क्या|कैसे|क्यों|कौन|कब|कहाँ|मुझे बताओ)|(क्या है|कैसे बनाते|कैसे करते)|^(ఏమిటి|ఎలా|ఎందుకు|ఎవరు|ఎప్పుడు|ఎక్కడ)|(ఎలా చేయాలి|ఏమిటి)")

    /** Live facts, places, reading, objects: phrased as questions, but they need the phone/camera/web, not a chat answer. */
    private val NOT_CHAT = Regex("(?i)weather|rain|umbrella|hot|cold|temperature|forecast|score|price|rate|news|today|tomorrow|match|" +
        "go to|reach|way to|route|near(est|by)?|hospital|station|address|" +
        "paper|letter|written|read|say\\b|this (machine|thing|remote|device|washing|microwave|tv|fan|geyser)|use this|work(s)? this|where am i|" +
        "बारिश|मौसम|गर्मी|रास्ता|पढ़|यह कैसे|వర్షం|వాతావరణం|దారి|చదువ|ఇది ఎలా")

    /** A question or chit-chat to answer out loud, not a phone task to navigate. */
    fun isQuestion(ctx: Context?, goal: String): Boolean {
        if (isGreeting(goal)) return true
        if (!QUESTION.containsMatchIn(goal.trim())) return false
        if (NOT_CHAT.containsMatchIn(goal)) return false
        if (PHONE_WORDS.containsMatchIn(goal)) return false
        // "How do I change my ringtone / set a wallpaper": a phone task to guide, not a chat answer.
        if (SETTINGS_TOPIC.containsMatchIn(goal) && SETTINGS_VERB.containsMatchIn(goal)) return false
        if (Skills.match(goal) != null) return false
        return ctx == null || AppLauncher.findInGoal(ctx, goal) == null
    }

    /** Things the system can just do (no guidance needed). */
    fun systemAction(goal: String): Int? {
        val g = goal.lowercase()
        return when {
            Regex("notification|नोटिफिकेशन|सूचनाएँ|నోటిఫికేషన్").containsMatchIn(g) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            Regex("quick settings|control cent(er|re)|क्विक सेटिंग").containsMatchIn(g) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            Regex("recent apps|open apps|all apps open|हाल के ऐप").containsMatchIn(g) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            Regex("^(go )?back$|^वापस( जाओ)?$|^వెనక్కి( వెళ్ళు)?$").containsMatchIn(g.trim()) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            Regex("lock (the )?(phone|screen)|फ़ोन लॉक|ఫోన్ లాక్").containsMatchIn(g) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            Regex("screenshot|स्क्रीनशॉट|స్క్రీన్‌షాట్").containsMatchIn(g) -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> null
        }
    }

    /** "How do I use this washing machine?" → the camera helps with the object in front of them. */
    fun isObjectHelp(goal: String) = Regex("(?i)how (do i|to|does) (use|work|operate|start) (this|the)\\b|how does this (work|thing)|use this (machine|thing|remote|device)|" +
        "यह कैसे चल|इसे कैसे चला|ఇది ఎలా వాడ|దీన్ని ఎలా").containsMatchIn(goal)

    /**
     * "Read this letter", "what's written here", "what does this paper say", "scan my medicine strip": the thing is in
     * their hand, so the camera reader is the answer, whatever the model thinks (the 1B model calls these "question").
     */
    fun cameraRead(goal: String): String? {
        val g = goal.lowercase()
        if (Regex("medicine strip|tablet strip|scan (my |the )?medicine|दवा का पत्ता|दवा स्कैन|మందుల స్ట్రిప్|మందు స్కాన్").containsMatchIn(g)) return "scan_medicine"
        return "read_this".takeIf {
            Regex("\\bread (this|it|the (letter|paper|bill|label|note|form|board|sign)s?)\\b|what (does|is) (this|it) (say|written)|what'?s written|written (here|on (this|it))|" +
                "(this|the) (letter|paper|bill|label|prescription) (say|mean)|पढ़कर सुनाओ|क्या लिखा है|ये पढ़ो|यह पढ़ो|चिट्ठी पढ़ो|చదివి వినిపించు|ఏం రాసి ఉంది|ఇది చదువు").containsMatchIn(g)
        }
    }

    /** Skills that do or open exactly the right thing (instant or one screen): they beat the generic "setting" route. */
    val DIRECT = setOf("torch", "volume", "font", "wifi", "bluetooth", "brightness", "battery", "storage", "storage_view", "camera",
        "dark_mode", "read_this", "scan_medicine", "home", "tv", "internet", "backup", "phone_school", "wa_photo")

    fun isScamCheck(goal: String) = goal.lowercase().has("scam", "fraud", "is this safe", "धोखा", "ठगी", "మోసం")

    /** Just "open WhatsApp" / "YouTube खोलो" / "కెమెరా తెరువు": open that app, no model, no guiding loop. */
    fun openOnly(ctx: Context, goal: String): Flow? {
        val g = goal.trim().trimEnd('.', '!', '?')
        if (!Regex("(?i)^(please\\s+)?(open|start|launch)\\s+(the\\s+|my\\s+)?[\\p{L}\\p{M}\\p{N} .&'-]{2,30}?(\\s+app)?(\\s+please)?$|^[\\p{L}\\p{M}\\p{N} .&'-]{2,30}\\s+(खोलो|खोल दो|चालू करो|తెరువు|ఓపెన్ చేయి|ఓపెన్ చెయ్యి)$").matches(g)) return null
        val app = AppLauncher.findInGoal(ctx, g) ?: return null
        return Flow("app_${app.pkg}", { c -> AppLauncher.launch(c, app.pkg) }, emptyList(), { _ -> true },
            say("${app.label} is open.", "${app.label} खुल गया।", "${app.label} తెరుచుకుంది."),
            say("Opening ${app.label}.", "${app.label} खोल रहा हूँ।", "${app.label} తెరుస్తున్నాను."), llmGoal = goal)
    }

    fun route(ctx: Context, goal: String): Flow? {
        val slots = SlotExtractor.from(goal, Prefs.family(ctx))
        Skills.match(goal)?.let { return it.build(ctx, slots) }
        settingsSearch(goal)?.let { return it }
        AppLauncher.findInGoal(ctx, goal)?.let { app ->
            val onlyOpen = Regex("(?i)^\\s*(open|start|launch)\\s+.+$|(खोलो|खोल दो|తెరువు|ఓపెన్ చేయి)\\s*$").containsMatchIn(goal)
            return Flow("app_${app.pkg}", { c -> AppLauncher.launch(c, app.pkg) }, emptyList(),
                if (onlyOpen) { _ -> true } else null,
                say("${app.label} is open.", "${app.label} खुल गया।", "${app.label} తెరుచుకుంది."),
                say("Opening ${app.label}.", "${app.label} खोल रहा हूँ।", "${app.label} తెరుస్తున్నాను."),
                llmGoal = goal)
        }
        return null
    }
}
