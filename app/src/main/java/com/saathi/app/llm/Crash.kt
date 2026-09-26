package com.saathi.app.llm

import android.content.Context
import android.content.SharedPreferences

/**
 * Crash guard (trap #7): a model that killed the process natively while loading is not retried. But the brain process
 * is also killed by app updates and by the system mid-load; those must not ban a model forever (field test: Gemma 4 E4B
 * was silently replaced by E2B after a reinstall). So a mark only counts if it's newer than the last app update and
 * less than a day old.
 */
object Crash {
    private fun k(key: String) = key.replaceFirst("crash_", "crashAt_")

    fun mark(p: SharedPreferences, key: String) { p.edit().putLong(k(key), System.currentTimeMillis()).commit() }
    fun clear(p: SharedPreferences, key: String) { p.edit().remove(k(key)).remove(key).commit() }

    fun recent(ctx: Context, p: SharedPreferences, key: String): Boolean {
        val at = p.getLong(k(key), 0L)
        if (at == 0L) return false
        val updated = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return at > updated && System.currentTimeMillis() - at < 24 * 3600_000L
    }
}
