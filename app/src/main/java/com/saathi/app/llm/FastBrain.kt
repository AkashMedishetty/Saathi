package com.saathi.app.llm

import android.content.Context

/**
 * The NPU brain (Gemma 3 1B for SM8850, ≈0.25 s) that understands every request. Runs in the ":brain" process
 * ([LocalFast]); null answers mean "not available" and the caller falls back to the GPU brain.
 */
object FastBrain {
    val isReady get() = Brain.sync(false) { it.fastReady() }

    suspend fun generate(ctx: Context, system: String, user: String): String? {
        Brain.connect(ctx)
        return AiMeter.time("NPU", "Gemma 3 1B") { Brain.call(null) { it.fast(system, user) } }
    }
}
