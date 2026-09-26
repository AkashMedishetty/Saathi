package com.saathi.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.saathi.app.R
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.ui.C
import com.saathi.app.ui.EASE
import com.saathi.app.ui.OrbView
import com.saathi.app.ui.Type
import com.saathi.app.ui.dp
import com.saathi.app.ui.dpf
import kotlin.math.abs

/**
 * Everything Saathi draws over other apps. All TYPE_ACCESSIBILITY_OVERLAY windows (no extra permission):
 *  halo (4 slim non-touchable windows around the target) · watcher (1×1, told about every outside touch) · card · bubble.
 *
 * The card follows one rule: one clear primary action, everything else quiet and labelled
 * (icons alone are hard for elderly users).
 */
class Overlay(
    private val ctx: Context,
    private val onAgain: () -> Unit,
    private val onDoIt: () -> Unit,
    private val onStop: () -> Unit,
    private val onFinalDone: () -> Unit,
    private val onBubble: () -> Unit,
    private val onBubbleLong: () -> Unit,
    private val onMic: () -> Unit,
    private val onTouchOutside: () -> Unit,
    private val onBack: () -> Unit = {},
    private val onHome: () -> Unit = {},
    private val onFamily: () -> Unit = {},
    private val onDecline: () -> Unit = {},
) {
    enum class Mode { INFO, THINKING, STEP, SCROLL, FINAL, WARN, DONE, PAUSED, LOST, ALARM, CONFIRM, AUTO, ASK, CHOICE }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    /** The pointer: four slim windows around the target, never over it (see Halo). */
    private val halo = Halo(ctx, wm)
    private val watcher = View(ctx)

    // bubble
    private val bubble = FrameLayout(ctx)
    private val bubbleOrb = OrbView(ctx)
    private var bubbleLp: WLP? = null
    private var bubbleWanted = true

    // card
    private val cardWrap = FrameLayout(ctx)
    private val card = LinearLayout(ctx)
    private val orb = OrbView(ctx)
    private val header = TextView(ctx)
    private val dots = LinearLayout(ctx)
    private val grab = View(ctx)
    private val cardText = TextView(ctx)
    private val tipText = TextView(ctx)
    private val primary = Pill(ctx)
    /** CHOICE mode: a second equally-big option ("WhatsApp" | "Phone call"). */
    private val secondary = Pill(ctx)
    private var choiceA: Triple<String, Int, () -> Unit>? = null
    private var choiceB: Triple<String, Int, () -> Unit>? = null

    /** Two clear options, both one tap, plus "Not now". */
    fun showChoice(text: String, a: Triple<String, Int, () -> Unit>, b: Triple<String, Int, () -> Unit>?) {
        choiceA = a; choiceB = b
        showCard(text, Mode.CHOICE)
    }
    private val quietRow = LinearLayout(ctx)
    private val btnAgain = Pill(ctx)
    private val btnMic = Pill(ctx)
    private val btnStop = Pill(ctx)
    private var cardShown = false
    private var cardAtTop = false
    private var mode = Mode.INFO
    private var onContinue: (() -> Unit)? = null

    private fun lp(w: Int, h: Int, touchable: Boolean) = WLP(
        w, h, WLP.TYPE_ACCESSIBILITY_OVERLAY,
        WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_LAYOUT_NO_LIMITS),
        PixelFormat.TRANSLUCENT,
    ).apply { layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }

    /** The a11y window token can be briefly invalid while the service (re)connects: never crash (trap #37). */
    private fun add(v: View, p: WLP) = runCatching { wm.addView(v, p) }.isSuccess

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        // Nothing of ours ever sits over the thing they tap: the halo is four windows around it (see Halo).
        attachWatcher()
        watcher.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                // Finger down anywhere: nothing of ours on screen while whatever they tap opens (trap #45).
                if (guardLaunch) quietForLaunch()
                else if (halo.showing) { clearForLaunch(); watcher.removeCallbacks(restore); watcher.postDelayed(restore, 1200) }
                onTouchOutside()
            }
            false
        }
        buildCard()
        buildBubble()
    }

    private var watcherAttached = false
    private var bubbleAttached = true

    private fun attachWatcher() {
        if (watcherAttached) return
        // 1×1 invisible window that hears about every touch elsewhere (ACTION_OUTSIDE): "the person is busy".
        watcherAttached = add(watcher, lp(1, 1, true).apply {
            flags = flags or WLP.FLAG_WATCH_OUTSIDE_TOUCH or WLP.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
        })
    }

    /**
     * Banking / UPI / IRCTC / password screens: Saathi steps back completely. No windows of ours over the app at all
     * (they reject touches when anything covers them, and it's the safe thing to do). Voice + call alarm still work.
     */
    var steppedBack = false; private set
    fun stepBack(on: Boolean) {
        if (on == steppedBack) return
        steppedBack = on
        if (on) {
            halo.suspend()
            if (watcherAttached) { runCatching { wm.removeView(watcher) }; watcherAttached = false }
            if (bubbleAttached) { runCatching { wm.removeView(bubble) }; bubbleAttached = false }
            hideCardNow()
        } else {
            halo.resume()
            attachWatcher()
            bubbleLp?.let { if (!bubbleAttached) bubbleAttached = add(bubble, it) }
        }
    }

    fun detach() {
        halo.suspend()
        listOf(watcher, bubble).forEach { runCatching { wm.removeView(it) } }
        watcherAttached = false; bubbleAttached = false
        if (cardShown) runCatching { wm.removeView(cardWrap) }
        cardShown = false
    }

    // ───────── glow ─────────

    fun highlight(r: Rect?, warn: Boolean) {
        val m = ctx.resources.displayMetrics
        val clipped = r?.let { Rect(it).apply { if (!intersect(0, 0, m.widthPixels, m.heightPixels)) setEmpty() } }?.takeIf { !it.isEmpty }
        if (steppedBack) return
        halo.show(clipped, warn)
        val prev = targetBox
        targetBox = clipped
        if (cardShown && clipped != null && clipped != prev) place(animateIn = false)
        r?.let { dodgeBubble(it) }
    }

    fun setMoving(moving: Boolean) = halo.setMoving(moving)

    /** A tap is about to open another screen (e.g. Settings): take every window away until it settles. */
    fun clearForLaunch() { halo.suspend(); if (bubbleAttached) { runCatching { wm.removeView(bubble) }; bubbleAttached = false } }
    private val restore = Runnable { restoreAfterLaunch() }
    /**
     * Pointing at the Settings icon: this phone switches off an accessibility app whose windows are on screen while
     * Settings opens (field, 02:31: even the card alone did it). Finger down → EVERY window of ours goes (card, halo,
     * bubble, watcher) until Settings has opened; then [onQuietEnd] lets the guide show the next step.
     */
    var guardLaunch = false
    var quiet = false; private set
    var onQuietEnd: () -> Unit = {}
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val endQuiet = Runnable {
        quiet = false
        if (steppedBack) return@Runnable
        attachWatcher(); halo.resume(); bubbleLp?.let { if (!bubbleAttached) bubbleAttached = add(bubble, it) }
        onQuietEnd()
    }
    fun quietForLaunch(ms: Long = 2500) {
        quiet = true; guardLaunch = false
        halo.suspend(); hideCardNow()
        if (bubbleAttached) { runCatching { wm.removeView(bubble) }; bubbleAttached = false }
        if (watcherAttached) { runCatching { wm.removeView(watcher) }; watcherAttached = false }
        com.saathi.app.DebugLog.i("overlay", "quiet for a launch (${ms} ms)")
        main.removeCallbacks(endQuiet); main.postDelayed(endQuiet, ms)
    }

    fun restoreAfterLaunch() { if (steppedBack || quiet) return; halo.resume(); bubbleLp?.let { if (!bubbleAttached) bubbleAttached = add(bubble, it) } }

    private var imeVisible = false
    private var imeTop: Int? = null
    private var focusBox: Rect? = null
    private var targetBox: Rect? = null

    /** Keyboard up: the card sits where it covers neither the keyboard, the box being typed in, nor the target. */
    fun setImeVisible(v: Boolean, top: Int? = null, focus: Rect? = null) {
        if (v == imeVisible && top == imeTop && focus == focusBox) return
        imeVisible = v; imeTop = if (v) top else null; focusBox = if (v) focus else null
        if (cardShown) place(animateIn = false, moved = true)
    }

    /** Lock screen: hide glow + card without forgetting them. */
    fun setHidden(h: Boolean) {
        val v = if (h) View.INVISIBLE else View.VISIBLE
        halo.setVisible(!h); cardWrap.visibility = v
    }

    /** Listening / thinking: the edge of the screen comes alive, and so do the orbs. */
    fun setAura(on: Boolean) {
        // No screen-edge glow over other apps any more (it covered every tap); the orbs show listening/thinking.
        if (steppedBack) return
        orb.mood = if (on) OrbView.Mood.ACTIVE else orb.mood
        bubbleOrb.mood = if (on) OrbView.Mood.ACTIVE else OrbView.Mood.IDLE
    }

    /** A card is up: it has its own mic and close, so the floating button steps aside (field: it sat on input boxes
     *  and on the card's close button). It comes back with the card gone, if they want it. */
    private fun bubbleForCard(cardOn: Boolean) {
        val show = !cardOn && bubbleWanted
        bubble.animate().cancel()
        if (show) bubble.visibility = View.VISIBLE
        bubble.animate().alpha(if (show) 1f else 0f).setDuration(160)
            .withEndAction { if (!show) bubble.visibility = View.GONE }.start()
    }

    fun setBubbleVisible(v: Boolean) {
        bubbleWanted = v
        bubble.animate().cancel()
        if (v) bubble.visibility = View.VISIBLE
        bubble.animate().alpha(if (v) 1f else 0f).scaleX(if (v) 1f else 0.6f).scaleY(if (v) 1f else 0.6f)
            .setInterpolator(EASE).setDuration(200)
            .withEndAction { if (!bubbleWanted) bubble.visibility = View.GONE }.start()
    }

    // ───────── card ─────────

    fun showCard(
        text: String, mode: Mode, targetCenterY: Int? = null, tip: String? = null,
        progress: Pair<Int, Int>? = null, onContinue: (() -> Unit)? = null,
    ) {
        if (steppedBack && mode != Mode.ALARM) return
        val changed = cardText.text.toString() != text || this.mode != mode
        this.mode = mode
        this.onContinue = onContinue
        val l = Prefs.lang(ctx)
        val scale = Prefs.textScale(ctx)
        val warn = mode == Mode.WARN || mode == Mode.ALARM
        val ink = if (warn) C.WHITE else C.INK
        val soft = if (warn) 0xD9FFFFFF.toInt() else C.MUTED

        (card.background as GradientDrawable).apply {
            setColor(if (warn) C.VERMILION else C.PAPER)
            setStroke(ctx.dp(1), if (warn) 0x33FFFFFF else C.LINE)
        }
        orb.mood = when (mode) {
            Mode.AUTO -> OrbView.Mood.ACTIVE
            Mode.WARN, Mode.ALARM -> OrbView.Mood.WARN
            Mode.DONE -> OrbView.Mood.DONE
            Mode.THINKING -> OrbView.Mood.ACTIVE
            else -> OrbView.Mood.IDLE
        }
        header.setTextColor(soft)
        header.typeface = Type.bold(ctx)
        val headerText = when (mode) {
            Mode.WARN -> say("Careful", "सावधान", "జాగ్రత్త").pick(l)
            Mode.ALARM -> say("Stop — are you on a call?", "रुकिए — क्या आप फ़ोन पर हैं?", "ఆగండి — మీరు కాల్‌లో ఉన్నారా?").pick(l)
            Mode.CONFIRM -> say("Before I press it", "दबाने से पहले", "నొక్కే ముందు").pick(l)
            Mode.AUTO -> say("Doing it for you · watch the glow", "आपके लिए कर रहा हूँ · चमक देखिए", "మీ కోసం చేస్తున్నాను · మెరుపు చూడండి").pick(l)
            Mode.THINKING -> say("Looking…", "देख रहा हूँ…", "చూస్తున్నాను…").pick(l)
            Mode.PAUSED -> say("Paused", "रुका हुआ", "ఆగింది").pick(l)
            else -> null
        }
        header.text = headerText ?: ""
        header.visibility = if (headerText != null) View.VISIBLE else View.GONE
        renderDots(if (warn) null else progress)
        grab.background = GradientDrawable().apply { cornerRadius = ctx.dpf(2); setColor(if (warn) 0x55FFFFFF else C.PAPER_3) }

        cardText.typeface = Type.display(ctx)
        cardText.textSize = 20f * scale
        // Telugu / Devanagari glyphs reach above and below Latin line bounds: without font padding and a taller
        // line the top and bottom of long Telugu lines were cut off.
        val indic = text.any { it.code in 0x0900..0x0DFF }
        cardText.includeFontPadding = indic
        cardText.setLineSpacing(0f, if (indic) 1.22f else 1.08f)
        if (android.os.Build.VERSION.SDK_INT >= 28) cardText.isFallbackLineSpacing = true
        cardText.setTextColor(ink)
        if (changed) {
            cardText.text = text
            cardText.alpha = 0f; cardText.translationY = ctx.dpf(6)
            cardText.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(260).start()
        }
        // Tips are spoken; on the card they'd double its height. Shown only in lesson mode (Phone School).
        tipText.visibility = View.GONE
        tipText.text = tip ?: ""

        // One primary action per state.
        val primaryText = when (mode) {
            Mode.STEP -> say("Do it for me", "आप कर दो", "మీరే చేయండి").pick(l)
            Mode.SCROLL -> say("Scroll for me", "आप स्क्रॉल कर दो", "మీరే స్క్రోల్ చేయండి").pick(l)
            Mode.PAUSED -> say("Continue", "जारी रखें", "కొనసాగించు").pick(l)
            Mode.LOST -> say("Take me home", "होम पर ले चलो", "హోమ్‌కి తీసుకెళ్ళు").pick(l)
            Mode.ALARM -> say("I'll hang up now", "मैं फ़ोन काट रहा हूँ", "నేను ఫోన్ పెట్టేస్తాను").pick(l)
            Mode.CONFIRM -> say("Yes, press it", "हाँ, दबा दो", "అవును, నొక్కండి").pick(l)
            Mode.AUTO -> say("Let me do it myself", "मैं ख़ुद करूँगा", "నేనే చేస్తాను").pick(l)
            Mode.ASK -> say("Yes, please", "हाँ, कीजिए", "అవును, చేయండి").pick(l)
            Mode.CHOICE -> choiceA?.first
            Mode.FINAL -> say("I'm done", "हो गया", "అయిపోయింది").pick(l)
            Mode.WARN -> if (targetCenterY != null) say("Show me the safe button", "सुरक्षित बटन दिखाओ", "సురక్షిత బటన్ చూపించు").pick(l) else if (onContinue != null) say("Take me back to safety", "मुझे सुरक्षित वापस ले चलो", "నన్ను సురక్షితంగా వెనక్కి తీసుకెళ్ళు").pick(l) else null
            else -> null
        }
        val primaryIcon = when (mode) {
            Mode.PAUSED -> R.drawable.ic_play_circle
            Mode.LOST -> R.drawable.ic_home
            Mode.ALARM -> R.drawable.ic_call_end
            Mode.CONFIRM, Mode.ASK -> R.drawable.ic_check
            Mode.CHOICE -> choiceA?.second ?: R.drawable.ic_check
            Mode.AUTO -> R.drawable.ic_pause_circle
            Mode.FINAL -> R.drawable.ic_check
            Mode.WARN -> R.drawable.ic_shield
            Mode.SCROLL -> R.drawable.ic_arrow_downward
            else -> R.drawable.ic_touch_app
        }
        primary.visibility = if (primaryText != null) View.VISIBLE else View.GONE
        primary.set(primaryText ?: "", primaryIcon,
            bg = if (warn) C.WHITE else C.PINE_DEEP, fg = if (warn) C.VERMILION else C.WHITE, big = true)

        secondary.visibility = if (mode == Mode.CHOICE && choiceB != null) View.VISIBLE else View.GONE
        choiceB?.let { if (mode == Mode.CHOICE) secondary.set(it.first, it.second, bg = C.PINE_DEEP, fg = C.WHITE, big = true) }
        val quietBg = if (warn) 0x26FFFFFF else C.PAPER_2
        val quietFg = if (warn) C.WHITE else C.PINE_DEEP
        if (mode == Mode.ALARM || mode == Mode.CONFIRM) {
            btnAgain.visibility = View.GONE; btnMic.visibility = View.GONE
        } else if (mode == Mode.ASK || mode == Mode.CHOICE) {
            // A question can be answered out loud too ("yes", "WhatsApp"): the mic stays (field: no way to reply).
            btnAgain.visibility = View.GONE
            btnMic.visibility = View.VISIBLE
            btnMic.round(say("Answer by voice", "बोलकर बताइए", "మాటతో చెప్పండి").pick(l), R.drawable.ic_mic, quietBg, quietFg)
        } else if (mode == Mode.LOST) {
            // Lost: Back · Ask family · Close, all one tap.
            btnAgain.visibility = View.VISIBLE
            btnAgain.round(say("Go back", "वापस जाओ", "వెనక్కి వెళ్ళు").pick(l), R.drawable.ic_arrow_back, quietBg, quietFg)
            btnMic.visibility = View.VISIBLE
            btnMic.round(say("Ask family", "परिवार से पूछें", "కుటుంబాన్ని అడగండి").pick(l), R.drawable.ic_family_restroom, quietBg, quietFg)
        } else {
            btnAgain.visibility = if (mode == Mode.THINKING || mode == Mode.PAUSED) View.GONE else View.VISIBLE
            btnAgain.round(say("Repeat", "फिर से", "మళ్ళీ").pick(l), R.drawable.ic_replay, quietBg, quietFg)
            btnMic.visibility = if (mode == Mode.DONE || mode == Mode.WARN) View.GONE else View.VISIBLE
            btnMic.round(say("Speak to Saathi", "साथी से बोलिए", "సాథీతో మాట్లాడండి").pick(l), R.drawable.ic_mic, quietBg, quietFg)
        }
        btnStop.round(if (mode == Mode.ALARM) say("It's family, continue", "परिवार है, जारी रखें", "కుటుంబమే, కొనసాగించు").pick(l)
            else if (mode == Mode.ASK || mode == Mode.CHOICE) say("Not now", "अभी नहीं", "ఇప్పుడు వద్దు").pick(l)
            else if (mode == Mode.CONFIRM) say("Don't press it", "मत दबाओ", "నొక్కవద్దు").pick(l)
            else if (mode == Mode.DONE || mode == Mode.LOST || mode == Mode.INFO) say("Close", "बंद करें", "మూసివేయి").pick(l) else say("Stop", "रोकें", "ఆపండి").pick(l),
            R.drawable.ic_close, quietBg, quietFg)
        lastTargetY = targetCenterY

        place(animateIn = !cardShown)
    }

    private var lastTargetY: Int? = null

    /** A spoken "yes" answers the card that is asking (ASK / PAUSED / CONFIRM). true = it was waiting for one. */
    fun acceptPending(): Boolean {
        if (!cardShown) return false   // a card that already went away answers nothing
        val go = onContinue
        return when {
            mode == Mode.CONFIRM -> { onDoIt(); true }
            (mode == Mode.ASK || mode == Mode.PAUSED) && go != null -> { onContinue = null; go(); true }
            else -> false
        }
    }

    /** A spoken "no" / "not now" closes the question, the same as the "Not now" button. */
    fun declinePending(): Boolean = if (!cardShown) false else when (mode) {
        Mode.ASK, Mode.CHOICE -> { onContinue = null; hideCard(); true }
        Mode.CONFIRM -> { onDecline(); true }
        else -> false
    }

    /** "WhatsApp" / "phone call" / "let me try" said aloud picks that button of a two-choice card. */
    fun pickChoice(said: String): Boolean {
        if (mode != Mode.CHOICE || !cardShown) return false
        val s = said.lowercase().trim()
        fun hit(label: String?) = label != null && label.lowercase().let { l -> s == l || (s.length >= 4 && (l.contains(s) || s.contains(l))) }
        return when {
            hit(choiceA?.first) -> { hideCard(); choiceA?.third?.invoke(); true }
            hit(choiceB?.first) -> { hideCard(); choiceB?.third?.invoke(); true }
            else -> false
        }
    }

    /**
     * Top or bottom: where the person dragged it, else away from the target. Never on top of the target
     * (trap #30), even if they chose that side.
     */
    /**
     * Where the card goes: the first spot (top, bottom, just below or above the target) that covers neither the
     * target, the box being typed in, nor the keyboard. It stays put while its spot is still clear (no jumping).
     */
    private fun place(animateIn: Boolean, moved: Boolean = false) {
        val screenH = ctx.resources.displayMetrics.heightPixels
        val topMin = ctx.dp(46)                                   // clear of the status bar (and the event's HackTracker pill)
        val bottomMax = (imeTop ?: screenH) - ctx.dp(10)
        val ch = (if (card.height > 0) card.height else ctx.dp(170)) + ctx.dp(16)
        val avoid = listOfNotNull(targetBox, focusBox).map { Rect(it).apply { inset(0, -ctx.dp(8)) } }
        fun clear(y: Int) = y >= topMin && y + ch <= bottomMax && avoid.none { it.top < y + ch && it.bottom > y }
        val tgt = targetBox
        val candidates = buildList {
            when (Prefs.cardPos(ctx)) { "top" -> add(topMin); "bottom" -> add(bottomMax - ch) }
            if (!animateIn && cardY != null) add(cardY!!)          // hysteresis: stay if still clear
            if (tgt != null && tgt.centerY() > screenH / 2) { add(topMin); add(bottomMax - ch) } else { add(bottomMax - ch); add(topMin) }
            if (tgt != null) { add(tgt.bottom + ctx.dp(14)); add(tgt.top - ctx.dp(14) - ch) }
            focusBox?.let { add(it.bottom + ctx.dp(14)) }
        }
        // Nothing fits cleanly: never over the keyboard (field: a tall login card sat on JioHotstar's keypad); overlap
        // the target / input box as little as possible instead.
        val y = candidates.firstOrNull { clear(it) }
            ?: candidates.map { it.coerceIn(topMin, maxOf(topMin, bottomMax - ch)) }
                .minByOrNull { c -> avoid.sumOf { a -> maxOf(0, minOf(a.bottom, c + ch) - maxOf(a.top, c)) } } ?: topMin
        val wantTop = y + ch / 2 < screenH / 2
        val p = lp(WLP.MATCH_PARENT, WLP.WRAP_CONTENT, true).apply { gravity = Gravity.TOP; this.y = y.coerceAtLeast(topMin) }
        val changed = y != cardY
        if (animateIn) {
            if (quiet) return   // back after the launch settles (onQuietEnd re-shows it)
            if (!add(cardWrap, p)) return
            cardShown = true
            bubbleForCard(true)
            card.translationY = ctx.dpf(if (wantTop) -40 else 40); card.alpha = 0f
            card.scaleX = 0.96f; card.scaleY = 0.96f
            card.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(EASE).setDuration(400).start()
        } else if (changed) {
            card.animate().alpha(0f).setDuration(110).withEndAction {
                runCatching { wm.updateViewLayout(cardWrap, p) }
                card.translationY = ctx.dpf(if (wantTop) -20 else 20)
                card.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(260).start()
            }.start()
        }
        cardY = y; cardAtTop = wantTop
    }

    private var cardY: Int? = null

    private fun hideCardNow() {
        if (!cardShown) return
        cardShown = false
        bubbleForCard(false)
        runCatching { wm.removeView(cardWrap) }
    }

    fun hideCard() {
        if (!cardShown) return
        cardShown = false
        bubbleForCard(false)
        card.animate().alpha(0f).translationY(ctx.dpf(if (cardAtTop) -36 else 36)).setInterpolator(EASE).setDuration(240)
            .withEndAction { if (!cardShown) runCatching { wm.removeView(cardWrap) } }.start()
    }

    private fun renderDots(progress: Pair<Int, Int>?) {
        dots.removeAllViews()
        val (i, n) = progress ?: return
        if (n < 2) return
        for (k in 1..n) {
            dots.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dpf(3)
                    setColor(when { k < i -> C.PINE; k == i -> C.MARIGOLD; else -> C.PAPER_3 })
                }
            }, LinearLayout.LayoutParams(ctx.dp(if (k == i) 16 else 6), ctx.dp(6)).apply { marginStart = ctx.dp(4) })
        }
    }

    // ───────── building blocks ─────────

    /** Rounded, labelled button with an icon; springy press. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Pill(ctx: Context) : LinearLayout(ctx) {
        val icon = ImageView(ctx)
        val text = TextView(ctx)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(icon)
            addView(text)
            text.maxLines = 1
            isClickable = true
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(3f)).setDuration(240).start()
                }
                false
            }
        }

        /** Icon-only circle (the label is still announced to TalkBack). */
        fun round(label: String, iconRes: Int, bg: Int, fg: Int) {
            val c = context
            text.visibility = GONE
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(fg)
            icon.layoutParams = LayoutParams(c.dp(24), c.dp(24))
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
            contentDescription = label
        }

        /** Big = one row, icon + label. Quiet = icon stacked over a label, so long words never clip. */
        fun set(label: String, iconRes: Int, bg: Int, fg: Int, big: Boolean = false) {
            val c = context
            orientation = if (big) HORIZONTAL else VERTICAL
            text.text = label
            text.textSize = if (big) 19f else 15f
            if (big) text.setAutoSizeTextTypeUniformWithConfiguration(13, 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            text.typeface = Type.bold(c)
            text.setTextColor(fg)
            text.ellipsize = android.text.TextUtils.TruncateAt.END
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(fg)
            val s = c.dp(if (big) 22 else 22)
            icon.layoutParams = LayoutParams(s, s).apply { if (big) marginEnd = c.dp(8) else bottomMargin = c.dp(2) }
            setPadding(c.dp(8), 0, c.dp(8), 0)
            background = GradientDrawable().apply { cornerRadius = c.dpf(if (big) 32 else 24); setColor(bg) }
            contentDescription = label
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCard() {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(14))
        card.background = GradientDrawable().apply { cornerRadius = ctx.dpf(28); setColor(C.PAPER) }
        card.elevation = ctx.dpf(20)

        // Grab handle + step dots: drag the card to the top or bottom of the screen.
        val handleRow = FrameLayout(ctx)
        handleRow.addView(grab, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(5), Gravity.CENTER))
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER_VERTICAL
        handleRow.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        card.addView(handleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(18)))

        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(orb, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)).apply { marginEnd = ctx.dp(12) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        header.textSize = 14f
        header.letterSpacing = 0.04f
        texts.addView(header)
        cardText.setLineSpacing(0f, 1.08f)
        cardText.includeFontPadding = false
        texts.addView(cardText)
        top.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })

        tipText.setPadding(ctx.dp(14), ctx.dp(2), 0, ctx.dp(2))
        tipText.background = TipBar(C.MARIGOLD, ctx.dpf(3))
        card.addView(tipText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })

        // One row: the primary action, then quiet round buttons.
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        row.addView(primary, LinearLayout.LayoutParams(0, ctx.dp(56), 1f).apply { marginEnd = ctx.dp(8) })
        row.addView(secondary, LinearLayout.LayoutParams(0, ctx.dp(56), 1f).apply { marginEnd = ctx.dp(8) })
        secondary.visibility = View.GONE
        secondary.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CONFIRM); hideCard(); choiceB?.third?.invoke() }
        fun r(p: Pill, last: Boolean = false) = row.addView(p, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)).apply { if (!last) marginEnd = ctx.dp(6) })
        r(btnAgain); r(btnMic); r(btnStop, last = true)
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        btnMic.setOnClickListener { if (mode == Mode.LOST) onFamily() else onMic() }
        btnAgain.setOnClickListener { if (mode == Mode.LOST) onBack() else onAgain() }
        primary.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when (mode) { Mode.CHOICE -> { hideCard(); choiceA?.third?.invoke() }; Mode.PAUSED, Mode.ASK -> onContinue?.invoke(); Mode.FINAL -> onFinalDone(); Mode.LOST, Mode.ALARM -> onHome(); else -> onDoIt() }
        }
        btnStop.setOnClickListener {
            when (mode) {
                Mode.ASK, Mode.CHOICE -> hideCard()
                Mode.CONFIRM -> onDecline()
                Mode.DONE, Mode.LOST, Mode.INFO, Mode.ALARM -> { hideCard(); onStop() }
                else -> onStop()
            }
        }

        // Drag anywhere on the card (not the buttons) to move it; it snaps to the nearer edge and stays there.
        // The WINDOW moves, not the card inside it: the window is only as tall as the card, so moving the view
        // clipped it at the window's edge (field: "it stays inside a container").
        var downY = 0f
        var startWinY = 0
        var dragging = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startWinY = (cardWrap.layoutParams as? WLP)?.y ?: 0; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > ctx.dp(8)) dragging = true
                    if (dragging) (cardWrap.layoutParams as? WLP)?.let { p ->
                        val h = ctx.resources.displayMetrics.heightPixels
                        p.y = (startWinY + dy.toInt()).coerceIn(0, maxOf(0, h - cardWrap.height))
                        runCatching { wm.updateViewLayout(cardWrap, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val h = ctx.resources.displayMetrics.heightPixels
                        val toTop = e.rawY < h / 2
                        Prefs.setCardPos(ctx, if (toTop) "top" else "bottom")
                        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        cardY = null   // no "stay where it was": go to the side they chose
                        place(animateIn = false, moved = true)
                    }
                    true
                }
                else -> false
            }
        }

        cardWrap.setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        cardWrap.clipToPadding = false
        cardWrap.clipChildren = false
        cardWrap.addView(card)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = ctx.dp(56)
        bubble.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.PINE_DEEP); setStroke(ctx.dp(2), 0x33FFFFFF) }
        bubble.elevation = ctx.dpf(12)
        bubble.addView(bubbleOrb, FrameLayout.LayoutParams(ctx.dp(38), ctx.dp(38), Gravity.CENTER))
        bubble.contentDescription = "Saathi. Tap to ask. Hold to speak."
        val m = ctx.resources.displayMetrics
        val p = lp(size, size, true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = m.widthPixels - size - ctx.dp(10)
            y = (m.heightPixels * 0.38f).toInt()
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onBubbleLong() }
        }
        bubble.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; longFired = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    v.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) + abs(dy) > ctx.dp(10)) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) { p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(bubble, p) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(4f)).setDuration(260).start()
                    if (moved) snapToEdge(p) else if (!longFired && e.action == MotionEvent.ACTION_UP) onBubble()
                }
            }
            true
        }
        bubbleLp = p
        add(bubble, p)
    }

    /** After a drag the bubble glides to the nearest side. */
    private fun snapToEdge(p: WLP) {
        val w = ctx.resources.displayMetrics.widthPixels
        val target = if (p.x + p.width / 2 < w / 2) ctx.dp(10) else w - p.width - ctx.dp(10)
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 340; interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { p.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(bubble, p) } }
            start()
        }
    }

    /** Never let the bubble sit on the thing we're asking them to tap. */
    private fun dodgeBubble(target: Rect) {
        val p = bubbleLp ?: return
        val gap = ctx.dp(24)
        val b = Rect(p.x, p.y, p.x + p.width, p.y + p.height)
        if (!Rect.intersects(b, Rect(target).apply { inset(-gap, -gap) })) return
        val h = ctx.resources.displayMetrics.heightPixels
        val to = if (target.bottom + gap + p.height < h * 0.7) target.bottom + gap else maxOf(ctx.dp(80), target.top - gap - p.height)
        ValueAnimator.ofInt(p.y, to).apply {
            duration = 300; interpolator = EASE
            addUpdateListener { p.y = it.animatedValue as Int; runCatching { wm.updateViewLayout(bubble, p) } }
            start()
        }
    }
}

/** A thin marigold bar on the left of teaching tips. */
private class TipBar(color: Int, private val w: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(c: Canvas) {
        val b = bounds
        c.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.left + w, b.bottom.toFloat(), w, w, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
