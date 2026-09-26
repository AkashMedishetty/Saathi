package com.saathi.app.guide

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** Battery awareness: on battery saver or under 15% (not charging), skip the LLM and use scripts + keywords. */
object Power {
    fun low(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) return true
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return false
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct in 1..14 && !bm.isCharging
    }
}
