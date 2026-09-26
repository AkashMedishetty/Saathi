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
    fun launch(ctx: Context, pkg: String?): Intent? = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    fun labelOf(ctx: Context, pkg: String): String = installed(ctx).firstOrNull { it.pkg == pkg }?.label ?: pkg

    /** "open calculator" / "how do I use Instagram" → that app; the longest label match wins. */
    fun findInGoal(ctx: Context, goal: String): App? {
        val g = " ${goal.lowercase()} "
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
        "what is my", "what's my", "when is my", "when do i", "do you remember", "what did i", "what did i tell", "which tablet", "which medicine",
        "क्या है मेरी", "मेरी दवा क्या", "मेरा क्या", "याद है", "क्या बताया था", "గుర్తుందా", "నా మందు ఏమిటి", "ఏం చెప్పాను")

    fun isBriefing(goal: String) = goal.lowercase().trim().let { g ->
        Regex("^(good morning|what'?s today|what is today|today'?s plan|my day)\\W*$").matches(g) || g.has("आज क्या है", "आज का दिन", "సుప్రభాతం", "ఈరోజు ఏమిటి")
    }

    fun isGreeting(goal: String) = Regex("(?i)^\\W*(hi|hello|hey|hlo|namaste|namaskar|namaskaram|good (morning|afternoon|evening|night)|thank you|thanks|how are you|" +
        "नमस्ते|नमस्कार|हैलो|धन्यवाद|शुक्रिया|आप कैसे हैं|హలో|హాయ్|నమస్కారం|ధన్యవాదాలు|బాగున్నారా)\\W*(saathi|साथी|సాథీ)?\\W*$").matches(goal.trim())

    private val PHONE_WORDS = Regex("(?i)phone|mobile|app\\b|screen|setting|button|whatsapp|call|message|wifi|wi-fi|bluetooth|youtube|netflix|hotstar|tv\\b|" +
        "photo|camera|alarm|remind|torch|volume|internet|battery|storage|ticket|irctc|upi|pay|notification|keyboard|font|letters|" +
        "फ़ोन|फोन|ऐप|स्क्रीन|सेटिंग|व्हाट्सएप|कॉल|मैसेज|वीडियो|फोटो|कैमरा|अलार्म|टीवी|ఫోన్|యాప్|స్క్రీన్|సెట్టింగ్|వాట్సాప్|కాల్|మెసేజ్|వీడియో|ఫోటో|టీవీ")
    private val QUESTION = Regex("(?i)^\\W*(how|what|why|who|when|where|which|can you|could you|tell me|explain|is it|should i|do you|are you|what's|whats)\\b|\\?\\s*$|" +
        "^(क्या|कैसे|क्यों|कौन|कब|कहाँ|मुझे बताओ)|(क्या है|कैसे बनाते|कैसे करते)|^(ఏమిటి|ఎలా|ఎందుకు|ఎవరు|ఎప్పుడు|ఎక్కడ)|(ఎలా చేయాలి|ఏమిటి)")

    /** A question or chit-chat to answer out loud, not a phone task to navigate. */
    fun isQuestion(ctx: Context?, goal: String): Boolean {
        if (isGreeting(goal)) return true
        if (!QUESTION.containsMatchIn(goal.trim())) return false
        if (PHONE_WORDS.containsMatchIn(goal)) return false
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

    fun isScamCheck(goal: String) = goal.lowercase().has("scam", "fraud", "is this safe", "धोखा", "ठगी", "మోసం")

    fun route(ctx: Context, goal: String): Flow? {
        val slots = SlotExtractor.from(goal, Prefs.family(ctx))
        Skills.match(goal)?.let { return it.build(ctx, slots) }
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
