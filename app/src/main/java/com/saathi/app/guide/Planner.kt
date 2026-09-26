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
    )

    /** "Knowledge pack": how Android screens work, in the words an elder needs. Kept short for speed. */
    private const val SYSTEM =
        "You are Saathi, a kind, patient helper for an elderly person in India using an Android phone.\n" +
        "You see their goal and a numbered list of what is on the screen. Pick the ONE item to tap next.\n" +
        "How phones work:\n" +
        "- A magnifying glass or 'Search' finds things. Three dots or 'More options' opens a menu.\n" +
        "- A gear or 'Settings' changes settings. An arrow at the top left or 'Back'/'Navigate up' goes back.\n" +
        "- '+' or 'New' creates something. A pencil or 'Edit' changes it. A paper plane or 'Send' sends.\n" +
        "- To type, pick the input box first. Switches turn things on and off.\n" +
        "Rules:\n" +
        "- Choose items whose words match the goal. Never choose ads, 'Sponsored', 'Install', or anything about paying,\n" +
        "  OTP, PIN or passwords unless the goal clearly asks for it.\n" +
        "- If what they need is not on this screen (maybe further down), reply TAP: -1.\n" +
        "Reply with exactly two lines and nothing else:\n" +
        "TAP: <number>\n" +
        "SAY: <one short, warm sentence telling them what to tap, under 15 words>\n" +
        "If the goal is already done, reply TAP: 0 and SAY: a short congratulation."

    /** Two worked examples, so a small model gets the format and the judgement right. */
    private const val SHOTS =
        "Example 1\nGoal: turn on dark mode\nScreen:\n[1] button \"Wi-Fi\"\n[2] button \"Display\"\n[3] button \"Sound\"\n" +
        "TAP: 2\nSAY: Tap Display, where the screen colours are.\n\n" +
        "Example 2\nGoal: add a new contact\nScreen:\n[1] button \"Search contacts\"\n[2] button \"Create new contact\"\n[3] text \"Rahul\"\n" +
        "TAP: 2\nSAY: Tap Create new contact to add someone.\n\n"

    /** Labels we will glow but never tap on the person's behalf. */
    private val RISKY = Regex(
        "(?i)\\bpay\\b|pay ₹|send money|transfer|upi pin|\\bpin\\b|otp|password|install|uninstall|delete|remove account|" +
            "factory reset|erase|screen ?shar|remote|anydesk|teamviewer|quicksupport|allow access|grant|buy|purchase|subscribe")

    fun buildUser(goal: String, screen: Screen, history: List<String>, learned: List<String>): String = buildString {
        append(SHOTS)
        append("Now\nGoal: ").append(goal).append('\n')
        if (history.isNotEmpty()) append("Already tapped: ").append(history.takeLast(4).joinToString(", ")).append('\n')
        if (learned.isNotEmpty()) append("On this phone, these names were right before: ").append(learned.take(4).joinToString(", ")).append('\n')
        append("Screen:\n").append(screen.forPrompt()).append('\n')
    }

    suspend fun decide(goal: String, screen: Screen, history: List<String>, lang: Lang, learned: List<String> = emptyList()): Decision {
        // Banking / UPI screens never go to the model at all: keywords and scripts only.
        val raw = if (LlmManager.isReady && !isMoneyApp(screen.pkg)) LlmManager.generate(SYSTEM, buildUser(goal, screen, history, learned)) else null
        val d = raw?.let { parse(it, screen, lang) } ?: heuristic(goal, screen, lang)
        return guard(d, screen, lang)
    }

    private val MONEY_PKGS = Regex("paisa|phonepe|paytm|npci|sbi|icici|hdfc|axis|kotak|bank|upi|wallet|pay", RegexOption.IGNORE_CASE)
    fun isMoneyApp(pkg: String) = MONEY_PKGS.containsMatchIn(pkg)

    /** Pure: model text → decision, or null if it isn't usable. */
    fun parse(raw: String, screen: Screen, lang: Lang = Lang.EN): Decision? {
        val id = Regex("TAP:\\s*(-?\\d+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val llmSay = Regex("SAY:\\s*\"?([^\"\\n]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()
            ?.takeIf { lang == Lang.EN && it.length in 4..140 && !Templates.garbled(it) && !it.contains(Regex("https?://|www\\.")) }
        if (id == 0) return Decision(null, llmSay ?: say("All done!", "हो गया!", "అయిపోయింది!").pick(lang), done = true, fromLlm = true)
        if (id < 0) return Decision(null, scrollSay(lang), done = false, fromLlm = true)
        val el = screen.byId(id)?.takeIf { it.role != "text" && it.label.isNotBlank() } ?: return null
        return Decision(el.id, llmSay ?: tapSay(el.title, lang), done = false, fromLlm = true)
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
        val words = goal.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }
        val best = screen.elements.filter { it.role != "text" && it.enabled && !it.password }
            .maxByOrNull { e -> words.count { it in e.label.lowercase() } }
            ?.takeIf { e -> words.any { it in e.label.lowercase() } }
        if (best != null) return Decision(best.id, tapSay(best.title, lang), done = false, fromLlm = false)
        return Decision(null, scrollSay(lang), done = false, fromLlm = false)
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
