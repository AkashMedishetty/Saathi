package com.saathi.app.service

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.saathi.app.llm.AiMeter
import java.io.File

/**
 * The AI monitor: a small see-through strip (touches pass through) showing which engine decided each step (NPU Gemma
 * 3 1B, GPU Gemma 4, NPU FastVLM, ML Kit OCR, app map) and how long it took, plus a live line: CPU % of Saathi's two
 * processes, their memory, and GPU busy % when the phone lets apps read it. Toggle: Saathi Settings → "AI monitor",
 * or `scripts/say.sh --monitor on|off`.
 */
class AiMonitor(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val text = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE; textSize = 10.5f; setTextColor(0xFFE8F5E9.toInt()); setLineSpacing(0f, 1.1f)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xCC0B1F1A.toInt()) }
    }
    private var shown = false
    private var lastCpu = mutableMapOf<Int, Pair<Long, Long>>()   // pid → (cpu ticks, wall ms)

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun setOn(on: Boolean) = main.post {
        if (on == shown) return@post
        if (on) {
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(34)
            }
            shown = runCatching { wm.addView(text, lp) }.isSuccess
            // The card keeps clear of the strip (Overlay.place reads this).
            text.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> Overlay.reserveTop = if (shown) v.height + dp(8) else 0 }
            com.saathi.app.DebugLog.i("monitor", "on=$shown")
            AiMeter.listener = { main.post { render() } }
            tick()
        } else {
            AiMeter.listener = null; main.removeCallbacksAndMessages(null)
            runCatching { wm.removeView(text) }; shown = false; Overlay.reserveTop = 0
        }
    }

    fun setVisible(v: Boolean) = main.post { text.visibility = if (v) android.view.View.VISIBLE else android.view.View.INVISIBLE }

    private val tick = Runnable { tick() }
    private fun tick() { if (!shown) return; render(); main.postDelayed(tick, 1000) }

    private fun render() {
        if (!shown) return
        val sb = StringBuilder()
        AiMeter.busy?.let { sb.append("● ").append(it).append(" …\n") }
        AiMeter.events().takeLast(4).reversed().forEach { e ->
            sb.append(String.format("%-3s %-13s %-12s %5d ms\n", e.unit, e.engine.take(13), e.what.take(12), e.ms))
        }
        // Which Saathi is running: the basic on-device app, or Saathi Pro (and whether its cloud brain is on).
        sb.append(if (com.saathi.app.BuildConfig.PRO) "SAATHI PRO" + (if (com.saathi.app.guide.Prefs.proOn(ctx)) " · cloud ON" else " · cloud off") else "SAATHI · on-device").append('\n')
        sb.append(live())
        text.text = sb.toString().trimEnd()
    }

    /** CPU % (of all cores) and memory of Saathi's processes; GPU busy % if readable. */
    private fun live(): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val procs = runCatching { am.runningAppProcesses.orEmpty().filter { it.processName.startsWith(ctx.packageName) } }.getOrDefault(emptyList())
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val hz = 100.0
        var cpu = 0.0
        val now = SystemClock.elapsedRealtime()
        for (p in procs) {
            val ticks = runCatching {
                File("/proc/${p.pid}/stat").readText().substringAfterLast(')').trim().split(' ').let { f -> f[11].toLong() + f[12].toLong() }
            }.getOrNull() ?: continue
            lastCpu[p.pid]?.let { (t0, w0) -> if (now > w0) cpu += (ticks - t0) / hz / ((now - w0) / 1000.0) / cores * 100 }
            lastCpu[p.pid] = ticks to now
        }
        val mem = runCatching { am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray()).sumOf { it.totalPss } / 1024 }.getOrDefault(0)
        val gpu = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
            .firstNotNullOfOrNull { runCatching { File(it).readText().trim().filter { c -> c.isDigit() }.take(3).ifBlank { null } }.getOrNull() }
        return String.format("CPU %2.0f%%  RAM %d MB  GPU %s", cpu, mem, gpu?.let { "$it%" } ?: "n/a")
    }
}
