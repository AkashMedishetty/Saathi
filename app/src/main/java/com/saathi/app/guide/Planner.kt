package com.saathi.app.guide

import com.saathi.app.llm.LlmManager
import com.saathi.app.llm.Templates

/**
 * Decides what to tap when no scripted step matches.
 *
 * The on-device LLM (Gemma 4 on the GPU) chooses WHAT; our code decides whether it's allowed and HOW it's said:
 *  - it can only pick an id that is on screen (trap #6), never a PIN/password field;
 *  - risky targets (pay, install, share screen, OTP…) are shown but "Do it" is refused, with a warning;
 *  - Hindi/Telugu speech comes from templates (trap #5); English keeps the model's sentence only if it's clean.
 * If the model isn't loaded or answers badly, a keyword heuristic takes over.
 */
object Planner {
    data class Decision(
        val targetId: Int?,
        val say: String,
        val done: Boolean,
        val fromLlm: Boolean,
        /** Glow it, but never tap it for them. */
        val noAct: Boolean = false,
        /** tap | type | scroll | back | ask | done */
        val action: String = "tap",
        /** For TYPE: what to type. For ASK: the question. */
        val text: String? = null,
    )

    /**
     * Model-first: Gemma decides with ONE tool call; our code only validates it and keeps it safe.
     * (Short on purpose: a 2B model follows a small, clear contract better than a long rule list.)
     */
    private const val SYSTEM =
        "You are Saathi, a patient helper guiding an elderly person through their Android phone, one step at a time.\n" +
        "You get their goal, the app that is open, what was already done, and a numbered list of what is on the screen.\n" +
        "Reply with exactly two lines:\n" +
        "Line 1, one action: TAP <n> | TYPE <n> <text> | SCROLL | BACK | DONE | ASK <short question for the person>\n" +
        "Line 2: SAY <one short, warm sentence telling them what to do, using the words written on the screen>\n" +
        "Use DONE when the goal is already achieved. Use ASK if the goal is unclear or they must decide (which person, which item).\n" +
        "Only they type passwords, PINs and OTPs, and only they sign in: for those, tell them what to do and use DONE if nothing else is needed."

    private const val SHOTS =
        "Example\nGoal: find the ringtone setting\nApp open: Settings\nScreen:\n[1] button \"Wi-Fi\"\n[2] button \"Sounds & vibration\"\n[3] button \"Display\"\n" +
        "TAP 2\nSAY Tap Sounds & vibration, where the ringtone is.\n\n" +
        "Example\nGoal: search for bhajans\nApp open: YouTube\nScreen:\n[1] input \"Search YouTube\"\n[2] button \"Back\"\n" +
        "TYPE 1 bhajan\nSAY Type bhajan in the search box.\n\n"

    /** Labels we will glow but never tap on the person's behalf. */
    private val RISKY = Regex(
        "(?i)\\bpay\\b|pay ₹|send money|transfer|upi pin|\\bpin\\b|otp|password|install|uninstall|delete|remove account|" +
            "factory reset|erase|screen ?shar|remote|anydesk|teamviewer|quicksupport|allow access|grant|buy|purchase|subscribe")

    fun buildUser(goal: String, screen: Screen, history: List<String>, learned: List<String>, app: String = ""): String = buildString {
        append(SHOTS)
        val talk = Conversation.recent().takeLast(3)
        if (talk.isNotEmpty()) append("Recent conversation:\n").append(talk.joinToString("\n") { (u, a) -> "- they said \"${u.take(80)}\", Saathi said \"${a.take(80)}\"" }).append('\n')
        append("Now\nApp open: ").append(app.ifBlank { screen.pkg }).append('\n')
        append("Goal: ").append(goal).append('\n')
        if (history.isNotEmpty()) append("Already tapped: ").append(history.takeLast(4).joinToString(", ")).append('\n')
        if (learned.isNotEmpty()) append("On this phone, these names were right before: ").append(learned.take(4).joinToString(", ")).append('\n')
        append("Screen:\n").append(screen.forPrompt()).append('\n')
    }

    fun isRisky(label: String) = RISKY.containsMatchIn(label)

    /**
     * The task's ongoing conversation with the model: the first turn carries the goal and examples; each later turn
     * says what just happened and shows the new screen. The model keeps everything earlier in its memory.
     */
    suspend fun decideInTask(taskKey: String, goal: String, screen: Screen, lastAction: String?, lang: Lang, app: String, allowLlm: Boolean,
                             progress: List<String> = emptyList()): Decision {
        if (!allowLlm || !LlmManager.isReady || isMoneyApp(screen.pkg)) return guard(heuristic(goal, screen, lang), screen, lang)
        val first = !LlmManager.inChat(taskKey)
        val msg = buildString {
            if (first) {
                append(SHOTS)
                val talk = Conversation.recent().takeLast(3)
                if (talk.isNotEmpty()) append("Recent conversation:\n").append(talk.joinToString("\n") { (u, a) -> "- they said \"${u.take(80)}\", Saathi said \"${a.take(80)}\"" }).append('\n')
                Memory.relevant(goal).takeIf { it.isNotEmpty() }?.let { append("What you know about them (use if helpful):\n").append(it.joinToString("\n") { f -> "- $f" }).append('\n') }
                append("Now the real task.\nGoal: ").append(goal).append('\n')
                // Resumed or re-started conversation: the model still knows what was done so far.
                if (progress.isNotEmpty()) append("Already done in this task: ").append(progress.takeLast(6).joinToString(" → ")).append('\n')
            } else {
                append(lastAction ?: "The screen changed.").append('\n')
                append("(Goal is still: ").append(goal).append(")\n")
            }
            append("App open: ").append(app.ifBlank { screen.pkg }).append('\n')
            append("Screen:\n").append(screen.forPrompt()).append('\n')
        }
        LlmManager.lastChatKey = taskKey
        val raw = LlmManager.chat(taskKey, SYSTEM, msg)
        val d = raw?.let { parse(it, screen, lang) } ?: heuristic(goal, screen, lang)
        return guard(ground(d, goal, screen, lang), screen, lang)
    }

    private val GENERIC_NAV = Regex("(?i)^(search|more|more options|menu|next|continue|ok|okay|allow|done|save|yes|apply|confirm|settings|open|start|got it|agree|" +
        "use without|skip|no,? thanks|not now|accept|dismiss|close|later|maybe later|while using|only this time|i agree)\\b")

    private fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()

    /**
     * Ground truth over guesses (field test: "eye protection" → the model tapped "System update", the top row).
     * An unrelated pick loses to an on-screen item that shares words with the goal; with nothing related visible,
     * scroll (or use search) instead of guessing. Generic navigation (Search, More, Next, OK…) stays allowed.
     */
    fun ground(d: Decision, goal: String, screen: Screen, lang: Lang): Decision {
        if (d.action != "tap" && d.action != "type") return d
        val el = d.targetId?.let { screen.byId(it) } ?: return d
        val gw = words(goal)
        if (gw.isEmpty() || words(el.label).any { it in gw } || GENERIC_NAV.containsMatchIn(el.title) || el.role == "input") return d
        val better = screen.elements.filter { it.role != "text" && it.enabled && !it.password }
            .map { it to words(it.label).count { w -> w in gw } }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        if (better != null) return d.copy(targetId = better.id, say = tapSay(better.title, lang))
        val search = screen.elements.firstOrNull { it.enabled && Regex("(?i)^search").containsMatchIn(it.title) }
        if (search != null) return d.copy(targetId = search.id, say = tapSay(search.title, lang))
        return d // nothing better on screen: trust the model (it has the whole task in memory)
    }

    suspend fun decide(goal: String, screen: Screen, history: List<String>, lang: Lang, learned: List<String> = emptyList(), allowLlm: Boolean = true, app: String = ""): Decision {
        // Banking / UPI screens never go to the model at all: keywords and scripts only. Low battery: keywords only.
        val raw = if (allowLlm && LlmManager.isReady && !isMoneyApp(screen.pkg)) LlmManager.generate(SYSTEM, buildUser(goal, screen, history, learned, app)) else null
        val d = raw?.let { parse(it, screen, lang) } ?: heuristic(goal, screen, lang)
        return guard(d, screen, lang)
    }

    private val MONEY_PKGS = Regex("paisa|phonepe|paytm|npci|sbi|icici|hdfc|axis|kotak|bank|upi|wallet|pay", RegexOption.IGNORE_CASE)
    fun isMoneyApp(pkg: String) = MONEY_PKGS.containsMatchIn(pkg)

    /** Pure: model text → decision, or null if it isn't usable. Accepts the tool format and the old TAP:/SAY: one. */
    fun parse(raw: String, screen: Screen, lang: Lang = Lang.EN): Decision? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sayLine = lines.firstOrNull { it.startsWith("SAY", true) }?.replace(Regex("(?i)^SAY:?\\s*"), "")?.trim('"', ' ')
        val llmSay = sayLine?.takeIf { lang == Lang.EN && it.length in 4..160 && !Templates.garbled(it) && !it.contains(Regex("https?://|www\\.")) }
        val act = lines.firstOrNull { Regex("(?i)^(TAP|TYPE|SCROLL|BACK|DONE|ASK)\\b").containsMatchIn(it) } ?: return null
        val verb = act.substringBefore(' ').substringBefore(':').uppercase()
        val rest = act.substringAfter(verb, "").trimStart(':', ' ')
        return when (verb) {
            "DONE" -> Decision(null, llmSay ?: say("All done!", "हो गया!", "అయిపోయింది!").pick(lang), done = true, fromLlm = true, action = "done")
            "SCROLL" -> Decision(null, llmSay ?: scrollSay(lang), done = false, fromLlm = true, action = "scroll")
            "BACK" -> Decision(null, llmSay ?: say("Let's go back one step.", "एक क़दम वापस चलते हैं।", "ఒక అడుగు వెనక్కి వెళ్దాం.").pick(lang), done = false, fromLlm = true, action = "back")
            "ASK" -> rest.takeIf { it.length > 3 }?.let { q -> Decision(null, if (lang == Lang.EN) q else say("", "कृपया थोड़ा और बताइए।", "దయచేసి ఇంకొంచెం చెప్పండి.").pick(lang), done = false, fromLlm = true, action = "ask", text = q) }
            else -> {
                val id = Regex("-?\\d+").find(rest)?.value?.toIntOrNull() ?: return null
                if (id == 0) return Decision(null, llmSay ?: say("All done!", "हो गया!", "అయిపోయింది!").pick(lang), done = true, fromLlm = true, action = "done")
                if (id < 0) return Decision(null, scrollSay(lang), done = false, fromLlm = true, action = "scroll")
                val el = screen.byId(id)?.takeIf { it.role != "text" && it.label.isNotBlank() } ?: return null
                val typed = if (verb == "TYPE") rest.substringAfter(id.toString()).trim().trim('"').takeIf { it.isNotBlank() && el.role == "input" } else null
                Decision(el.id, llmSay ?: tapSay(el.title, lang), done = false, fromLlm = true, action = if (typed != null) "type" else "tap", text = typed)
            }
        }
    }

    /** Hard rules that no model output can bypass. */
    fun guard(d: Decision, screen: Screen, lang: Lang): Decision {
        val el = d.targetId?.let { screen.byId(it) } ?: return d
        if (el.password) return d.copy(noAct = true, say = say(
            "Type your PIN yourself, privately. I will never type it or read it.",
            "अपना PIN ख़ुद, छुपाकर लिखिए। मैं इसे कभी नहीं लिखूँगा।",
            "మీ PIN మీరే రహస్యంగా టైప్ చేయండి. నేను ఎప్పుడూ టైప్ చేయను.").pick(lang))
        if (RISKY.containsMatchIn(el.label)) return d.copy(noAct = true, say = d.say + " " + say(
            "Check carefully before you tap. I won't tap this one for you.",
            "दबाने से पहले ध्यान से जाँचिए। यह मैं आपके लिए नहीं दबाऊँगा।",
            "నొక్కే ముందు జాగ్రత్తగా చూడండి. దీన్ని నేను నొక్కను.").pick(lang))
        return d
    }

    fun heuristic(goal: String, screen: Screen, lang: Lang): Decision {
        val words = goal.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }
        val best = screen.elements.filter { it.role != "text" && it.enabled && !it.password }
            .maxByOrNull { e -> words.count { it in e.label.lowercase() } }
            ?.takeIf { e -> words.any { it in e.label.lowercase() } }
        if (best != null) return Decision(best.id, tapSay(best.title, lang), done = false, fromLlm = false)
        return Decision(null, scrollSay(lang), done = false, fromLlm = false, action = "scroll")
    }

    fun tapSay(label: String, lang: Lang) = say(
        "Tap “${label.take(40)}”.", "“${label.take(40)}” दबाइए।", "“${label.take(40)}” నొక్కండి.").pick(lang)

    private fun scrollSay(lang: Lang) = say(
        "I can't see it yet. Slowly scroll down to see more.",
        "मुझे अभी नहीं दिख रहा। धीरे से नीचे स्क्रॉल कीजिए।",
        "ఇంకా కనిపించట్లేదు. నెమ్మదిగా కిందకు స్క్రోల్ చేయండి.").pick(lang)

    /** "Where am I? / What is this?" in two short sentences; template fallback. */
    suspend fun explain(screen: Screen, appLabel: String, lang: Lang): String {
        val buttons = screen.elements.filter { it.role == "button" }.take(3).joinToString(", ") { it.title.take(25) }
        if (lang == Lang.EN && LlmManager.isReady) {
            val out = LlmManager.generate(
                "You explain phone screens to elderly people in very simple English. Two short sentences. No lists.",
                "App: $appLabel\nWhat is this screen and what can I do here?\nScreen:\n${screen.forPrompt(25)}",
            )
            if (!out.isNullOrBlank() && !Templates.garbled(out) && out.length < 300) return out
        }
        return say("You are in $appLabel. Here you can tap: $buttons.",
            "आप $appLabel में हैं। यहाँ आप दबा सकते हैं: $buttons।",
            "మీరు $appLabel లో ఉన్నారు. ఇక్కడ మీరు నొక్కగలిగేవి: $buttons.").pick(lang)
    }

    private val STOP = setOf("the", "and", "for", "with", "how", "open", "please", "want", "can", "you", "help", "use", "make",
        "text", "bigger", "smaller", "change", "phone", "turn", "set", "show", "this", "that", "my")
}
