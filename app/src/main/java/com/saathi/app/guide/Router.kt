package com.saathi.app.guide

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Installed apps, for "open <app>" and for naming the app we pause on. */
object AppLauncher {
    data class App(val label: String, val pkg: String)

    @Volatile private var cache: List<App>? = null

    fun installed(ctx: Context): List<App> = cache ?: run {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .also { cache = it }
    }

    fun isInstalled(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    fun first(ctx: Context, vararg pkgs: String): String? = pkgs.firstOrNull { isInstalled(ctx, it) }
    fun launch(ctx: Context, pkg: String?): Intent? = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
    fun labelOf(ctx: Context, pkg: String): String = installed(ctx).firstOrNull { it.pkg == pkg }?.label ?: pkg

    /** "open calculator" / "how do I use Instagram" → that app; the longest label match wins. */
    fun findInGoal(ctx: Context, goal: String): App? {
        val g = " ${goal.lowercase()} "
        return installed(ctx)
            .filter { it.pkg != ctx.packageName && it.label.length >= 3 }
            .filter { g.contains(" ${it.label.lowercase()}") || g.contains(it.label.lowercase().replace(" ", "")) }
            .maxByOrNull { it.label.length }
    }
}

/** Goal text → Flow. P0-3 adds the full skills registry in front of the app fallback. */
object IntentRouter {
    private fun String.has(vararg w: String) = w.any { it in this }

    fun isExplain(goal: String) = goal.lowercase().has(
        "what is on", "what's on", "explain", "what is this", "samjhao", "समझाओ", "क्या है", "ఏమిటి", "వివరించు", "ఏముంది")

    fun isScamCheck(goal: String) = goal.lowercase().has("scam", "fraud", "is this safe", "धोखा", "ठगी", "మోసం")

    fun route(ctx: Context, goal: String): Flow? {
        val g = goal.lowercase()
        if (g.has("text bigger", "bigger text", "font", "letters bigger", "big letters", "अक्षर बड़े", "बड़ा", "అక్షరాలు పెద్ద")) return CoreFlows.fontSize()
        if (g.has("wifi", "wi-fi", "वाईफाई", "వైఫై")) return CoreFlows.wifi()
        AppLauncher.findInGoal(ctx, goal)?.let { app ->
            val onlyOpen = Regex("(?i)^\\s*(open|start|launch|खोलो|తెరువు)\\s+\\S+(\\s+app)?\\s*$").matches(goal)
            return Flow("app_${app.pkg}", { c -> AppLauncher.launch(c, app.pkg) }, emptyList(),
                if (onlyOpen) { _ -> true } else null,
                say("${app.label} is open.", "${app.label} खुल गया।", "${app.label} తెరుచుకుంది."),
                say("Opening ${app.label}.", "${app.label} खोल रहा हूँ।", "${app.label} తెరుస్తున్నాను."),
                llmGoal = goal)
        }
        return null
    }
}
