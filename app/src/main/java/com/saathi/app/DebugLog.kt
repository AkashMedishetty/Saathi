package com.saathi.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Field-test log: everything Saathi decides and does, written to an app-private file so we can test on the phone
 * away from the laptop and review later (scripts/pull-logs.sh). Starts at the time set by `say.sh --log-from HH:MM`
 * (debug builds). Never leaves the phone by itself. Long digit runs are masked (no OTPs / account numbers).
 */
object DebugLog {
    private const val TAG = "SaathiLog"
    private val io = Executors.newSingleThreadExecutor()
    private var dir: File? = null
    @Volatile private var fromMs = Long.MAX_VALUE
    private var debuggable = false
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.applicationContext.filesDir, "logs").apply { mkdirs() }
        // Privacy: test builds only (a release build never writes a log), and nothing older than 2 days is kept.
        debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        fromMs = if (debuggable) ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE).getLong("log_from", Long.MAX_VALUE) else Long.MAX_VALUE
        io.execute { runCatching { dir?.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 2 * 86_400_000L }?.forEach { it.delete() } } }
        // Crashes are the most important thing to capture.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("CRASH", "${t.name}: ${Log.getStackTraceString(e)}", sync = true) }
            prev?.uncaughtException(t, e)
        }
    }

    fun setFrom(ctx: Context, ms: Long) {
        if (!debuggable) return
        fromMs = ms
        ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE).edit().putLong("log_from", ms).apply()
        i("log", "logging from ${Date(ms)}")
    }

    val enabled get() = System.currentTimeMillis() >= fromMs

    fun i(area: String, msg: String) = write(area, msg)
    fun w(area: String, msg: String, t: Throwable? = null) = write("W/$area", msg + (t?.let { " :: ${Log.getStackTraceString(it).take(1500)}" } ?: ""))

    private fun write(area: String, msg: String, sync: Boolean = false) {
        if (!enabled) return
        // Privacy: OTPs, card/account/Aadhaar/PAN numbers masked before anything is written (policy.Redactor).
        val clean = com.saathi.app.policy.Redactor.forLog(msg).replace(Regex("\\d{5,}"), "#####").replace('\n', ' ').take(2000)
        Log.i(TAG, "[$area] $clean")
        val line = "${ts.format(Date())} [$area] $clean\n"
        val job = Runnable {
            runCatching {
                val d = dir ?: return@runCatching
                val f = File(d, "saathi-${day.format(Date())}.log")
                if (f.length() > 8_000_000) f.renameTo(File(d, "saathi-${day.format(Date())}-${System.currentTimeMillis()}.log"))
                f.appendText(line)
            }
        }
        if (sync) job.run() else io.execute(job)
    }
}
