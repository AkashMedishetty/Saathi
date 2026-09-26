package com.saathi.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class SaathiService : AccessibilityService() {

    companion object {
        const val TAG = "Saathi"
        @Volatile var instance: SaathiService? = null; private set

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, SaathiService::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    private lateinit var wm: WindowManager
    private var glow: GlowView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        instance = this
        attachGlow()
        // P0-1 smoke test: glow a fixed rect in the middle of the screen.
        val m = resources.displayMetrics
        val w = (m.widthPixels * 0.6f).toInt()
        val h = (72 * m.density).toInt()
        val left = (m.widthPixels - w) / 2
        val top = m.heightPixels / 2 - h / 2
        glow?.show(Rect(left, top, left + w, top + h))
        Log.i(TAG, "service connected")
    }

    private fun attachGlow() {
        val v = GlowView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        // The service can reconnect while a window add is in flight; never crash on that.
        runCatching { wm.addView(v, lp); glow = v }.onFailure { Log.w(TAG, "glow attach failed", it) }
    }

    fun showGlow(r: Rect?) { glow?.show(r) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        glow?.let { runCatching { wm.removeView(it) } }
        glow = null
        super.onDestroy()
    }
}
