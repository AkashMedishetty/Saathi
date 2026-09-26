package com.saathi.app.llm

import android.os.SystemClock

/**
 * Which engine decided what, on which processor, and how long it took: the AI monitor's feed (service.AiMonitor).
 * Model calls are timed in this (main) process around the brain-process call, so the time is what the person waits.
 * Android has no public NPU-utilisation counter: "busy" says a model call is running on that processor right now.
 */
object AiMeter {
    data class Ev(val unit: String, val engine: String, val what: String, val ms: Long)

    private val lock = Any()
    private val recent = ArrayDeque<Ev>()
    @Volatile var busy: String? = null; private set
    @Volatile var listener: (() -> Unit)? = null

    /** What the next model call is for ("understand", "rewrite", "plan step"…), set by the caller just before it. */
    @Volatile var purpose: String = ""

    fun events(): List<Ev> = synchronized(lock) { recent.toList() }

    fun record(unit: String, engine: String, what: String, ms: Long) {
        synchronized(lock) { recent.addLast(Ev(unit, engine, what, ms)); while (recent.size > 5) recent.removeFirst() }
        runCatching { com.saathi.app.DebugLog.i("ai", "$unit · $engine · $what · $ms ms") }
        listener?.invoke()
    }

    /** Time a model call on [unit] ("NPU" / "GPU"); the purpose set by the caller is used and cleared. */
    suspend fun <T> time(unit: String, engine: String, block: suspend () -> T): T {
        val what = purpose.ifBlank { "model call" }; purpose = ""
        busy = "$unit · $engine · $what"; listener?.invoke()
        val t0 = SystemClock.elapsedRealtime()
        try { return block() } finally {
            busy = null
            record(unit, engine, what, SystemClock.elapsedRealtime() - t0)
        }
    }
}
