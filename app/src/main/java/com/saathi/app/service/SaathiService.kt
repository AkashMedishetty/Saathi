package com.saathi.app.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.saathi.app.guide.Guide
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs

class SaathiService : AccessibilityService() {

    companion object {
        const val TAG = "Saathi"
        const val ACTION_GOAL = "com.saathi.GOAL"
        @Volatile var instance: SaathiService? = null; private set

        /** True while one of Saathi's own screens is in front: no guiding, no bubble. */
        @Volatile var ownUiOpen = false
            set(v) { field = v; instance?.overlay?.setBubbleVisible(!v) }

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, SaathiService::class.java).flattenToString()
            val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    var overlay: Overlay? = null; private set
    private lateinit var speaker: Speaker
    lateinit var guide: Guide; private set
    private var debugReceiver: BroadcastReceiver? = null
    private var wasLocked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Memory.init(this)
        speaker = Speaker(this)
        val o = Overlay(
            this,
            onAgain = { guide.repeat() },
            onDoIt = { guide.doItForMe() },
            onStop = { guide.stop() },
            onFinalDone = { guide.onFinalDone() },
            onBubble = { openAsk(listen = false) },
            onBubbleLong = { openAsk(listen = true) },
            onMic = { openAsk(listen = true) },
            onTouchOutside = { if (::guide.isInitialized) guide.onUserMotion() },
        )
        o.attach()
        overlay = o
        guide = Guide(this, o, speaker)
        instance = this
        o.setBubbleVisible(!ownUiOpen)

        // The system accessibility button / shortcut: talk to Saathi from anywhere.
        runCatching {
            accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = openAsk(listen = true)
            })
        }
        registerDebugTrigger()
        guide.offerResume()
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || instance !== this) return
        if (event.packageName == packageName) return // our own windows (trap #9)
        // Never float over the lock screen.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked != wasLocked) { wasLocked = locked; overlay?.setBubbleVisible(!locked && !ownUiOpen) }
        if (locked) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> guide.onUserMotion()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> guide.onWindowChanged()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> guide.onUserTap()
            // Content changes are constant; only worth reading while a task runs.
            else -> if (guide.active) guide.onScreenEvent()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        overlay?.detach()
        overlay = null
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    /** The listening sheet: an Activity, so the mic is reliably allowed (trap #26). */
    fun openAsk(listen: Boolean) {
        runCatching {
            startActivity(Intent(this, com.saathi.app.ui.AskActivity::class.java)
                .putExtra(com.saathi.app.ui.AskActivity.EXTRA_LISTEN, listen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }

    /** Fallback when a node refuses ACTION_CLICK. */
    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    fun buzz() {
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(30, 90)) }
    }

    /** Debug: the raw tree to logcat (uiautomator dump would kill a11y services, trap #23). */
    private fun dumpTree() {
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
            n ?: return
            val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            Log.i("SaathiDump", "  ".repeat(d) + "${n.className?.toString()?.substringAfterLast('.')} " +
                "t=${n.text} d=${n.contentDescription} id=${n.viewIdResourceName?.substringAfter('/')} " +
                "${if (n.isClickable) "C" else ""}${if (n.isScrollable) "S" else ""}${if (n.isCheckable) "K" else ""}" +
                "${if (n.isFocusable) "F" else ""} ri=${n.rangeInfo?.let { "${it.min}..${it.max}=${it.current}" }} " +
                "acts=${n.actionList.joinToString(",") { it.id.toString() }} $r")
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(rootInActiveWindow, 0)
        com.saathi.app.guide.ScreenReader.read(rootInActiveWindow)?.let { Log.i("SaathiDump", "READ:\n" + it.forPrompt(90)) }
    }

    /**
     * Debug builds only: `scripts/say.sh "make the text bigger" [HI|TE]`.
     * Lets us test and rehearse without typing on the phone (adb input text drops characters, trap #24).
     */
    private fun registerDebugTrigger() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        debugReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (instance !== this@SaathiService) return
                i.getStringExtra("lang")?.let { l -> runCatching { Prefs.setLang(c, Lang.valueOf(l.uppercase())) } }
                when (val cmd = i.getStringExtra("cmd")) {
                    "doit" -> guide.doItForMe()
                    "stop" -> guide.stop()
                    "aura" -> overlay?.setAura(i.getBooleanExtra("on", true))
                    "dump" -> dumpTree()
                    "ask" -> openAsk(i.getBooleanExtra("listen", false))
                    null -> i.getStringExtra("goal")?.let { guide.handleUtterance(it) }
                    else -> Log.w(TAG, "unknown cmd $cmd")
                }
            }
        }
        ContextCompat.registerReceiver(this, debugReceiver, IntentFilter(ACTION_GOAL), ContextCompat.RECEIVER_EXPORTED)
    }
}
