package com.saathi.app.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The NPU brain: Gemma 3 1B compiled for this exact chip (SM8850, Hexagon V81). Handles the quick, frequent work —
 * understanding every request and routing it — in a fraction of a second without touching the GPU.
 * Gemma 4 on the GPU (LlmManager) stays for the heavy thinking (coach, planning). Falls back to LlmManager if absent.
 */
object FastBrain {
    private const val TAG = "SaathiLLM"
    private val lock = Mutex()
    @Volatile private var engine: LiteRtEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idle: Job? = null
    @Volatile var label: String? = null; private set
    @Volatile var lastMs = 0L; private set
    @Volatile private var failed = false

    val isReady get() = engine != null

    fun loadAsync(ctx: Context) { if (engine == null && !failed) scope.launch { lock.withLock { load(ctx.applicationContext) } } }

    private fun load(ctx: Context): LiteRtEngine? {
        engine?.let { return it }
        val f = ModelLocator.fast(ctx) ?: return null
        val prefs = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE)
        val key = "crash_${f.name}_NPU"
        if (prefs.getBoolean(key, false)) { failed = true; return null }
        prefs.edit().putBoolean(key, true).commit()
        val t0 = SystemClock.elapsedRealtime()
        val e = runCatching { LiteRtEngine(ctx, f, "NPU") }.onFailure { Log.w(TAG, "fast brain NPU load failed", it); failed = true }.getOrNull()
        prefs.edit().putBoolean(key, false).commit()
        if (e != null) {
            engine = e; label = e.label
            com.saathi.app.DebugLog.i("npu", "fast brain ${e.label} loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
        return e
    }

    /** Quick one-shot generation on the NPU; null if the NPU brain isn't available (caller falls back to the GPU brain). */
    suspend fun generate(ctx: Context, system: String, user: String): String? = lock.withLock {
        val e = engine ?: load(ctx.applicationContext) ?: return@withLock null
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val out = runCatching { e.generate(system, user) }.onFailure { Log.w(TAG, "fast brain gen failed", it) }.getOrNull()
            lastMs = SystemClock.elapsedRealtime() - t0
            com.saathi.app.DebugLog.i("npu", "gen $lastMs ms: ${out?.take(120)}")
            touch()
            out?.let { Templates.clean(it) }
        }
    }

    private fun touch() {
        idle?.cancel()
        idle = scope.launch { delay(8 * 60_000L); lock.withLock { runCatching { engine?.close() }; engine = null } }
    }

    fun unload() { runCatching { engine?.close() }; engine = null }
}
