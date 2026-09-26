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

/**
 * The guide loop. It never fights the person holding the phone:
 *
 *   screen settles → read → scam check → right app? (else pause) → done? → latest visible scripted step
 *   → scroll hint → planner (LLM in P0-4)  ⇒  glow + card + voice.
 *
 * Saathi only taps when the person presses "Do it", and only after re-finding the target on a fresh read.
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

    val active get() = goal != null

    // ───────────────────────── entry points ─────────────────────────

    /** A spoken or typed sentence. During a task, short commands steer the task. */
    fun handleUtterance(text: String) {
        val t = text.lowercase().trim()
        fun any(vararg w: String) = w.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
        if (active || current != null) when {
            any("do it", "do it for me", "you do it", "कर दो", "आप करो", "तुम करो", "చేయి", "మీరే చేయండి") -> { doItForMe(); return }
            any("again", "repeat", "say again", "फिर से", "दोबारा", "మళ్ళీ") -> { repeat(); return }
            any("stop", "cancel", "bas", "बस", "बंद करो", "रुको", "ఆపు", "ఆపండి", "వద్దు") -> { stop(); return }
            any("done", "ho gaya", "हो गया", "అయింది") -> { onFinalDone(); return }
        }
        start(text)
    }

    fun start(goalText: String) {
        lang = Prefs.lang(svc)
        hideJob?.cancel()
        Log.i(TAG, "goal: $goalText")
        if (IntentRouter.isScamCheck(goalText)) { scamCheck(); return }
        if (IntentRouter.isFamilyHelp(goalText)) { askFamily(); return }
        if (IntentRouter.isExplain(goalText)) { explain(); return }
        rememberRequest(goalText)?.let { finish(it); return }
        val f = IntentRouter.route(svc, goalText)

        // Instant skills (torch, volume): just do it and say so.
        f?.action?.let { act ->
            val done = act(svc).pick(lang)
            Memory.completed(f.id)
            finish(done)
            return
        }

        goal = goalText
        flow = f
        history.clear(); current = null; lastSpokenKey = null; lastSig = 0; warnedSig = 0; lastStepIdx = -1; scrolls = 0
        taskPkgs.clear(); paused = false; pendingLearn = null; adoptPkg = true

        var hello = f?.start?.pick(lang) ?: say(
            "Okay! Let's do it together. Watch for the glow.",
            "ठीक है! साथ में करते हैं। चमक को देखिए।",
            "సరే! కలిసి చేద్దాం. మెరుపును చూడండి.").pick(lang)
        f?.launch?.let { make ->
            val intent = runCatching { make(svc) }.getOrNull()
            val ok = intent != null && runCatching { svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) resolvePkg(intent!!)?.let { taskPkgs += it; adoptPkg = false }
            else hello = say("That app isn't on this phone, so let's do it from here.",
                "यह ऐप फ़ोन में नहीं है, तो यहीं से करते हैं।",
                "ఆ యాప్ ఈ ఫోన్‌లో లేదు, ఇక్కడి నుంచే చేద్దాం.").pick(lang)
        }
        val n = f?.let { Memory.timesDone(it.id) } ?: 0
        if (f != null && f.steps.isNotEmpty() && n >= 3) {
            hello += " " + say("You've done this $n times. Try first; I'll glow if you need me.",
                "आप यह $n बार कर चुके हैं। पहले ख़ुद कोशिश कीजिए, ज़रूरत हो तो मैं दिखाऊँगा।",
                "మీరు ఇది $n సార్లు చేశారు. ముందు మీరే ప్రయత్నించండి.").pick(lang)
        }
        persist()
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

    // ───────────────────────── the decision ladder (playbook §5) ─────────────────────────

    private suspend fun tick() {
        // 0. Never guide inside Saathi's own screens (trap #10).
        if (SaathiService.ownUiOpen) return
        // 1. The person is touching or scrolling: wait.
        val wait = settling()
        if (wait > 0) { job = scope.launch { delay(wait + 30); tick() }; return }
        overlay.setMoving(false)

        // 2. Read; nothing changed → nothing to do.
        val screen = ScreenReader.read(svc.rootInActiveWindow) ?: return
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

        // 4. No task → nothing more.
        val g = goal ?: return
        val f = flow
        // 5. Keyboard, notification shade, permission dialog: just wait.
        if (isTransient(screen.pkg)) return

        // 6. Right app? Adopt the first real app for open-ended tasks; otherwise pause politely.
        if (adoptPkg && screen.pkg.isNotBlank()) { taskPkgs += screen.pkg; adoptPkg = false }
        val stepHere = f?.let { matchStep(it, screen) }
        if (taskPkgs.isNotEmpty() && screen.pkg !in taskPkgs) {
            if (stepHere != null) taskPkgs += screen.pkg // flows legitimately hop apps (photo picker)
            else { pause(); return }
        }
        if (paused) resume()

        if (f != null) {
            // 7. Finished?
            if (f.isDone?.invoke(screen) == true) { complete(f); return }
            // 8. Latest scripted step whose target is visible.
            if (stepHere != null) { showStep(f, screen, stepHere.first, stepHere.second); return }
            // 9. The next target is probably just off-screen: ask them to scroll (max 3, trap #13).
            if (f.steps.isNotEmpty() && scrolls < 3 && screen.scrollable() != null) {
                val next = f.steps.getOrNull(lastStepIdx + 1) ?: f.steps.first()
                val name = next.targets.first().pattern.replace(Regex("[\\^$\\\\()?*+.\\[\\]]"), "").substringBefore('|').trim()
                val text = if (name.length >= 3) say("Slowly scroll down. Look for \"$name\".", "धीरे से नीचे स्क्रॉल कीजिए। \"$name\" ढूँढिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి. \"$name\" వెతకండి.").pick(lang)
                else say("Slowly scroll down to see more.", "धीरे से नीचे स्क्रॉल कीजिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేయండి.").pick(lang)
                scrolls++
                show(Target(null, text, "scroll_${next.key}", scroll = true))
                return
            }
        }

        // 10a. The planner already picked something and it's still here: follow it (it may have moved), don't re-ask.
        if (!replan) current?.takeIf { it.key.startsWith("plan_") && it.el != null && goal != null }?.let { cur ->
            val still = screen.elements.firstOrNull { it.label == cur.el!!.label && it.role == cur.el.role }
            if (still != null) { if (still.bounds != cur.el!!.bounds) show(cur.copy(el = still)); return }
        }

        // 10. Anything else: the planner (on-device LLM, loaded on first need; keywords while it warms up).
        if (!LlmManager.isReady) LlmManager.loadAsync(svc)
        delay(50)
        thinking = true
        overlay.highlight(null, false)
        overlay.setAura(true)
        overlay.showCard(say("Let me look…", "मैं देख रहा हूँ…", "చూస్తున్నాను…").pick(lang), Overlay.Mode.THINKING)
        // First use: give the model a few seconds to load (Gemma 4 on the GPU ≈ 4.5 s) before falling back.
        var waited = 0
        while (!LlmManager.isReady && LlmManager.state.value is LlmManager.State.Loading && waited < 8000) { delay(200); waited += 200 }
        val learned = f?.steps?.mapNotNull { Memory.learnedLabel(screen.pkg, it.key) }.orEmpty()
        replan = false
        val d = try { Planner.decide(f?.llmGoal ?: g, screen, history, lang, learned) } finally { thinking = false; overlay.setAura(false) }
        if (goal == null) return
        if (settling() > 0 || ScreenReader.read(svc.rootInActiveWindow)?.signature != screen.signature) {
            lastSig = 0; schedule(200, force = true); return // the screen moved while we thought: look again
        }
        if (d.done) { f?.let { complete(it) } ?: finish(d.say) }
        else {
            val el = d.targetId?.let { screen.byId(it) }
            if (f != null && el != null) f.steps.getOrNull(lastStepIdx + 1)?.let { pendingLearn = Triple(screen.pkg, it.key, el.title) }
            show(Target(el, d.say, "plan_${el?.label ?: "scroll"}", noAct = d.noAct, scroll = el == null))
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
        current = t
        delayedGlow?.cancel()
        val newKey = t.key != lastSpokenKey
        if (practiced && newKey && !t.warn) {
            // Fading help: they've done this before, so give them a moment to find it themselves.
            overlay.highlight(null, false)
            delayedGlow = scope.launch { delay(3500); if (current?.key == t.key) overlay.highlight(t.el?.bounds, false) }
        } else overlay.highlight(t.el?.bounds, t.warn)
        val mode = when {
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
    }

    private fun pause() {
        if (paused) return
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

    private fun resume() {
        paused = false
        lastSpokenKey = null // re-announce the step we're on
    }

    fun repeat() { current?.let { speaker.say(it.text, lang) } }

    /** "Do it for me": tap or type on their behalf, only on a settled, freshly re-read screen. */
    fun doItForMe() {
        val t = current ?: return
        if (t.noAct) { speaker.say(t.text, lang); return }
        val wait = settling()
        if (wait > 0) { scope.launch { delay(wait + 50); doItForMe() }; return }
        // List rows get recycled while scrolling: re-find by label + role on a fresh read (trap #11).
        val fresh = ScreenReader.read(svc.rootInActiveWindow)
        val el = t.el?.let { old ->
            fresh?.elements?.firstOrNull { it.label == old.label && it.role == old.role }
                ?: run { lastSig = 0; schedule(200, force = true); return }
        }
        if (el == null) {
            // A scroll hint: "Do it" scrolls for them.
            fresh?.scrollable()?.node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
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
        f.memo?.let { Memory.addReminder(it) }
        finish(f.doneSay.pick(lang))
    }

    private fun finish(text: String) {
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
            val screen = ScreenReader.read(svc.rootInActiveWindow) ?: return@launch
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
            val screen = ScreenReader.read(svc.rootInActiveWindow)
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
    fun askFamily() {
        lang = Prefs.lang(svc)
        val screen = ScreenReader.read(svc.rootInActiveWindow)
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
