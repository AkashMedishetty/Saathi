package com.saathi.app.policy

import com.saathi.app.guide.Say
import com.saathi.app.guide.say

sealed interface LoopVerdict {
    data object Ok : LoopVerdict
    data class Repeating(val say: Say) : LoopVerdict
    data class Stuck(val say: Say) : LoopVerdict
    data class OutOfSteps(val say: Say) : LoopVerdict
}

/** One instance per task. Inject a monotonic millisecond clock in tests; no raw screen text/typed values. */
class LoopGuard(val maxSteps: Int = 25, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(maxSteps > 0) }
    private var progressAt = nowMs()
    private var screen: Pair<String, String>? = null
    private var actions = 0
    private val transitions = ArrayDeque<Pair<String, String>>()
    private val repeats = mutableMapOf<String, Int>()
    private var repeating = false

    fun onScreen(fingerprint: String, pkg: String) {
        val next = pkg to fingerprint
        if (next == screen) return
        screen = next
        progressAt = maxOf(progressAt, nowMs())
        repeats.clear()
        transitions.addLast(next)
        if (transitions.size > 4) transitions.removeFirst()
        if (transitions.size == 4) {
            val t = transitions.toList()
            if (t[0] == t[2] && t[1] == t[3] && t[0] != t[1]) repeating = true
        }
    }

    fun onAction(key: String) {
        actions++
        val count = (repeats[key] ?: 0) + 1
        repeats[key] = count
        if (count >= 3) repeating = true
    }

    fun verdict(): LoopVerdict = when {
        actions >= maxSteps -> LoopVerdict.OutOfSteps(say(
            "Let's pause here. We can try another way or ask someone you trust.",
            "यहाँ रुकते हैं। दूसरा तरीका आज़मा सकते हैं या किसी भरोसेमंद व्यक्ति से पूछ सकते हैं।",
            "ఇక్కడ ఆగుదాం. మరో మార్గం ప్రయత్నించవచ్చు లేదా నమ్మకమైన వ్యక్తిని అడగవచ్చు."))
        repeating -> LoopVerdict.Repeating(say(
            "We are repeating the same steps. Shall we try another way or ask someone you trust?",
            "हम वही कदम दोहरा रहे हैं। दूसरा तरीका आज़माएँ या किसी भरोसेमंद व्यक्ति से पूछें?",
            "మనం అదే దశలను పునరావృతం చేస్తున్నాం. మరో మార్గం ప్రయత్నిద్దామా లేదా నమ్మకమైన వ్యక్తిని అడుగుదామా?"))
        nowMs() - progressAt >= 40_000 -> LoopVerdict.Stuck(say(
            "This is taking a while. Shall we try another way or ask someone you trust?",
            "इसमें समय लग रहा है। दूसरा तरीका आज़माएँ या किसी भरोसेमंद व्यक्ति से पूछें?",
            "దీనికి సమయం పడుతోంది. మరో మార్గం ప్రయత్నిద్దామా లేదా నమ్మకమైన వ్యక్తిని అడుగుదామా?"))
        else -> LoopVerdict.Ok
    }
}
