package com.saathi.app.guide

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.saathi.app.llm.LlmManager
import com.saathi.app.service.Overlay
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The guide loop. It never fights the person holding the phone:
 *
 *   screen settles → read → scam check → right app? (else pause) → done? → latest visible scripted step
 *   → scroll hint → planner (LLM in P0-4)  ⇒  glow + card + voice.
 *
 * Saathi only taps when the person presses "Do it" (or asked "do it all for me": auto mode, which still stops
 * and asks before anything risky, and never touches PINs), and only after re-finding the target on a fresh read.
 */
class Guide(
    private val svc: SaathiService,
    private val overlay: Overlay,
    private val speaker: Speaker,
) {
    data class Target(
        val el: UiElement?,
        val text: String,
        val key: String,
        val fill: String? = null,
        val warn: Boolean = false,
        val final: Boolean = false,
        val scroll: Boolean = false,
        /** Glow only: Saathi will not tap this for them (risky or a PIN field). */
        val noAct: Boolean = false,
        val tip: String? = null,
        val progress: Pair<Int, Int>? = null,
    )

    private companion object {
        val SPOKEN_YES = Regex("(?i)^\\W*(yes|yes please|yeah|yep|ok|okay|sure|please do|go ahead|haan|haan ji|han|ha|ji|हाँ|हां|हाँ जी|जी|जी हाँ|ठीक है|అవును|సరే|ఓకే|అలాగే)\\W*$")
        val LOST_WORDS = Regex("(?i)\\b(where am i|i'?m lost|i am lost|i got lost|confused|what (is|'s) this (page|screen)|which (page|screen) is this|how do i get back)\\b|" +
            "कहाँ हूँ|कहां हूं|खो गया|खो गई|समझ नहीं आ रहा|यह कौन सा पेज|ఎక్కడ ఉన్నాను|తప్పిపోయాను|అర్థం కావట్లేదు|ఇది ఏ పేజీ")
        val SPOKEN_NO = Regex("(?i)^\\W*(no|no thanks|not now|nahi|nahin|नहीं|नहीं जी|अभी नहीं|వద్దు|లేదు|ఇప్పుడు వద్దు)\\W*$")
        /** "my liked videos", "मेरी प्लेलिस्ट", "నా వీడియోలు": the person's own things inside an app, not a search. */
        val OWN_THINGS = Regex("(?i)\\b(my|mine)\\s+(own\\s+)?(liked|saved|downloaded|downloads|playlists?|library|history|watch later|uploads?|videos|account|profile|orders?|bookings?|trips?|rides?)\\b|" +
            "\\bi (liked|saved|watched|downloaded|ordered|booked)\\b|(मेरे|मेरी|मेरा)\\s+(लाइक|सेव|डाउनलोड|प्लेलिस्ट|वीडियो|ऑर्डर)|నా\\s+(లైక్|సేవ్|డౌన్‌?లోడ్|ప్లేలిస్ట్|వీడియో|ఆర్డర్)")
        /** "book a cab to THIS location", "send THIS photo": about what is on the screen right now. */
        // Whole words only: Devanagari/Telugu have no \b in Java regex, so "ये" matched inside "खोजिये", "इस" inside "इस्तेमाल".
        val DEICTIC = Regex("(?i)\\b(this|these|here)\\b|(?<![\\p{L}\\p{M}])(यह|ये|इस|इसे|इसको|यहाँ|ఈ|ఇది|ఇక్కడ|దీన్ని)(?![\\p{L}\\p{M}])")
        const val TAG = "Saathi"
        const val THROTTLE_MS = 200L
        const val SETTLE_MS = 450L
        const val START_MS = 900L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var goal: String? = null
    private var flow: Flow? = null
    private var lang = Lang.EN
    private var current: Target? = null
    private var lastSpokenKey: String? = null
    private var lastSig = 0
    private var warnedSig = 0
    private val history = mutableListOf<String>()
    private var job: Job? = null
    private var thinking = false
    private var pending = false
    private var lastStepIdx = -1
    private var scrolls = 0
    private var delayedGlow: Job? = null
    private var hideJob: Job? = null
    /** Something was tapped or a new screen opened: the planner must decide afresh (maybe it's done). */
    private var replan = false

    /** Packages this task lives in. Anything else (except transient system UI) pauses the task. */
    private val taskPkgs = mutableSetOf<String>()
    private var adoptPkg = false
    private var lastFrontPkg: String? = null
    /** Practice run ("Let me try"): Saathi names the step but waits; the glow comes only if they're stuck. */
    private var practice = false
    /** App-map route in progress (maps.AppMaps): known screens, deterministic steps; the planner only if unmapped. */
    private var mapRoute: com.saathi.app.maps.Route? = null
    private var mapSlots: Map<String, String> = emptyMap()
    private var mapStep = -1
    /** Repeating the same steps / too many steps → offer another way (policy.LoopGuard; one per task). */
    private var loop: com.saathi.app.policy.LoopGuard? = null
    /** Online form walk-through (forms.OnlineForm): one field at a time, in screen order. */
    private var form: List<com.saathi.app.forms.FillPlan>? = null
    private var formI = 0
    private var prevExternalPkg: String? = null
    /** Settings task: the person opens Settings themselves (trap #45: vivo switches Saathi off if Saathi opens it). */
    private var needSettings = false
    /** Learn mode: they open the app themselves from its icon (pkg, label); Saathi only points the way. */
    private var needApp: Pair<String, String>? = null
    /** Learning (from the Learn section, or "teach me / how do I / show me how"): no shortcuts, every step shown. */
    var learn = false
    /** Just arrived in Settings: it may reopen on an old sub-page; step back to the main page first (lost-context fix). */
    private var settingsFresh = false
    private var settingsBacks = 0
    private var paused = false
    /** The planner stood in for a scripted step: remember its pick, commit once the flow moves past it. */
    private var pendingLearn: Triple<String, String, String>? = null

    /** Last time the person touched or scrolled. */
    @Volatile private var lastMotion = 0L

    // ── Auto mode ("do it all for me"): Saathi taps each step itself, but stops and asks at risky ones. ──
    private var auto = false
    private var awaitingConfirm = false
    private var autoJob: Job? = null
    private var autoSteps = 0
    private var autoLastKey: String? = null
    private var autoSameKey = 0
    /** Our own taps/scrolls also produce events; don't mistake them for the person taking over. */
    @Volatile private var lastOwnAction = 0L

    // ── Loop control (field test: the planner re-asked every second and wandered into Help). ──
    private data class Plan(val label: String?, val role: String?, val say: String, val noAct: Boolean)
    private val planCache = HashMap<Int, Plan>()
    private val unsureCount = HashMap<Int, Int>()
    private var plansThisTask = 0
    private var wallFp = 0
    @Volatile private var lastTapAt = 0L
    private var lastActKey: String? = null
    private var lastActAt = 0L
    private var sameActCount = 0
    /** The model's memory for this task: one live conversation, told what happened after each step. */
    private var taskKey = ""
    private var lastActionNote: String? = null
    /** The task is set aside while Saathi answers something else; the loop waits until they continue. */
    private var setAside = false
    /** Arrived on a Settings page: the page's own control is the last step (slider / switch / choice list). */
    private data class Settle(val routeId: String, var kind: String, val label: String, var say: String,
        var start: String, var last: String, var changedAt: Long = 0L, var asked: Boolean = false)
    private var settle: Settle? = null
    /** Unscripted (planner) path: how close each planned screen was to the goal (goal words on it), and search-first. */
    private val planScores = mutableListOf<Int>()
    private var mapUnknownSince = 0L
    private var searchTried = false
    /** What they answered to Saathi's questions in this task ("play the playlist"): the planner sees it every time. */
    private val answers = mutableListOf<String>()
    /** The current map step's checked explanation ("The magnifying glass means search."): the grounded answer to a doubt. */
    private var stepWhy: String? = null

    // ── Watchdog: no progress for 2 minutes → offer help (once per task). ──
    private var lastProgress = 0L
    private var stuckOffered = false
    private var watchdog: Job? = null

    val active get() = goal != null

    // ───────────────────────── entry points ─────────────────────────

    /** A spoken or typed sentence. During a task, short commands steer the task. */
    /** From the Learn section: teach every step (no shortcuts). */
    fun learnTask(text: String) = start(text, autoMode = false, learnMode = true)

    fun handleUtterance(text: String) {
        val t = text.lowercase().trim()
        fun any(vararg w: String) = w.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
        val allRx = Regex("(?i)do (it )?all( of it)?( for me)?|do everything|you do everything|सब (आप )?कर दो|पूरा कर दो|सब कुछ कर दो|అన్నీ చేయి|మొత్తం చేయి|అన్నీ మీరే చేయండి")
        allRx.find(text)?.let { m ->
            val rest = text.removeRange(m.range).trim(' ', ',', ':', '.', '-')
            if (rest.length > 3) { start(rest, autoMode = true); return }
            if (active) { enableAuto(); return }
        }
        // While watching / listening: "pause", "next", "louder", "close this app" work any time, mid-task or not.
        if (mediaCommand(text)) return
        // "Where am I?" / "I'm lost": the app and page, what we were doing, and the ways back. Any app, any time.
        if (LOST_WORDS.containsMatchIn(text)) { whereAmI(); return }
        // A card is waiting for an answer ("Shall I open the Play Store?", "WhatsApp or Phone?"): a spoken reply answers
        // it (field: "yes" became a brand-new goal called "yes", so the question dead-ended).
        if (overlay.pickChoice(t)) return
        if (SPOKEN_YES.matches(t) && overlay.acceptPending()) return
        if (SPOKEN_NO.matches(t) && overlay.declinePending()) return
        // The coach asked them something: their reply continues the coaching conversation.
        if (coachWaiting && coachGoal != null) {
            if (Regex("(?i)^(stop|cancel|bas|बस|रुको|ఆపు)\\b").containsMatchIn(text.trim())) { endCoach(null); return }
            coachWaiting = false; coachTurn("Person: ${text.trim()}"); return
        }
        // Saathi asked them something ("Which contact?"): their reply refines the same task, it isn't a new one.
        if (active && current?.key?.startsWith("ask_") == true) {
            // The planner reads the flow's goal, not `goal`: keep the answer where it looks (field: "play playlist"
            // was dropped and the model started over with "Tap Search").
            answers += text.trim()
            goal = "$goal (${text.trim()})"
            com.saathi.app.DebugLog.i("ask", "answered: $text → goal=$goal")
            planCache.clear(); lastSig = 0; replan = true
            schedule(200, force = true)
            return
        }
        if (active && (paused || setAside) && Regex("(?i)^(continue|yes|go on|resume|जारी|हाँ|हां|కొనసాగించు|అవును)").containsMatchIn(t)) { resumeTask(); return }
        if (active || current != null) when {
            any("do it", "do it for me", "you do it", "कर दो", "आप करो", "तुम करो", "చేయి", "మీరే చేయండి") -> { doItForMe(); return }
            any("again", "repeat", "say again", "फिर से", "दोबारा", "మళ్ళీ") -> { repeat(); return }
            any("stop", "cancel", "bas", "बस", "बंद करो", "रुको", "ఆపు", "ఆపండి", "వద్దు") -> { stop(); return }
            any("done", "ho gaya", "हो गया", "అయింది") -> { onFinalDone(); return }
        }
        // A doubt in the middle of a task ("what is this button?", "why is it asking for my number?") is answered with
        // the task in mind, and the task waits; it is not a new goal (field: every mid-task question wiped the task).
        // A follow-up inside the task ("I want to go to JBS", "title as purple hat", "type jbs in the where to field")
        // continues it; it is not a new request (field 05:50 / 06:25: each one restarted the task or was rewritten).
        if (active && !paused && isFollowUp(text)) { followUp(text); return }
        // On-screen context with no task: in Notes / a chat / a form, "write …" / "type …" goes into the box on screen.
        if (!active && WRITE_HERE.containsMatchIn(text)) { scope.launch { if (!writeHere(text)) writeElsewhere(text) }; return }
        if (active && isAsideQuestion(text)) {
            com.saathi.app.DebugLog.i("aside", "mid-task question: \"$text\" (task: \"$goal\")")
            // About this step ("what does the magnifying glass mean?", "why this?"): the map's own checked
            // explanation, not the model (field: the small model explained real magnifying glasses).
            val why = stepWhy; val cur = current
            if (why != null && cur != null && aboutStep(text, cur)) {
                com.saathi.app.DebugLog.i("answer", "q=\"$text\" a=\"$why\" (step's own explanation)")
                setAside = true; autoJob?.cancel()
                overlay.showCard(why + "\n\n" + cur.text, Overlay.Mode.PAUSED, onContinue = { resumeTask() })
                speaker.say(why + " " + cur.text, lang)
                return
            }
            answerQuestion(text, aside = "I am in the middle of: \"$goal\". Saathi's current instruction: \"${current?.text ?: "none yet"}\".")
            return
        }
        start(text)
    }

    /**
     * The everyday controls while a video or song plays, in the person's words: pause / play / next / louder / softer /
     * close this app / go home. Media keys and the music volume need no permission and never open Settings.
     */
    private fun mediaCommand(text: String): Boolean {
        val g = text.lowercase().trim()
        val am = svc.getSystemService(android.media.AudioManager::class.java) ?: return false
        fun key(k: Int) = runCatching {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, k))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, k))
        }
        fun vol(dir: Int) = runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI) }
        val (said, act) = when {
            Regex("^(pause|pause (it|this|the (video|song|music))|stop the (video|song|music)|रोको|रोक दो|वीडियो रोको|गाना रोको|ఆపు|ఆపండి|పాజ్)\\W*$").matches(g) ->
                say("Paused. Say “play” to continue.", "रोक दिया। चलाने के लिए “play” बोलिए।", "ఆపాను. మళ్ళీ ప్లే చేయాలంటే “play” అనండి.") to { key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) }
            Regex("^(play|resume|continue (the )?(video|song|music)|play (it )?again|चलाओ|फिर से चलाओ|ప్లే చేయి|మళ్ళీ ప్లే)\\W*$").matches(g) && goal == null ->
                say("Playing.", "चल रहा है।", "ప్లే అవుతోంది.") to { key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }
            Regex("^(next|next (song|video|one)|skip( this)?|अगला|अगला गाना|తర్వాతిది|తర్వాతి పాట)\\W*$").matches(g) ->
                say("Next one.", "अगला।", "తర్వాతిది.") to { key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
            Regex("^(louder|volume up|increase (the )?volume|turn it up|आवाज़ बढ़ाओ|आवाज बढ़ाओ|సౌండ్ పెంచు|శబ్దం పెంచు)\\W*$").matches(g) ->
                say("A little louder.", "आवाज़ थोड़ी बढ़ा दी।", "కొంచెం సౌండ్ పెంచాను.") to { vol(android.media.AudioManager.ADJUST_RAISE); vol(android.media.AudioManager.ADJUST_RAISE) }
            Regex("^(softer|quieter|volume down|decrease (the )?volume|turn it down|आवाज़ कम करो|आवाज कम करो|సౌండ్ తగ్గించు|శబ్దం తగ్గించు)\\W*$").matches(g) ->
                say("A little softer.", "आवाज़ थोड़ी कम कर दी।", "కొంచెం సౌండ్ తగ్గించాను.") to { vol(android.media.AudioManager.ADJUST_LOWER); vol(android.media.AudioManager.ADJUST_LOWER) }
            Regex("^(close (this|the|it)( app)?|close (youtube|the video|the song)|exit( this)?( app)?|i'?m done|go (to )?home|(take me )?(to )?(the )?home ?screen|बंद करो|ऐप बंद करो|होम पर जाओ|మూసేయి|యాప్ మూసేయి|హోమ్‌?కి వెళ్ళు)\\W*$").matches(g) ->
                say("Done. You're on the home screen.", "हो गया। आप होम स्क्रीन पर हैं।", "అయింది. మీరు హోమ్ స్క్రీన్‌లో ఉన్నారు.") to {
                    stop(); svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
            else -> return false
        }
        lang = Prefs.lang(svc)
        com.saathi.app.DebugLog.i("media", "\"$text\"")
        act()
        speaker.say(said.pick(lang), lang)
        return true
    }

    /** "…, Play the playlist, or search for something else?" → ("Play the playlist", "Search for something else"). */
    private fun choicesIn(q: String): Pair<String, String>? {
        val last = q.trim().split(Regex("(?<=[.!?।])\\s+")).lastOrNull { it.endsWith("?") } ?: return null
        val m = Regex("(?i)^(?:.*?(?::|—|-)\\s*)?(.+?),?\\s+(?:or|या|లేదా)\\s+(.+?)\\?$").find(last) ?: return null
        fun tidy(x: String) = x.trim().removePrefix("do you want to ").removePrefix("would you like to ").replaceFirstChar { it.uppercase() }
        if (m.groupValues[1].count { it == ',' } >= 2) return null   // "Home, Shorts, Create, or You?": a list, not a choice
        var a = tidy(m.groupValues[1]); val b = tidy(m.groupValues[2])
        // "What would you like to do now, play the playlist" → keep what comes after the last comma.
        if (a.contains(",")) a = tidy(a.substringAfterLast(","))
        return if (a.length in 2..40 && b.length in 2..40) a to b else null
    }

    private val WRITE_HERE = Regex("(?i)^\\s*(write|type|note down)\\b|लिखो|लिख दो|टाइप करो|రాయి|రాసి|టైప్ చేయి")

    /**
     * "Write a birthday wish for my son" / "type buy milk" on a screen with a text box: literal words are typed as they
     * are; a description is composed by Gemma 4 (short, their language), shown, and typed only when they tap Do it.
     * false = no box here (or it's Saathi's own screen) → the normal path.
     */
    private suspend fun writeHere(text: String): Boolean {
        lang = Prefs.lang(svc)
        var waited = 0
        while (waited < 2500 && SaathiService.ownUiOpen) { delay(150); waited += 150 }
        val root = appRoot() ?: return false
        if (root.packageName?.toString() in listOf(svc.packageName, launcherPkg())) return false
        val screen = ScreenReader.read(root) ?: return false
        val inputs = screen.elements.filter { it.role == "input" && !it.password }
        if (inputs.isEmpty()) return false
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val biggest = inputs.maxByOrNull { it.bounds.height() * it.bounds.width() }
        var box = inputs.firstOrNull { it.node == focused } ?: biggest ?: return false
        val body = Regex("(?i)^\\s*(?:write|type|note down|put|add)\\s+(?:down\\s+)?(?:that\\s+|this\\s+|:\\s*)?(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: text.trim()
        // A description to compose ("a birthday wish for my son", "a note about tomorrow's doctor visit") vs the words themselves.
        val describe = Regex("(?i)^(a|an|some|something|my)\\b|\\b(wish|letter|message for|poem|note about|about|for my|reply)\\b").containsMatchIn(body)
        val words = if (describe && (LlmManager.isReady || run { LlmManager.loadAsync(svc); false })) {
            overlay.showCard(say("Writing it…", "लिख रहा हूँ…", "రాస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            com.saathi.app.llm.AiMeter.purpose = "compose"
            LlmManager.generate("Write what an elderly person asked for, in ${if (lang == Lang.EN) "simple English" else lang.label}. " +
                "At most 3 short sentences. Only the text itself, no quotes, no explanation.", body)
                ?.lines()?.filter { it.isNotBlank() }?.joinToString(" ")?.trim('"', ' ')?.take(400)
        } else body
        if (words.isNullOrBlank()) return false
        // A sentence or more goes in the body (the biggest box), not a one-line title.
        if (words.length > 40 && biggest != null) box = biggest
        com.saathi.app.DebugLog.i("write", "${screen.pkg} box=\"${box.title.take(30)}\" composed=$describe")
        // One step, not a task: no planner afterwards (field 06:58: it replaced the text with "type 'love ver'").
        stop()
        show(Target(box, say("I'll write: “$words”. Tap Do it and I'll type it here.", "मैं लिखूँगा: “$words”। 'आप कर दो' दबाइए, मैं यहाँ लिख दूँगा।",
            "నేను రాస్తాను: “$words”. 'మీరే చేయండి' నొక్కండి, ఇక్కడ టైప్ చేస్తాను.").pick(lang), "write_here", fill = words))
        return true
    }

    /** "Type …" with no box on screen (Notes on its list page): plan from here in THIS app (e.g. "tap + for a new note"),
     *  never go find another app (field 08:15: Pro started installing Google Keep). On the home screen: the normal path. */
    private fun writeElsewhere(text: String) {
        val here = runCatching { appRoot()?.packageName?.toString() }.getOrNull()
        if (here == null || here == launcherPkg() || here == svc.packageName) { start(text); return }
        com.saathi.app.DebugLog.i("write", "no box in $here: plan from here")
        begin(text, plannerTask(text, null), false)
    }

    /** Not a new task: names no other app and isn't a clear command of its own. */
    private fun isFollowUp(text: String): Boolean {
        if (IntentRouter.phrasedAsQuestion(text) || LOST_WORDS.containsMatchIn(text) || IntentRouter.isSos(text)) return false
        if (Regex("(?i)^\\s*(open|launch|start|stop|cancel|new|call|video call|remind|set an alarm|read|help me fill|go home|close)\\b").containsMatchIn(text)) return false
        val app = AppLauncher.findInGoal(svc, text)
        return app == null || app.pkg in taskPkgs || app.pkg == flow?.appPkg || app.pkg == mapRoute?.pkg
    }

    /** What they said goes into the task: text for the box on screen, a slot for the route, context for the planner. */
    private fun followUp(text: String) {
        lang = Prefs.lang(svc)
        val payload = (Regex("(?i)(?:type|write|enter|put|fill|title(?: is| as)?|name(?: is| as)?|search(?: for)?|call it|say)\\s+(.+?)(?:\\s+(?:in|into|on) (?:the )?[\\p{L} ]{1,20}?(?:field|box|bar|title))?\\s*$").find(text)
            ?: Regex("(?i)(?:go|going|take me|drop me|ride|cab|auto|destination is|to)\\s+(?:to\\s+)?(.+?)\\s*$").find(text))?.groupValues?.get(1)?.trim()
            ?.takeIf { it.length in 2..60 }
        com.saathi.app.DebugLog.i("followup", "\"$text\" → ${payload ?: "context"} (task: \"$goal\")")
        answers += text.trim()
        // The route's own slot, when it has one (Uber's destination, a search, a message).
        mapRoute?.let { r -> payload?.let { p -> r.slots.firstOrNull { it in setOf("place", "destination", "query", "text") }?.let { k ->
            mapSlots = mapSlots + (k to p) + (if (k == "place") mapOf("destination" to p) else emptyMap()) } } }
        scope.launch { followUpOnScreen(text, payload) }
    }

    private suspend fun followUpOnScreen(text: String, payload: String?) {
        // A box on screen to put it in: the focused one, else the one its words name ("where to", "title"), else the only one.
        val screen = readScreen()
        val inputs = screen?.elements?.filter { it.role == "input" && !it.password }.orEmpty()
        val focused = runCatching { appRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val box = inputs.firstOrNull { e -> focused != null && e.node == focused }
            ?: inputs.firstOrNull { e -> e.label.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }.any { text.lowercase().contains(it) } }
            ?: inputs.singleOrNull()
        if (payload != null && box != null) {
            show(Target(box, say("Type “$payload” here. Or tap Do it and I'll type it.", "यहाँ “$payload” लिखिए। या 'आप कर दो' दबाइए।",
                "ఇక్కడ “$payload” టైప్ చేయండి. లేదా 'మీరే చేయండి' నొక్కండి.").pick(lang), "follow_${box.label.take(20)}", fill = payload))
            return
        }
        overlay.showCard(say("Okay: “${text.take(40)}”.", "ठीक है: “${text.take(40)}”।", "సరే: “${text.take(40)}”.").pick(lang), Overlay.Mode.THINKING)
        planCache.clear(); lastSig = 0; replan = true; lastSpokenKey = null
        schedule(250, force = true)
    }

    /** Is the doubt about the step on screen? "this / why / what does it mean", or it names a word of the instruction. */
    private fun aboutStep(q: String, t: Target): Boolean {
        if (Regex("(?i)\\b(this|that|it|why|mean|means)\\b|यह|ये|क्यों|मतलब|ఇది|ఎందుకు|అర్థం").containsMatchIn(q)) return true
        val words = { x: String -> x.lowercase().split(Regex("[^\\p{L}\\p{M}]+")).filter { it.length >= 4 }.toSet() }
        return (words(q) intersect (words(t.text) + words(t.el?.label ?: ""))).isNotEmpty()
    }

    /** A question, not a new task: phrased as one, and it names no other app, skill or route. */
    private fun isAsideQuestion(text: String): Boolean {
        if (!IntentRouter.phrasedAsQuestion(text)) return false
        if (IntentRouter.wantsToLearn(text) && AppLauncher.findInGoal(svc, text) != null) return false
        if (IntentRouter.isExplain(text) || IntentRouter.cameraRead(text) != null || IntentRouter.isFormHelp(text)) return false
        if (Skills.match(text) != null || mapRouteFor(text) != null || IntentRouter.settingsTask(text) != null) return false
        val app = AppLauncher.findInGoal(svc, text)
        return app == null || app.pkg in taskPkgs
    }

    fun start(goalText: String, autoMode: Boolean = Prefs.expert(svc), learnMode: Boolean = false) {
        learn = learnMode || practice || IntentRouter.wantsToLearn(goalText)
        lang = Prefs.lang(svc)
        hideJob?.cancel()
        stopAuto()
        Log.i(TAG, "goal: $goalText (auto=$autoMode)")
        com.saathi.app.DebugLog.i("goal", "\"$goalText\" lang=$lang auto=$autoMode locked=${svc.isLocked()}")
        if (IntentRouter.isSos(goalText)) { sos(); return }
        if (navButtonHelp(goalText)) return
        // Teach-once first: "watch me …" used to sit ~20 checks deep, so almost any sentence was grabbed earlier.
        if (teachOnce(goalText)) return
        Routines.parse(goalText)?.let { (h, m, g) -> addRoutine(h, m, g); return }
        java.util.Calendar.getInstance().let { Reminders.parse(goalText, it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE)) }
            ?.let { (due, _, what) -> addReminder(due / 60, due % 60, what); return }
        // Train tickets / Tatkal: removed (too hard to do safely); say so plainly instead of wandering into IRCTC.
        if (Regex("(?i)tatkal|irctc|train ticket|railway ticket|तत्काल|ट्रेन टिकट|रेल टिकट|తత్కాల్|రైలు టికెట్").containsMatchIn(goalText)) {
            finish(say("I can't help with train tickets yet. Please ask family to book it with you.", "ट्रेन टिकट में मैं अभी मदद नहीं कर सकता। परिवार के साथ बुक कीजिए।",
                "రైలు టికెట్లలో ఇంకా సహాయం చేయలేను. కుటుంబంతో కలిసి బుక్ చేయండి.").pick(lang)); return }
        // "Where is my Aadhaar card?" / "send my Aadhaar to my son": their own pictures, read on the phone.
        DocFinder.ask(goalText)?.let { a -> docRequest(goalText, a); return }
        if (IntentRouter.isRecall(goalText)) { recall(goalText); return }
        IntentRouter.systemAction(goalText)?.let { action ->
            stop()
            svc.performGlobalAction(action)
            com.saathi.app.DebugLog.i("system", "global action $action for \"$goalText\"")
            // "Take a screenshot and send it to Akash on WhatsApp": the screenshot is only the first half (field: the
            // sending half was dropped). The new screenshot is the newest photo, so the rest is a normal photo send.
            if (action == android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) screenshotThen(goalText)?.let { rest ->
                com.saathi.app.DebugLog.i("system", "then: \"$rest\"")
                // That exact screenshot, straight into the messaging app's own "Send to…" screen; a gallery's
                // "newest photo" isn't the screenshot on every phone (field: Photos opened an old camera photo).
                scope.launch { delay(2200); screenshotShareFlow(rest)?.let { begin(rest, it, autoMode) } ?: start(rest, autoMode, learnMode) }
            }
            return
        }
        if (Coach.wants(goalText, null) && goalText.lowercase().trim() != coachHandedOff) { startCoach(goalText); return }
        if (IntentRouter.isScamCheck(goalText)) { scamCheck(); return }
        if (IntentRouter.isFamilyHelp(goalText)) { askFamily(); return }
        if (IntentRouter.isReadMessages(goalText)) { readMessages(); return }
        if (IntentRouter.isExplain(goalText)) { explain(); return }
        if (IntentRouter.isBriefing(goalText)) { briefing(); return }
        if (IntentRouter.isObjectHelp(goalText)) { begin(goalText, Skills.byId("learn_app")?.build(svc, SlotExtractor.from(goalText)), autoMode); return }
        IntentRouter.cameraRead(goalText)?.let { id -> begin(goalText, Skills.byId(id)?.build(svc, SlotExtractor.from(goalText)), autoMode); return }
        if (IntentRouter.isFormHelp(goalText)) { formHelp(); return }
        // General first, not app by app: a request about what's on the screen ("book a cab to THIS location" in a
        // WhatsApp chat) or about the person's own things in an app ("my liked videos") is planned from the live
        // screen by the model, starting where they are. A scripted route would launch its app and lose "this".
        // Something a family member taught me? That path wins, even over the app maps: it's known to work on this very
        // phone (field: a taught "open my youtube subscriptions" was replaced by the map route).
        Recipes.find(svc, goalText)?.let { r -> begin(goalText, Recipes.toFlow(r), autoMode); return }
        hereTask(goalText)?.let { begin(goalText, it, autoMode); return }
        ownThingsTask(goalText)?.let { begin(goalText, it, autoMode); return }
        mapRouteFor(goalText)?.let { r ->
            // The NPU checks every phrase-matched route (Gemma 3 1B, ~0.4 s): it names the app the request needs; if it
            // disagrees, Gemma 4 rewrites the request instead of trusting the phrase (field: "see the photo my grandson
            // sent" matched video call). A rewrite's own route isn't re-checked (no loop).
            if (verifiedGoal == goalText.lowercase().trim()) { verifiedGoal = null; dispatchMap(goalText, r, autoMode); return }
            scope.launch {
                if (npuAgrees(goalText, r)) dispatchMap(goalText, r, autoMode)
                else {
                    val c = canonical(goalText)
                    if (c != null && c.lowercase().trim() != goalText.lowercase().trim()) { verifiedGoal = c.lowercase().trim(); start(c, autoMode, learnMode = learn) }
                    else dispatchMap(goalText, r, autoMode)
                }
            }
            return
        }
        IntentRouter.settingsTask(goalText)?.let { begin(goalText, it, autoMode); return }
        // Saathi Pro: "how can I access my VPS through SSH from this phone" is a do-it-with-me task for a power user, not a
        // question or an old lesson (field 07:53 / 07:56: web results, then the camera). Pro picks the app and guides.
        // Only for power-user topics, or when Saathi's own phone help has nothing (field 09:07: "how to show battery
        // percentage" went to the Play Store for a "Battery Percentage" app instead of the phone's own setting).
        if (Prefs.proOn(svc) && PRO_HOWTO.containsMatchIn(goalText) &&
            (PRO_TOPIC.containsMatchIn(goalText) || IntentRouter.phoneHowTo(svc, goalText) == null)) {
            scope.launch { if (!proStart(goalText, howTo = true)) respond(goalText) }
            return
        }
        IntentRouter.phoneHowTo(svc, goalText)?.let { begin(goalText, it, autoMode); return }
        if (IntentRouter.isQuestion(svc, goalText)) { respond(goalText); return }
        rememberRequest(goalText)?.let { finish(it); return }
        IntentRouter.openOnly(svc, goalText)?.let { begin(goalText, it, autoMode); return }
        // An instant phone action named in their words ("make the volume full", "turn on the torch"): do it now, before
        // any model (field 08:26: volume went to the rewrite, then Pro tried to install an "app" for it).
        Skills.match(goalText)?.takeIf { it.id in IntentRouter.DIRECT }?.let { sk -> begin(goalText, sk.build(svc, SlotExtractor.from(goalText, Prefs.family(svc))), autoMode); return }
        if (LlmManager.isReady || com.saathi.app.llm.ModelLocator.fast(svc) != null) {
            // Let the model pick the helper (NPU ≈0.25 s); keywords only if it can't.
            overlay.showCard(say("Okay…", "ठीक है…", "సరే…").pick(lang), Overlay.Mode.THINKING)
            scope.launch {
                // 0) The bridge: the model picks from what Saathi actually knows how to do (the app-map routes), so a
                //    request in any words reaches the right route; phrases are only the fast path.
                //    The model rewrites their words (any language) as one plain command; a command that reaches a known
                //    route is run as if they'd said it. Small models rewrite well; they pick badly from numbered lists.
                //    Gemma 4 (GPU) only. Tried the NPU 1B for this (04:05): yes/no said yes to everything, the topic
                //    variant sent "an ugly ringtone" to the Phone call route. It keeps doing intent classification only.
                canonical(goalText)?.let { c -> start(c, autoMode, learnMode = learn); return@launch }
                //    Saathi Pro: a task nothing on the phone knows ("set up an SSH terminal") → the cloud brain names the
                //    app; Saathi installs it with them (Play Store route) or opens it, then plans each step there.
                if (proStart(goalText)) return@launch
                // 1) Understand the vague request. 2) Jump there with an intent. 3) Only then guide / answer.
                com.saathi.app.llm.AiMeter.purpose = "understand"
                val u = Understand.parse(goalText, svc)
                com.saathi.app.DebugLog.i("understand", "\"$goalText\" → $u")
                if (u != null && handleIntent(goalText, u, autoMode)) return@launch
                when (val r = IntentRouter.smartRoute(svc, goalText)) {
                    is IntentRouter.Route.Question -> respond(goalText)
                    is IntentRouter.Route.Skill -> begin(goalText, r.flow, autoMode)
                }
            }
            return
        }
        begin(goalText, IntentRouter.route(svc, goalText), autoMode)
    }

    private val COMMAND_EXAMPLES = listOf(
        "video call my son on whatsapp", "call my son", "send a whatsapp message to my son saying I reached home",
        "see the photo my son sent on whatsapp", "send a photo to my son on whatsapp", "search for old telugu songs on youtube",
        "show my liked videos on youtube", "show my youtube subscriptions", "make the text bigger", "change my ringtone",
        "turn on bluetooth", "make the screen brighter", "change the wallpaper", "install hotstar", "watch anupama on hotstar",
        "where is my aadhaar card", "send my aadhaar card to my son", "take a screenshot and send it to my son on whatsapp",
        "set an alarm for 6 am", "take me to the nearest hospital", "book an uber to the airport", "open the camera",
        "how do i use spotify", "where am i")

    /** Their words → one plain English command that a known route handles, or null (a question, or nothing fits). */
    private suspend fun canonical(g: String): String? {
        val sys = "You turn what an elderly person says (English, Hindi or Telugu) into ONE short English phone command. " +
            "Write it in the same style as these: " + COMMAND_EXAMPLES.joinToString("; ") + ". Keep names and search words " +
            "they said. Reply with the command only. If they ask a question to be answered (weather, facts, health, money), reply QUESTION."
        // Gemma 4 only: the 1B model copied examples ("a nicer ringtone" → "turn on bluetooth"); a wrong confident
        // action is worse than the old path. Load it if needed (a few seconds), else give up quietly.
        if (!LlmManager.isReady) {
            LlmManager.loadAsync(svc)
            var waited = 0
            overlay.showCard(say("Let me think…", "सोच रहा हूँ…", "ఆలోచిస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            while (!LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed && LlmManager.state.value !is LlmManager.State.NoModel && waited < 12000) { delay(200); waited += 200 }
            if (!LlmManager.isReady) { com.saathi.app.DebugLog.i("route", "model: not ready, old path"); return null }
        }
        com.saathi.app.llm.AiMeter.purpose = "rewrite"
        val out = runCatching { LlmManager.generate(sys, g) }.getOrNull()?.lines()
            ?.map { it.trim().trim('"', '.', '\'', '*', '`', ' ', '-') }
            ?.lastOrNull { it.isNotBlank() && !it.endsWith(":") && !Regex("(?i)^(okay|sure|here|command)\\b").containsMatchIn(it) }?.lowercase()
        // It must keep at least one of their words (Latin script): "title as purple had" was rewritten to "set wallpaper…".
        val theirs = g.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()
        val keeps = theirs.isEmpty() || out?.lowercase()?.split(Regex("[^a-z]+"))?.any { w -> w.length >= 4 && theirs.any { t -> t.take(4) == w.take(4) } } == true
        val c = out?.takeIf { it.length in 4..120 && !it.contains("question") && !it.equals(g.trim(), true) && keeps }
        val ok = c != null && (mapRouteFor(c) != null || DocFinder.ask(c) != null || IntentRouter.settingsTask(c) != null ||
            LOST_WORDS.containsMatchIn(c) || Skills.match(c)?.id in IntentRouter.DIRECT)
        com.saathi.app.DebugLog.i("route", "model: \"$g\" → \"${out ?: "-"}\" ${if (ok) "(known route)" else "(not used)"}")
        return if (ok) c else null
    }

    private var verifiedGoal: String? = null

    private fun dispatchMap(goalText: String, r: com.saathi.app.maps.Route, autoMode: Boolean) {
        // "Video call my son": their choice first (WhatsApp or the phone's own), unless they said WhatsApp.
        if (r.id == "wa_video_call" && !Regex("(?i)whats ?app|व्हाट्स|వాట్స").containsMatchIn(goalText)) { videoCall(goalText, null, autoMode); return }
        beginMap(goalText, r, autoMode)
    }

    private val VERIFY_APPS = listOf("youtube", "whatsapp", "settings", "photos", "phone", "messages", "maps", "uber", "spotify",
        "playstore", "chrome", "camera", "clock", "instagram", "docs", "hotstar")

    /** NPU check of a phrase route: true = agrees (or can't tell); false = it clearly names a DIFFERENT known app. */
    private suspend fun npuAgrees(g: String, r: com.saathi.app.maps.Route): Boolean {
        val mapName = (com.saathi.app.maps.AppMaps.mapOf(r)?.name ?: "").lowercase().replace(" ", "").replace("google", "").replace("jio", "")
        com.saathi.app.llm.AiMeter.purpose = "verify route"
        val out = runCatching { com.saathi.app.llm.FastBrain.generate(svc,
            "Which phone app does this request need? Answer with ONE word from this list: ${VERIFY_APPS.joinToString(", ")}, none.", g) }
            .getOrNull()?.lowercase()?.replace(" ", "") ?: return true
        val said = VERIFY_APPS.firstOrNull { out.contains(it) } ?: return true
        val agree = mapName.contains(said) || said.contains(mapName) || (said == "phone" && r.pkg.contains("dialer|contacts".toRegex()))
        com.saathi.app.DebugLog.i("verify", "npu=$said route=${r.id} ${if (agree) "agrees" else "DISAGREES → Gemma 4 rewrite"}")
        return agree
    }

    /** The 3 likeliest known routes for [g]: same app (+5) and shared words with the route's description. */
    private fun shortlist(g: String, n: Int = 3): List<Pair<com.saathi.app.maps.Route, String>> {
        val words = g.lowercase().split(Regex("[^\\p{L}\\p{M}]+")).filter { it.length >= 3 }.toSet() - setOf("the", "and", "for", "want", "please", "can", "you", "how", "what")
        val app = AppLauncher.findInGoal(svc, g)?.pkg
        return com.saathi.app.maps.AppMaps.all.flatMap { m -> m.routes.map { r -> m to r } }
            .filter { (_, r) -> runCatching { AppLauncher.isInstalled(svc, r.pkg) }.getOrDefault(false) }
            .map { (m, r) ->
                val d = describeRoute(m, r)
                Triple(r, d, (if (app != null && (r.pkg == app || app in m.alsoPkgs)) 5 else 0) + words.count { w -> d.lowercase().contains(w) })
            }.filter { it.third > 0 }.sortedByDescending { it.third }.take(n).map { it.first to it.second }
    }

    /**
     * NLU on the NPU: yes / no, one known route at a time (small models answer yes/no well; they pick badly from lists).
     * Null = no model, nothing likely, or every answer was no → Gemma 4's rewrite, then the old path.
     */
    private suspend fun npuRoute(g: String): com.saathi.app.maps.Route? {
        // The 1B answered "yes" to everything (field 04:05: "make the writing larger" → Photos brightness), so it no
        // longer judges. It only pulls out the TOPIC in a few words; Saathi's own rules map the topic to a route.
        com.saathi.app.llm.AiMeter.purpose = "topic"
        val topic = runCatching { com.saathi.app.llm.FastBrain.generate(svc,
            "Say in 2 to 4 plain English words what the person wants to change, see or do on their phone. Reply with the words only.",
            g) }.getOrNull()?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*', ' ')?.lowercase()?.take(40)
        if (topic.isNullOrBlank()) return null
        val candidates = listOf(topic, "change my $topic", "show my $topic", "open $topic", "see $topic")
        val r = candidates.firstNotNullOfOrNull { c -> mapRouteFor(c) }
        com.saathi.app.DebugLog.i("route", "npu topic: \"$topic\" → ${r?.id ?: "none"}")
        return r
    }

    private suspend fun npuYesNo(g: String): com.saathi.app.maps.Route? {
        val cands = shortlist(g)
        if (cands.isEmpty()) return null
        val sys = "You check if a phone task matches what an elderly person asked for. Answer only yes or no."
        for ((r, d) in cands) {
            com.saathi.app.llm.AiMeter.purpose = "route yes/no"
            val out = runCatching { com.saathi.app.llm.FastBrain.generate(svc, sys,
                "Person asked: \"$g\"\nTask: $d\nDoes this task do what the person asked? yes or no:") }.getOrNull() ?: return null
            val yes = Regex("(?i)^\\W*(yes|haan|हाँ|అవును)\\b").containsMatchIn(out.trim())
            com.saathi.app.DebugLog.i("route", "npu: ${r.id}? ${out.trim().take(12)}")
            if (yes) return r
        }
        return null
    }

    // ───────── Saathi Pro (Pro build + switched on + a key): the cloud brain for power-user tasks ─────────

    /** What a finger can hit: nothing whose middle is under the navigation bar (field 08:05: the cloud brain chose Play
     *  Store's Search tab drawn under the nav buttons; tapping it pressed Home). Ids are kept, so lookups still work. */
    private fun planScreen(s: Screen): Screen {
        val usable = (android.content.res.Resources.getSystem().displayMetrics.heightPixels * 0.955f).toInt()
        return s.copy(elements = s.elements.filter { it.bounds.centerY() < usable })
    }

    private fun proCfg(model: String = Prefs.proModels(svc).firstOrNull() ?: "", timeoutMs: Int = 15000) =
        com.saathi.app.llm.ProConfig(Prefs.proUrl(svc), Prefs.proKey(svc), model, timeoutMs)

    /** Each listed model in turn (biggest first); the first usable answer wins. Big ones get 12 s, the last one 20 s. */
    private suspend fun <T> proTry(what: String, call: suspend (com.saathi.app.llm.ProConfig) -> T?): T? {
        val models = Prefs.proModels(svc)
        for ((i, m) in models.withIndex()) {
            val t0 = SystemClock.elapsedRealtime()
            val r = runCatching { call(proCfg(m, if (i == models.lastIndex) 20000 else 12000)) }.getOrNull()
            com.saathi.app.llm.AiMeter.record("CLOUD", m.substringAfter('/').substringBefore(':').take(13), if (r != null) what else "$what ✗", SystemClock.elapsedRealtime() - t0)
            if (r != null) return r
        }
        return null
    }
    /** The goal Pro is installing an app for: continued in that app once it's installed. */
    private var proPending: String? = null

    /** One planner step from the cloud brain, in the on-device planner's format; null = not Pro / money app / no answer. */
    private suspend fun proDecide(g: String, screen: Screen): Planner.Decision? {
        if (!Prefs.proOn(svc) || com.saathi.app.service.CallGuard.isSensitive(screen.pkg)) return null
        val d = proTry("plan step") { cfg -> com.saathi.app.llm.ProBrain.plan(cfg, g, history.toList(), answers.toList(),
            AppLauncher.labelOf(svc, screen.pkg), screen.forPrompt(60), lang.name)?.let { Planner.parse(it, screen, lang) } }?.copy(fromLlm = true)
        com.saathi.app.DebugLog.i("pro", "plan ${if (d != null) "ok" else "none"} in ${screen.pkg}")
        return d
    }

    /** A task no route, skill or phrase knows: the cloud brain names the Play Store app for it; install or open it. */
    private val PRO_TOPIC = Regex("(?i)\\b(ssh|terminal|vps|server|linux|code|coding|program|website|domain|edit (a |my )?video|video edit|workspace|spreadsheet|excel|pdf|vpn)\\b")

    // ── "Where is the home button?": the phone's own buttons (3-button bar: ≡ recents · ○ home · ‹ back) ──
    private val NAV_BTN = Regex("(?i)\\b(where|which|show me|find|what is)\\b.*\\b(home|back|recent|recents|overview)\\s*(button|key)|" +
        "(home|back|recent|होम|बैक|హోమ్|బ్యాక్)\\s*(button|key|बटन|బటన్)?.*(kahan|kahaan|कहाँ|कहां|कहा|ఎక్కడ)")
    private var navGlowAt = 0L

    private fun navButtonHelp(g: String): Boolean {
        if (!NAV_BTN.containsMatchIn(g)) return false
        val t = g.lowercase()
        val which = when {
            Regex("back|बैक|పీఛే|పీచే|వెనక|బ్యాక్").containsMatchIn(t) -> 2
            Regex("recent|overview").containsMatchIn(t) -> 0
            else -> 1
        }
        val b = svc.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val id = svc.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = if (id > 0) svc.resources.getDimensionPixelSize(id) else (48 * svc.resources.displayMetrics.density).toInt()
        val cx = (b.width() * floatArrayOf(0.21f, 0.5f, 0.79f)[which]).toInt()
        val r = android.graphics.Rect(cx - navH * 3 / 4, b.height() - navH, cx + navH * 3 / 4, b.height())
        val text = when (which) {
            0 -> say("This is the recent apps button, the three lines at the bottom left. Tap it to see the apps you opened.",
                "यह हाल के ऐप्स वाला बटन है, नीचे बाईं ओर तीन लाइनें। इसे दबाकर खुले ऐप्स देखिए।",
                "ఇది ఇటీవలి యాప్‌ల బటన్, కింద ఎడమ వైపు మూడు గీతలు. దీన్ని నొక్కితే తెరిచిన యాప్‌లు కనిపిస్తాయి.")
            2 -> say("This is the back button, the arrow at the bottom right. Tap it to go one step back.",
                "यह वापस जाने वाला बटन है, नीचे दाईं ओर तीर। एक कदम पीछे जाने के लिए इसे दबाइए।",
                "ఇది వెనక్కి వెళ్ళే బటన్, కింద కుడి వైపు బాణం. ఒక అడుగు వెనక్కి వెళ్ళడానికి దీన్ని నొక్కండి.")
            else -> say("This is the home button, the circle at the bottom middle. Tap it any time to come back to the main screen.",
                "यह होम बटन है, नीचे बीच में गोला। मुख्य स्क्रीन पर लौटने के लिए इसे कभी भी दबाइए।",
                "ఇది హోమ్ బటన్, కింద మధ్యలో ఉన్న గుండ్రటి బటన్. ప్రధాన స్క్రీన్‌కి రావడానికి ఎప్పుడైనా దీన్ని నొక్కండి.")
        }.pick(lang)
        com.saathi.app.DebugLog.i("nav", "button=$which rect=$r")
        finish(text)
        overlay.highlight(r, false)
        val at = SystemClock.uptimeMillis().also { navGlowAt = it }
        scope.launch { delay(12_000); if (navGlowAt == at && goal == null) overlay.highlight(null, false) }
        return true
    }

    private val PRO_HOWTO = Regex("(?i)\\bhow (can|do|should|would) i\\b|\\bhow to\\b|\\bhelp me (set ?up|install|configure|connect|edit|make|create)|" +
        "\\b(set ?up|configure|connect to|ssh|terminal|vps|server|edit (a |my )?video|code|coding|workspace)\\b")

    private suspend fun proStart(g: String, howTo: Boolean = false): Boolean {
        if (!Prefs.proOn(svc) || (!howTo && IntentRouter.phrasedAsQuestion(g))) return false
        if (WRITE_HERE.containsMatchIn(g)) return false   // typing is about the app they're in, never a new app
        overlay.showCard(say("Finding the right app…", "सही ऐप ढूँढ रहा हूँ…", "సరైన యాప్ వెతుకుతున్నాను…").pick(lang), Overlay.Mode.THINKING)
        val name = proTry("pick app") { cfg -> com.saathi.app.llm.ProBrain.ask(cfg,
            "You recommend Android apps. Reply with ONLY the exact Google Play Store name of the single best app for the task, " +
                "nothing else: no sentence, no punctuation, no explanation. Examples: JuiceSSH, Termux, CapCut, Google Docs, Snapseed.",
            "Task: $g")
            ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.trim('"', '.', '*', ' ')?.take(40)?.ifBlank { null } }
        com.saathi.app.DebugLog.i("pro", "app for \"$g\": ${name ?: "none"}")
        // A real app name only (field 08:26: "I'm uncertain - volume control is a built-in…" was sent to the Play Store).
        if (name.isNullOrBlank() || name.split(Regex("\\s+")).size > 5 ||
            Regex("(?i)uncertain|not sure|built.?in|no app|none|n/a|sorry|cannot|can't|don't|settings app|i'm|i am").containsMatchIn(name)) return false
        val app = AppLauncher.findInGoal(svc, name) ?: AppLauncher.installed(svc).firstOrNull { it.label.equals(name, true) }
        if (app != null) { begin(g, plannerTask(g, app.pkg), false); return true }
        proPending = g
        start("install $name", false)
        return true
    }

    /** A route in plain words for the model: "WhatsApp — see photo: Here it is, big…". */
    private fun describeRoute(m: com.saathi.app.maps.AppMap, r: com.saathi.app.maps.Route): String {
        val what = r.id.substringAfter('_').replace('_', ' ')
        val clean = { s: String -> s.replace(Regex("\\{[a-z]+\\}"), "someone").replace(Regex("\\[[^]]*\\]"), "") }
        // What it's about, how it goes (its steps' words) and how it ends: enough for a yes / no.
        val steps = r.steps.takeLast(2).joinToString(" ") { clean(it.say[Lang.EN] ?: "") }.take(110)
        return "in ${m.name}, $what. Steps: $steps Ends: ${clean(r.doneSay[Lang.EN] ?: "").take(70)}"
    }

    /**
     * NLU over the route catalog: shortlist the routes that share the app or words with the request (the NPU model
     * has a short context), then the model picks one number or 0. Null = none / no model → the old path.
     */
    private suspend fun modelRoute(g: String): com.saathi.app.maps.Route? {
        val words = g.lowercase().split(Regex("[^\\p{L}\\p{M}]+")).filter { it.length >= 3 }.toSet()
        val app = AppLauncher.findInGoal(svc, g)?.pkg
        val scored = com.saathi.app.maps.AppMaps.all.flatMap { m -> m.routes.map { r -> m to r } }
            .filter { (_, r) -> runCatching { AppLauncher.isInstalled(svc, r.pkg) }.getOrDefault(false) }
            .map { (m, r) ->
                val d = describeRoute(m, r)
                val appHit = app != null && (r.pkg == app || app in m.alsoPkgs)
                Triple(r, d, (if (appHit) 5 else 0) + words.count { w -> d.lowercase().contains(w) })
            }.filter { it.third > 0 }.sortedByDescending { it.third }.take(8)
        if (scored.isEmpty()) return null
        val list = scored.mapIndexed { i, t -> "${i + 1}. ${t.second}" }.joinToString("\n")
        val sys = "You match an elderly person's request to ONE task that a phone helper knows how to guide. " +
            "Reply with only the task number. Reply 0 if no task clearly fits, or if they are asking a question to be answered."
        val out = runCatching { com.saathi.app.llm.FastBrain.generate(svc, sys, "Request: \"$g\"\nTasks:\n$list\nTask number:") }.getOrNull()
        val n = out?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val pick = n?.takeIf { it in 1..scored.size }?.let { scored[it - 1].first }
        com.saathi.app.DebugLog.i("route", "model picked ${pick?.id ?: "none"} (answer=\"${out?.take(20)}\", from ${scored.map { it.first.id }})")
        return pick
    }

    /** Returns true if the intent was handled (deep link / answer / skill); false → fall back to routing + agent. */
    private suspend fun handleIntent(goalText: String, u: Understand.Intent2, autoMode: Boolean): Boolean {
        // The model sometimes keeps the verb ("play hanuman chalisa"): search for the thing itself.
        val q = (u.query ?: goalText).replace(Regex("(?i)^\\s*(please\\s+)?(play|watch|put on|search( for)?|find|show me|listen to|open)\\s+"), "").ifBlank { u.query ?: goalText }
        fun skill(id: String, g: String = goalText) = Skills.byId(id)?.build(svc, SlotExtractor.from(g, Prefs.family(svc)))
        // A precise, reliable skill (torch, font, storage, selfie…) beats the model's broad category.
        Skills.match(goalText)?.takeIf { it.id in IntentRouter.DIRECT && (u.intent !in setOf("weather", "lookup", "question", "watch", "music") ||
            (u.intent == "question" && !IntentRouter.phrasedAsQuestion(goalText))) }?.let {
            begin(goalText, it.build(svc, SlotExtractor.from(goalText, Prefs.family(svc))), autoMode); return true
        }
        if ((Coach.wants(goalText, u.intent) || (u.intent == "watch" && u.device == "tv")) && goalText.lowercase().trim() != coachHandedOff) { startCoach(goalText); return true }
        // The model named an app ("ఇంస్టాగ్రామ్ ఎలా వాడాలి" → APP=Instagram): guide inside it, never a text answer.
        if (u.intent in setOf("question", "other", "open_app")) u.app?.let { AppLauncher.findInGoal(svc, "open ${it}") }?.let { app ->
            com.saathi.app.DebugLog.i("route", "model named ${app.label}: guide in the app")
            begin(goalText, Skills.byId("learn_app")?.build(svc, SlotExtractor.from("how do I use ${app.label}")), autoMode); return true
        }
        when (u.intent) {
            "question" -> { respond(goalText); return true }
            "weather", "lookup" -> { lookUp(goalText, if (u.intent == "weather" && !q.contains("weather", true)) "$q weather" else q); return true }
            "watch", "music" -> {
                // "My liked videos", "my playlist": inside the app, not a search for those words.
                if (OWN_THINGS.containsMatchIn(goalText)) (AppLauncher.findInGoal(svc, goalText)?.pkg
                    ?: "com.google.android.youtube".takeIf { AppLauncher.isInstalled(svc, it) })?.let { pkg ->
                    begin(goalText, plannerTask(goalText, pkg), autoMode); return true
                }
                // "Turn the TV volume up" / "TV channel 5": the TV-remote skill, not watching something.
                if (Regex("(?i)\\b(volume|channel|remote|mute|turn (the )?tv (on|off))\\b|आवाज़|चैनल|వాల్యూమ్|ఛానెల్").containsMatchIn(goalText))
                    Skills.byId("tv")?.let { begin(goalText, skill("tv"), autoMode); return true }
                if (u.device == "tv") { watchOnTv("$q on tv ${u.app ?: ""}"); return true }
                // A named streaming app (Hotstar, Prime, Netflix, Zee5, SonyLIV…) or any other named app wins over the YouTube
                // default (field test: "Watch my serial on Hotstar" → the model missed Hotstar → YouTube).
                if (Regex("(?i)hot ?star|prime video|amazon prime|netflix|zee ?5|sony ?liv|jio ?cinema|\\baha\\b|sun ?nxt|mx player|हॉटस्टार|नेटफ्लिक्स|హాట్‌?స్టార్|నెట్‌?ఫ్లిక్స్").containsMatchIn(goalText) ||
                    u.app?.let { Regex("(?i)hotstar|prime|netflix|zee|sony|jio|aha|sun").containsMatchIn(it) } == true) {
                    begin(goalText, skill("ott"), autoMode); return true
                }
                AppLauncher.findInGoal(svc, goalText)?.takeIf { it.pkg != "com.google.android.youtube" }?.let { app ->
                    begin(goalText, Skills.byId("learn_app")?.build(svc, SlotExtractor.from("how do I use ${app.label} to $q")), autoMode); return true
                }
                // YouTube: the app map (tested screen by screen: search → type → the real first result, ads skipped). The
                // old deep link's "pick" matched the search bar itself and looped (field, 03:43).
                if (u.app?.contains("netflix", true) != true) com.saathi.app.maps.AppMaps.routeById("yt_search")
                    ?.takeIf { AppLauncher.isInstalled(svc, it.pkg) }?.let { r -> beginMap("search for $q on youtube", r, autoMode); return true }
                // Learning: every step (open, search, type, pick), no jumping straight to the results.
                if (learn && u.app?.contains("netflix", true) != true) { begin(goalText, skill("youtube", "play $q on youtube"), autoMode); return true }
                val netflix = u.app?.contains("netflix", true) == true
                val link = (if (netflix) Understand.netflixSearch(svc, q) else null) ?: Understand.youtubeSearch(svc, q) ?: return false
                val pkg = link.`package`
                val key = q.split(" ").maxByOrNull { it.length } ?: q
                begin(goalText, Flow("watch_direct", { link },
                    // A result title is longer than the query (the search box itself just shows the query).
                    listOf(Step("pick", listOf(Regex(Regex.escape(key) + ".{12,}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))), role = "button", say = say(
                        "Here are the results for “$q”. Tap the one you want — the picture shows what it is.",
                        "“$q” के नतीजे ये रहे। जो देखना है उसे दबाइए।", "“$q” ఫలితాలు ఇవి. చూడాలనుకున్నది నొక్కండి."))),
                    { sc -> sc.elements.any { Regex("^(Pause|Play) video|^Minimi[sz]e|Enter fullscreen|^Pause$", RegexOption.IGNORE_CASE).containsMatchIn(it.label) } },
                    say("Enjoy!", "आनंद लीजिए!", "ఆనందించండి!"),
                    say("Finding “$q”.", "“$q” ढूँढ रहा हूँ।", "“$q” వెతుకుతున్నాను."), llmGoal = "play $q", appPkg = pkg), autoMode)
                return true
            }
            "call" -> { begin(goalText, skill("call"), autoMode); return true }
            "video_call" -> { videoCall(goalText, u.person, autoMode); return true }
            "message" -> { begin(goalText, skill("wa_message"), autoMode); return true }
            "photo" -> { begin(goalText, skill("wa_photo"), autoMode); return true }
            "alarm" -> { begin(goalText, skill("alarm"), autoMode); return true }
            "directions" -> { begin(goalText, skill("maps", "take me to $q"), autoMode); return true }
            "setting" -> { begin(goalText, IntentRouter.settingsSearch(goalText) ?: IntentRouter.settingsFlowPublic(q), autoMode); return true }
            "tv" -> { begin(goalText, skill("tv"), autoMode); return true }
        }
        return false
    }

    /** Live facts: open the search results, then READ them and say the answer (grounded in what's on screen). */
    private fun lookUp(goalText: String, q: String) {
        stop()
        runCatching { svc.startActivity(Understand.webSearch(svc, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        overlay.setAura(true)
        overlay.showCard(say("Looking it up…", "देख रहा हूँ…", "చూస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
        scope.launch {
            var text = ""
            for (i in 0 until 8) { // wait for results to render (up to ~6 s)
                delay(800)
                val sc = readScreen() ?: continue
                if (sc.pkg != svc.packageName && sc.allText.length > 200) { text = sc.allText; if (i >= 2) break }
            }
            val a = if (text.isNotBlank() && LlmManager.isReady) LlmManager.generate(
                "Answer the elderly person's question in 1 or 2 short, warm sentences, using ONLY the search results given. " +
                    "If the results don't answer it, say you couldn't find it. " +
                    when (lang) { Lang.EN -> "Answer in English."; Lang.HI -> "Answer in Hindi (Devanagari)."; Lang.TE -> "Answer in Telugu script." },
                "Question: $goalText\nSearch results:\n${text.take(3000)}")?.takeIf { it.isNotBlank() && it.length < 400 && !com.saathi.app.llm.Templates.garbled(it) }
            else null
            overlay.setAura(false)
            if (text.isBlank()) { answerQuestion(goalText); return@launch } // no results (offline): a careful short answer
            // Rules first (the answer card itself), then the model's summary; either must be supported by the page.
            val page = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val candidate = com.saathi.app.policy.Grounded.extract(goalText, page)?.text ?: a
            val checked = candidate?.let { com.saathi.app.policy.AnswerCheck.verify(goalText, it, text, lang) }
            com.saathi.app.DebugLog.i("ground", "candidate=${candidate != null} ok=${checked?.ok} ${checked?.reasons}")
            val t = candidate?.takeIf { checked?.ok == true }
                ?: say("Here are the results. I've opened them for you.", "नतीजे खोल दिए हैं।", "ఫలితాలు తెరిచాను.").pick(lang)
            com.saathi.app.DebugLog.i("lookup", "q=\"$q\" a=\"${t.take(200)}\"")
            Conversation.remember(goalText, t)
            current = Target(null, t, "lookup")
            overlay.showCard(t, Overlay.Mode.DONE)
            speaker.say(t, lang)
            hideJob?.cancel(); hideJob = scope.launch { delay(25_000); if (goal == null) overlay.hideCard() }
        }
    }

    private fun begin(goalText: String, f: Flow?, autoMode: Boolean) {
        // Another app is about to be in front: keep Saathi light so the OS doesn't clean it up. The NPU brain stays;
        // the GPU brain reloads on demand if a later step needs the planner.

        // Instant skills (torch, volume): just do it and say so.
        f?.action?.let { act ->
            val done = act(svc).pick(lang)
            Memory.completed(f.id)
            if (f.quiet) { stop(); return } // the screen it opened speaks for itself
            finish(done)
            return
        }

        // No app and no skill, asked from the home screen: nothing to guide on. Don't wander: ask/look it up (respond).
        if (f == null) {
            val here = svc.rootInActiveWindow?.packageName?.toString()
            if (here == null || here == launcherPkg() || here == svc.packageName) { respond(goalText); return }
        }

        goal = goalText
        loop = com.saathi.app.policy.LoopGuard()
        flow = f
        history.clear(); current = null; lastSpokenKey = null; lastSig = 0; warnedSig = 0; lastStepIdx = -1; scrolls = 0
        taskPkgs.clear(); paused = false; pendingLearn = null; adoptPkg = true
        auto = autoMode; autoSteps = 0; autoLastKey = null; autoSameKey = 0; setAside = false; answers.clear(); planScores.clear(); searchTried = false; mapUnknownSince = 0L
        planCache.clear(); unsureCount.clear(); plansThisTask = 0; wallFp = 0; lastActKey = null; sameActCount = 0
        LlmManager.endChat(); taskKey = "task_${SystemClock.uptimeMillis()}"; lastActionNote = null
        lastProgress = SystemClock.uptimeMillis(); stuckOffered = false
        startWatchdog()

        var hello = f?.start?.pick(lang) ?: say(
            "Okay! Let's do it together. Watch for the glow.",
            "ठीक है! साथ में करते हैं। चमक को देखिए।",
            "సరే! కలిసి చేద్దాం. మెరుపును చూడండి.").pick(lang)
        f?.launch?.let { make ->
            val intent = runCatching { make(svc) }.getOrNull()
            // Already in Settings (they opened it themselves)? Guide from here; don't start Settings again (trap #45).
            val inSettingsAlready = intent != null && resolvePkg(intent) == "com.android.settings" &&
                runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull() == "com.android.settings"
            val toSettings = intent != null && resolvePkg(intent) == "com.android.settings"
            if (toSettings) { settingsFresh = true; settingsBacks = 0 }
            // Settings: never opened by Saathi. Go Home and teach them to open it (they learn where it lives, too).
            val askToOpen = toSettings && !inSettingsAlready
            val pkgHere = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            val targetPkg = intent?.let { resolvePkg(it) }
            val learnOpen = learn && !toSettings && targetPkg != null && targetPkg != pkgHere && targetPkg != svc.packageName &&
                intent.action in setOf(null, Intent.ACTION_MAIN) // a plain app launch (not a dialer/deep link we built)
            if (learnOpen) {
                val label = AppLauncher.labelOf(svc, targetPkg!!)
                needApp = targetPkg to label
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                hello = say("First, let's find $label. Look for its icon.", "पहले $label ढूँढते हैं। उसका आइकन देखिए।", "ముందు $label ని వెతుకుదాం. దాని ఐకాన్ చూడండి.").pick(lang)
                com.saathi.app.DebugLog.i("begin", "learn mode: they open $label themselves")
            }
            if (askToOpen) {
                needSettings = true
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                hello = say("First, open Settings: tap the Settings icon, the grey gear.", "पहले Settings खोलिए: Settings का आइकन, ग्रे गियर, दबाइए।",
                    "ముందు Settings తెరవండి: Settings ఐకాన్, బూడిద రంగు గేర్, నొక్కండి.").pick(lang)
            }
            val ok = intent != null && (inSettingsAlready || askToOpen || learnOpen || runCatching { svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                // Practice and app-map routes start from the app's first screen, not wherever it was left (field: YouTube
                // reopened on an old playlist page, WhatsApp on someone else's chat). Launcher intents only.
                (if (practice || (f?.id?.startsWith("map_") == true && intent.action == Intent.ACTION_MAIN)) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))) }.isSuccess)
            if (inSettingsAlready) com.saathi.app.DebugLog.i("begin", "already in Settings: guiding from this screen")
            if (askToOpen) com.saathi.app.DebugLog.i("begin", "asking them to open Settings (Saathi never opens it)")
            if (ok) resolvePkg(intent!!)?.let { taskPkgs += it; adoptPkg = false }
            else { missingApp(f); return }
        }
        val n = f?.let { Memory.timesDone(it.id) } ?: 0
        if (auto) hello += " " + say("I'll do each step for you — watch the glow. I'll ask before anything important.",
            "मैं हर क़दम ख़ुद करूँगा — चमक देखते रहिए। ज़रूरी चीज़ से पहले पूछूँगा।",
            "ప్రతి అడుగు నేనే చేస్తాను — మెరుపు చూడండి. ముఖ్యమైనదానికి ముందు అడుగుతాను.").pick(lang)
        else if (f != null && f.steps.isNotEmpty() && n >= 3) {
            hello += " " + say("You've done this $n times. Try first; I'll glow if you need me.",
                "आप यह $n बार कर चुके हैं। पहले ख़ुद कोशिश कीजिए, ज़रूरत हो तो मैं दिखाऊँगा।",
                "మీరు ఇది $n సార్లు చేశారు. ముందు మీరే ప్రయత్నించండి.").pick(lang)
        }
        persist()
        if (svc.isLocked()) hello = say("Please unlock your phone first. Then I'll show you.", "पहले फ़ोन का लॉक खोलिए। फिर मैं दिखाऊँगा।", "ముందు ఫోన్ లాక్ తెరవండి. తర్వాత నేను చూపిస్తాను.").pick(lang)
        overlay.highlight(null, false)
        overlay.showCard(hello, Overlay.Mode.INFO)
        speaker.say(hello, lang)
        schedule(START_MS, force = true)
    }

    /** "remember my BP tablet is Telma 40" → a note. */
    private fun rememberRequest(g: String): String? {
        val m = Regex("(?i)^\\s*(remember|note down|याद रखो|याद रखना|గుర్తుంచుకో)\\s+(that\\s+)?(.+)$").find(g) ?: return null
        Memory.note(m.groupValues[3].trim())
        return say("Got it. I'll remember that.", "ठीक है, मैं याद रखूँगा।", "సరే, గుర్తుంచుకుంటాను.").pick(lang)
    }

    /** After a restart: offer to continue the unfinished task. */
    fun offerResume() {
        val t = Memory.task() ?: return
        lang = Prefs.lang(svc)
        // The phone switched Saathi off for a moment (opening Settings does that here) and the guard put it back: the
        // person did nothing wrong, so just carry on, no question (field, 02:35).
        if (android.os.SystemClock.uptimeMillis() - com.saathi.app.service.A11yGuard.healedAt < 8000) {
            com.saathi.app.DebugLog.i("resume", "after the self-heal: \"${t.goal}\"")
            Memory.clearTask(); start(t.goal); return
        }
        val text = say("Shall we continue: \"${t.goal}\"?", "क्या हम जारी रखें: \"${t.goal}\"?", "కొనసాగిద్దామా: \"${t.goal}\"?").pick(lang)
        overlay.showCard(text, Overlay.Mode.PAUSED, onContinue = { Memory.clearTask(); start(t.goal) })
    }

    // ───────────────────────── events ─────────────────────────

    fun onScreenEvent() {
        if (SaathiService.ownUiOpen) return
        schedule(THROTTLE_MS)
    }

    /**
     * They tapped something (maybe our target): drop the glow right away so it never lingers on a
     * screen that's already changing, then look again as soon as the screen settles.
     */
    /** The person tapped something labelled [label] in [pkg] (e.g. "SBI_YONO_update.apk" in a chat). */
    fun onUserClick(pkg: String, label: String?) {
        if (label.isNullOrBlank() || !Prefs.scamGuard(svc)) return
        com.saathi.app.scam.ScamShield.onScreen(com.saathi.app.scam.ScreenEvent(pkg, prevExternalPkg, emptyList(), label))
            ?.let { shieldAlert(it, pkg) }
    }

    fun onUserTap() {
        lastTapAt = SystemClock.uptimeMillis()
        current?.el?.let { lastActionNote = "They tapped something (the glowing item was \"${it.title}\")." }
        if (!active) return
        delayedGlow?.cancel()
        overlay.highlight(null, false)
        lastSig = 0
        replan = true
        lastMotion = SystemClock.uptimeMillis() - SETTLE_MS + 250
        schedule(260, force = !thinking)
    }

    /** A new window (screen) opened: re-read quickly instead of waiting for the throttle. */
    fun onWindowChanged() {
        if (SaathiService.ownUiOpen) return
        if (active) { lastSig = 0; replan = true; schedule(150, force = !thinking) } else schedule(THROTTLE_MS)
    }

    /** A real finger on the screen (from the 1×1 touch watcher). In auto mode, the person is taking over. */
    fun onUserTouch() {
        if (auto && SystemClock.uptimeMillis() - lastOwnAction > 900 && !awaitingConfirm) {
            stopAuto()
            if (active) speaker.say(say("Okay, you carry on. I'll keep showing the way.", "ठीक है, आप कीजिए। मैं रास्ता दिखाता रहूँगा।",
                "సరే, మీరు చేయండి. నేను దారి చూపిస్తూ ఉంటాను.").pick(lang), lang)
        }
        onUserMotion()
    }

    /** Touches and scrolls: freeze guidance until the screen settles, so we never point at a moving target. */
    fun onUserMotion() {
        lastMotion = SystemClock.uptimeMillis()
        overlay.setMoving(true)
        schedule(SETTLE_MS, force = !thinking)
    }

    /** Throttle, not debounce: busy screens fire events every ~100 ms and a debounce would never fire (trap #8). */
    private fun schedule(ms: Long, force: Boolean = false) {
        if (thinking) { pending = true; return }
        if (!force && job?.isActive == true) return
        job?.cancel()
        job = scope.launch { delay(ms); tick() }
    }

    private fun settling(): Long = SETTLE_MS - (SystemClock.uptimeMillis() - lastMotion)

    /**
     * Read the screen off the main thread with a time limit, so a huge or frozen app can never make
     * Saathi (or the phone) stutter. A slow read just means "look again shortly" (no weird states).
     */
    private suspend fun readScreen(): Screen? = withTimeoutOrNull(1500) {
        withContext(Dispatchers.Default) { runCatching { ScreenReader.read(svc.rootInActiveWindow) }.getOrNull() }
    }

    // ───────────────────────── the decision ladder (playbook §5) ─────────────────────────

    private suspend fun tick() {
        // 0. Never guide inside Saathi's own screens (trap #10), nor over the lock screen.
        if (SaathiService.ownUiOpen || svc.isLocked() || setAside) return
        // 1. The person is touching or scrolling: wait.
        val wait = settling()
        if (wait > 0) { job = scope.launch { delay(wait + 30); tick() }; return }
        overlay.setMoving(false)

        // 2. Read; nothing changed → nothing to do.
        val screen = readScreen() ?: run { if (active) schedule(600, force = true); return }
        if (screen.pkg == svc.packageName) return
        updateIme() // a keyboard opening doesn't change the app's tree, so check it before the "nothing changed" exit
        // A slider moving or a switch flipping doesn't change the signature: the Settings last step watches values.
        if (screen.signature == lastSig) { settle?.let { st -> lang = Prefs.lang(svc); settleTick(st, screen) }; return }
        lastSig = screen.signature
        lang = Prefs.lang(svc)

        // 3. Scam guard: always on, even with no task.
        if (Prefs.scamGuard(svc)) ScamGuard.check(screen)?.let { alert ->
            if (warnedSig != screen.signature) {
                warnedSig = screen.signature
                show(Target(screen.find(alert.safe), alert.say.pick(lang), "scam_${alert.id}", warn = true))
            }
            return
        }
        // Scam shield on the screen: APK files in chats, the installer right after a chat/browser, remote-control
        // apps, the UPI "PIN to receive" trick. prevExternalPkg = the last different app in front.
        if (screen.pkg != lastFrontPkg && screen.pkg.isNotBlank() && !isTransient(screen.pkg)) { prevExternalPkg = lastFrontPkg; lastFrontPkg = screen.pkg }
        if (Prefs.scamGuard(svc)) com.saathi.app.scam.ScamShield.onScreen(com.saathi.app.scam.ScreenEvent(screen.pkg, prevExternalPkg,
            screen.elements.map { it.label }.filter { it.isNotBlank() }, null))?.let { w -> shieldAlert(w, screen.pkg); return }
        // An on-screen scam warning goes when that screen goes; message warnings stay until the person dismisses them.
        if (current?.warn == true && goal == null && current?.key?.startsWith("scam_") == true) clearVisuals()

        // (Keyboard handling moved to updateIme(), which runs on every tick.)
        if (false) runCatching {
            val ime = svc.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val imeTop = ime?.let { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.top }
            val focus = if (ime != null) svc.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) } } else null
            overlay.setImeVisible(ime != null, imeTop, focus)
        }

        // Online form walk-through: move on once the current box has something in it.
        form?.let { plan ->
            val cur = plan.getOrNull(formI) ?: return
            val filled = com.saathi.app.forms.FormNodes.fields(svc.rootInActiveWindow).firstOrNull { it.first.box == cur.node.box }
                ?.second?.let { n -> val t = n.text?.toString().orEmpty(); t.isNotBlank() && t != n.hintText?.toString() } == true
            if (filled) { formI++; showFormStep() }
            return
        }
        // 4. No task → nothing more.
        val g = goal ?: return
        loop?.let { lg ->
            lg.onScreen(ScreenKinds.fingerprint(screen).toString(), screen.pkg)
            when (val v = lg.verdict()) {
                is com.saathi.app.policy.LoopVerdict.Repeating, is com.saathi.app.policy.LoopVerdict.OutOfSteps -> {
                    val say = when (v) { is com.saathi.app.policy.LoopVerdict.Repeating -> v.say; is com.saathi.app.policy.LoopVerdict.OutOfSteps -> v.say; else -> null }!!
                    com.saathi.app.DebugLog.i("loop", "${v::class.simpleName} in ${screen.pkg}")
                    loop = null; stopAuto()
                    // Going round in circles: say where they are and offer the ways back (not just "ask family").
                    whereAmI(lead = say.pick(lang) + " ")
                    return
                }
                else -> {}
            }
        }
        val f = flow
        // WhatsApp is working now (its chat list or a chat is showing): forget an old "not set up" (field: it was never
        // cleared after registering, so "video call my son" skipped the WhatsApp | Phone choice).
        if (screen.pkg.startsWith("com.whatsapp") && Prefs.waNotSetUp(svc) &&
            screen.elements.any { Regex("^(Chats|Calls|Message|Type a message)$").matches(it.label) }) Prefs.setWaNotSetUp(svc, false)
        // 4b. App-map route: known screens → the exact next step (Kiro's maps). Unknown screens fall through.
        settle?.let { st -> if (settleTick(st, screen)) return }
        mapRoute?.let { r -> if (mapTick(r, screen)) { mapUnknownSince = 0L; return } }
        // A route's own app on a screen the map doesn't know yet (splash, loading): give it 2.5 s to become a known page
        // before any model is asked (field 08:07: YouTube's splash went to the cloud brain for 9 s).
        mapRoute?.takeIf { r -> screen.pkg == r.pkg || r.steps.any { it.pkg == screen.pkg } }?.let {
            val now = SystemClock.uptimeMillis()
            if (mapUnknownSince == 0L) mapUnknownSince = now
            if (now - mapUnknownSince < 2500) { lastSig = 0; schedule(600, force = true); return }
        }
        // 5. Keyboard, notification shade, permission dialog: just wait.
        if (isTransient(screen.pkg)) return

        // 5a. Learn mode: they open the app from its icon (home screen or all-apps), Saathi points the way.
        needApp?.let { (pkg, label) ->
            if (screen.pkg != pkg) {
                val icon = appIcon(label)
                show(Target(icon, if (icon != null) say("Tap the glowing $label icon to open it.", "$label खोलने के लिए चमकता आइकन दबाइए।", "$label తెరవడానికి మెరుస్తున్న ఐకాన్ నొక్కండి.").pick(lang)
                    else say("Put your finger in the middle of the screen and slide it up, to see all your apps. Then find $label.",
                        "उँगली स्क्रीन के बीच में रखकर ऊपर सरकाइए, सारे ऐप दिखेंगे। फिर $label ढूँढिए।",
                        "వేలిని స్క్రీన్ మధ్యలో పెట్టి పైకి జరపండి, అన్ని యాప్‌లు కనిపిస్తాయి. తర్వాత $label వెతకండి.").pick(lang),
                    if (icon != null) "open_app" else "find_app", noAct = true))
                return
            }
            needApp = null
            com.saathi.app.DebugLog.i("learn", "$label opened by the person")
            if (practice || learn) speaker.say(say("Well done, you opened $label!", "शाबाश, आपने $label खोल लिया!", "భలే, మీరు $label తెరిచారు!").pick(lang), lang)
        }

        // 5b. Settings tasks: they open Settings themselves; then back out of any old sub-page to the main page.
        if (needSettings) {
            if (screen.pkg != "com.android.settings") {
                // The Settings APP icon, straight from the accessibility tree (labels repeat: the launcher's own
                // "Settings" button is a thin strip; the app icon is roughly square and may read "Settings, 1 notification(s)").
                val icon = appIcon("Settings")
                // Their next touch may open Settings: every window of ours goes first, back once it has opened.
                overlay.guardLaunch = true
                overlay.onQuietEnd = { lastSig = 0; schedule(0, force = true) }
                show(Target(icon, if (icon != null) say("Open Settings: tap the glowing Settings icon.", "Settings खोलिए: चमकते Settings आइकन को दबाइए।", "Settings తెరవండి: మెరుస్తున్న Settings ఐకాన్ నొక్కండి.").pick(lang)
                    else say("Let's open Settings. Put your finger in the middle of the screen and slide it up, to see all your apps.",
                        "चलिए Settings खोलते हैं। उँगली स्क्रीन के बीच में रखिए और ऊपर की ओर सरकाइए, सारे ऐप दिखेंगे।",
                        "Settings తెరుద్దాం. వేలిని స్క్రీన్ మధ్యలో పెట్టి పైకి జరపండి, అన్ని యాప్‌లు కనిపిస్తాయి.").pick(lang),
                    if (icon != null) "open_settings" else "find_settings", noAct = true))
                return
            }
            needSettings = false; overlay.guardLaunch = false
            com.saathi.app.DebugLog.i("settings", "opened by the person")
        }
        if (settingsFresh && screen.pkg == "com.android.settings") {
            val home = screen.find(listOf(Regex("^Search settings", RegexOption.IGNORE_CASE), Regex("^Search$", RegexOption.IGNORE_CASE))) != null
            if (home || (f != null && matchStep(f, screen) != null)) settingsFresh = false
            else {
                // Saathi never presses anything inside Settings itself (the phone treats that as a hijack, trap #45):
                // ask them to go back, glowing the back arrow.
                com.saathi.app.DebugLog.i("settings", "old sub-page open → asking them to go back")
                val back = screen.find(listOf(Regex("^(Navigate up|Back|Go back|Up)$", RegexOption.IGNORE_CASE)))
                show(Target(back, say("This is an older Settings page. Tap the back arrow at the top until you see the main Settings page.",
                    "यह पुराना Settings पेज है। ऊपर पीछे वाला तीर दबाइए, जब तक मुख्य Settings पेज न दिखे।",
                    "ఇది పాత Settings పేజీ. ప్రధాన Settings పేజీ వచ్చే వరకు పైన వెనక్కి బాణం నొక్కండి.").pick(lang), "settings_back", noAct = true))
                return
            }
        }

        // 6. Right app? Adopt the first real app for open-ended tasks; otherwise pause politely.
        if (adoptPkg && screen.pkg.isNotBlank() && screen.pkg != launcherPkg()) { taskPkgs += screen.pkg; adoptPkg = false }
        val stepHere = f?.let { matchStep(it, screen) }
        if (taskPkgs.isNotEmpty() && screen.pkg !in taskPkgs) {
            // Apps hand off all the time: sign-in with Google, the app store, a photo picker, the editor.
            // If it followed a tap inside the task, or it's a known helper app, it's part of the task.
            // Only going Home (or switching away on their own) pauses.
            val sinceTap = SystemClock.uptimeMillis() - maxOf(lastTapAt, lastOwnAction)
            if (screen.pkg != launcherPkg() && (stepHere != null || sinceTap < 6000 || helperApp(screen.pkg))) {
                taskPkgs += screen.pkg
                com.saathi.app.DebugLog.i("adopt", "${screen.pkg} (sinceTap=$sinceTap)")
            } else { pause(); return }
        }
        if (paused) resume()

        // 6b. Ads / popups: point at the way out. Sign-in / setup walls: explain once, then wait for them.
        if (stepHere == null) {
            (ScreenKinds.ad(screen) ?: ScreenKinds.nag(screen))?.let { close ->
                show(Target(close, say("An ad or popup is in the way. Tap “${close.title}” to close it.", "विज्ञापन बीच में है। बंद करने के लिए “${close.title}” दबाइए।",
                    "ప్రకటన అడ్డం వచ్చింది. మూసేయడానికి “${close.title}” నొక్కండి.").pick(lang), "ad_${close.title}"))
                return
            }
            ScreenKinds.wall(screen)?.let { w ->
                val fp = ScreenKinds.fingerprint(screen)
                if (fp != wallFp) { wallFp = fp; showWall(w, screen) }
                return
            }
        }

        if (f != null) {
            // 7. Finished?
            if (f.isDone?.invoke(screen) == true) { complete(f); return }
            // 8. Latest scripted step whose target is visible.
            if (stepHere != null) { showStep(f, screen, stepHere.first, stepHere.second); return }
            // 8b. Play Store is installing: just wait (no scroll hints, never glow Cancel).
            if (f.id.startsWith("install_") && Regex("(?i)installing|pending|downloading|\\d{1,3}\\s?%|waiting for").containsMatchIn(screen.allText)) {
                show(Target(null, say("It's installing. Please wait a moment.", "इंस्टॉल हो रहा है। थोड़ा रुकिए।", "ఇన్‌స్టాల్ అవుతోంది. కొంచెం ఆగండి.").pick(lang), "install_wait", noAct = true))
                return
            }
            // 9. The next target is probably just off-screen: ask them to scroll (max 3, trap #13).
            if (f.steps.isNotEmpty() && scrolls < 3 && screen.scrollable() != null) {
                val next = f.steps.getOrNull(lastStepIdx + 1) ?: f.steps.first()
                val name = next.targets.first().pattern.replace("\\Q", "").replace("\\E", "").replace(Regex("\\{\\d+,?\\d*\\}"), "")
                    .replace(Regex("[\\^$\\\\()?*+.\\[\\]]"), "").substringBefore('|').trim()
                // Only quote a plain word/phrase; a pattern with lookaheads or alternatives means "use the step's words".
                val plain = name.length in 3..40 && Regex("^[\\p{L}\\p{M}\\p{N} '&,-]+$").matches(name) &&
                    !Regex("[?!]").containsMatchIn(next.targets.first().pattern.take(4))
                val text = if (plain) say("Slowly scroll down. Look for \"$name\".", "धीरे से नीचे स्क्रॉल कीजिए। \"$name\" ढूँढिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి. \"$name\" వెతకండి.").pick(lang)
                else if (next.say.isNotEmpty()) (say("Slowly scroll down.", "धीरे से नीचे स्क्रॉल कीजिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి.").pick(lang) + " " + next.say.pick(lang))
                else say("Slowly scroll down to see more.", "धीरे से नीचे स्क्रॉल कीजिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి.").pick(lang)
                scrolls++
                show(Target(null, text, "scroll_${next.key}", scroll = true))
                return
            }
        }

        // 10a. Planned this exact screen before (and nothing was tapped since)? Reuse it: never re-ask in a loop.
        val fp = ScreenKinds.fingerprint(screen)
        if (replan) { planCache.remove(fp); replan = false }
        planCache[fp]?.let { p ->
            val el = p.label?.let { l -> screen.elements.firstOrNull { it.label == l && it.role == p.role } }
            if (el != null) show(Target(el, p.say, "plan_${p.label}", noAct = p.noAct))
            return
        }
        if (unsureCount[fp] ?: 0 >= 2 || plansThisTask >= 8) { unsure(fp); return }

        // 9b. Login / OTP / password page: the person's own step. Never plan (or type) here; explain once and wait.
        if (ScreenKinds.secretEntry(screen)) {
            if (fp != wallFp) { wallFp = fp; showWall(ScreenKinds.Wall(null, true), screen) }
            return
        }
        // 9d. Unscripted path, validated (field: vague tasks drifted: the model tapped "something" on any screen).
        driftCheck(screen, f, g)?.let { return }
        // 10. Anything else: the planner (on-device LLM, loaded on first need; keywords while it warms up).
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        delay(50)
        thinking = true
        overlay.highlight(null, false)
        overlay.setAura(true)
        overlay.showCard(say("Let me look…", "मैं देख रहा हूँ…", "చూస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
        // First use: give the model a few seconds to load (Gemma 4 on the GPU ≈ 4.5 s) before falling back.
        var waited = 0
        val lowPower = Power.low(svc)
        // (The brain lives in its own process: right after loadAsync its state can still read Idle, so wait on
        // "not ready and not failed", not on "Loading" — otherwise we fell straight to the weak keyword guess.)
        while (!lowPower && !LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed &&
            LlmManager.state.value !is LlmManager.State.NoModel && waited < 18000) { delay(250); waited += 250 }
        val learned = f?.steps?.mapNotNull { Memory.learnedLabel(screen.pkg, it.key) }.orEmpty()
        replan = false
        plansThisTask++
        var d = try {
            com.saathi.app.llm.AiMeter.purpose = "plan step"
            proDecide(f?.llmGoal ?: g, planScreen(screen)) ?: Planner.decideInTask(taskKey, (f?.llmGoal ?: g) + answers.joinToString("") { " (they chose: $it)" }, planScreen(screen), lastActionNote, lang, AppLauncher.labelOf(svc, screen.pkg), allowLlm = !lowPower,
                progress = history.toList())
        } finally { thinking = false; overlay.setAura(false) }
        lastActionNote = null
        // The person asked something / stopped while the model was thinking: this plan is stale, drop it.
        if (setAside || goal == null || SaathiService.ownUiOpen) return
        // Never lead them to Help / About / Privacy / Terms unless they asked for it.
        d.targetId?.let { screen.byId(it) }?.let { el -> if (ScreenKinds.avoid(el.label, g)) d = d.copy(targetId = null) }
        com.saathi.app.DebugLog.i("plan", "goal=\"${f?.llmGoal ?: g}\" pkg=${screen.pkg} llm=${d.fromLlm} target=${d.targetId?.let { screen.byId(it)?.label?.take(50) }} done=${d.done} noAct=${d.noAct} lowPower=$lowPower")
        if (goal == null) return
        if (settling() > 0 || readScreen()?.signature != screen.signature) {
            lastSig = 0; schedule(200, force = true); return // the screen moved while we thought: look again
        }
        if (d.done && d.fromLlm && f?.isDone?.invoke(screen) != true) {
            // The model says "done" but nothing on screen proves it (field 06:31: "successfully cropped" with nothing
            // cropped): ask them.
            com.saathi.app.DebugLog.i("plan", "model says done, unproven: asking")
            val q = say("Is it done the way you wanted?", "क्या काम हो गया, जैसा आप चाहते थे?", "మీరు అనుకున్నట్టు అయిపోయిందా?").pick(lang)
            current = Target(null, q, "ask_done_$fp"); lastSpokenKey = current?.key
            overlay.highlight(null, false)
            overlay.showChoice(q, Triple(say("Yes, done", "हाँ, हो गया", "అవును").pick(lang), com.saathi.app.R.drawable.ic_check, { f?.let { complete(it) } ?: finish(d.say) }),
                Triple(say("Not yet", "अभी नहीं", "ఇంకా లేదు").pick(lang), com.saathi.app.R.drawable.ic_replay, {
                    answers += "not done yet"; planCache.clear(); lastSig = 0; replan = true; schedule(0, force = true) }))
            speaker.say(q, lang)
        }
        else if (d.done) { f?.let { complete(it) } ?: finish(d.say) }
        else if (d.action == "back") {
            show(Target(null, d.say, "plan_back_$fp", fill = "__BACK__"))
        } else if (d.action == "ask") {
            // The model needs them to decide: ask out loud, wait for their answer (the Ask sheet adds it to the goal).
            current = Target(null, d.say, "ask_$fp"); lastSpokenKey = current?.key
            planCache[fp] = Plan(null, null, d.say, false)
            overlay.highlight(null, false)
            // "Play the playlist, or search for something else?": two big buttons, not voice only (field: no way to
            // answer but the mic). Tapping one answers exactly like saying it.
            val opts = choicesIn(d.say)
            // A question about buttons that are on the screen ("Update or Not now?"): their choice glows THAT button, so
            // the tap visibly does something (field: tapping "Not now" on our card did nothing).
            val onScreen = { o: String -> screen.elements.firstOrNull { e -> e.role != "text" && e.title.equals(o.trim(), true) } }
            val pickIt = { o: String -> onScreen(o)?.let { el -> show(Target(el, Planner.tapSay(el.title, lang), "plan_${el.label}")) } ?: handleUtterance(o) }
            if (opts != null) overlay.showChoice(d.say,
                Triple(opts.first, com.saathi.app.R.drawable.ic_check, { pickIt(opts.first) }),
                Triple(opts.second, com.saathi.app.R.drawable.ic_chevron_right, { pickIt(opts.second) }))
            else overlay.showCard(d.say, Overlay.Mode.ASK)
            speaker.say(d.say, lang)
        } else {
            val el = d.targetId?.let { screen.byId(it) }
            if (el != null) {
                if (f != null) f.steps.getOrNull(lastStepIdx + 1)?.let { pendingLearn = Triple(screen.pkg, it.key, el.title) }
                // Speak what's actually written on the button, not the model's paraphrase (it invented "Options").
                val text = if (lang == Lang.EN && d.fromLlm && d.say.contains(el.title.take(12), ignoreCase = true)) d.say else Planner.tapSay(el.title, lang)
                planCache[fp] = Plan(el.label, el.role, text, d.noAct)
                // A search box: "Do it" types the key words of their request (e.g. "ringtone").
                val fill = if (el.role == "input" && !el.password) (d.text ?: searchTerm(f?.llmGoal ?: g)) else null
                // Not a guess dressed as certainty: a target unrelated to the goal and not a normal way to move around
                // is offered as "I'm not sure" with no "Do it".
                val words = goalWords(f?.llmGoal ?: g)
                val related = words.any { el.label.contains(it, true) } || NAV_WORDS.containsMatchIn(el.title) || fill != null
                val unsureText = say("I'm not sure, but try “${el.title.take(30)}”. If it's wrong, say “I'm lost”.",
                    "पक्का नहीं पता, पर “${el.title.take(30)}” आज़माइए। ग़लत हो तो कहिए “मैं खो गया”।",
                    "ఖచ్చితంగా తెలియదు, కానీ “${el.title.take(30)}” ప్రయత్నించండి. తప్పైతే “తప్పిపోయాను” అనండి.").pick(lang)
                if (!related) com.saathi.app.DebugLog.i("drift", "unrelated target \"${el.title}\" for $words: offered as unsure")
                show(Target(el, if (fill != null) say("Tap the search box and type “$fill”.", "खोज में “$fill” लिखिए।", "వెతుకులో “$fill” టైప్ చేయండి.").pick(lang)
                    else if (!related) unsureText else text, "plan_${el.label}", fill = fill, noAct = d.noAct || !related))
            } else {
                unsureCount[fp] = (unsureCount[fp] ?: 0) + 1
                if (screen.scrollable() != null && (unsureCount[fp] ?: 0) == 1) show(Target(null, d.say, "plan_scroll_$fp", scroll = true))
                else unsure(fp)
            }
        }
        if (pending) { pending = false; lastSig = 0; schedule(300, force = true) }
    }

    private fun showStep(f: Flow, screen: Screen, i: Int, el: UiElement) {
        pendingLearn?.let { (pkg, key, label) -> if (i > lastStepIdx) Memory.learnLabel(pkg, key, label) }
        pendingLearn = null
        lastStepIdx = i; scrolls = 0
        persist()
        val st = f.steps[i]
        val teach = f.teach || Prefs.teach(svc)
        // Phones name things differently: if the real label isn't in our sentence, quote it (trap #14).
        val text = quoteRealLabel(st.say.pick(lang), el).let { t ->
            if (practice) say("Your turn. ", "अब आपकी बारी। ", "ఇప్పుడు మీ వంతు. ").pick(lang) + t + say(" I'll show you if you need.", " ज़रूरत हो तो मैं दिखाऊँगा।", " అవసరమైతే చూపిస్తాను.").pick(lang) else t
        }
        // Install / Send / Pay / Call / anything the policy keeps manual: no "Do it for me" on the card at all.
        val manual = com.saathi.app.policy.ActionPolicy.check(com.saathi.app.policy.ActionRequest(
            if (el.role == "input" && st.fill != null) com.saathi.app.policy.Kind.TYPE else com.saathi.app.policy.Kind.TAP,
            screen.pkg, el.label, el.role, el.password, st.fill, screen.allText, com.saathi.app.policy.Mode.DO_IT_ONCE, prevExternalPkg))
            .let { it is com.saathi.app.policy.Verdict.GlowOnly || it is com.saathi.app.policy.Verdict.Block }
        show(Target(el, text, st.key, st.fill, noAct = manual,
            final = f.isDone == null && i == f.steps.lastIndex,
            tip = if (teach) st.tip?.pick(lang) else null,
            progress = (i + 1) to f.steps.size), practiced = practice || Memory.timesDone(f.id) >= 3)
    }

    /**
     * "Tap Display." on a phone whose row says "Display, brightness & eye protection" becomes
     * "Tap “Display, brightness & eye protection”." so they look for exactly what's written.
     */
    private fun quoteRealLabel(text: String, el: UiElement): String {
        val shown = el.title
        if (el.role == "input" || el.role == "slider" || shown.length !in 2..48) return text
        if (text.contains(shown, ignoreCase = true)) return text
        // Replace the longest leading phrase of the real label that our sentence mentions ("Font size").
        val words = shown.split(Regex("\\s+"))
        for (n in words.size downTo 1) {
            val phrase = words.take(n).joinToString(" ").trimEnd(',', '&', ':')
            if (phrase.length < 3) continue
            val re = Regex("['‘“\"]?" + Regex.escape(phrase) + "['’”\"]?", RegexOption.IGNORE_CASE)
            if (re.containsMatchIn(text)) return text.replaceFirst(re, "“$shown”")
        }
        return "$text — “$shown”"
    }

    /** Latest step whose target is visible, including labels this phone taught us. */
    /** Ads are never a step's target, in any app. */
    private val NOT_A_TARGET = Regex("(?i)\\bSponsored\\b|^Ad\\s*[·•]|· Ad\\b")

    private fun matchStep(f: Flow, rawScreen: Screen): Pair<Int, UiElement>? {
        // Only things a finger can actually hit: ≥60 px visible above the navigation bar (field test: a 30 px sliver of
        // a YouTube row at the screen edge was glowed; tapping it hit the nav bar and went Home).
        val h = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val usable = (h * 0.955f).toInt()
        val screen = rawScreen.copy(elements = rawScreen.elements.filter { e ->
            !NOT_A_TARGET.containsMatchIn(e.label) && minOf(e.bounds.bottom, usable) - maxOf(e.bounds.top, 0) >= 60 && e.bounds.width() >= 40
        })
        for (i in f.steps.indices.reversed()) {
            val st = f.steps[i]
            val learned = Memory.learnedLabel(screen.pkg, st.key)?.let { listOf(Regex("^" + Regex.escape(it) + "$")) }.orEmpty()
            if (st.screenHas != null && !st.screenHas.containsMatchIn(screen.allText)) continue
            if (st.unlessVisible.isNotEmpty() && screen.elements.any { e -> st.unlessVisible.any { it.containsMatchIn(e.label) } }) continue
            val el = screen.find(st.targets + learned, st.role) ?: continue
            return i to el
        }
        return null
    }

    private fun show(t: Target, practiced: Boolean = false) {
        if (!t.key.startsWith("map_")) stepWhy = null
        hideJob?.cancel()
        current = t
        delayedGlow?.cancel()
        val newKey = t.key != lastSpokenKey
        if (practiced && newKey && !t.warn) {
            // Fading help: they've done this before, so give them a moment to find it themselves.
            overlay.highlight(null, false)
            delayedGlow = scope.launch { delay(if (practice) 8000 else 3500); if (current?.key == t.key) overlay.highlight(t.el?.bounds, false) }
        } else overlay.highlight(t.el?.bounds, t.warn)
        if (newKey) lastProgress = SystemClock.uptimeMillis()
        if (newKey) com.saathi.app.DebugLog.i("show", "key=${t.key} el=\"${t.el?.label?.take(60)}\" role=${t.el?.role} bounds=${t.el?.bounds?.toShortString()} warn=${t.warn} final=${t.final} noAct=${t.noAct} auto=$auto text=\"${t.text.take(120)}\" pkg=${taskPkgs.firstOrNull()}")
        val mode = when {
            t.fill == "__BACK__" -> Overlay.Mode.STEP
            auto && !t.warn && !t.noAct && (t.el != null || t.scroll) && !t.final -> Overlay.Mode.AUTO
            t.warn -> Overlay.Mode.WARN
            t.final -> Overlay.Mode.FINAL
            t.scroll -> Overlay.Mode.SCROLL
            t.el == null || t.noAct -> Overlay.Mode.INFO
            else -> Overlay.Mode.STEP
        }
        overlay.showCard(t.text, mode, targetCenterY = t.el?.bounds?.centerY(), tip = t.tip, progress = t.progress)
        if (newKey) {
            lastSpokenKey = t.key
            speaker.say(if (t.tip != null) "${t.text} ${t.tip}" else t.text, lang)
            t.el?.let { history += it.title.take(30) }
            svc.buzz()
        }
        if (auto) scheduleAuto(t)
    }

    // ───────────────────────── auto mode ─────────────────────────

    private val CONFIRM_KEYS = setOf("send", "confirm", "dial", "video", "pay", "now", "junk", "clean", "book")

    fun enableAuto() {
        if (!active) return
        auto = true; autoSteps = 0; autoSameKey = 0
        speaker.say(say("Okay, I'll do it for you. Watch the glow. I'll ask before anything important.",
            "ठीक है, मैं कर देता हूँ। चमक देखिए। ज़रूरी चीज़ से पहले पूछूँगा।",
            "సరే, నేను చేస్తాను. మెరుపు చూడండి. ముఖ్యమైనదానికి ముందు అడుగుతాను.").pick(lang), lang)
        current?.let { show(it.copy()) } ?: run { lastSig = 0; schedule(200, force = true) }
    }

    private fun stopAuto() {
        if (auto) com.saathi.app.DebugLog.i("auto", "stopped at key=${current?.key}")
        auto = false; awaitingConfirm = false
        autoJob?.cancel()
        current?.let { if (active && it.el != null) overlay.showCard(it.text, if (it.noAct) Overlay.Mode.INFO else Overlay.Mode.STEP, targetCenterY = it.el.bounds.centerY(), progress = it.progress) }
    }

    /** After a short, visible pause (so they can see what's happening), do the step — or ask first. */
    private fun scheduleAuto(t: Target) {
        autoJob?.cancel()
        if (t.final || t.warn) { auto = false; return } // last step / warnings: the person decides
        autoJob = scope.launch {
            delay(1700)
            if (!auto || current?.key != t.key || paused || goal == null) return@launch
            if (t.key == autoLastKey) autoSameKey++ else { autoSameKey = 0; autoLastKey = t.key }
            if (++autoSteps > 30 || autoSameKey >= 2) {
                stopAuto()
                val m = say("This isn't moving forward. Let's do it together — follow the glow.", "आगे नहीं बढ़ रहा। साथ में करते हैं — चमक देखिए।", "ముందుకు వెళ్ళట్లేదు. కలిసి చేద్దాం — మెరుపు చూడండి.").pick(lang)
                speaker.say(m, lang); return@launch
            }
            if (t.noAct || t.el?.password == true) {
                stopAuto()
                speaker.say(say("This part is for you to do yourself.", "यह हिस्सा आप ख़ुद कीजिए।", "ఈ భాగం మీరే చేయండి.").pick(lang), lang)
                return@launch
            }
            val label = t.el?.title.orEmpty()
            if (t.key in CONFIRM_KEYS || (label.isNotBlank() && Planner.isRisky(label))) { askConfirm(t); return@launch }
            performStep(t)
        }
    }

    private var approvedKey: String? = null

    private fun askConfirm(t: Target, ask: String? = null) {
        com.saathi.app.DebugLog.i("auto", "confirm before \"${t.el?.title}\"")
        awaitingConfirm = true
        val label = t.el?.title?.take(30) ?: ""
        val q = ask ?: say("Shall I press “$label”?", "क्या मैं “$label” दबाऊँ?", "“$label” నొక్కనా?").pick(lang)
        overlay.highlight(t.el?.bounds, false)
        overlay.showCard(q, Overlay.Mode.CONFIRM, targetCenterY = t.el?.bounds?.centerY())
        speaker.say(q, lang)
    }

    /** "Don't press it" on a confirm: stay on this step, back to normal guidance. */
    fun declineConfirm() {
        awaitingConfirm = false
        stopAuto()
    }

    private fun pause() {
        if (paused) return
        com.saathi.app.DebugLog.i("pause", "left task apps $taskPkgs")
        paused = true
        delayedGlow?.cancel()
        overlay.highlight(null, false)
        val app = taskPkgs.firstOrNull()?.let { AppLauncher.labelOf(svc, it) } ?: ""
        val text = say("Paused. Tap Continue to go back to $app.", "रुक गए। $app पर लौटने के लिए 'जारी रखें' दबाइए।",
            "ఆగాం. $app కి తిరిగి వెళ్ళడానికి 'కొనసాగించు' నొక్కండి.").pick(lang)
        current = null
        overlay.showCard(text, Overlay.Mode.PAUSED, onContinue = {
            taskPkgs.firstOrNull()?.let { AppLauncher.launch(svc, it) }
                ?.let { runCatching { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        })
    }

    /** Back to the task that a question/message set aside: the model is re-primed with its progress. */
    fun resumeTask() {
        if (goal == null) return
        setAside = false; paused = false; lastSpokenKey = null; lastSig = 0; replan = true
        taskPkgs.firstOrNull()?.let { AppLauncher.launch(svc, it) }?.let { runCatching { svc.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        schedule(700, force = true)
    }

    private fun resume() {
        paused = false
        lastSpokenKey = null // re-announce the step we're on
    }

    fun repeat() { current?.let { speaker.say(it.text, lang) } }

    /** The card's primary button / "do it" by voice. In auto mode it means "let me do it myself" (pause auto). */
    fun doItForMe() {
        form?.let { plan -> fillFormField(plan); return }
        val t = current ?: return
        if (awaitingConfirm) { awaitingConfirm = false; approvedKey = t.key; performStep(t); return } // one-use approval
        if (auto) {
            stopAuto()
            speaker.say(say("Okay, your turn. Tap where it glows.", "ठीक है, अब आप दबाइए जहाँ चमक है।", "సరే, ఇప్పుడు మీరు మెరుస్తున్న చోట నొక్కండి.").pick(lang), lang)
            return
        }
        performStep(t)
    }

    /** Tap or type on their behalf, only on a settled, freshly re-read screen. */
    private fun performStep(t: Target) {
        if (t.noAct) { speaker.say(t.text, lang); return }
        scope.launch {
            val wait = settling()
            if (wait > 0) delay(wait + 50)
            // List rows get recycled while scrolling: re-find by label + role on a fresh read (trap #11).
            val fresh = readScreen()
            // Inside Settings, Saathi only types into the search box; taps must be the person's own (trap #45).
            if (fresh?.pkg == "com.android.settings" && t.fill == null) {
                speaker.say(say("Please tap it yourself here. Settings only accepts your own finger.", "यहाँ आप ख़ुद दबाइए। Settings सिर्फ़ आपकी उँगली मानता है।",
                    "ఇక్కడ మీరే నొక్కండి. Settings మీ వేలినే ఒప్పుకుంటుంది.").pick(lang), lang)
                return@launch
            }
            if (t.key.startsWith("map_")) {
                val r = mapRoute ?: return@launch
                val live = MapBridge.read(svc.rootInActiveWindow)
                val d = com.saathi.app.maps.AppMaps.next(r, fresh?.pkg ?: "", live.nodes, maxOf(mapStep, 0), mapSlots)
                if (d is com.saathi.app.maps.Decision.Glow) {
                    act(t.copy(fill = d.fill), fresh, MapBridge.uiElement(d.node, live.infoFor(d.node), if (d.node.editable) "input" else "button"))
                } else { lastSig = 0; schedule(200, force = true) }
                return@launch
            }
            val el = t.el?.let { old ->
                fresh?.elements?.firstOrNull { it.label == old.label && it.role == old.role }
                    ?: run { lastSig = 0; schedule(200, force = true); return@launch }
            }
            act(t, fresh, el)
        }
    }

    private fun act(t: Target, fresh: Screen?, el: UiElement?) {
        lastOwnAction = SystemClock.uptimeMillis()
        lastActionNote = when {
            t.fill == "__BACK__" -> "I pressed Back for them."
            t.fill == "__ENTER__" -> "I pressed the keyboard's search key for them."
            el == null -> "I scrolled down for them."
            t.fill != null -> "I typed \"${t.fill}\" into \"${el.title}\"."
            else -> "I tapped \"${el.title}\" for them."
        }
        if (t.key == lastActKey && lastOwnAction - lastActAt < 6000) sameActCount++ else sameActCount = 0
        lastActKey = t.key; lastActAt = lastOwnAction
        if (sameActCount >= 2) {
            // Pressed the same thing three times and nothing changed: stop pressing, tell them.
            stopAuto()
            speaker.say(say("That button isn't responding. Let's try something else — say it another way, or tap Back.",
                "यह बटन काम नहीं कर रहा। कुछ और करते हैं — दूसरे तरीक़े से कहिए, या वापस दबाइए।",
                "ఈ బటన్ పనిచేయట్లేదు. వేరే విధంగా చెప్పండి, లేదా వెనక్కి నొక్కండి.").pick(lang), lang)
            return
        }
        // Final safety gate (policy.ActionPolicy): on the freshly re-read target, every time, before any action.
        if (fresh == null) return
        val kind = when {
            t.fill == "__BACK__" -> com.saathi.app.policy.Kind.BACK
            t.fill == "__ENTER__" -> com.saathi.app.policy.Kind.TAP
            el == null || el.role == "slider" -> com.saathi.app.policy.Kind.SCROLL
            el.role == "input" && t.fill != null -> com.saathi.app.policy.Kind.TYPE
            else -> com.saathi.app.policy.Kind.TAP
        }
        val verdict = com.saathi.app.policy.ActionPolicy.check(com.saathi.app.policy.ActionRequest(kind, fresh.pkg, el?.label, el?.role,
            el?.password ?: false, if (kind == com.saathi.app.policy.Kind.TYPE) t.fill else null, fresh.allText,
            if (auto) com.saathi.app.policy.Mode.AUTO else com.saathi.app.policy.Mode.DO_IT_ONCE, prevExternalPkg))
        when (verdict) {
            is com.saathi.app.policy.Verdict.Allow -> {}
            is com.saathi.app.policy.Verdict.Confirm -> if (approvedKey == t.key) approvedKey = null else {
                stopAuto(); askConfirm(t, verdict.ask.pick(lang)); return }
            is com.saathi.app.policy.Verdict.GlowOnly -> {
                stopAuto(); overlay.highlight(el?.bounds, false); speaker.say(verdict.say.pick(lang), lang)
                com.saathi.app.DebugLog.i("policy", "glow only: ${t.key}"); return }
            is com.saathi.app.policy.Verdict.Block -> {
                stopAuto(); speaker.say(verdict.say.pick(lang), lang)
                com.saathi.app.DebugLog.i("policy", "blocked: ${t.key}"); return }
        }
        loop?.onAction(t.key)
        com.saathi.app.DebugLog.i("act", "key=${t.key} target=\"${el?.label?.take(60)}\" role=${el?.role} auto=$auto fill=${t.fill != null}")
        if (el == null && t.fill == "__BACK__") {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            lastSig = 0; replan = true; schedule(600, force = true); return
        }
        if (t.fill == "__ENTER__") {
            // The keyboard's search / enter key, on the box itself (Android 11+).
            el?.node?.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            lastOwnAction = SystemClock.uptimeMillis(); lastSig = 0; schedule(700, force = true); return
        }
        if (el == null) {
            // A scroll hint: "Do it" scrolls for them: the page's main list, else a finger swipe up the middle.
            val h = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            val w = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            val list = fresh.scrollable()?.takeIf { it.bounds.height() > h * 0.4f }
            if (list?.node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) runCatching {
                val path = android.graphics.Path().apply { moveTo(w / 2f, h * 0.70f); lineTo(w / 2f, h * 0.35f) }
                svc.dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
            }
            lastOwnAction = SystemClock.uptimeMillis()
            lastSig = 0
            schedule(700, force = true)
            return
        }
        if (el.password) {
            speaker.say(say("Only you should type your PIN. I will never type it.",
                "PIN सिर्फ़ आप ही डालिए। मैं कभी नहीं डालूँगा।",
                "PIN మీరే టైప్ చేయాలి. నేను ఎప్పుడూ టైప్ చేయను.").pick(lang), lang)
            return
        }
        if (el.role == "input" && t.fill != null) {
            el.node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t.fill) }
            el.node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else if (el.role == "slider") {
            el.node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } else if (!clickUp(el.node)) {
            svc.tap(el.bounds.exactCenterX(), el.bounds.exactCenterY())
        }
        lastOwnAction = SystemClock.uptimeMillis()
        lastSig = 0
        replan = true
        schedule(550, force = true)
    }

    private fun clickUp(n: AccessibilityNodeInfo?): Boolean {
        var cur = n
        repeat(5) {
            val c = cur ?: return false
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = c.parent
        }
        return false
    }

    private fun complete(f: Flow) {
        Memory.completed(f.id)
        goal?.let { Memory.journal("Did: $it") }
        f.memo?.let { Memory.addReminder(it) }
        runCatching { f.onDone?.invoke(svc) }
        val g = goal
        val wasPractice = practice
        practice = false
        // A task that teaches something can be practised: same task again, the person leads.
        val canPractise = g != null && (f.steps.size >= 2 || f.id.startsWith("map_")) && f.launch != null && f.id !in setOf("phone_video", "wa_video", "call")
        val tryIt = if (canPractise) Triple(say("Let me try", "मैं ख़ुद करूँ", "నేనే చేస్తాను").pick(lang), com.saathi.app.R.drawable.ic_touch_app,
            { practise(g!!, f) }) else null
        val praise = if (wasPractice) say("You did it yourself! ", "आपने ख़ुद कर लिया! ", "మీరే చేశారు! ").pick(lang) else ""
        finish(praise + f.doneSay.pick(lang), tryIt)
    }

    /** "Let me try": the same task from the app's start screen; Saathi prompts, the glow waits (see show()). */
    private fun practise(g: String, f: Flow) {
        practice = true; learn = true
        com.saathi.app.DebugLog.i("practice", "start ${f.id}")
        if (f.id.startsWith("map_")) com.saathi.app.maps.AppMaps.routeById(f.id.removePrefix("map_"))?.let { r ->
            mapRoute = r; mapSlots = com.saathi.app.maps.MapSlots.of(r, g, Prefs.family(svc)); mapStep = -1 }
        begin(g, f, autoMode = false)
    }

    private fun finish(text: String, first: Triple<String, Int, () -> Unit>? = null) {
        LlmManager.endChat()
        goal?.let { Conversation.remember(it, text) }
        com.saathi.app.DebugLog.i("finish", "\"${text.take(120)}\" goal=\"$goal\"")
        auto = false; awaitingConfirm = false; autoJob?.cancel(); watchdog?.cancel()
        goal = null; flow = null; current = null; paused = false; taskPkgs.clear(); needApp = null; learn = false
        delayedGlow?.cancel()
        Memory.clearTask()
        overlay.highlight(null, false)
        // Never a dead end: what next? (Practise it, or something else by voice.)
        val more = Triple(say("Something else", "कुछ और", "ఇంకేదైనా").pick(lang), com.saathi.app.R.drawable.ic_mic, { svc.openAsk(listen = true) })
        if (first != null) overlay.showChoice(text, first, more) else overlay.showChoice(text, more, null)
        speaker.say(text, lang)
        svc.buzz()
        hideJob?.cancel()
        // A choice waits longer (older people read slowly; field: "Send to Akash" was gone before the tap).
        hideJob = scope.launch { delay(if (first != null) 60_000 else 20_000); if (goal == null) overlay.hideCard() }
    }

    fun stop() {
        setAside = false; overlay.guardLaunch = false
        coachGoal = null; coachWaiting = false; coachKey = ""   // Stop stops the coach too (its pending turn sees the key change)
        LlmManager.endChat()
        goal?.let { g -> if (lastStepIdx >= 0 || history.isNotEmpty()) Memory.journal("Started but stopped: $g (got to: ${history.lastOrNull() ?: "start"})") }
        if (goal != null) com.saathi.app.DebugLog.i("stop", "goal=\"$goal\" step=$lastStepIdx")
        auto = false; awaitingConfirm = false; autoJob?.cancel(); watchdog?.cancel()
        goal = null; flow = null; paused = false; taskPkgs.clear(); needSettings = false; settingsFresh = false; practice = false; needApp = null; learn = false; form = null; loop = null; mapRoute = null; mapStep = -1; settle = null
        delayedGlow?.cancel()
        Memory.clearTask()
        clearVisuals()
        speaker.stop()
    }

    private fun clearVisuals() {
        current = null; lastSpokenKey = null
        overlay.highlight(null, false)
        overlay.hideCard()
    }

    /** "Is this a scam?" about whatever is on screen right now. */
    fun scamCheck() {
        lang = Prefs.lang(svc)
        scope.launch {
            delay(900)
            val screen = readScreen() ?: return@launch
            val alert = ScamGuard.check(screen)
            if (alert != null) show(Target(screen.find(alert.safe), alert.say.pick(lang), "scam_${alert.id}", warn = true))
            else finish(say("I don't see warning signs here. Still: never share an OTP or PIN with anyone who calls you.",
                "यहाँ कोई ख़तरे का निशान नहीं दिखा। फिर भी OTP या PIN किसी को फ़ोन पर मत बताइए।",
                "ఇక్కడ ప్రమాద సంకేతాలు లేవు. అయినా OTP, PIN ఫోన్‌లో ఎవరికీ చెప్పకండి.").pick(lang))
        }
    }

    /** "Where am I?" / "What is this?": explain the current screen, offer Back / Home. */
    fun explain() {
        lang = Prefs.lang(svc)
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        scope.launch {
            overlay.setAura(true)
            overlay.showCard(say("Let me look…", "मैं देख रहा हूँ…", "చూస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            delay(700)
            val screen = readScreen()
            if (screen == null) { overlay.setAura(false); overlay.hideCard(); return@launch }
            val text = Planner.explain(screen, AppLauncher.labelOf(svc, screen.pkg), lang)
            overlay.setAura(false)
            current = Target(null, text, "explain")
            overlay.showCard(text, Overlay.Mode.LOST)
            speaker.say(text, lang)
            hideJob?.cancel()
            hideJob = scope.launch { delay(15_000); if (goal == null) overlay.hideCard() }
        }
    }

    /**
     * On-call scam alarm (§9.8): a banking/UPI/remote-access app opened while a call is active is the classic
     * "bank officer on the line" fraud. Full stop card, spoken, before they type anything.
     */
    fun callAlarm(appLabel: String) {
        com.saathi.app.DebugLog.i("alert", "on-call alarm for $appLabel")
        lang = Prefs.lang(svc)
        val t = say("Is someone on the phone telling you to open $appLabel? Hang up now. Banks and police never ask you to open apps or share an OTP on a call.",
            "क्या फ़ोन पर कोई आपसे $appLabel खुलवा रहा है? अभी फ़ोन काटिए। बैंक और पुलिस कभी फ़ोन पर ऐप खुलवाते या OTP नहीं माँगते।",
            "ఫోన్‌లో ఎవరైనా $appLabel తెరవమంటున్నారా? వెంటనే ఫోన్ పెట్టేయండి. బ్యాంకులు, పోలీసులు ఎప్పుడూ ఫోన్‌లో యాప్ తెరవమని లేదా OTP అడగరు.").pick(lang)
        hideJob?.cancel()
        current = Target(null, t, "call_alarm", warn = true)
        overlay.highlight(null, false)
        overlay.showCard(t, Overlay.Mode.ALARM)
        speaker.say(t, lang)
        svc.buzz(); svc.buzz()
    }

    /** An incoming message looks like a scam: say so before they act on it. */
    /** Scam shield (com.saathi.app.scam): one warning per (id, app) per minute; STOP ones buzz and offer "back". */
    private val shieldSeen = HashMap<String, Long>()
    fun shieldAlert(w: com.saathi.app.scam.Warning, where: String, from: String? = null) {
        val key = "${w.id}|$where"
        val now = SystemClock.uptimeMillis()
        if ((shieldSeen[key] ?: 0L) > now - 60_000) return
        shieldSeen[key] = now
        lang = Prefs.lang(svc)
        com.saathi.app.DebugLog.i("alert", "shield ${w.id} ${w.level} in $where")
        val head = from?.let { say("A message from $it.", "$it का संदेश।", "$it నుంచి సందేశం.").pick(lang) + " " } ?: ""
        val t = head + w.say.pick(lang)
        hideJob?.cancel()
        current = Target(null, t, "shield_${w.id}", warn = true)
        overlay.highlight(null, false)
        val back: (() -> Unit)? = if (w.safeAction == "back") ({ overlay.hideCard(); svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }) else null
        overlay.showCard(t, Overlay.Mode.WARN, onContinue = back)
        speaker.say(t, lang)
        svc.buzz(); if (w.level == com.saathi.app.scam.Level.STOP) svc.buzz()
    }

    fun messageAlert(sender: String, app: String, hit: MessageScam.Hit) {
        com.saathi.app.DebugLog.i("alert", "message scam ${hit.id} from $app")
        lang = Prefs.lang(svc)
        val head = say("A message from $sender on $app.", "$app पर $sender का संदेश।", "$app లో $sender నుంచి సందేశం.").pick(lang)
        val t = "$head ${hit.say.pick(lang)}"
        hideJob?.cancel()
        current = Target(null, t, "msg_${hit.id}", warn = true)
        overlay.highlight(null, false)
        overlay.showCard(t, Overlay.Mode.WARN)
        speaker.say(t, lang)
        svc.buzz()
    }

    /** "Read my messages": the last three, from RAM only. */
    fun readMessages() {
        lang = Prefs.lang(svc)
        val list = com.saathi.app.service.MessageListener.latest(3)
        val t0 = if (list.isEmpty()) say("No new messages. (If this is wrong, allow Saathi to read notifications in Settings.)",
            "कोई नया संदेश नहीं। (अगर ग़लत है, तो Settings में Saathi को सूचनाएँ पढ़ने दीजिए।)",
            "కొత్త సందేశాలు లేవు. (తప్పైతే, Settings లో Saathi కి నోటిఫికేషన్ అనుమతి ఇవ్వండి.)").pick(lang)
        else say("${list.size} recent messages. ", "${list.size} नए संदेश। ", "${list.size} కొత్త సందేశాలు. ").pick(lang) +
            list.joinToString(" ") { m -> say("${m.sender} on ${m.app} says: ${m.text.take(160)}.", "${m.app} पर ${m.sender} ने लिखा: ${m.text.take(160)}।", "${m.app} లో ${m.sender}: ${m.text.take(160)}.").pick(lang) }
        com.saathi.app.service.MessageListener.clear() // read aloud → gone from memory
        val t = com.saathi.app.policy.Redactor.forSpeech(t0, lang) // an OTP or account number is never read aloud
        current = Target(null, t, "messages")
        overlay.highlight(null, false)
        overlay.showCard(t, Overlay.Mode.INFO)
        speaker.say(t, lang)
        hideJob?.cancel()
        hideJob = scope.launch { delay(30_000); if (goal == null) overlay.hideCard() }
    }

    fun goBack() {
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        scope.launch { delay(700); explain() }
    }

    fun goHome() {
        stop()
        svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        val t = say("You're on the home screen. Tap the Saathi light any time.", "आप होम स्क्रीन पर हैं। कभी भी साथी की रोशनी छूइए।", "మీరు హోమ్ స్క్రీన్‌లో ఉన్నారు. ఎప్పుడైనా సాథీ వెలుగును తాకండి.").pick(lang)
        finish(t)
    }

    /** Prefill a redacted help message to the registered family contact. Saathi never sends it. */
    fun askFamily() { scope.launch { askFamilyNow() } }

    private suspend fun askFamilyNow() {
        lang = Prefs.lang(svc)
        val screen = readScreen()
        val pkg = screen?.pkg ?: ""
        val onOwn = pkg == svc.packageName || pkg.isBlank()
        val title = screen?.elements?.firstOrNull { it.role == "text" && it.label.length in 3..40 }?.label
        val text = FamilyHelp.message(Prefs.name(svc), if (onOwn) "" else AppLauncher.labelOf(svc, pkg), if (onOwn) null else title,
            goal, Planner.isMoneyApp(pkg), lang)
        val intent = FamilyHelp.intent(svc, text)
        if (intent == null) {
            finish(say("No family contact yet. Ask a family member to add their number in Saathi Settings.",
                "अभी परिवार का नंबर नहीं है। परिवार वालों से Saathi की सेटिंग में नंबर जुड़वाइए।",
                "ఇంకా కుటుంబ నంబర్ లేదు. Saathi సెట్టింగ్స్‌లో నంబర్ చేర్చమని కుటుంబ సభ్యుడిని అడగండి.").pick(lang))
            return
        }
        val ok = runCatching { svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        val fam = Prefs.family(svc).ifBlank { say("your family", "परिवार", "మీ కుటుంబం").pick(lang) }
        val t = if (ok) say("I've written a message to $fam. Read it, then press the green send button.",
            "मैंने $fam के लिए संदेश लिख दिया है। पढ़कर हरा भेजें बटन दबाइए।",
            "$fam కి సందేశం రాశాను. చదివి ఆకుపచ్చ పంపు బటన్ నొక్కండి.").pick(lang)
            else say("I couldn't open the message app.", "संदेश ऐप नहीं खुला।", "సందేశ యాప్ తెరుచుకోలేదు.").pick(lang)
        // Pause the task (if any) so we don't guide inside the chat; they can continue afterwards.
        if (goal != null) paused = true
        current = Target(null, t, "family")
        overlay.highlight(null, false)
        overlay.showCard(t, Overlay.Mode.INFO)
        speaker.say(t, lang)
    }

    // ───────────────────────── field-test fixes ─────────────────────────

    /** A page the app maps know, in plain words ("the search results page"), from its screen id's last part. */
    private fun knownPage(pkg: String, root: AccessibilityNodeInfo?): Say? {
        val id = runCatching { com.saathi.app.maps.AppMaps.screenOf(pkg, MapBridge.read(root).nodes) }.getOrNull() ?: return null
        val w = id.substringAfter('_')
        return when {
            w == "home" || w.startsWith("home") || w == "grid" || w == "list" -> say("the main page", "मुख्य पेज", "ప్రధాన పేజీ")
            w.startsWith("results") -> say("the search results page", "खोज के नतीजों वाला पेज", "వెతికిన ఫలితాల పేజీ")
            w.startsWith("search") || w == "typing" -> say("the search page", "खोज वाला पेज", "వెతికే పేజీ")
            w == "watch" || w == "now_playing" || w == "viewer" -> say("the page that's playing it", "चलने वाला पेज", "ప్లే అవుతున్న పేజీ")
            w == "playlist" -> say("a playlist page", "एक प्लेलिस्ट पेज", "ఒక ప్లేలిస్ట్ పేజీ")
            w.startsWith("chat") || w == "thread" -> say("a chat", "एक चैट", "ఒక చాట్")
            w == "subs" -> say("the subscriptions page", "सब्सक्रिप्शन पेज", "సబ్‌స్క్రిప్షన్ల పేజీ")
            w == "history" -> say("the history page", "हिस्ट्री पेज", "హిస్టరీ పేజీ")
            w.startsWith("settings") || w == "page" -> say("a settings page", "एक सेटिंग पेज", "ఒక సెట్టింగ్ పేజీ")
            w == "details" -> say("the app's page in the store", "स्टोर में ऐप का पेज", "స్టోర్‌లో యాప్ పేజీ")
            w == "editor" || w == "crop" -> say("the editing page", "एडिट करने वाला पेज", "ఎడిట్ చేసే పేజీ")
            else -> null
        }
    }

    /** The page's name: the biggest short text near the top that isn't the app's own name (toolbar titles). */
    private fun pageTitle(screen: Screen, app: String): String? {
        val h = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        return screen.elements.filter { e ->
            e.bounds.top in 1..(h * 0.22f).toInt() && e.bounds.height() >= 40 && e.title.length in 2..40 &&
                !e.title.equals(app, true) && e.role != "input" &&
                // Never a view id turned into words ("scrollable list"): only what's written on screen.
                e.node?.viewIdResourceName?.substringAfter('/')?.replace('_', ' ')?.equals(e.title, true) != true &&
                !Regex("(?i)^(back|navigate up|more|more options|search|menu|close|\\d{1,2}:\\d{2}.*)$").matches(e.title)
        }.maxByOrNull { it.bounds.height() * 10 - it.bounds.top / 100 }?.title
    }

    /**
     * "Where am I?" / "I'm lost" (or Saathi sees them going round in circles): the app and the page, what we were
     * doing and its next step, and one tap back: Continue / Back during a task, Back / Start of the app otherwise.
     */
    fun whereAmI(lead: String = "") {
        lang = Prefs.lang(svc)
        val root = appRoot()
        val screen = root?.let { ScreenReader.read(it) }
        val pkg = screen?.pkg.orEmpty()
        if (screen == null || pkg.isBlank() || pkg == launcherPkg()) {
            finish(lead + say("You're on the home screen, where all your apps are. Tell me what you'd like to do.",
                "आप होम स्क्रीन पर हैं, जहाँ सारे ऐप हैं। बताइए क्या करना है।", "మీరు హోమ్ స్క్రీన్‌లో ఉన్నారు, అన్ని యాప్‌లు ఇక్కడే. ఏం చేయాలో చెప్పండి.").pick(lang))
            return
        }
        val app = AppLauncher.labelOf(svc, pkg)
        val known = knownPage(pkg, root)?.pick(lang)
        val title = if (known == null) pageTitle(screen, app) else null
        val here = (when {
            known != null -> say("You're in $app, on $known.", "आप $app में हैं, $known पर।", "మీరు $app లో, $known లో ఉన్నారు.")
            title != null -> say("You're in $app, on the “$title” page.", "आप $app में हैं, “$title” पेज पर।", "మీరు $app లో, “$title” పేజీలో ఉన్నారు.")
            else -> say("You're in $app.", "आप $app में हैं।", "మీరు $app లో ఉన్నారు.")
        }).pick(lang)
        val g = goal
        val step = current?.takeIf { g != null && it.el != null }?.text
        val doing = if (g != null) say(" We were doing “${g.take(50)}”.", " हम “${g.take(50)}” कर रहे थे।", " మనం “${g.take(50)}” చేస్తున్నాం.").pick(lang) +
            (step?.let { " " + say("Next: ", "अगला: ", "తర్వాత: ").pick(lang) + it } ?: "") else ""
        val text = lead + here + doing
        com.saathi.app.DebugLog.i("where", "$pkg page=${known ?: "\"$title\""} goal=${g != null}")
        if (g != null) { setAside = true; autoJob?.cancel() }
        hideJob?.cancel()
        current = Target(null, text, "where_$pkg"); lastSpokenKey = current?.key
        overlay.highlight(null, false)
        val back = Triple(say("Go back", "पीछे जाएँ", "వెనక్కి").pick(lang), com.saathi.app.R.drawable.ic_arrow_back, {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            if (goal != null) { setAside = false; lastSpokenKey = null; lastSig = 0; replan = true; schedule(700, force = true) } else overlay.hideCard()
        })
        // Wandered into another app (an ad opened the Play Store…): the first button takes them back to the task's app.
        val taskApp = (mapRoute?.pkg ?: flow?.appPkg ?: taskPkgs.firstOrNull())?.takeIf { it != pkg && AppLauncher.isInstalled(svc, it) }
        val cont = if (taskApp != null) {
            val name = AppLauncher.labelOf(svc, taskApp)
            name.take(12)   // the button is just the app's name (with the play icon): "Back to YouTube" didn't fit
        } else say("Continue", "जारी रखें", "కొనసాగించు").pick(lang)
        if (g != null) overlay.showChoice(text, Triple(cont, com.saathi.app.R.drawable.ic_play_circle, {
            taskApp?.let { AppLauncher.launch(svc, it)?.let { i -> runCatching { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
            setAside = false; lastSpokenKey = null; lastSig = 0; replan = true; schedule(if (taskApp != null) 900 else 0, force = true) }), back)
        else overlay.showChoice(text, back, Triple(say("App start", "शुरू से", "మొదటి నుంచి").pick(lang), com.saathi.app.R.drawable.ic_home, {
            overlay.hideCard()
            AppLauncher.launch(svc, pkg)?.let { i -> runCatching { svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) } }
        }))
        speaker.say(text, lang)
    }

    /** Nothing sensible to tap here: say so and offer the ways out, instead of wandering. */
    private fun unsure(fp: Int) {
        if (current?.key == "unsure_$fp") return
        stopAuto()
        val t = say("I'm not sure what to tap here for “${goal?.take(40)}”. Tell me in other words, go back, or ask family.",
            "यहाँ “${goal?.take(40)}” के लिए क्या दबाना है, मुझे पक्का नहीं पता। दूसरे शब्दों में बताइए, वापस जाइए, या परिवार से पूछिए।",
            "ఇక్కడ “${goal?.take(40)}” కోసం ఏం నొక్కాలో నాకు ఖచ్చితంగా తెలియదు. వేరే మాటల్లో చెప్పండి, వెనక్కి వెళ్ళండి, లేదా కుటుంబాన్ని అడగండి.").pick(lang)
        com.saathi.app.DebugLog.i("unsure", "fp=$fp plans=$plansThisTask")
        current = Target(null, t, "unsure_$fp")
        lastSpokenKey = current?.key
        overlay.highlight(null, false)
        overlay.showCard(t, Overlay.Mode.LOST)
        speaker.say(t, lang)
    }

    /** Sign-in / first-run screens: only the person (or family) should do these. Explain once, point at the button. */
    private fun showWall(w: ScreenKinds.Wall, screen: Screen) {
        stopAuto()
        val app = AppLauncher.labelOf(svc, screen.pkg)
        val t = if (w.setup) say("$app is asking you to log in with your phone number and a code sent by SMS. Only you, or family, should type these. I'll wait here.",
                "$app आपका फ़ोन नंबर और SMS पर आया कोड माँग रहा है। इन्हें सिर्फ़ आप या परिवार वाले लिखें। मैं यहीं इंतज़ार करूँगा।",
                "$app మీ ఫోన్ నంబర్, SMS లో వచ్చే కోడ్ అడుగుతోంది. వీటిని మీరు లేదా కుటుంబం మాత్రమే టైప్ చేయాలి. నేను ఇక్కడే వేచి ఉంటాను.").pick(lang)
            else say("$app needs you to sign in first. Only you should type your password. Tap “${w.button?.title ?: "Sign in"}”.",
                "$app में पहले साइन इन करना होगा। पासवर्ड सिर्फ़ आप लिखिए। “${w.button?.title ?: "Sign in"}” दबाइए।",
                "$app లో ముందు సైన్ ఇన్ చేయాలి. పాస్‌వర్డ్ మీరే టైప్ చేయండి. “${w.button?.title ?: "Sign in"}” నొక్కండి.").pick(lang)
        com.saathi.app.DebugLog.i("wall", "${screen.pkg} setup=${w.setup} button=${w.button?.title}")
        if (screen.pkg.startsWith("com.whatsapp")) Prefs.setWaNotSetUp(svc, true)
        show(Target(if (w.setup) null else w.button, t, "wall_${screen.pkg}", noAct = w.setup))
    }

    /** The task's app isn't installed: offer the Play Store (their choice), never wander elsewhere. */
    private fun missingApp(f: Flow) {
        val pkg = f.appPkg
        val name = pkg?.let { KNOWN_APPS[it] } ?: say("That app", "वह ऐप", "ఆ యాప్").pick(lang)
        goal = null; flow = null; taskPkgs.clear(); watchdog?.cancel()
        val t = say("$name isn't on this phone.${if (pkg != null) " Shall I open the Play Store so you can get it?" else ""}",
            "$name इस फ़ोन में नहीं है।${if (pkg != null) " Play Store खोलूँ ताकि आप इसे ले सकें?" else ""}",
            "$name ఈ ఫోన్‌లో లేదు.${if (pkg != null) " దాన్ని పొందడానికి Play Store తెరవనా?" else ""}").pick(lang)
        com.saathi.app.DebugLog.i("missing", "app=$pkg flow=${f.id}")
        current = Target(null, t, "missing")
        if (pkg != null) overlay.showCard(t, Overlay.Mode.ASK, onContinue = { overlay.hideCard(); installApp(pkg, name) })
        else overlay.showCard(t, Overlay.Mode.DONE)
        speaker.say(t, lang)
    }

    /**
     * Get a missing app, guided: its Play Store page → the person taps Install (never Saathi) → "installing, please
     * wait" → Open → "want me to show you how to use it?".
     */
    private fun installApp(pkg: String, name: String) {
        // The https form addressed to Play Store avoids vivo's own store intercepting "market://" with a picker.
        val f = Flow("install_$pkg", { Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).setPackage("com.android.vending") },
            listOf(
                // If the phone still asks which store: Android's own picker, explained (the pivot: Saathi explains Android).
                Step("store", rx("^Google Play Store$", "^Play Store$"), say("Your phone is asking which store to use. Tap Google Play Store.",
                    "फ़ोन पूछ रहा है कौन-सा स्टोर। Google Play Store दबाइए।", "ఏ స్టోర్ వాడాలని ఫోన్ అడుగుతోంది. Google Play Store నొక్కండి."),
                    screenHas = Regex("(?i)open with|just once|always")),
                Step("once", rx("^Just once$"), say("Now tap Just once.", "अब Just once दबाइए।", "ఇప్పుడు Just once నొక్కండి.")),
                Step("install", rx("^Install$", "^Update$"), say("Tap the green Install button. $name is free; it takes a minute.",
                    "हरा Install बटन दबाइए। $name मुफ़्त है; एक मिनट लगेगा।", "ఆకుపచ్చ Install బటన్ నొక్కండి. $name ఉచితం; ఒక నిమిషం పడుతుంది.")),
                Step("open", rx("^Open$", "^Play$"), say("$name is ready. Tap Open.", "$name तैयार है। Open दबाइए।", "$name సిద్ధం. Open నొక్కండి.")),
            ),
            { sc -> sc.pkg == pkg },
            say("$name is open!", "$name खुल गया!", "$name తెరుచుకుంది!"),
            say("Let's get $name from the Play Store.", "Play Store से $name लेते हैं।", "Play Store నుంచి $name తీసుకుందాం."),
            teach = true, appPkg = "com.android.vending",
            onDone = { c -> scope.launch { delay(1800); offerLearn(name) } })
        begin("install $name", f, autoMode = false)
    }

    /** After an install: "Want me to show you how to use it?" → the learn guide for that app. */
    private fun offerLearn(name: String) {
        val q = say("Want me to show you how to use $name?", "क्या मैं $name चलाना सिखाऊँ?", "$name ఎలా వాడాలో చూపించనా?").pick(lang)
        current = Target(null, q, "offer_learn"); lastSpokenKey = current?.key
        overlay.showCard(q, Overlay.Mode.ASK, onContinue = { overlay.hideCard(); learnTask("how do I use $name") })
        speaker.say(q, lang)
    }

    private val KNOWN_APPS = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.youtube" to "YouTube", "com.netflix.mediaclient" to "Netflix",
        "in.startv.hotstar" to "JioHotstar", "com.jio.media.ondemand" to "JioCinema", "com.graymatrix.did" to "Zee5",
        "com.sonyliv" to "SonyLIV", "com.amazon.avod.thirdpartyclient" to "Prime Video", "com.spotify.music" to "Spotify", "com.ubercab" to "Uber", "cris.org.in.prs.ima" to "IRCTC Rail Connect", "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.apps.photos" to "Google Photos", "com.google.android.apps.nbu.paisa.user" to "Google Pay", "com.phonepe.app" to "PhonePe")

    /** A question or chit-chat: answer out loud (the Clicky lesson), no screen navigation. */
    /** Keyboard + the box being typed in: the card must cover neither. */
    private fun updateIme() = runCatching {
        val ime = svc.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val imeTop = ime?.let { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) }.top }
        val focus = if (ime != null) svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.let { n -> android.graphics.Rect().also { n.getBoundsInScreen(it) } } else null
        overlay.setImeVisible(ime != null, imeTop, focus)
    }

    /** A mapped route for this request, if its app is on the phone (or it's the Play Store journey). */
    private fun mapRouteFor(g: String): com.saathi.app.maps.Route? = runCatching {
        com.saathi.app.maps.AppMaps.route(g)?.takeIf { r -> AppLauncher.isInstalled(svc, r.pkg) }
    }.getOrNull()

    /** Teach-once: "watch me: video call Rahul" … "done teaching". true = handled. */
    private fun teachOnce(goalText: String): Boolean {
        Regex("(?i)^\\s*(watch me|learn this|let me show you|i'?ll show you|देखो मैं|मैं दिखाता|నేను చూపిస్తా)\\W*(.*)$").find(goalText)?.let { m ->
            val name = m.groupValues[2].trim().ifBlank { "my task" }
            stop()
            Recipes.startRecording(name)
            com.saathi.app.DebugLog.i("teach", "recording \"$name\"")
            finish(say("I'm watching. Do “$name” now; say “done teaching” when finished.", "मैं देख रहा हूँ। “$name” करके दिखाइए; ख़त्म होने पर “सिखा दिया” कहिए।",
                "నేను చూస్తున్నాను. “$name” చేసి చూపించండి; అయ్యాక “నేర్పించాను” అనండి.").pick(lang))
            return true
        }
        if (Recipes.recording != null && Regex("(?i)done teaching|finished|that's it|सिखा दिया|हो गया|నేర్పించాను|అయింది").containsMatchIn(goalText)) {
            val r = Recipes.stopRecording(svc)
            com.saathi.app.DebugLog.i("teach", "saved ${r?.name} taps=${r?.taps?.map { it.label }}")
            finish(if (r != null) say("Learned “${r.name}” in ${r.taps.size} steps. Anyone can ask me for it now.", "“${r.name}” सीख लिया, ${r.taps.size} क़दम। अब कोई भी मुझसे पूछ सकता है।",
                "“${r.name}” నేర్చుకున్నాను, ${r.taps.size} అడుగులు. ఇప్పుడు ఎవరైనా అడగవచ్చు.").pick(lang)
                else say("I didn't see any taps, so nothing was saved.", "कोई टैप नहीं दिखा, कुछ सेव नहीं हुआ।", "ఏ ట్యాప్ కనిపించలేదు, ఏదీ సేవ్ కాలేదు.").pick(lang))
            return true
        }
        return false
    }

    /** The newest screenshot on the phone (last 2 minutes), or null. Needs READ_MEDIA_IMAGES (granted at install). */
    private fun latestScreenshot(): android.net.Uri? = runCatching {
        val col = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val since = System.currentTimeMillis() / 1000 - 120
        svc.contentResolver.query(col, arrayOf(android.provider.MediaStore.Images.Media._ID),
            "${android.provider.MediaStore.Images.Media.DATE_ADDED} >= ? AND (${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)",
            arrayOf(since.toString(), "%Screenshot%", "%Screenshot%"), "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) android.content.ContentUris.withAppendedId(col, c.getLong(0)) else null
        }
    }.onFailure { com.saathi.app.DebugLog.w("share", "screenshot lookup failed", it) }.getOrNull()

    /** "…send it to my son on WhatsApp": WhatsApp's own picker with the screenshot → tap him → the green arrow (theirs). */
    private fun screenshotShareFlow(g: String): Flow? = latestScreenshot()?.let { uri ->
        shareImageFlow(uri, g, say("It's your screenshot. Tap the green send arrow.", "यह आपका स्क्रीनशॉट है। हरा भेजें वाला तीर दबाइए।",
            "ఇది మీ స్క్రీన్‌షాట్. ఆకుపచ్చ పంపు బాణం నొక్కండి."), say("Here's your screenshot in WhatsApp.", "WhatsApp में आपका स्क्रीनशॉट तैयार है।",
            "WhatsApp లో మీ స్క్రీన్‌షాట్ సిద్ధంగా ఉంది."))
    }

    /** A picture on the phone → WhatsApp's own Send-to picker → tap them → the green arrow (always their tap). */
    private fun shareImageFlow(uri: android.net.Uri, g: String, sendSay: Say, startSay: Say): Flow? {
        val wa = AppLauncher.first(svc, "com.whatsapp", "com.whatsapp.w4b") ?: return null
        val who = SlotExtractor.from(g, Prefs.family(svc)).contact ?: Prefs.family(svc).ifBlank { null }
        com.saathi.app.DebugLog.i("share", "screenshot → $wa for ${who ?: "(they choose)"}")
        val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).setPackage(wa)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val steps = listOfNotNull(
            who?.let { w -> Step("pick", listOf(Regex("(?i)^" + Regex.escape(w))), say("Tap $w in the list.", "सूची में $w को दबाइए।", "జాబితాలో $w ని నొక్కండి."),
                unlessVisible = listOf(Regex("(?i)^(Add a caption|Add caption)"))) },
            // Only once THEIR person is the one ticked (the picker keeps earlier ticks: field 07:57 "to other" went to Akash).
            Step("send", listOf(Regex("(?i)^Send$")), sendSay, screenHas = who?.let { Regex("(?i)" + Regex.escape(it.split(" ").first())) }),
        )
        return Flow("share_screenshot", { send }, steps,
            { sc -> sc.pkg.startsWith("com.whatsapp") && sc.elements.any { Regex("(?i)^(Message|Type a message)$").matches(it.label) } &&
                sc.elements.none { Regex("(?i)^(Add a caption|Add caption)").containsMatchIn(it.label) } },
            say("Sent! It's in the chat now.", "भेज दिया! अब चैट में है।", "పంపారు! ఇప్పుడు చాట్‌లో ఉంది."),
            startSay, llmGoal = g, appPkg = wa)
    }

    /** "Show / send my Aadhaar card": find the picture (reading the phone's photos if needed), then Photos or WhatsApp. */
    private fun docRequest(g: String, a: DocFinder.Ask) {
        lang = Prefs.lang(svc)
        stop()
        val name = a.kind.name(lang)
        overlay.showCard(say("Looking through your photos for your $name…", "आपकी फ़ोटो में $name ढूँढ रहा हूँ…", "మీ ఫోటోల్లో $name వెతుకుతున్నాను…").pick(lang), Overlay.Mode.THINKING)
        scope.launch {
            var uri = DocFinder.find(svc, a.kind)
            if (uri == null) { DocFinder.index(svc); uri = DocFinder.find(svc, a.kind) }
            com.saathi.app.DebugLog.i("docs", "${a.kind} → ${uri != null} send=${a.send}")
            if (uri == null) {
                finish(say("I couldn't find a photo of your $name on this phone. If you have it on paper, I can read it with the camera.",
                    "इस फ़ोन में आपके $name की फ़ोटो नहीं मिली। काग़ज़ पर है तो मैं कैमरे से पढ़ सकता हूँ।",
                    "ఈ ఫోన్‌లో మీ $name ఫోటో దొరకలేదు. కాగితం మీద ఉంటే కెమెరాతో చదవగలను.").pick(lang))
                return@launch
            }
            val secret = a.kind == DocFinder.Kind.AADHAAR || a.kind == DocFinder.Kind.PAN || a.kind == DocFinder.Kind.VOTER || a.kind == DocFinder.Kind.LICENCE
            val careful = if (secret) say("This is your $name. Send it only to family you trust. ", "यह आपका $name है। सिर्फ़ भरोसेमंद परिवार को भेजिए। ",
                "ఇది మీ $name. నమ్మకమైన కుటుంబానికి మాత్రమే పంపండి. ") else say("This is your $name. ", "यह आपका $name है। ", "ఇది మీ $name. ")
            val sendIt = {
                shareImageFlow(uri, g, careful.mapValues { (l, v) -> v + say("Tap the green send arrow.", "हरा भेजें वाला तीर दबाइए।", "ఆకుపచ్చ పంపు బాణం నొక్కండి.")[l] },
                    say("Here's your $name in WhatsApp.", "WhatsApp में आपका $name तैयार है।", "WhatsApp లో మీ $name సిద్ధంగా ఉంది."))?.let { begin(g, it, false) }
            }
            if (a.send) { overlay.hideCard(); sendIt(); return@launch }
            // Show it in their own gallery app (Google Photos), then offer the natural next step.
            val photos = AppLauncher.first(svc, "com.google.android.apps.photos")
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { photos?.let { setPackage(it) } }
            runCatching { svc.startActivity(view) }.onFailure { runCatching { svc.startActivity(view.setPackage(null)) } }
            delay(1200)
            val fam = Prefs.family(svc).ifBlank { null }
            val text = say("Here's your $name. It's in your photos, so you can find it here any time.", "यह रहा आपका $name। यह आपकी फ़ोटो में है, कभी भी यहाँ देख सकते हैं।",
                "ఇదిగో మీ $name. ఇది మీ ఫోటోల్లో ఉంది, ఎప్పుడైనా ఇక్కడ చూడవచ్చు.").pick(lang)
            finish(text, fam?.let { f -> Triple(say("Send to $f", "$f को भेजें", "$f కి పంపు").pick(lang), com.saathi.app.R.drawable.ic_send, { sendIt(); Unit }) })
        }
    }

    /** "take a screenshot and send it to Akash on WhatsApp" → "send this photo to Akash on WhatsApp"; null = nothing after. */
    private fun screenshotThen(g: String): String? {
        if (!Regex("(?i)send|share|whatsapp|भेज|शेयर|పంప|షేర్").containsMatchIn(g)) return null
        return g.replace(Regex("(?i)(take|capture|click)\\s+(a\\s+|the\\s+)?"), "")
            .replace(Regex("(?i)screenshot|स्क्रीनशॉट|స్క్రీన్‌?షాట్"), "photo")
            .replace(Regex("(?i)^\\s*(a\\s+)?photo\\s+(and|then|और|తీసి)\\s+"), "")
            .replace(Regex("(?i)\\bsend it\\b"), "send this photo")
            .trim().ifBlank { null }
    }

    /** An open-ended task in [pkg] (or right here when null): no script, the planner reads each screen. */
    private fun plannerTask(g: String, pkg: String?): Flow = Flow(
        "here_${pkg ?: "screen"}", pkg?.let { p -> { c: android.content.Context -> AppLauncher.launch(c, p) } }, emptyList(), null,
        say("Done!", "हो गया!", "అయింది!"),
        say("Okay, let's do it from here. Watch for the ring.", "ठीक है, यहीं से करते हैं। घेरे को देखिए।", "సరే, ఇక్కడి నుంచే చేద్దాం. రింగ్ చూడండి."),
        llmGoal = g, appPkg = pkg)

    /** "Book a cab to this location" (in a chat), "send this photo" (in the gallery): start where they are. */
    private fun hereTask(g: String): Flow? {
        // Only the request itself, not the words of a message ("…saying I reached here").
        val ask = SlotExtractor.from(g).text?.let { g.replace(it, " ") } ?: g
        if (!DEICTIC.containsMatchIn(ask)) return null
        val here = runCatching { appRoot()?.packageName?.toString() }.getOrNull() ?: return null
        if (here == launcherPkg() || here == svc.packageName || here == "com.android.settings" || here.contains("systemui")) return null
        // A precise instant skill ("make this text bigger" → font size) still wins.
        if (Skills.match(g)?.id in IntentRouter.DIRECT) return null
        com.saathi.app.DebugLog.i("route", "about this screen ($here): planner from here")
        return plannerTask(g, null)
    }

    /** "Show my liked videos on YouTube": the person's own things are never a search query. Planner inside the app. */
    private fun ownThingsTask(g: String): Flow? {
        if (!OWN_THINGS.containsMatchIn(g)) return null
        val r = mapRouteFor(g) ?: return null
        if ("query" !in r.slots) return null   // "message my son" etc: the map's own route is right
        com.saathi.app.DebugLog.i("route", "own things, not a search (${r.id} skipped): planner in ${r.pkg}")
        return plannerTask(g, r.pkg)
    }

    private fun beginMap(g: String, r: com.saathi.app.maps.Route, autoMode: Boolean) {
        val map = com.saathi.app.maps.AppMaps.mapOf(r)
        com.saathi.app.DebugLog.i("map", "route ${r.id} (${map?.name})")
        mapRoute = r; mapSlots = com.saathi.app.maps.MapSlots.of(r, g, Prefs.family(svc)); mapStep = -1
        // Reuse the flow machinery for launch / learn-mode / Settings rules; steps come from the map.
        val f = Flow("map_${r.id}", { c -> AppLauncher.launch(c, r.pkg) }, emptyList(), null,
            r.doneSay, r.start ?: say("Let's do it together. Watch for the ring.", "साथ में करते हैं। घेरे को देखिए।", "కలిసి చేద్దాం. రింగ్ చూడండి."),
            teach = true, llmGoal = g, appPkg = r.pkg)
        begin(g, f, autoMode)
        r.steps.mapNotNull { it.pkg }.forEach { taskPkgs += it }
    }

    /** One map decision for this screen. true = handled (shown / waited / done); false = not mapped → planner. */
    private fun mapTick(r: com.saathi.app.maps.Route, screen: Screen): Boolean {
        // The app's own window, never Saathi's mic sheet / card (field: the sheet's nodes read as "unknown screen" and
        // the planner ran on YouTube mid-route). Not readable yet → wait for the next event.
        val root = svc.rootInActiveWindow?.takeIf { it.packageName?.toString() == screen.pkg }
            ?: runCatching { svc.windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == screen.pkg } }.getOrNull()
            ?: return true
        val live = MapBridge.read(root)
        // "Done" only after the route's last step was really reached (field test: a Short's Like button on YouTube's
        // home screen made the search route "done" before a single step).
        val rr = if (r.doneNeedsLastStep) r else r.copy(doneNeedsLastStep = true)
        val t0 = SystemClock.elapsedRealtime()
        val d = runCatching { com.saathi.app.maps.AppMaps.next(rr, screen.pkg, live.nodes, maxOf(mapStep, 0), mapSlots) }.getOrNull() ?: return false
        if (d is com.saathi.app.maps.Decision.Glow && d.step != mapStep)
            com.saathi.app.llm.AiMeter.record("CPU", "App map", "${com.saathi.app.maps.AppMaps.mapOf(r)?.name ?: ""} #${d.step + 1}", SystemClock.elapsedRealtime() - t0)
        // The next target is hidden and a nag popup is on top: point at its "Maybe later" first, in any app.
        if (d !is com.saathi.app.maps.Decision.Glow && d !is com.saathi.app.maps.Decision.Done) ScreenKinds.nag(screen)?.let { later ->
            show(Target(later, say("A popup is in the way. Tap “${later.title}” to close it.", "एक पॉपअप बीच में है। बंद करने के लिए “${later.title}” दबाइए।",
                "ఒక పాపప్ అడ్డం వచ్చింది. మూసేయడానికి “${later.title}” నొక్కండి.").pick(lang), "nag_${later.title}"))
            return true
        }
        when (d) {
            is com.saathi.app.maps.Decision.Glow -> {
                // Only what a finger can hit: ≥60 px above the navigation bar (field: a 130 px sliver of a playlist
                // row behind the nav bar was glowed). Otherwise it's just below: scroll a little.
                val usable = (android.content.res.Resources.getSystem().displayMetrics.heightPixels * 0.955f).toInt()
                if (minOf(d.box.b, usable) - d.box.t < 60) {
                    show(Target(null, say("Slowly scroll down a little.", "धीरे से थोड़ा नीचे स्क्रॉल कीजिए।", "నెమ్మదిగా కొంచెం కిందకు స్క్రోల్ చేయండి.").pick(lang), "map_scroll_${d.step}", scroll = true))
                    return true
                }
                if (d.step > mapStep) mapStep = d.step
                // The box already says what we wanted (typed, or no suggestion matches it exactly): the next move is the
                // keyboard's search / enter key, and "Do it" presses it (field: YouTube stayed on "Type …" forever).
                val fillNow = d.fill?.trim()
                if (fillNow != null && d.node.editable && d.node.text?.trim()?.equals(fillNow, ignoreCase = true) == true) {
                    val box = MapBridge.uiElement(d.node, live.infoFor(d.node), "input")
                    show(Target(box, say("Now press the search key on the keyboard, at the bottom right.", "अब कीबोर्ड पर नीचे दाईं ओर खोज वाला बटन दबाइए।",
                        "ఇప్పుడు కీబోర్డ్‌లో కింద కుడివైపు సెర్చ్ బటన్ నొక్కండి.").pick(lang), "submit_${r.id}", fill = "__ENTER__"))
                    return true
                }
                val el = MapBridge.uiElement(d.node, live.infoFor(d.node), if (d.node.editable) "input" else "button")
                    .copy(bounds = android.graphics.Rect(d.box.l, d.box.t, d.box.r, d.box.b))
                val teach = Prefs.teach(svc) || learn
                val text = d.say.pick(lang).let { t -> if (practice) say("Your turn. ", "अब आपकी बारी। ", "ఇప్పుడు మీ వంతు. ").pick(lang) + t else t }
                show(Target(el, text, "map_${r.id}_${d.step}", d.fill, noAct = d.risky,
                    tip = if (teach) d.why?.pick(lang) else null, progress = (d.step + 1) to r.steps.size), practiced = practice)
                stepWhy = d.why?.pick(lang)
            }
            is com.saathi.app.maps.Decision.Scroll -> show(Target(null, d.hint.pick(lang), "map_scroll_${d.step}", scroll = true))
            is com.saathi.app.maps.Decision.WrongScreen -> {
                val back = d.node?.let { MapBridge.uiElement(it, live.infoFor(it), "button") }
                show(Target(back, d.backHint.pick(lang), "map_back_${d.expect}", noAct = true))
            }
            is com.saathi.app.maps.Decision.Wait -> show(Target(null, d.say.pick(lang), "map_wait", noAct = true))
            is com.saathi.app.maps.Decision.Done -> {
                com.saathi.app.DebugLog.i("map", "done ${r.id}")
                val f = flow; mapRoute = null
                val newApp = mapSlots["query"] ?: mapSlots["app"]
                if (r.pkg == "com.android.vending" && newApp != null && r.id.contains("install", true)) {
                    // A new app is in: the next natural step is learning it.
                    Memory.completed(f?.id ?: r.id)
                    val pending = proPending; proPending = null
                    val newPkg = AppLauncher.findInGoal(svc, newApp)?.pkg
                    if (pending != null && newPkg != null) finish(com.saathi.app.maps.AppMaps.fillIn(r.doneSay, mapSlots).pick(lang),
                        Triple(say("Set it up now", "अब सेट करें", "ఇప్పుడు సెట్ చేద్దాం").pick(lang), com.saathi.app.R.drawable.ic_play_circle,
                            { begin(pending, plannerTask(pending, newPkg), false) }))
                    else finish(com.saathi.app.maps.AppMaps.fillIn(r.doneSay, mapSlots).pick(lang),
                        Triple(say("Show me how to use it", "इसे चलाना सिखाओ", "దీన్ని వాడటం నేర్పు").pick(lang), com.saathi.app.R.drawable.ic_school,
                            { learnTask("how do I use $newApp") }))
                } else if (r.id.startsWith("settings_") && enterSettle(r, screen)) {
                    // The page is open: now its own control, then "is it good like this?".
                } else if (f != null) complete(f) else finish(r.doneSay.pick(lang))
            }
            is com.saathi.app.maps.Decision.Unknown -> return false
        }
        return true
    }

    // ───────── Settings: the last step is the page's own control ─────────

    /** The page's main control: a slider, else the switch for this topic, else a list of choices. */
    private fun settleControl(screen: Screen, term: String): Pair<String, UiElement?>? {
        val words = term.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }
        // A slider only for size / brightness / volume topics (field: "ringtone" glowed a volume slider).
        if (Regex("(?i)font|size|text|bright|volume|loud").containsMatchIn(term)) screen.elements.firstOrNull { it.role == "slider" }?.let { return "slider" to it }
        // The row that names the topic ("Incoming call ringtone", "Wallpaper", "Language").
        screen.elements.firstOrNull { e -> e.role == "button" && e.bounds.top > 300 && words.any { e.title.lowercase().contains(it) } }?.let { return "row" to it }
        screen.elements.firstOrNull { e -> e.role == "switch" && words.any { e.label.lowercase().contains(it) } }?.let { return "switch" to it }
        if (screen.elements.count { it.role == "switch" } >= 3) return "list" to screen.scrollable()
        screen.elements.filter { it.role == "switch" }.takeIf { it.size in 1..2 }?.first()?.let { return "switch" to it }
        return null
    }

    /** What the control says now: the slider's value, the switch's state, which choice is ticked. */
    private fun settleValue(kind: String, screen: Screen, el: UiElement?): String = when (kind) {
        "slider" -> el?.node?.let { n -> runCatching { n.refresh(); n.rangeInfo?.current?.toString() }.getOrNull() } ?: ""
        "switch" -> el?.node?.let { n -> runCatching { n.refresh(); n.isChecked.toString() }.getOrNull() } ?: el?.checked.toString()
        else -> screen.elements.filter { it.role == "switch" && it.checked }.joinToString { it.title }
    }

    /** The words next to a slider that name the level ("Large"): read out after a change. */
    private fun settleLevel(screen: Screen, el: UiElement?): String? = el?.let { c ->
        screen.elements.filter { it.role == "text" && it.label.length in 2..20 && it.bounds.bottom <= c.bounds.top + 10 && c.bounds.top - it.bounds.bottom < 260 }
            .maxByOrNull { it.bounds.bottom }?.label
    }

    private fun enterSettle(r: com.saathi.app.maps.Route, screen: Screen): Boolean {
        val term = mapSlots["term"] ?: return false
        val (kind, el) = settleControl(screen, term) ?: return false
        val lead = com.saathi.app.maps.AppMaps.fillIn(r.doneSay, mapSlots).pick(lang)
        val how = when (kind) {
            "slider" -> say(" Watch the words on this page change as you move it.", " सरकाते ही इस पेज के अक्षर बदलते दिखेंगे।",
                " జరుపుతుంటే ఈ పేజీలోని అక్షరాలు మారడం చూడండి.")
            "switch" -> say(" Tap the glowing switch to turn it on or off.", " चमकता स्विच दबाकर चालू या बंद कीजिए।", " మెరుస్తున్న స్విచ్ నొక్కి ఆన్ లేదా ఆఫ్ చేయండి.")
            "row" -> say(" Tap “${el?.title}” to see the choices.", " चुनने के लिए “${el?.title}” दबाइए।", " ఎంచుకోవడానికి “${el?.title}” నొక్కండి.")
            else -> say(" Tap the one you like. You can change it again any time.", " जो पसंद हो उसे दबाइए। बाद में कभी भी बदल सकते हैं।", " నచ్చినదాన్ని నొక్కండి. తర్వాత ఎప్పుడైనా మార్చవచ్చు.")
        }.pick(lang)
        val v = settleValue(kind, screen, el)
        settle = Settle(r.id, kind, el?.title ?: "", lead + how, v, v)
        com.saathi.app.DebugLog.i("settle", "${r.id}: $kind \"${el?.title}\" = $v")
        show(Target(el, lead + how, "settle_${r.id}", noAct = true))
        return true
    }

    /** true = handled. They change it with their own finger; once it rests, "is it good like this?". */
    private fun settleTick(st: Settle, screen: Screen): Boolean {
        if (screen.pkg != "com.android.settings") { settle = null; finish(say("Okay.", "ठीक है।", "సరే.").pick(lang)); return true }
        val (kind, el) = settleControl(screen, mapSlots["term"] ?: "") ?: return true
        if (kind != st.kind) {
            // The row opened its page (the ringtone list…): now "tap the one you like" and watch which one is ticked.
            st.kind = kind; st.asked = false
            st.say = say("Tap the one you like. You can change it again any time.", "जो पसंद हो उसे दबाइए। बाद में कभी भी बदल सकते हैं।",
                "నచ్చినదాన్ని నొక్కండి. తర్వాత ఎప్పుడైనా మార్చవచ్చు.").pick(lang)
            settleValue(kind, screen, el).let { st.start = it; st.last = it }
            com.saathi.app.DebugLog.i("settle", "${st.routeId}: now $kind")
        }
        val v = settleValue(kind, screen, el)
        val now = SystemClock.uptimeMillis()
        if (v != st.last) { st.last = v; st.changedAt = now; st.asked = false; schedule(1600, force = true) }
        if (st.last != st.start && !st.asked && now - st.changedAt >= 1500) {
            st.asked = true
            val level = if (kind == "slider") settleLevel(screen, el) else null
            val q = (if (level != null) say("Now it's “$level”. ", "अब “$level” है। ", "ఇప్పుడు “$level”. ").pick(lang) else "") +
                say("Is it good like this?", "क्या ऐसे ठीक है?", "ఇలా బాగుందా?").pick(lang)
            com.saathi.app.DebugLog.i("settle", "${st.routeId}: changed ${st.start} → ${st.last} (${level ?: "-"})")
            current = Target(el, q, "settle_ask_${st.routeId}"); lastSpokenKey = current?.key
            overlay.highlight(null, false)
            overlay.showChoice(q,
                Triple(say("It's good", "ठीक है", "బాగుంది").pick(lang), com.saathi.app.R.drawable.ic_check, {
                    settle = null; flow?.let { complete(it) } ?: finish(say("Done!", "हो गया!", "అయింది!").pick(lang)) }),
                Triple(say("Change", "बदलना है", "మార్చాలి").pick(lang), com.saathi.app.R.drawable.ic_touch_app, {
                    st.start = st.last; lastSpokenKey = null; lastSig = 0; schedule(0, force = true) }))
            speaker.say(q, lang)
            return true
        }
        if (st.asked) return true   // the question is up: wait for their answer
        show(Target(el, st.say, "settle_${st.routeId}", noAct = true))
        // vivo's slider sends no event when it moves: look again every second while we wait for their change.
        schedule(1000, force = true)
        return true
    }

    /** "Help me fill this form": a form on screen → guide it box by box; otherwise the paper-form camera. */
    fun formHelp() {
        lang = Prefs.lang(svc)
        // Asked by voice, Saathi's own mic sheet can still be the active window for a moment: that screen has no
        // boxes, so every "fill this form on my screen" went to the camera. Look at the app underneath, after a beat.
        // A window hidden behind the sheet isn't even listed: wait (up to 2.5 s) until the sheet has gone.
        scope.launch {
            var waited = 0
            while (waited < 2500 && (SaathiService.ownUiOpen || svc.rootInActiveWindow?.packageName?.toString() == svc.packageName)) { delay(150); waited += 150 }
            if (waited > 0) delay(250)
            formHelpNow()
        }
    }

    /** The app window under Saathi's own UI (the active one if it isn't ours). */
    private fun appRoot(): AccessibilityNodeInfo? {
        val own = svc.packageName
        svc.rootInActiveWindow?.takeIf { it.packageName?.toString() != own }?.let { return it }
        return runCatching {
            svc.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() != own }
        }.getOrNull()
    }

    private fun formHelpNow() {
        val root = appRoot()
        // The page's own boxes only: never a browser's address / search bar.
        val fields = com.saathi.app.forms.FormNodes.fields(root).filterNot { (f, n) ->
            Regex("(?i)url_bar|omnibox|location_bar|search_box|search_src_text").containsMatchIn(n.viewIdResourceName ?: "") ||
                Regex("(?i)connection is secure|search or type|search google|address bar|type url").containsMatchIn("${f.label} ${f.hint}")
        }
        if (fields.size < 2) { com.saathi.app.DebugLog.i("form", "paper form → camera"); com.saathi.app.ui.FormActivity.start(svc); return }
        val plan = com.saathi.app.forms.OnlineForm.plan(fields.map { it.first }, com.saathi.app.forms.FormProfile.load(svc))
        com.saathi.app.DebugLog.i("form", "online form: ${plan.size} boxes")
        stop()
        goal = "fill this form"; form = plan; formI = 0
        taskPkgs += (root?.packageName?.toString() ?: "")
        speaker.say(say("I'll show you this form one box at a time. I never press Submit.", "मैं यह फ़ॉर्म एक-एक डिब्बा दिखाऊँगा। Submit मैं कभी नहीं दबाता।",
            "ఈ ఫారం ఒక్కో బాక్స్ చూపిస్తాను. Submit నేను ఎప్పుడూ నొక్కను.").pick(lang), lang)
        showFormStep()
    }

    private fun showFormStep() {
        val plan = form ?: return
        val p = plan.getOrNull(formI) ?: run {
            form = null
            finish(say("That's every box. Read it once more, then press Submit yourself.", "सारे डिब्बे हो गए। एक बार फिर पढ़िए, फिर Submit ख़ुद दबाइए।",
                "అన్ని బాక్స్‌లు అయ్యాయి. ఒకసారి మళ్ళీ చదివి, Submit మీరే నొక్కండి.").pick(lang))
            return
        }
        val b = p.node.box
        val el = UiElement(-1, p.node.label ?: p.node.hint ?: "box", "input", android.graphics.Rect(b.l, b.t, b.r, b.b), true, p.node.password, false, false, null)
        show(Target(el, p.say.pick(lang), "form_$formI", fill = p.value, noAct = p.sensitive || p.value == null, progress = (formI + 1) to plan.size))
    }

    /** "Do it" on a form box: type the saved value (never a secret; the policy checks it again), then the next box. */
    private fun fillFormField(plan: List<com.saathi.app.forms.FillPlan>) {
        val p = plan.getOrNull(formI) ?: return
        if (p.sensitive || p.value == null) { speaker.say(say("Please type this one yourself.", "यह आप ख़ुद लिखिए।", "ఇది మీరే టైప్ చేయండి.").pick(lang), lang); return }
        val pair = com.saathi.app.forms.FormNodes.fields(svc.rootInActiveWindow).firstOrNull { it.first.box == p.node.box } ?: return
        val node = pair.second
        val v = com.saathi.app.policy.ActionPolicy.check(com.saathi.app.policy.ActionRequest(com.saathi.app.policy.Kind.TYPE,
            svc.rootInActiveWindow?.packageName?.toString() ?: "", p.node.label ?: p.node.hint, "input", node.isPassword, p.value, "",
            com.saathi.app.policy.Mode.DO_IT_ONCE, prevExternalPkg))
        if (v !is com.saathi.app.policy.Verdict.Allow) { speaker.say(say("Please type this one yourself.", "यह आप ख़ुद लिखिए।", "ఇది మీరే టైప్ చేయండి.").pick(lang), lang); return }
        lastOwnAction = SystemClock.uptimeMillis()
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.value) })
        com.saathi.app.DebugLog.i("form", "filled box ${formI + 1} (${p.key})")
        formI++
        scope.launch { delay(450); showFormStep() }
    }

    /**
     * "Video call my son": WhatsApp or a normal phone video call? Ask, unless they named WhatsApp, or WhatsApp isn't
     * installed / set up on this phone (then the phone's own video call). Saathi never presses Call itself.
     */
    fun videoCall(goalText: String, person: String?, autoMode: Boolean = false) {
        lang = Prefs.lang(svc)
        val g = if (person != null && !goalText.contains(person, true)) "video call $person" else goalText
        val wa = {
            // The WhatsApp map (known screens, the real top-bar button); the old skill only if the map isn't there.
            val r = com.saathi.app.maps.AppMaps.routeById("wa_video_call")?.takeIf { AppLauncher.isInstalled(svc, it.pkg) }
            if (r != null) beginMap(g, r, autoMode) else begin(goalText, Skills.byId("wa_video")?.build(svc, SlotExtractor.from(g, Prefs.family(svc))), autoMode)
        }
        val phone = { phoneVideoCall(g, autoMode) }
        val saidWa = Regex("(?i)whats ?app|व्हाट्स|వాట్స").containsMatchIn(goalText)
        val waOk = AppLauncher.first(svc, "com.whatsapp", "com.whatsapp.w4b") != null && !Prefs.waNotSetUp(svc)
        when {
            saidWa -> wa()
            !waOk -> phone()
            else -> {
                val q = say("Video call on WhatsApp, or a normal phone video call?", "WhatsApp पर वीडियो कॉल, या फ़ोन से सीधी वीडियो कॉल?",
                    "WhatsApp లో వీడియో కాల్ చేయాలా, లేక మామూలు ఫోన్ వీడియో కాల్?").pick(lang)
                // A task was running: its card fades out after stop(); show the choice once that's done, or the fade
                // removes it (field 08:15: the spoken "WhatsApp" found no card and became a new request).
                val hadTask = goal != null
                if (hadTask) stop()
                hideJob?.cancel()
                current = Target(null, q, "choose_video"); lastSpokenKey = current?.key
                com.saathi.app.DebugLog.i("choice", "choose_video: WhatsApp | Phone")
                scope.launch {
                    if (hadTask) delay(350)
                    overlay.showChoice(q, Triple("WhatsApp", com.saathi.app.R.drawable.ic_chat, wa),
                        Triple(say("Phone call", "फ़ोन कॉल", "ఫోన్ కాల్").pick(lang), com.saathi.app.R.drawable.ic_call, phone))
                }
                speaker.say(q, lang)
            }
        }
    }

    /** The phone's own video call: the dialer opens with their number; Saathi glows the video button. */
    private fun phoneVideoCall(g: String, autoMode: Boolean) {
        val slots = SlotExtractor.from(g, Prefs.family(svc))
        val who = slots.contact ?: Prefs.family(svc)
        val number = Prefs.contacts(svc).firstOrNull { it.name.equals(who, true) }?.phone?.takeIf { it.isNotBlank() }
            ?: Prefs.familyPhone(svc).takeIf { it.isNotBlank() && who.equals(Prefs.family(svc), true) }
        if (number == null) { begin(g, Skills.byId("call")?.build(svc, slots), autoMode); return } // find them in Contacts
        val f = Flow("phone_video", { Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + number)) },
            listOf(Step("video", rx("^Video call", "^Video$", "video call", "^Make video call"),
                say("Tap the video camera button to start the video call.", "वीडियो कॉल शुरू करने के लिए वीडियो कैमरा वाला बटन दबाइए।",
                    "వీడియో కాల్ మొదలుపెట్టడానికి వీడియో కెమెరా బటన్ నొక్కండి.")),
                Step("call", rx("^Call$", "^Dial$", "^Voice call"), say("No video button here? Tap the green call button; you can switch on video in the call.",
                    "यहाँ वीडियो बटन नहीं? हरा कॉल बटन दबाइए; कॉल में वीडियो चालू कर सकते हैं।",
                    "ఇక్కడ వీడియో బటన్ లేదా? ఆకుపచ్చ కాల్ బటన్ నొక్కండి; కాల్‌లో వీడియో ఆన్ చేయవచ్చు."))),
            { sc -> Regex("Calling|Dialing|Ringing|End call", RegexOption.IGNORE_CASE).containsMatchIn(sc.allText) },
            say("Calling $who. Hold the phone in front of your face!", "$who को कॉल लग रहा है। फ़ोन चेहरे के सामने रखिए!", "$who కి కాల్ వెళ్తోంది. ఫోన్ ముఖం ముందు పెట్టుకోండి!"),
            say("Let's video call $who.", "$who को वीडियो कॉल करते हैं।", "$who కి వీడియో కాల్ చేద్దాం."))
        begin(g, f, autoMode)
    }

    /**
     * Saathi acts and guides; it isn't a chatbot (Google already answers questions). So a "question":
     *  - mid-task → a short answer, then back to the task (answerQuestion);
     *  - a greeting → one line and "what shall we do?";
     *  - mentions an app → "Do you want to use <app>? I'll show you." (misheard speech lands here, e.g. "Instagram …");
     *  - anything else → the real Google results on screen, with the answer read from them (lookUp), and a
     *    model-only answer only when there are no results (offline).
     */
    fun respond(q: String) {
        lang = Prefs.lang(svc)
        if (goal != null) { answerQuestion(q); return }
        Conversation.paper()?.takeIf { aboutPaper(q, it) }?.let { paper -> answerFromPaper(q, paper); return }
        if (IntentRouter.isGreeting(q)) {
            finish(say("Namaste! I'm here. What shall we do on your phone?", "नमस्ते! मैं यहीं हूँ। फ़ोन पर क्या करें?", "నమస్కారం! నేను ఇక్కడే ఉన్నాను. ఫోన్‌లో ఏం చేద్దాం?").pick(lang))
            return
        }
        AppLauncher.findInGoal(svc, q)?.let { app ->
            val t = say("Do you want to use ${app.label}? I'll show you.", "क्या आप ${app.label} चलाना चाहते हैं? मैं दिखाता हूँ।", "${app.label} వాడాలనుకుంటున్నారా? నేను చూపిస్తాను.").pick(lang)
            com.saathi.app.DebugLog.i("respond", "app mentioned → ask: ${app.label}")
            current = Target(null, t, "ask_app"); lastSpokenKey = current?.key
            overlay.showCard(t, Overlay.Mode.ASK, onContinue = { overlay.hideCard(); start("how do I use ${app.label}") })
            speaker.say(t, lang)
            return
        }
        com.saathi.app.DebugLog.i("respond", "general question → look it up")
        lookUp(q, q)
    }

    /** Does the question refer to the paper ("this bill", "due date", "कितना", "ఎంత") or share real words with it? */
    private fun aboutPaper(q: String, paper: String): Boolean {
        if (Regex("(?i)\\b(this|it|paper|letter|bill|notice|form|due|amount|date|pay|total|who sent|what does)\\b|यह|इस|काग़ज़|कागज|बिल|कितना|कब|ఇది|దీని|కాగితం|బిల్లు|ఎంత|ఎప్పుడు").containsMatchIn(q)) return true
        val pw = paper.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 4 }.toSet()
        return q.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).count { it.length >= 4 && it in pw } >= 1
    }

    /** A question about the paper the camera just read: answered only from its words. */
    private fun answerFromPaper(q: String, paper: String) {
        overlay.showCard(say("Let me check the paper…", "काग़ज़ देख रहा हूँ…", "కాగితం చూస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
        scope.launch {
            val sys = "Answer the elderly person's question using ONLY the paper's text below, in 1 or 2 short, simple sentences. " +
                "If the answer is not in the text, say: I can't see that on this paper. Never guess amounts, dates or names. " +
                when (lang) { Lang.EN -> "Answer in English."; Lang.HI -> "Answer in Hindi (Devanagari)."; Lang.TE -> "Answer in Telugu script." }
            val user = "Paper text:\n$paper\n\nQuestion: $q"
            val raw = if (LlmManager.isReady) LlmManager.generate(sys, user) else com.saathi.app.llm.FastBrain.generate(svc, sys, user)
            val notThere = say("I can't see that on this paper.", "यह इस काग़ज़ पर नहीं दिख रहा।", "ఇది ఈ కాగితం మీద కనిపించట్లేదు.").pick(lang)
            // Grounded or nothing: every name, number and date must be on the paper (policy.AnswerCheck).
            val a = raw?.trim()?.takeIf { it.isNotBlank() }?.let { c ->
                if (com.saathi.app.policy.AnswerCheck.verify(q, c, paper, lang).ok) c else null } ?: notThere
            com.saathi.app.DebugLog.i("answer", "paper q=\"$q\" a=\"${a.take(160)}\"")
            finish(a)
        }
    }

    fun answerQuestion(q: String, aside: String? = null) {
        // A question in the middle of a task sets the task aside (not lost): answer, then offer to continue it.
        val interrupted = goal
        if (interrupted != null) { setAside = true; autoJob?.cancel(); overlay.highlight(null, false) } else stop()
        lang = Prefs.lang(svc)
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        scope.launch {
            overlay.setAura(true)
            overlay.showCard(say("Let me think…", "सोच रहा हूँ…", "ఆలోచిస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            var waited = 0
            while (!LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed && waited < 6000) { delay(200); waited += 200 }
            com.saathi.app.llm.AiMeter.purpose = "answer"
            val a = Conversation.answer(if (aside != null) "$aside\nMy question: $q" else q, lang, Prefs.name(svc), svc).let { raw ->
                // No source: only safe, general advice passes; facts, medical, legal, money → the kind fallback.
                val c = com.saathi.app.policy.AnswerCheck.verify(q, raw, null, lang)
                // Help with the phone itself ("what does the magnifying glass mean?", a doubt mid-task) is not a fact
                // to source-check: allow a short answer when "no source" is the ONLY objection (medical, money, secrets,
                // current facts and garbled text still fall back). Field: every mid-task doubt got "I could not check".
                val phoneHelp = aside != null || IntentRouter.aboutPhone(svc, q) ||
                    Regex("(?i)\\b(button|icon|symbol|sign|screen|tap|app|phone|mean|arrow|dots|menu)\\b|बटन|निशान|मतलब|బటన్|గుర్తు|అర్థం").containsMatchIn(q)
                val okHelp = phoneHelp && c.reasons == listOf("no_supported_offline_advice") && raw.length <= 400
                if (!c.ok) com.saathi.app.DebugLog.i("answer", "check ${c.reasons} phoneHelp=$phoneHelp raw=\"${raw.take(160)}\"")
                if (c.ok || okHelp || IntentRouter.isGreeting(q)) raw else c.fallback?.pick(lang) ?: raw
            }
            overlay.setAura(false)
            Conversation.remember(q, a)
            com.saathi.app.DebugLog.i("answer", "q=\"$q\" a=\"${a.take(200)}\"")
            // "Shall I find a video?" only for real-world how-tos (cooking, crafts), never phone tasks or mid-task.
            val howTo = Regex("(?i)^how (to|do|can)|recipe|कैसे|ఎలా").containsMatchIn(q) && !IntentRouter.isGreeting(q) &&
                interrupted == null && !IntentRouter.aboutPhone(svc, q)
            current = Target(null, a, "answer")
            if (howTo) overlay.showCard(a + "\n\n" + say("Shall I find a video?", "वीडियो ढूँढूँ?", "వీడియో వెతకనా?").pick(lang), Overlay.Mode.ASK, onContinue = {
                overlay.hideCard(); start("play ${SlotExtractor.searchPhrase(q)} on YouTube")
            }) else if (interrupted != null) overlay.showCard(a + "\n\n" + say("Shall we continue “${interrupted.take(40)}”?", "क्या “${interrupted.take(40)}” जारी रखें?", "“${interrupted.take(40)}” కొనసాగిద్దామా?").pick(lang),
                Overlay.Mode.PAUSED, onContinue = { resumeTask() })
            else overlay.showCard(a, Overlay.Mode.DONE)
            speaker.say(if (howTo) a + " " + say("Shall I find a video?", "वीडियो ढूँढूँ?", "వీడియో వెతకనా?").pick(lang) else a, lang)
            hideJob?.cancel(); hideJob = scope.launch { delay(25_000); if (goal == null && current?.key == "answer") overlay.hideCard() }
        }
    }

    private fun tvWatchIntent(g: String): Boolean {
        val tv = Regex("(?i)\\b(on|in) (the |my )?tv\\b|टीवी पर|టీవీలో|టీవీ లో").containsMatchIn(g)
        val watch = Regex("(?i)watch|play|movie|film|serial|netflix|youtube|hotstar|prime|देख|चला|लगा|చూడ|పెట్టు").containsMatchIn(g)
        return tv && watch
    }

    /** "Play X on Netflix on the TV": wake the TV's home screen over IR, then coach the arrows on the big remote. */
    private fun watchOnTv(g: String) {
        stop()
        val sent = IrRemote.send(svc, IrRemote.Key.HOME)
        val app = Regex("(?i)netflix|youtube|hotstar|prime").find(g)?.value?.replaceFirstChar { it.uppercase() } ?: "the app"
        val t = say("${if (sent) "I've opened your TV's home screen. " else ""}On the remote, use the arrows to reach $app, then press OK. Say “TV go right” or “TV OK” and I'll press them for you.",
            "${if (sent) "मैंने टीवी का होम खोल दिया है। " else ""}रिमोट पर तीर से $app तक जाइए, फिर OK दबाइए। “टीवी दाएँ” या “टीवी OK” कहिए, मैं दबा दूँगा।",
            "${if (sent) "టీవీ హోమ్ తెరిచాను. " else ""}రిమోట్‌లో బాణాలతో $app కి వెళ్ళి OK నొక్కండి. “టీవీ కుడి” లేదా “టీవీ OK” అనండి, నేను నొక్కుతాను.").pick(lang)
        runCatching { svc.startActivity(Intent(svc, com.saathi.app.ui.RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        speaker.say(t, lang)
        Conversation.remember(g, t)
    }

    /** "change my ringtone" → "ringtone": the words worth typing into a search box. */
    private val NAV_WORDS = Regex("(?i)^(search|search .*|menu|more|more options|next|continue|ok|okay|done|allow|open|home|back|navigate up|settings|library|you|profile|account|tabs?|skip|not now|no thanks|later|maybe later|got it|close|dismiss|accept|agree|start|get started)$|search|^(new|create|add|compose|write)\\b")

    /** The goal's own words ("ringtone", "liked", "akash"): what a screen closer to the goal would show. */
    private fun goalWords(goal: String): List<String> {
        val stop = setOf("change", "the", "how", "want", "please", "show", "open", "find", "make", "with", "from", "that", "this",
            "some", "have", "what", "where", "phone", "mobile", "there", "help", "need", "like", "about", "your", "into", "want",
            "teach", "learn", "using", "does", "work", "tell", "explain", "please")
        val app = runCatching { AppLauncher.findInGoal(svc, goal)?.label?.lowercase() }.getOrNull()
        return SlotExtractor.searchPhrase(goal).lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .filter { it.length >= 4 && it !in stop && (app == null || !app.contains(it)) }.distinct()
    }

    /**
     * The unscripted path's checks, before the model plans a step. Returns Unit when it handled the screen.
     * - Stay in the task's app: a planner step that ended up in another app → "where am I" (not more guesses there).
     * - Search first: a vague goal in an app with a search box/icon → "let's search for …" (what a careful person does).
     * - Progress: three planned screens in a row with none of the goal's words → "I may have taken a wrong turn".
     */
    private fun driftCheck(screen: Screen, f: Flow?, g: String): Unit? {
        if (f != null && f.steps.isNotEmpty()) return null   // scripted flows check their own screens
        if (mapRoute != null) return null                     // app maps check their own screens too
        val home = f?.appPkg
        if (home != null && screen.pkg != home && plansThisTask > 0 && !helperApp(screen.pkg) && screen.pkg != launcherPkg()) {
            com.saathi.app.DebugLog.i("drift", "left ${home} for ${screen.pkg}")
            whereAmI(lead = say("This isn't the app we were in. ", "यह वह ऐप नहीं है जिसमें हम थे। ", "ఇది మనం ఉన్న యాప్ కాదు. ").pick(lang))
            return Unit
        }
        val words = goalWords(f?.llmGoal ?: g)
        // Search is for FINDING something (a song, a chat, a setting), never for making one (field: "add my doctor
        // appointment" was sent to Calendar's search).
        val finding = Regex("(?i)\\b(find|show|see|look|watch|play|listen|where|search|read|check|open)\\b|दिखा|ढूंढ|ढूँढ|चलाओ|చూపించు|వెతుకు").containsMatchIn(g) &&
            !Regex("(?i)\\b(add|create|new|make|set|send|call|write|book|delete|remove|schedule|save)\\b|जोड़|बनाओ|भेजो|జోడించు|పంపు").containsMatchIn(g)
        if (!searchTried && finding && words.isNotEmpty()) {
            searchTried = true
            val s = screen.elements.firstOrNull { e -> (e.role == "input" && Regex("(?i)search").containsMatchIn(e.label)) } ?:
                screen.elements.firstOrNull { e -> e.role == "button" && Regex("(?i)^search\\b|^search$|magnif").containsMatchIn(e.title) }
            if (s != null) {
                val appName = AppLauncher.labelOf(svc, screen.pkg).lowercase()
                val term = (searchTerm(f?.llmGoal ?: g) ?: words.take(2).joinToString(" ")).split(" ")
                    .filter { !appName.contains(it.lowercase()) && it.lowercase() != "app" }.joinToString(" ").ifBlank { words.first() }
                com.saathi.app.DebugLog.i("drift", "search first: \"$term\" via \"${s.title}\"")
                show(Target(s, if (s.role == "input") say("Let's search. Tap the search box and type “$term”.", "खोजते हैं। खोज में “$term” लिखिए।",
                    "వెతుకుదాం. వెతుకులో “$term” టైప్ చేయండి.").pick(lang)
                    else say("Let's search for “$term”. Tap the magnifying glass.", "“$term” खोजते हैं। आवर्धक काँच दबाइए।",
                        "“$term” వెతుకుదాం. భూతద్దం నొక్కండి.").pick(lang), "plan_search", fill = if (s.role == "input") term else null))
                return Unit
            }
        }
        val score = words.count { screen.allText.contains(it, true) }
        planScores += score
        if (planScores.size >= 3 && planScores.takeLast(3).all { it == 0 }) {
            com.saathi.app.DebugLog.i("drift", "3 screens with none of $words: stop and show where we are")
            planScores.clear()
            whereAmI(lead = say("I may have taken a wrong turn. ", "शायद हम ग़लत रास्ते पर हैं। ", "మనం తప్పు దారిలో వెళ్ళామేమో. ").pick(lang))
            return Unit
        }
        return null
    }

    private fun searchTerm(goal: String): String? {
        val stop = setOf("change", "my", "the", "a", "an", "how", "to", "do", "i", "set", "open", "turn", "on", "off", "make", "please", "want", "can", "you", "find", "show", "me", "is", "in")
        val w = SlotExtractor.searchPhrase(goal).split(Regex("\\s+")).filter { it.length > 2 && it.lowercase() !in stop }
        return w.takeLast(2).joinToString(" ").ifBlank { null }
    }

    private fun launcherPkg(): String? = runCatching {
        svc.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()

    /** A launcher app icon by name: icon-shaped (≥100 px, roughly square), largest wins. */
    private fun appIcon(name: String): UiElement? = runCatching {
        val rx = Regex("^" + Regex.escape(name) + "(\\s*,.*)?$", RegexOption.IGNORE_CASE)
        var best: UiElement? = null
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        svc.rootInActiveWindow?.let { q += it }
        var seen = 0
        while (q.isNotEmpty() && seen < 1500) {
            val n = q.removeFirst(); seen++
            val label = (n.contentDescription ?: n.text)?.toString()?.trim().orEmpty()
            if (rx.matches(label)) {
                val r = android.graphics.Rect(); n.getBoundsInScreen(r)
                val ok = r.width() >= 100 && r.height() >= 100 && r.width() * 3 >= r.height() && r.height() * 3 >= r.width()
                if (ok && (best == null || r.width() * r.height() > best!!.bounds.width() * best!!.bounds.height()))
                    best = UiElement(-1, name, "button", r, true, false, false, false, n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q += it }
        }
        best
    }.getOrNull()

    /** Apps that tasks legitimately hop into: sign-in, browser tabs, the store, pickers, camera, payment sheets. */
    private fun helperApp(pkg: String) = Regex("gms|chrome|browser|vending|appstore|packageinstaller|documentsui|photopicker|camera|gallery|" +
        "providers|webview|auth|login|paisa|phonepe|paytm|npci|contacts|dialer|incallui|telecom").containsMatchIn(pkg)

    // ───────────────────────── the coach (think → check → ask → guide) ─────────────────────────

    private var coachGoal: String? = null
    private var coachKey = ""
    private var coachSteps = 0
    @Volatile private var coachWaiting = false

    /** A task the coach handed to the guide is never handed back (field 05:01: GUIDE ↔ coach every 3 s). */
    private var coachHandedOff: String? = null
    private val coachStarts = mutableMapOf<String, MutableList<Long>>()

    fun startCoach(g: String) {
        // The same errand at most twice a minute: a third time is a loop, not a person asking again.
        val now = SystemClock.uptimeMillis()
        val times = coachStarts.getOrPut(g.lowercase().trim()) { mutableListOf() }.apply { removeAll { now - it > 60_000 }; add(now) }
        if (times.size > 2) {
            com.saathi.app.DebugLog.i("coach", "loop stopped: \"$g\" started ${times.size} times in a minute")
            coachGoal = null; coachWaiting = false
            whereAmI(lead = say("I got stuck on this one. ", "मैं इसमें अटक गया। ", "నేను ఇక్కడ ఆగిపోయాను. ").pick(lang))
            return
        }
        stop()
        lang = Prefs.lang(svc)
        coachGoal = g; coachKey = "coach_${SystemClock.uptimeMillis()}"; coachSteps = 0; coachWaiting = false
        LlmManager.endChat()
        com.saathi.app.DebugLog.i("coach", "start \"$g\"")
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        val facts = Memory.relevant(g)
        coachTurn(Coach.EXAMPLE + (if (facts.isNotEmpty()) "What you know about them: ${facts.joinToString("; ")}\n" else "") +
            "Their language: ${lang.label}. Say things in simple ${if (lang == Lang.EN) "English" else lang.label}.\n" +
            "Now the real task: $g")
    }

    private fun coachTurn(msg: String) {
        val g = coachGoal ?: return
        scope.launch {
            if (++coachSteps > 18) { endCoach(say("Let's stop here for now. Ask me again any time.", "अभी यहीं रुकते हैं। कभी भी फिर पूछिए।", "ఇప్పుడు ఇక్కడ ఆపుదాం. ఎప్పుడైనా మళ్ళీ అడగండి.").pick(lang)); return@launch }
            overlay.setAura(true)
            overlay.showCard(say("Thinking…", "सोच रहा हूँ…", "ఆలోచిస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            var waited = 0
            while (!LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed && waited < 12000) { delay(200); waited += 200 }
            LlmManager.lastChatKey = coachKey
            val myKey = coachKey
            val raw = LlmManager.chat(coachKey, Coach.SYSTEM, msg)
            overlay.setAura(false)
            if (coachGoal == null || coachKey != myKey) return@launch   // stopped (or replaced) while the model was thinking
            var call = raw?.let { Coach.parse(it) }
            // Watching something on the TV: check where it streams before touching the TV (ground truth, not assumption).
            if (coachSteps == 1 && call?.tool != "LOOKUP" && Coach.isWatchOnTv(g)) call = Coach.Call("LOOKUP", "where to watch ${Coach.title(g)} online India")
            com.saathi.app.DebugLog.i("coach", "step $coachSteps: ${call ?: raw?.take(120)}")
            if (coachGoal != g) return@launch
            if (call == null) { endCoach(say("I'm not sure how to go on. Tell me again in other words?", "आगे कैसे करें, पक्का नहीं। दूसरे शब्दों में फिर बताइए?", "ఎలా కొనసాగించాలో తెలియట్లేదు. వేరే మాటల్లో చెప్పండి?").pick(lang)); return@launch }
            runTool(call)
        }
    }

    private suspend fun runTool(c: Coach.Call) {
        when (c.tool) {
            "ASK" -> {
                coachWaiting = true
                TvSession.screen?.let { it.instruct(c.arg, question = true); return }
                current = Target(null, c.arg, "coach_ask_$coachSteps"); lastSpokenKey = current?.key
                overlay.showCard(c.arg, Overlay.Mode.INFO)
                speaker.say(c.arg, lang)
                Conversation.remember(coachGoal ?: "", c.arg)
            }
            "SAY" -> {
                TvSession.screen?.let { it.instruct(c.arg, question = false); delay(2200L + c.arg.length * 45L); coachTurn("Result: you said it."); return }
                current = Target(null, c.arg, "coach_say_$coachSteps")
                overlay.showCard(c.arg, Overlay.Mode.INFO)
                speaker.say(c.arg, lang)
                delay(2200L + c.arg.length * 45L)
                coachTurn("Result: you said it.")
            }
            "LOOKUP" -> {
                overlay.showCard(say("Checking…", "पता कर रहा हूँ…", "తెలుసుకుంటున్నాను…").pick(lang), Overlay.Mode.THINKING)
                runCatching { svc.startActivity(Understand.webSearch(svc, c.arg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                var text = ""
                for (i in 0 until 8) { delay(800); val sc = readScreen() ?: continue; if (sc.pkg != svc.packageName && sc.allText.length > 200) { text = sc.allText; if (i >= 2) break } }
                coachTurn("Result: " + (text.take(1400).ifBlank { "no results could be read" }))
            }
            "OPEN" -> {
                val app = AppLauncher.findInGoal(svc, "open ${c.arg}")
                val ok = app != null && runCatching { svc.startActivity(AppLauncher.launch(svc, app.pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                delay(1500)
                coachTurn(if (ok) "Result: opened ${app!!.label}" else "Result: ${c.arg} is not installed on this phone")
            }
            "TV" -> {
                val keyName = c.arg.uppercase().replace(' ', '_').replace("VOLUME", "VOL").substringBefore('_').let { if (it == "VOL") c.arg.uppercase().replace(' ', '_').replace("VOLUME", "VOL") else it }
                if (Prefs.tvIr(svc) && IrRemote.available(svc)) {
                    // Optional: the phone presses it (IR). Then look again.
                    val key = runCatching { IrRemote.Key.valueOf(keyName) }.getOrNull() ?: IrRemote.keyFor(c.arg)
                    val ok = key != null && IrRemote.send(svc, key)
                    delay(1500)
                    TvSession.screen?.lookNow() ?: coachTurn(if (ok) "Result: pressed ${key!!.name}" else "Result: could not press that")
                    return
                }
                // Default: coach THEM to press it on their own remote; the camera then checks what happened.
                val t = say("Press ${TvSession.buttonWords(keyName, Lang.EN)} on your remote.", "रिमोट पर ${TvSession.buttonWords(keyName, Lang.HI)} दबाइए।",
                    "రిమోట్‌లో ${TvSession.buttonWords(keyName, Lang.TE)} నొక్కండి.").pick(lang)
                lastTvKey = keyName
                val scr = TvSession.screen
                if (scr != null) scr.instruct(t, question = false)
                else {
                    runCatching { svc.startActivity(Intent(svc, com.saathi.app.ui.ReadActivity::class.java)
                        .putExtra(com.saathi.app.ui.ReadActivity.EXTRA_MODE, com.saathi.app.ui.ReadActivity.MODE_TV)
                        .putExtra("instruction", t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            "LOOK_TV" -> {
                TvSession.screen?.let { it.lookNow(); return }
                val t = say("Point the camera at your TV screen and tap the big button.", "कैमरा टीवी की स्क्रीन की ओर करके बड़ा बटन दबाइए।", "కెమెరాను టీవీ స్క్రీన్ వైపు పెట్టి పెద్ద బటన్ నొక్కండి.").pick(lang)
                speaker.say(t, lang); overlay.hideCard()
                runCatching { svc.startActivity(Intent(svc, com.saathi.app.ui.ReadActivity::class.java)
                    .putExtra(com.saathi.app.ui.ReadActivity.EXTRA_MODE, com.saathi.app.ui.ReadActivity.MODE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                // coachObserve() continues when the photo is understood
            }
            "GUIDE" -> {
                val g = c.arg.ifBlank { coachGoal ?: "" }
                com.saathi.app.DebugLog.i("coach", "handoff to guide: $g")
                coachGoal = null; coachWaiting = false; coachHandedOff = g.lowercase().trim()
                val u = Understand.parse(g, svc)
                if (u == null || !handleIntent(g, u, false)) begin(g, IntentRouter.route(svc, g), false)
            }
            "DONE" -> endCoach(c.arg.ifBlank { say("Done!", "हो गया!", "అయింది!").pick(lang) })
            else -> endCoach(null)
        }
    }

    private var lastTvKey: String? = null

    /** The camera looked at the TV (FastVLM on the NPU): tell the coach what it saw (after which button). */
    fun coachObserve(description: String) {
        if (coachGoal == null) return
        val after = lastTvKey?.let { "after they pressed $it, " } ?: ""
        lastTvKey = null
        coachTurn("Result: ${after}the TV shows: ${description.take(600)}")
    }

    private fun endCoach(text: String?) {
        TvSession.screen?.end(text ?: say("Okay, stopping.", "ठीक है, रुकते हैं।", "సరే, ఆపుతున్నాను.").pick(lang))
        coachGoal = null; coachWaiting = false
        LlmManager.endChat()
        if (text != null) finish(text) else stop()
    }

    /**
     * Evaluation: what WOULD Saathi do with this sentence? Mirrors start()'s decision order, acts on nothing.
     * Label = the path: sos, routine, recall, system, question, coach, family, messages, explain, briefing, teach,
     * recipe, intent:<intent>, skill:<id>, app:<pkg>, agent.
     */
    suspend fun decideOnly(g: String): String {
        if (IntentRouter.isSos(g)) return "sos"
        if (Routines.parse(g) != null) return "routine"
        if (Reminders.parse(g, 12, 0) != null) return "reminder"
        if (IntentRouter.isRecall(g)) return "recall"
        if (IntentRouter.systemAction(g) != null) return "system"
        if (Coach.wants(g, null)) return "coach"
        if (IntentRouter.isScamCheck(g)) return "scamcheck"
        if (IntentRouter.isFamilyHelp(g)) return "family"
        if (IntentRouter.isReadMessages(g)) return "messages"
        if (IntentRouter.isExplain(g)) return "explain"
        if (IntentRouter.isBriefing(g)) return "briefing"
        if (IntentRouter.isObjectHelp(g)) return "skill:learn_app"
        IntentRouter.cameraRead(g)?.let { return "skill:$it" }
        if (IntentRouter.isFormHelp(g)) return "form"
        IntentRouter.settingsTask(g)?.let { return "skill:${it.id}" }
        mapRouteFor(g)?.let { return "map:${it.id}" }
        IntentRouter.phoneHowTo(svc, g)?.let { return "skill:${it.id}" }
        if (IntentRouter.isQuestion(svc, g)) return "question"
        if (Regex("(?i)^\\s*(remember|note down|याद रखो|याद रखना|గుర్తుంచుకో)\\b").containsMatchIn(g)) return "note"
        Recipes.find(svc, g)?.let { return "recipe" }
        IntentRouter.openOnly(svc, g)?.let { return "app:${it.id.removePrefix("app_")}" }
        if (LlmManager.isReady || com.saathi.app.llm.FastBrain.isReady || com.saathi.app.llm.ModelLocator.fast(svc) != null) {
            val u = Understand.parse(g, svc)
            if (u != null) {
                if (Coach.wants(g, u.intent) || (u.intent == "watch" && u.device == "tv")) return "coach"
                Skills.match(g)?.takeIf { it.id in IntentRouter.DIRECT && (u.intent !in setOf("weather", "lookup", "question", "watch", "music") ||
                    (u.intent == "question" && !IntentRouter.phrasedAsQuestion(g))) }?.let { return "skill:${it.id}" }
                if (u.intent in setOf("watch", "music") && Regex("(?i)hot ?star|prime video|netflix|zee ?5|sony ?liv|jio ?cinema").containsMatchIn(g)) return "skill:ott"
                if (u.intent in setOf("question", "weather", "lookup", "watch", "music", "call", "video_call", "message", "photo", "alarm", "directions", "setting", "tv"))
                    return "intent:${u.intent}"
            }
            return when (val r = IntentRouter.smartRoute(svc, g)) {
                is IntentRouter.Route.Question -> "question"
                is IntentRouter.Route.Skill -> r.flow?.let { f -> if (f.id.startsWith("app_")) "app:${f.id.removePrefix("app_")}" else "skill:${f.id}" } ?: "agent"
            }
        }
        return IntentRouter.route(svc, g)?.let { "skill:${it.id}" } ?: "agent"
    }

    // ───────────────────────── watchdog ─────────────────────────

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (goal != null) {
                delay(30_000)
                if (goal == null || paused || stuckOffered || thinking) continue
                if (SystemClock.uptimeMillis() - lastProgress > 120_000) {
                    stuckOffered = true
                    stopAuto()
                    val t = say("Stuck? No problem. I can take you home, go back, or ask your family to help.",
                        "अटक गए? कोई बात नहीं। मैं होम पर ले चलूँ, वापस जाऊँ, या परिवार से मदद माँगूँ।",
                        "ఆగిపోయారా? పర్వాలేదు. హోమ్‌కి తీసుకెళ్ళనా, వెనక్కి వెళ్ళనా, లేదా కుటుంబాన్ని అడగనా.").pick(lang)
                    current = Target(null, t, "stuck")
                    overlay.highlight(null, false)
                    overlay.showCard(t, Overlay.Mode.LOST)
                    speaker.say(t, lang)
                }
            }
        }
    }

    // ───────────────────────── memory recall ─────────────────────────

    /** "What's my BP tablet?" → answered only from what they told Saathi (grounded; never invented). */
    fun recall(q: String) {
        lang = Prefs.lang(svc)
        val facts = Memory.notes() + Memory.reminders() + Memory.journalEntries(30) + Routines.all(svc).map { "${it.goal} · ${it.time} daily" } +
            Prefs.contacts(svc).map { "Family: ${it.name}" }
        val words = q.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 && it !in RECALL_STOP }
        val hits = facts.map { f -> f to words.count { it in f.lowercase() } }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        scope.launch {
            val answer = when {
                hits.isEmpty() -> say("I don't know that yet. Say “remember …” and I'll keep it for you.",
                    "यह मुझे अभी नहीं पता। “याद रखो …” कहिए, मैं याद रखूँगा।",
                    "అది నాకు ఇంకా తెలియదు. “గుర్తుంచుకో …” అని చెప్పండి.").pick(lang)
                lang == Lang.EN && LlmManager.isReady -> LlmManager.generate(
                    "Answer the elderly person's question in one short sentence using ONLY these notes. If the notes don't answer it, say you don't know.",
                    "Notes:\n${hits.take(5).joinToString("\n") { "- $it" }}\nQuestion: $q")?.takeIf { it.isNotBlank() && it.length < 240 }
                    ?: say("You told me: ${hits.first()}.", "", "").pick(lang)
                else -> say("You told me: ${hits.first()}.", "आपने बताया था: ${hits.first()}।", "మీరు చెప్పారు: ${hits.first()}.").pick(lang)
            }
            finish(answer)
        }
    }

    private val RECALL_STOP = setOf("what", "whats", "what's", "when", "where", "which", "my", "the", "is", "are", "you", "remember", "tell", "do", "did",
        "क्या", "मेरी", "मेरा", "मेरे", "कब", "कहाँ", "है", "याद", "ఏమిటి", "నా", "ఎప్పుడు", "గుర్తుందా")

    // ───────────────────────── SOS & routines ─────────────────────────

    fun sos() {
        stop()
        runCatching { svc.startActivity(Intent(svc, com.saathi.app.ui.SosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun addRoutine(h: Int, m: Int, g: String) {
        val kind = if (Regex("(?i)^remind|याद दिला|గుర్తు చేయి").containsMatchIn(g) || Skills.match(g)?.id == "medicine") "remind" else "do"
        val r = Routines.add(svc, h, m, g, kind)
        finish(say("Done. Every day at ${r.time} I'll ask: “$g”.", "ठीक है। रोज़ ${r.time} बजे मैं पूछूँगा: “$g”।", "సరే. ప్రతి రోజు ${r.time}కి అడుగుతాను: “$g”.").pick(lang))
    }

    /** A one-time reminder: Saathi keeps it itself (offline) and speaks it at the time. */
    private fun addReminder(h: Int, m: Int, what: String) {
        val r = Routines.add(svc, h, m, what, "note")
        com.saathi.app.DebugLog.i("reminder", "set ${r.time} \"$what\"")
        finish(say("Okay. At ${r.time} I'll remind you: “$what”.", "ठीक है। ${r.time} बजे याद दिलाऊँगा: “$what”।", "సరే. ${r.time}కి గుర్తు చేస్తాను: “$what”.").pick(lang))
    }

    /** A routine's time came: offer it (card + voice). Reminders just remind; tasks run on "Yes". */
    fun routineDue(r: Routines.Routine) {
        lang = Prefs.lang(svc)
        Log.i(TAG, "routine due: ${r.goal} (active=$active)")
        com.saathi.app.DebugLog.i("routine", "due ${r.goal} kind=${r.kind} active=$active")
        val med = r.kind == "remind" || r.kind == "note"
        val what = r.goal.replace(Regex("(?i)^remind me (to )?"), "").replace(Regex("(?i)\\bmy\\b"), "your")
        if (active) {
            // Don't break the task in progress, but a reminder is never dropped: say it out loud.
            if (med) { speaker.say(say("Reminder: $what.", "याद दिलाना: $what।", "గుర్తు: $what.").pick(lang), lang); svc.buzz(); svc.buzz() }
            return
        }
        hideJob?.cancel() // a previous "done" card must not hide this offer
        val q = if (med) say("It's ${r.time}. Time to $what.", "${r.time} बज गए। $what का समय।", "${r.time} అయింది. $what సమయం.").pick(lang)
            else say("It's ${r.time}. Shall I help you “${r.goal}”?", "${r.time} बज गए। क्या मैं “${r.goal}” में मदद करूँ?", "${r.time} అయింది. “${r.goal}” చేయనా?").pick(lang)
        current = Target(null, q, "routine_${r.id}")
        overlay.showCard(q, if (med) Overlay.Mode.DONE else Overlay.Mode.ASK, onContinue = { overlay.hideCard(); start(r.goal) })
        speaker.say(q, lang)
        svc.buzz(); svc.buzz()
        if (med) { hideJob?.cancel(); hideJob = scope.launch { delay(60_000); if (goal == null) overlay.hideCard() } }
    }

    /** "Good morning" / "what's today": reminders, routines, and who they might call. */
    fun briefing() {
        lang = Prefs.lang(svc)
        val name = Prefs.name(svc)
        val items = (Memory.reminders().take(3) + Routines.all(svc).take(3).map { "${it.goal} · ${it.time}" })
        val person = Memory.topPeople(1).firstOrNull() ?: Prefs.family(svc).ifBlank { null }
        val t = buildString {
            append(say("Good morning${if (name.isNotBlank()) ", $name ji" else ""}. ", "सुप्रभात${if (name.isNotBlank()) " $name जी" else ""}। ", "శుభోదయం${if (name.isNotBlank()) " $name గారు" else ""}. ").pick(lang))
            if (items.isNotEmpty()) append(say("Today: ", "आज: ", "ఈరోజు: ").pick(lang)).append(items.joinToString("; ")).append(". ")
            person?.let { append(say("Shall we call $it today?", "आज $it को फ़ोन करें?", "ఈరోజు $it కి ఫోన్ చేద్దామా?").pick(lang)) }
        }
        finish(t)
    }

    fun onFinalDone() { flow?.let { complete(it) } ?: finish(say("Done!", "हो गया!", "అయింది!").pick(lang)) }

    // ───────────────────────── helpers ─────────────────────────

    private fun persist() { goal?.let { Memory.saveTask(it, flow?.id, lastStepIdx, taskPkgs) } }

    private fun resolvePkg(i: Intent): String? =
        (i.`package` ?: i.component?.packageName
            ?: runCatching { svc.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull())
            ?.takeIf { it != "android" }

    private fun isTransient(pkg: String): Boolean =
        pkg.isBlank() || pkg == "com.android.systemui" || pkg == "android" ||
            "permissioncontroller" in pkg || "inputmethod" in pkg || "keyboard" in pkg ||
            pkg == "com.android.intentresolver" || "packageinstaller" in pkg
}
