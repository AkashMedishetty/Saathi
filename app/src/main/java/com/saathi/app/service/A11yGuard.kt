package com.saathi.app.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Demo-phone safety net. On the loaner phone, the system sometimes removes Saathi from the enabled accessibility
 * services when Settings comes to the front (trap #45). If (and only if) WRITE_SECURE_SETTINGS was granted over adb
 * (`scripts/grant-heal.sh`), Saathi puts ONLY its own entry back. Every other service in the list, HackTracker
 * included, is kept exactly as it is. Without the grant this does nothing (normal phones).
 */
object A11yGuard {
    private var started = false
    /** When Saathi last put itself back (the service restarts right after): the guide just carries on then. */
    @Volatile var healedAt = 0L
    private val main = Handler(Looper.getMainLooper())

    fun canHeal(c: Context) = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (started || !canHeal(app)) return
        started = true
        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
            // At once, and again shortly after: this phone freezes the process soon after the service is switched off
            // (field: the 700 ms delayed check never ran).
            override fun onChange(selfChange: Boolean) { heal(app); main.removeCallbacks(check); main.postDelayed(check, 300) }
            private val check = Runnable { heal(app) }
        })
    }

    /** The other Saathi app's service (basic ↔ Pro): "com.saathi.app.pro/…" for the basic one, and the reverse. */
    private fun sibling(c: Context): String {
        val other = if (c.packageName.endsWith(".pro")) c.packageName.removeSuffix(".pro") else c.packageName + ".pro"
        return "$other/${SaathiService::class.java.name}"
    }

    /** "Use this Saathi": this app's helper on, the other Saathi's off. HackTracker and everyone else stay exactly as they are. */
    fun makeActive(c: Context): Boolean {
        if (!canHeal(c)) return false
        val me = ComponentName(c, SaathiService::class.java).flattenToString()
        val sib = sibling(c)
        val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = cur.split(':').filter { it.isNotBlank() && it != "null" && !it.equals(sib, true) && !it.equals(me, true) }
        return runCatching {
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + me).joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            com.saathi.app.DebugLog.i("guard", "made active; other Saathi switched off")
        }.isSuccess
    }

    private fun heal(c: Context) {
        val me = ComponentName(c, SaathiService::class.java).flattenToString()
        val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = cur.split(':').filter { it.isNotBlank() && it != "null" }
        if (entries.any { it.equals(me, true) }) return
        // The other Saathi is on: it was switched on deliberately ("Use this Saathi"), so this one stays off.
        if (entries.any { it.equals(sibling(c), true) }) return
        // Append ours; everything else untouched and in the same order.
        runCatching {
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + me).joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.onSuccess { healedAt = android.os.SystemClock.uptimeMillis(); com.saathi.app.DebugLog.i("guard", "the system removed Saathi's accessibility entry; put it back") }
            .onFailure { com.saathi.app.DebugLog.w("guard", "couldn't restore the accessibility entry", it) }
    }
}
