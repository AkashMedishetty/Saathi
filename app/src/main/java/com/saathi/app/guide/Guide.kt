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

    // ── Watchdog: no progress for 2 minutes → offer help (once per task). ──
    private var lastProgress = 0L
    private var stuckOffered = false
    private var watchdog: Job? = null

    val active get() = goal != null

    // ───────────────────────── entry points ─────────────────────────

    /** A spoken or typed sentence. During a task, short commands steer the task. */
    fun handleUtterance(text: String) {
        val t = text.lowercase().trim()
        fun any(vararg w: String) = w.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
        val allRx = Regex("(?i)do (it )?all( of it)?( for me)?|do everything|you do everything|सब (आप )?कर दो|पूरा कर दो|सब कुछ कर दो|అన్నీ చేయి|మొత్తం చేయి|అన్నీ మీరే చేయండి")
        allRx.find(text)?.let { m ->
            val rest = text.removeRange(m.range).trim(' ', ',', ':', '.', '-')
            if (rest.length > 3) { start(rest, autoMode = true); return }
            if (active) { enableAuto(); return }
        }
        // The coach asked them something: their reply continues the coaching conversation.
        if (coachWaiting && coachGoal != null) {
            if (Regex("(?i)^(stop|cancel|bas|बस|रुको|ఆపు)\\b").containsMatchIn(text.trim())) { endCoach(null); return }
            coachWaiting = false; coachTurn("Person: ${text.trim()}"); return
        }
        // Saathi asked them something ("Which contact?"): their reply refines the same task, it isn't a new one.
        if (active && current?.key?.startsWith("ask_") == true) {
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
        start(text)
    }

    fun start(goalText: String, autoMode: Boolean = Prefs.expert(svc)) {
        lang = Prefs.lang(svc)
        hideJob?.cancel()
        stopAuto()
        Log.i(TAG, "goal: $goalText (auto=$autoMode)")
        com.saathi.app.DebugLog.i("goal", "\"$goalText\" lang=$lang auto=$autoMode locked=${svc.isLocked()}")
        if (IntentRouter.isSos(goalText)) { sos(); return }
        Routines.parse(goalText)?.let { (h, m, g) -> addRoutine(h, m, g); return }
        if (IntentRouter.isRecall(goalText)) { recall(goalText); return }
        IntentRouter.systemAction(goalText)?.let { action ->
            stop()
            svc.performGlobalAction(action)
            com.saathi.app.DebugLog.i("system", "global action $action for \"$goalText\"")
            return
        }
        if (Coach.wants(goalText, null)) { startCoach(goalText); return }
        if (IntentRouter.isScamCheck(goalText)) { scamCheck(); return }
        if (IntentRouter.isFamilyHelp(goalText)) { askFamily(); return }
        if (IntentRouter.isReadMessages(goalText)) { readMessages(); return }
        if (IntentRouter.isExplain(goalText)) { explain(); return }
        if (IntentRouter.isBriefing(goalText)) { briefing(); return }
        if (IntentRouter.isObjectHelp(goalText)) { begin(goalText, Skills.byId("learn_app")?.build(svc, SlotExtractor.from(goalText)), autoMode); return }
        IntentRouter.cameraRead(goalText)?.let { id -> begin(goalText, Skills.byId(id)?.build(svc, SlotExtractor.from(goalText)), autoMode); return }
        if (IntentRouter.isQuestion(svc, goalText)) { answerQuestion(goalText); return }
        rememberRequest(goalText)?.let { finish(it); return }
        // Teach-once: "watch me: video call Rahul" … "done teaching".
        Regex("(?i)^\\s*(watch me|learn this|let me show you|i'?ll show you|देखो मैं|मैं दिखाता|నేను చూపిస్తా)\\W*(.*)$").find(goalText)?.let { m ->
            val name = m.groupValues[2].trim().ifBlank { "my task" }
            Recipes.startRecording(name)
            com.saathi.app.DebugLog.i("teach", "recording \"$name\"")
            finish(say("I'm watching. Do “$name” now; say “done teaching” when finished.", "मैं देख रहा हूँ। “$name” करके दिखाइए; ख़त्म होने पर “सिखा दिया” कहिए।",
                "నేను చూస్తున్నాను. “$name” చేసి చూపించండి; అయ్యాక “నేర్పించాను” అనండి.").pick(lang))
            return
        }
        if (Recipes.recording != null && Regex("(?i)done teaching|finished|that's it|सिखा दिया|हो गया|నేర్పించాను|అయింది").containsMatchIn(goalText)) {
            val r = Recipes.stopRecording(svc)
            com.saathi.app.DebugLog.i("teach", "saved ${r?.name} taps=${r?.taps?.map { it.label }}")
            finish(if (r != null) say("Learned “${r.name}” in ${r.taps.size} steps. Anyone can ask me for it now.", "“${r.name}” सीख लिया, ${r.taps.size} क़दम। अब कोई भी मुझसे पूछ सकता है।",
                "“${r.name}” నేర్చుకున్నాను, ${r.taps.size} అడుగులు. ఇప్పుడు ఎవరైనా అడగవచ్చు.").pick(lang)
                else say("I didn't see any taps, so nothing was saved.", "कोई टैप नहीं दिखा, कुछ सेव नहीं हुआ।", "ఏ ట్యాప్ కనిపించలేదు, ఏదీ సేవ్ కాలేదు.").pick(lang))
            return
        }
        // Something a family member taught me? That path wins: it's known to work on this very phone.
        Recipes.find(svc, goalText)?.let { r -> begin(goalText, Recipes.toFlow(r), autoMode); return }
        if (LlmManager.isReady || com.saathi.app.llm.ModelLocator.fast(svc) != null) {
            // Let the model pick the helper (NPU ≈0.25 s); keywords only if it can't.
            overlay.showCard(say("Okay…", "ठीक है…", "సరే…").pick(lang), Overlay.Mode.THINKING)
            scope.launch {
                // 1) Understand the vague request. 2) Jump there with an intent. 3) Only then guide / answer.
                val u = Understand.parse(goalText, svc)
                com.saathi.app.DebugLog.i("understand", "\"$goalText\" → $u")
                if (u != null && handleIntent(goalText, u, autoMode)) return@launch
                when (val r = IntentRouter.smartRoute(svc, goalText)) {
                    is IntentRouter.Route.Question -> answerQuestion(goalText)
                    is IntentRouter.Route.Skill -> begin(goalText, r.flow, autoMode)
                }
            }
            return
        }
        begin(goalText, IntentRouter.route(svc, goalText), autoMode)
    }

    /** Returns true if the intent was handled (deep link / answer / skill); false → fall back to routing + agent. */
    private suspend fun handleIntent(goalText: String, u: Understand.Intent2, autoMode: Boolean): Boolean {
        val q = u.query ?: goalText
        fun skill(id: String, g: String = goalText) = Skills.byId(id)?.build(svc, SlotExtractor.from(g, Prefs.family(svc)))
        // A precise, reliable skill (torch, font, storage, selfie…) beats the model's broad category.
        Skills.match(goalText)?.takeIf { it.id in IntentRouter.DIRECT && u.intent !in setOf("weather", "lookup", "question", "watch", "music") }?.let {
            begin(goalText, it.build(svc, SlotExtractor.from(goalText, Prefs.family(svc))), autoMode); return true
        }
        if (Coach.wants(goalText, u.intent) || (u.intent == "watch" && u.device == "tv")) { startCoach(goalText); return true }
        when (u.intent) {
            "question" -> { answerQuestion(goalText); return true }
            "weather", "lookup" -> { lookUp(goalText, if (u.intent == "weather" && !q.contains("weather", true)) "$q weather" else q); return true }
            "watch", "music" -> {
                if (u.device == "tv") { watchOnTv("$q on tv ${u.app ?: ""}"); return true }
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
            "video_call" -> { begin(goalText, skill("wa_video", if (u.person != null && !goalText.contains(u.person, true)) "video call ${u.person}" else goalText), autoMode); return true }
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
            val t = a ?: say("Here are the results. I've opened them for you.", "नतीजे खोल दिए हैं।", "ఫలితాలు తెరిచాను.").pick(lang)
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
        if (f?.launch != null && coachGoal == null) LlmManager.unload()

        // Instant skills (torch, volume): just do it and say so.
        f?.action?.let { act ->
            val done = act(svc).pick(lang)
            Memory.completed(f.id)
            if (f.quiet) { stop(); return } // the screen it opened speaks for itself
            finish(done)
            return
        }

        // No app and no skill, asked from the home screen: there's nothing to guide on. Answer instead of wandering.
        if (f == null) {
            val here = svc.rootInActiveWindow?.packageName?.toString()
            if (here == null || here == launcherPkg() || here == svc.packageName) { answerQuestion(goalText); return }
        }

        goal = goalText
        flow = f
        history.clear(); current = null; lastSpokenKey = null; lastSig = 0; warnedSig = 0; lastStepIdx = -1; scrolls = 0
        taskPkgs.clear(); paused = false; pendingLearn = null; adoptPkg = true
        auto = autoMode; autoSteps = 0; autoLastKey = null; autoSameKey = 0; setAside = false
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
            val ok = intent != null && runCatching { svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
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
        if (screen.signature == lastSig) return
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
        if (current?.warn == true && goal == null) clearVisuals()

        // Keep the card clear of the keyboard.
        overlay.setImeVisible(runCatching { svc.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false))

        // 4. No task → nothing more.
        val g = goal ?: return
        val f = flow
        // 5. Keyboard, notification shade, permission dialog: just wait.
        if (isTransient(screen.pkg)) return

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
            ScreenKinds.ad(screen)?.let { close ->
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
            // 9. The next target is probably just off-screen: ask them to scroll (max 3, trap #13).
            if (f.steps.isNotEmpty() && scrolls < 3 && screen.scrollable() != null) {
                val next = f.steps.getOrNull(lastStepIdx + 1) ?: f.steps.first()
                val name = next.targets.first().pattern.replace("\\Q", "").replace("\\E", "").replace(Regex("\\{\\d+,?\\d*\\}"), "")
                    .replace(Regex("[\\^$\\\\()?*+.\\[\\]]"), "").substringBefore('|').trim()
                val text = if (name.length >= 3) say("Slowly scroll down. Look for \"$name\".", "धीरे से नीचे स्क्रॉल कीजिए। \"$name\" ढूँढिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి. \"$name\" వెతకండి.").pick(lang)
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
        while (!lowPower && !LlmManager.isReady && LlmManager.state.value is LlmManager.State.Loading && waited < 8000) { delay(200); waited += 200 }
        val learned = f?.steps?.mapNotNull { Memory.learnedLabel(screen.pkg, it.key) }.orEmpty()
        replan = false
        plansThisTask++
        var d = try {
            Planner.decideInTask(taskKey, f?.llmGoal ?: g, screen, lastActionNote, lang, AppLauncher.labelOf(svc, screen.pkg), allowLlm = !lowPower,
                progress = history.toList())
        } finally { thinking = false; overlay.setAura(false) }
        lastActionNote = null
        // Never lead them to Help / About / Privacy / Terms unless they asked for it.
        d.targetId?.let { screen.byId(it) }?.let { el -> if (ScreenKinds.avoid(el.label, g)) d = d.copy(targetId = null) }
        com.saathi.app.DebugLog.i("plan", "goal=\"${f?.llmGoal ?: g}\" pkg=${screen.pkg} llm=${d.fromLlm} target=${d.targetId?.let { screen.byId(it)?.label?.take(50) }} done=${d.done} noAct=${d.noAct} lowPower=$lowPower")
        if (goal == null) return
        if (settling() > 0 || readScreen()?.signature != screen.signature) {
            lastSig = 0; schedule(200, force = true); return // the screen moved while we thought: look again
        }
        if (d.done) { f?.let { complete(it) } ?: finish(d.say) }
        else if (d.action == "back") {
            show(Target(null, d.say, "plan_back_$fp", fill = "__BACK__"))
        } else if (d.action == "ask") {
            // The model needs them to decide: ask out loud, wait for their answer (the Ask sheet adds it to the goal).
            current = Target(null, d.say, "ask_$fp"); lastSpokenKey = current?.key
            planCache[fp] = Plan(null, null, d.say, false)
            overlay.highlight(null, false)
            overlay.showCard(d.say, Overlay.Mode.INFO)
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
                show(Target(el, if (fill != null) say("Tap the search box and type “$fill”.", "खोज में “$fill” लिखिए।", "వెతుకులో “$fill” టైప్ చేయండి.").pick(lang) else text,
                    "plan_${el.label}", fill = fill, noAct = d.noAct))
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
        val text = quoteRealLabel(st.say.pick(lang), el)
        show(Target(el, text, st.key, st.fill,
            final = f.isDone == null && i == f.steps.lastIndex,
            tip = if (teach) st.tip?.pick(lang) else null,
            progress = (i + 1) to f.steps.size), practiced = Memory.timesDone(f.id) >= 3)
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
    private fun matchStep(f: Flow, screen: Screen): Pair<Int, UiElement>? {
        for (i in f.steps.indices.reversed()) {
            val st = f.steps[i]
            val learned = Memory.learnedLabel(screen.pkg, st.key)?.let { listOf(Regex("^" + Regex.escape(it) + "$")) }.orEmpty()
            if (st.screenHas != null && !st.screenHas.containsMatchIn(screen.allText)) continue
            if (st.unlessVisible.isNotEmpty() && screen.find(st.unlessVisible) != null) continue
            val el = screen.find(st.targets + learned, st.role) ?: continue
            return i to el
        }
        return null
    }

    private fun show(t: Target, practiced: Boolean = false) {
        hideJob?.cancel()
        current = t
        delayedGlow?.cancel()
        val newKey = t.key != lastSpokenKey
        if (practiced && newKey && !t.warn) {
            // Fading help: they've done this before, so give them a moment to find it themselves.
            overlay.highlight(null, false)
            delayedGlow = scope.launch { delay(3500); if (current?.key == t.key) overlay.highlight(t.el?.bounds, false) }
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

    private fun askConfirm(t: Target) {
        com.saathi.app.DebugLog.i("auto", "confirm before \"${t.el?.title}\"")
        awaitingConfirm = true
        val label = t.el?.title?.take(30) ?: ""
        val q = say("Shall I press “$label”?", "क्या मैं “$label” दबाऊँ?", "“$label” నొక్కనా?").pick(lang)
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
        val t = current ?: return
        if (awaitingConfirm) { awaitingConfirm = false; performStep(t); return }
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
        com.saathi.app.DebugLog.i("act", "key=${t.key} target=\"${el?.label?.take(60)}\" role=${el?.role} auto=$auto fill=${t.fill != null}")
        if (el == null && t.fill == "__BACK__") {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            lastSig = 0; replan = true; schedule(600, force = true); return
        }
        if (el == null) {
            // A scroll hint: "Do it" scrolls for them.
            fresh?.scrollable()?.node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
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
        finish(f.doneSay.pick(lang))
    }

    private fun finish(text: String) {
        LlmManager.endChat()
        goal?.let { Conversation.remember(it, text) }
        com.saathi.app.DebugLog.i("finish", "\"${text.take(120)}\" goal=\"$goal\"")
        auto = false; awaitingConfirm = false; autoJob?.cancel(); watchdog?.cancel()
        goal = null; flow = null; current = null; paused = false; taskPkgs.clear()
        delayedGlow?.cancel()
        Memory.clearTask()
        overlay.highlight(null, false)
        overlay.showCard(text, Overlay.Mode.DONE)
        speaker.say(text, lang)
        svc.buzz()
        hideJob?.cancel()
        hideJob = scope.launch { delay(6000); if (goal == null) overlay.hideCard() }
    }

    fun stop() {
        setAside = false
        LlmManager.endChat()
        goal?.let { g -> if (lastStepIdx >= 0 || history.isNotEmpty()) Memory.journal("Started but stopped: $g (got to: ${history.lastOrNull() ?: "start"})") }
        if (goal != null) com.saathi.app.DebugLog.i("stop", "goal=\"$goal\" step=$lastStepIdx")
        auto = false; awaitingConfirm = false; autoJob?.cancel(); watchdog?.cancel()
        goal = null; flow = null; paused = false; taskPkgs.clear()
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
        val t = if (list.isEmpty()) say("No new messages. (If this is wrong, allow Saathi to read notifications in Settings.)",
            "कोई नया संदेश नहीं। (अगर ग़लत है, तो Settings में Saathi को सूचनाएँ पढ़ने दीजिए।)",
            "కొత్త సందేశాలు లేవు. (తప్పైతే, Settings లో Saathi కి నోటిఫికేషన్ అనుమతి ఇవ్వండి.)").pick(lang)
        else say("${list.size} recent messages. ", "${list.size} नए संदेश। ", "${list.size} కొత్త సందేశాలు. ").pick(lang) +
            list.joinToString(" ") { m -> say("${m.sender} on ${m.app} says: ${m.text.take(160)}.", "${m.app} पर ${m.sender} ने लिखा: ${m.text.take(160)}।", "${m.app} లో ${m.sender}: ${m.text.take(160)}.").pick(lang) }
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
        val t = if (w.setup) say("$app isn't set up on this phone yet. It needs your phone number and a code by SMS — best done together with family. I'll wait.",
                "$app अभी इस फ़ोन पर चालू नहीं है। इसके लिए आपका नंबर और SMS कोड चाहिए — परिवार के साथ कीजिए। मैं इंतज़ार करूँगा।",
                "$app ఇంకా ఈ ఫోన్‌లో సెట్ కాలేదు. దీనికి మీ నంబర్, SMS కోడ్ కావాలి — కుటుంబంతో కలిసి చేయండి. నేను వేచి ఉంటాను.").pick(lang)
            else say("$app needs you to sign in first. Only you should type your password. Tap “${w.button?.title ?: "Sign in"}”.",
                "$app में पहले साइन इन करना होगा। पासवर्ड सिर्फ़ आप लिखिए। “${w.button?.title ?: "Sign in"}” दबाइए।",
                "$app లో ముందు సైన్ ఇన్ చేయాలి. పాస్‌వర్డ్ మీరే టైప్ చేయండి. “${w.button?.title ?: "Sign in"}” నొక్కండి.").pick(lang)
        com.saathi.app.DebugLog.i("wall", "${screen.pkg} setup=${w.setup} button=${w.button?.title}")
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
        if (pkg != null) overlay.showCard(t, Overlay.Mode.ASK, onContinue = {
            overlay.hideCard()
            runCatching { svc.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) else overlay.showCard(t, Overlay.Mode.DONE)
        speaker.say(t, lang)
    }

    private val KNOWN_APPS = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.youtube" to "YouTube", "com.netflix.mediaclient" to "Netflix",
        "in.startv.hotstar" to "JioHotstar", "cris.org.in.prs.ima" to "IRCTC Rail Connect", "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.apps.photos" to "Google Photos", "com.google.android.apps.nbu.paisa.user" to "Google Pay", "com.phonepe.app" to "PhonePe")

    /** A question or chit-chat: answer out loud (the Clicky lesson), no screen navigation. */
    fun answerQuestion(q: String) {
        // A question in the middle of a task sets the task aside (not lost): answer, then offer to continue it.
        val interrupted = goal
        if (interrupted != null) { setAside = true; autoJob?.cancel(); overlay.highlight(null, false) } else stop()
        lang = Prefs.lang(svc)
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        scope.launch {
            overlay.setAura(true)
            overlay.showCard(say("Let me think…", "सोच रहा हूँ…", "ఆలోచిస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
            var waited = 0
            while (!LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed && waited < 9000) { delay(200); waited += 200 }
            val a = Conversation.answer(q, lang, Prefs.name(svc))
            overlay.setAura(false)
            Conversation.remember(q, a)
            com.saathi.app.DebugLog.i("answer", "q=\"$q\" a=\"${a.take(200)}\"")
            val howTo = Regex("(?i)^how (to|do|can)|recipe|कैसे|ఎలా").containsMatchIn(q) && !IntentRouter.isGreeting(q)
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
    private fun searchTerm(goal: String): String? {
        val stop = setOf("change", "my", "the", "a", "an", "how", "to", "do", "i", "set", "open", "turn", "on", "off", "make", "please", "want", "can", "you", "find", "show", "me", "is", "in")
        val w = SlotExtractor.searchPhrase(goal).split(Regex("\\s+")).filter { it.length > 2 && it.lowercase() !in stop }
        return w.takeLast(2).joinToString(" ").ifBlank { null }
    }

    private fun launcherPkg(): String? = runCatching {
        svc.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()

    /** Apps that tasks legitimately hop into: sign-in, browser tabs, the store, pickers, camera, payment sheets. */
    private fun helperApp(pkg: String) = Regex("gms|chrome|browser|vending|appstore|packageinstaller|documentsui|photopicker|camera|gallery|" +
        "providers|webview|auth|login|paisa|phonepe|paytm|npci|contacts|dialer|incallui|telecom").containsMatchIn(pkg)

    // ───────────────────────── the coach (think → check → ask → guide) ─────────────────────────

    private var coachGoal: String? = null
    private var coachKey = ""
    private var coachSteps = 0
    @Volatile private var coachWaiting = false

    fun startCoach(g: String) {
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
            val raw = LlmManager.chat(coachKey, Coach.SYSTEM, msg)
            overlay.setAura(false)
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
                coachGoal = null; coachWaiting = false
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
        if (IntentRouter.isQuestion(svc, g)) return "question"
        if (Regex("(?i)^\\s*(remember|note down|याद रखो|याद रखना|గుర్తుంచుకో)\\b").containsMatchIn(g)) return "note"
        Recipes.find(svc, g)?.let { return "recipe" }
        if (LlmManager.isReady || com.saathi.app.llm.FastBrain.isReady || com.saathi.app.llm.ModelLocator.fast(svc) != null) {
            val u = Understand.parse(g, svc)
            if (u != null) {
                if (Coach.wants(g, u.intent) || (u.intent == "watch" && u.device == "tv")) return "coach"
                Skills.match(g)?.takeIf { it.id in IntentRouter.DIRECT && u.intent !in setOf("weather", "lookup", "question", "watch", "music") }?.let { return "skill:${it.id}" }
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

    /** A routine's time came: offer it (card + voice). Reminders just remind; tasks run on "Yes". */
    fun routineDue(r: Routines.Routine) {
        lang = Prefs.lang(svc)
        Log.i(TAG, "routine due: ${r.goal} (active=$active)")
        com.saathi.app.DebugLog.i("routine", "due ${r.goal} kind=${r.kind} active=$active")
        if (active) return // don't interrupt a task in progress
        hideJob?.cancel() // a previous "done" card must not hide this offer
        val med = r.kind == "remind"
        val what = r.goal.replace(Regex("(?i)^remind me (to )?"), "")
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
