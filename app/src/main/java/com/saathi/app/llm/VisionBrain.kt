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
 * FastVLM-0.5B compiled for this phone's Snapdragon (SM8850) and run on the Hexagon NPU: it looks at a photo
 * the person took on purpose (never the screen) and explains it. Loads on first use (~0.2 s), unloads after 2 idle min.
 */
object VisionBrain {
    private const val TAG = "SaathiLLM"
    private val lock = Mutex()
    private var engine: LiteRtEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idle: Job? = null
    @Volatile var label: String? = null; private set
    @Volatile var lastMs = 0L; private set

    /** Plain-language explanation of the photo, or null if the vision model isn't available. */
    suspend fun describe(ctx: Context, jpeg: ByteArray, prompt: String): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            val e = engine ?: load(ctx) ?: return@withContext null
            val t0 = SystemClock.elapsedRealtime()
            val out = runCatching { e.describe(jpeg, prompt) }.onFailure { Log.w(TAG, "vision failed", it) }.getOrNull()
            lastMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "vision ${lastMs} ms: ${out?.take(200)}")
            com.saathi.app.DebugLog.i("vision", "${lastMs} ms: ${out?.take(200)}")
            touch()
            out?.let { Templates.clean(it) }?.takeIf { it.isNotBlank() && !Templates.garbled(it) }
        }
    }

    private fun load(ctx: Context): LiteRtEngine? {
        val f = ModelLocator.vision(ctx) ?: return null
        val prefs = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE)
        for (b in listOf("NPU", "GPU")) {
            val key = "crash_${f.name}_vision_$b"
            if (prefs.getBoolean(key, false)) continue
            prefs.edit().putBoolean(key, true).commit()
            val e = runCatching { LiteRtEngine(ctx.applicationContext, f, b, vision = true) }
                .onFailure { Log.w(TAG, "vision load $b failed", it) }.getOrNull()
            prefs.edit().putBoolean(key, false).commit()
            if (e != null) { engine = e; label = e.label; Log.i(TAG, "vision loaded ${e.label}"); return e }
        }
        return null
    }

    fun unload() { runCatching { engine?.close() }; engine = null }

    private fun touch() {
        idle?.cancel()
        idle = scope.launch { delay(120_000); lock.withLock { runCatching { engine?.close() }; engine = null } }
    }
}
