package com.saathi.app.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import com.saathi.app.llm.LlmManager.State

/**
 * Runs only in the ":brain" process (BrainService); the app talks to it through [LlmManager].
 * The one shared on-device text brain. Loads lazily on first need, serialises inference,
 * and unloads after 3 idle minutes so ~2 GB goes back to the phone (trap #28).
 * Order: NPU build for this chip → Gemma 4 on the GPU → any .litertlm → MediaPipe .task (GPU, CPU).
 */
object LocalLlm {
    private const val TAG = "SaathiLLM"
    private const val IDLE_UNLOAD_MS = 3 * 60_000L


    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    @Volatile private var engine: LlmEngine? = null
    private val loadLock = Mutex()
    private val genLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null

    val isReady get() = engine != null
    val label get() = engine?.label
    @Volatile var lastGenMs = 0L; private set

    fun loadAsync(ctx: Context) {
        if (engine != null || _state.value is State.Loading) return
        scope.launch { load(ctx.applicationContext) }
    }

    suspend fun load(ctx: Context) = loadLock.withLock {
        if (engine != null) return@withLock
        val files = ModelLocator.text(ctx)
        if (files.isEmpty()) { _state.value = State.NoModel; return@withLock }
        val prefs = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE)
        val errors = mutableListOf<String>()
        for (f in files) for (backend in backendsFor(f)) {
            // Crash guard (trap #7): if this file+backend killed the process natively last time, never retry it.
            val crashKey = "crash_${f.name}_$backend"
            if (Crash.recent(ctx, prefs, crashKey)) { errors += "${f.name} $backend: skipped (crashed before)"; continue }
            _state.value = State.Loading("${f.nameWithoutExtension.take(24)} · $backend")
            Crash.mark(prefs, crashKey)
            val t0 = SystemClock.elapsedRealtime()
            val e = try {
                withContext(Dispatchers.IO) { create(ctx, f, backend) }
            } catch (t: Throwable) {
                Log.w(TAG, "load failed ${f.name} $backend", t)
                com.saathi.app.DebugLog.w("llm", "load failed ${f.name} $backend", t)
                errors += "${f.name} $backend: ${t.message?.take(90)}"
                null
            } finally {
                Crash.clear(prefs, crashKey)
            }
            if (e != null) {
                engine = e
                touchIdle()
                _state.value = State.Ready(e.label, e.backend, SystemClock.elapsedRealtime() - t0)
                Log.i(TAG, "loaded ${e.label} in ${SystemClock.elapsedRealtime() - t0} ms")
                com.saathi.app.DebugLog.i("llm", "loaded ${e.label} in ${SystemClock.elapsedRealtime() - t0} ms")
                return@withLock
            }
        }
        _state.value = State.Failed(errors.joinToString("\n"))
    }

    /** NPU only for builds compiled for this chip; "-gpu" builds have no CPU path (trap #35). */
    private fun backendsFor(f: File): List<String> {
        val n = f.name.lowercase()
        return when {
            n.endsWith(".task") -> listOf("GPU", "CPU")
            "sm8850" in n || "qualcomm" in n -> listOf("NPU", "GPU", "CPU")
            "gpu" in n -> listOf("GPU")
            else -> listOf("GPU", "CPU")
        }
    }

    private fun create(ctx: Context, f: File, backend: String): LlmEngine =
        if (f.name.endsWith(".litertlm")) LiteRtEngine(ctx, f, backend)
        else MediaPipeEngine(ctx, f, gpu = backend == "GPU")

    /** Cleaned model text, or null when no model is loaded. Never throws. */
    suspend fun generate(system: String, user: String): String? = genLock.withLock {
        val e = engine ?: return@withLock null
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val out = try { e.generate(system, user) } catch (t: Throwable) { Log.w(TAG, "gen failed", t); "" }
            lastGenMs = SystemClock.elapsedRealtime() - t0
            touchIdle()
            Log.i(TAG, "gen ${lastGenMs} ms: ${out.take(200).replace('\n', ' ')}")
            com.saathi.app.DebugLog.i("llm", "gen ${lastGenMs} ms: ${out.take(200)}")
            Templates.clean(out)
        }
    }

    /** A turn in the task's ongoing conversation. Returns (text, isFirstTurnOfSession). */
    suspend fun chat(key: String, system: String, user: String): String? = genLock.withLock {
        val e = engine ?: return@withLock null
        withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val out = try { e.chat(key, system, user) } catch (t: Throwable) { Log.w(TAG, "chat failed", t); runCatching { e.endChat() }; "" }
            lastGenMs = SystemClock.elapsedRealtime() - t0
            touchIdle()
            com.saathi.app.DebugLog.i("llm", "chat[$key] ${lastGenMs} ms: ${out.take(200)}")
            Templates.clean(out)
        }
    }

    /** Turns in the live conversation (the client knows which task key it belongs to). */
    fun turns() = (engine as? LiteRtEngine)?.turns ?: 0

    fun endChat() { runCatching { engine?.endChat() } }

    private fun touchIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            genLock.withLock { Log.i(TAG, "idle → unloading"); unload() }
        }
    }

    fun unload() {
        runCatching { engine?.close() }
        engine = null
        _state.value = State.Idle
    }
}
