package com.saathi.app.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/** One-shot proof that a model runs on a given backend: load time, reply time, and the reply. */
object NpuProbe {
    private const val TAG = "SaathiLLM"

    fun run(ctx: Context, backend: String = "NPU", which: String = "vision", visionBackend: Boolean = true): String {
        val f: File = when (which) {
            "vision" -> ModelLocator.vision(ctx)
            "fast" -> ModelLocator.fast(ctx)
            else -> ModelLocator.text(ctx).firstOrNull()
        } ?: return "✗ no model found in ${ModelLocator.dirs(ctx).joinToString()}".also { Log.w(TAG, it) }
        Log.i(TAG, "probe: ${f.name} on $backend (visionBackend=$visionBackend)")
        return runCatching {
            val t0 = SystemClock.elapsedRealtime()
            val e = if (f.name.endsWith(".litertlm")) LiteRtEngine(ctx, f, backend, vision = which == "vision" && visionBackend)
                    else MediaPipeEngine(ctx, f, gpu = backend == "GPU")
            val t1 = SystemClock.elapsedRealtime()
            val out = e.generate("You are a helpful assistant.", "In one short sentence: what is a smartphone?")
            val t2 = SystemClock.elapsedRealtime()
            e.close()
            "✓ ${e.label}\nload ${t1 - t0} ms · reply ${t2 - t1} ms\n${Templates.clean(out).take(200)}"
        }.getOrElse { "✗ ${f.name} on $backend: ${it.javaClass.simpleName}: ${it.message?.take(400)}" }
            .also { Log.i(TAG, "probe result: $it") }
    }
}
