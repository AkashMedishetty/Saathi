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
        com.saathi.app.DebugLog.init(this)
        com.saathi.app.DebugLog.i("service", "connected")
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
            onTouchOutside = { if (::guide.isInitialized && !isLocked()) guide.onUserTouch() },
            onBack = { guide.goBack() },
            onHome = { guide.goHome() },
            onFamily = { guide.askFamily() },
            onDecline = { guide.declineConfirm() },
        )
        o.attach()
        overlay = o
        guide = Guide(this, o, speaker)
        instance = this
        o.setBubbleVisible(!ownUiOpen)

        registerDebugTrigger()
        guide.offerResume()
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || instance !== this) return
        if (event.packageName == packageName) return // our own windows (trap #9)
        // Never float over the lock screen.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked != wasLocked) {
            wasLocked = locked
            overlay?.setBubbleVisible(!locked && !ownUiOpen)
            // Nothing of Saathi shows over the lock screen; the task picks up again once unlocked.
            if (locked) overlay?.setHidden(true) else { overlay?.setHidden(false); guide.onWindowChanged() }
        }
        if (locked) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) checkCallRisk(event.packageName?.toString())
        // "Teach Saathi once": while recording, remember the label of everything they tap (never pixels).
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && com.saathi.app.guide.Recipes.recording != null) {
            val label = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: event.contentDescription?.toString()
                ?: event.source?.let { n -> (0 until minOf(n.childCount, 4)).mapNotNull { n.getChild(it)?.text?.toString() }.joinToString(" · ") }
            com.saathi.app.guide.Recipes.onClick(label, if (event.className?.contains("EditText") == true) "input" else "button", event.packageName?.toString() ?: "")
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> guide.onUserMotion()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> guide.onWindowChanged()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> guide.onUserTap()
            // Content changes are constant; only worth reading while a task runs.
            else -> if (guide.active) guide.onScreenEvent()
        }
    }

    override fun onInterrupt() {}

    fun isLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Android is short on memory: give the models back first (they reload on demand). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.i(TAG, "trim memory $level → unloading models")
            com.saathi.app.DebugLog.i("memory", "trim $level → models unloaded")
            com.saathi.app.llm.LlmManager.unload()
            com.saathi.app.llm.VisionBrain.unload()
        }
    }

    private var lastAlarmPkg: String? = null
    private var lastAlarmAt = 0L

    /** Risky app opened during a call → alarm (once per app per minute). Needs no call permission: AudioManager mode. */
    private fun checkCallRisk(pkg: String?) {
        pkg ?: return
        if (!Prefs.scamGuard(this) || !CallGuard.isRisky(pkg)) return
        val mode = getSystemService(android.media.AudioManager::class.java)?.mode
        if (mode != android.media.AudioManager.MODE_IN_CALL && mode != android.media.AudioManager.MODE_IN_COMMUNICATION) return
        val now = System.currentTimeMillis()
        if (pkg == lastAlarmPkg && now - lastAlarmAt < 60_000) return
        lastAlarmPkg = pkg; lastAlarmAt = now
        guide.callAlarm(com.saathi.app.guide.AppLauncher.labelOf(this, pkg))
    }

    override fun onDestroy() {
        com.saathi.app.DebugLog.i("service", "destroyed")
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
                    "log_from" -> com.saathi.app.DebugLog.setFrom(this@SaathiService, i.getLongExtra("ms", 0L))
                    "ask" -> openAsk(i.getBooleanExtra("listen", false))
                    "routine" -> com.saathi.app.guide.Routines.all(this@SaathiService).firstOrNull()?.let { guide.routineDue(it) }
                    "scam_sms" -> com.saathi.app.guide.MessageScam.check("Dear customer your SBI KYC is pending, account will be blocked today. Update now bit.ly/kyc-sbi")
                        ?.let { guide.messageAlert("VM-SBIUPD", "Messages", it) }
                    "tts" -> Log.i(TAG, "tts: " + Lang.entries.joinToString { "${it.tag}=${speaker.supports(it)}" })
                    null -> i.getStringExtra("goal")?.let { guide.handleUtterance(it) }
                    else -> Log.w(TAG, "unknown cmd $cmd")
                }
            }
        }
        ContextCompat.registerReceiver(this, debugReceiver, IntentFilter(ACTION_GOAL), ContextCompat.RECEIVER_EXPORTED)
    }
}
