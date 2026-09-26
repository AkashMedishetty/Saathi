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
 *  glow (full screen, not touchable) · watcher (1×1, told about every outside touch) · card · bubble.
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
) {
    enum class Mode { INFO, THINKING, STEP, SCROLL, FINAL, WARN, DONE, PAUSED }

    private val wm = ctx.getSystemService(WindowManager::class.java)
    val glow = GlowView(ctx)
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
        add(glow, lp(WLP.MATCH_PARENT, WLP.MATCH_PARENT, false))
        // 1×1 invisible window that hears about every touch elsewhere (ACTION_OUTSIDE): "the person is busy".
        add(watcher, lp(1, 1, true).apply {
            flags = flags or WLP.FLAG_WATCH_OUTSIDE_TOUCH or WLP.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
        })
        watcher.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) onTouchOutside(); false }
        buildCard()
        buildBubble()
    }

    fun detach() {
        listOf(glow, watcher, bubble).forEach { runCatching { wm.removeView(it) } }
        if (cardShown) runCatching { wm.removeView(cardWrap) }
        cardShown = false
    }

    // ───────── glow ─────────

    fun highlight(r: Rect?, warn: Boolean) {
        glow.dim = Prefs.dim(ctx)
        val m = ctx.resources.displayMetrics
        val clipped = r?.let { Rect(it).apply { if (!intersect(0, 0, m.widthPixels, m.heightPixels)) setEmpty() } }?.takeIf { !it.isEmpty }
        glow.setTarget(clipped, warn)
        r?.let { dodgeBubble(it) }
    }

    fun setMoving(moving: Boolean) = glow.setMoving(moving)

    /** Listening / thinking: the edge of the screen comes alive, and so do the orbs. */
    fun setAura(on: Boolean) {
        glow.setAura(on)
        bubbleOrb.mood = if (on) OrbView.Mood.ACTIVE else OrbView.Mood.IDLE
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
        val changed = cardText.text.toString() != text || this.mode != mode
        this.mode = mode
        this.onContinue = onContinue
        val l = Prefs.lang(ctx)
        val scale = Prefs.textScale(ctx)
        val warn = mode == Mode.WARN
        val ink = if (warn) C.WHITE else C.INK
        val soft = if (warn) 0xD9FFFFFF.toInt() else C.MUTED

        (card.background as GradientDrawable).apply {
            setColor(if (warn) C.VERMILION else C.PAPER)
            setStroke(ctx.dp(1), if (warn) 0x33FFFFFF else C.LINE)
        }
        orb.mood = when (mode) {
            Mode.WARN -> OrbView.Mood.WARN
            Mode.DONE -> OrbView.Mood.DONE
            Mode.THINKING -> OrbView.Mood.ACTIVE
            else -> OrbView.Mood.IDLE
        }
        header.setTextColor(soft)
        header.typeface = Type.bold(ctx)
        val headerText = when (mode) {
            Mode.WARN -> say("Careful", "सावधान", "జాగ్రత్త").pick(l)
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
            Mode.FINAL -> say("I'm done", "हो गया", "అయిపోయింది").pick(l)
            Mode.WARN -> if (targetCenterY != null) say("Show me the safe button", "सुरक्षित बटन दिखाओ", "సురక్షిత బటన్ చూపించు").pick(l) else null
            else -> null
        }
        val primaryIcon = when (mode) {
            Mode.PAUSED -> R.drawable.ic_play_circle
            Mode.FINAL -> R.drawable.ic_check
            Mode.WARN -> R.drawable.ic_shield
            Mode.SCROLL -> R.drawable.ic_arrow_downward
            else -> R.drawable.ic_touch_app
        }
        primary.visibility = if (primaryText != null) View.VISIBLE else View.GONE
        primary.set(primaryText ?: "", primaryIcon,
            bg = if (warn) C.WHITE else C.PINE_DEEP, fg = if (warn) C.VERMILION else C.WHITE, big = true)

        val quietBg = if (warn) 0x26FFFFFF else C.PAPER_2
        val quietFg = if (warn) C.WHITE else C.PINE_DEEP
        btnAgain.visibility = if (mode == Mode.THINKING || mode == Mode.PAUSED) View.GONE else View.VISIBLE
        btnAgain.round(say("Repeat", "फिर से", "మళ్ళీ").pick(l), R.drawable.ic_replay, quietBg, quietFg)
        btnMic.visibility = if (mode == Mode.DONE || mode == Mode.WARN) View.GONE else View.VISIBLE
        btnMic.round(say("Speak to Saathi", "साथी से बोलिए", "సాథీతో మాట్లాడండి").pick(l), R.drawable.ic_mic, quietBg, quietFg)
        btnStop.round(if (mode == Mode.DONE) say("Close", "बंद करें", "మూసివేయి").pick(l) else say("Stop", "रोकें", "ఆపండి").pick(l),
            R.drawable.ic_close, quietBg, quietFg)
        lastTargetY = targetCenterY

        place(animateIn = !cardShown)
    }

    private var lastTargetY: Int? = null

    /**
     * Top or bottom: where the person dragged it, else away from the target. Never on top of the target
     * (trap #30), even if they chose that side.
     */
    private fun place(animateIn: Boolean) {
        val screenH = ctx.resources.displayMetrics.heightPixels
        val ty = lastTargetY
        val targetLow = ty != null && ty > screenH * 0.52
        val wantTop = when (Prefs.cardPos(ctx)) {
            "top" -> !(ty != null && ty < screenH * 0.40)
            "bottom" -> ty != null && ty > screenH * 0.60
            else -> targetLow
        }
        val p = lp(WLP.MATCH_PARENT, WLP.WRAP_CONTENT, true).apply {
            gravity = if (wantTop) Gravity.TOP else Gravity.BOTTOM
            y = ctx.dp(if (wantTop) 46 else 10) // clear the status bar (and the event HackTracker pill)
        }
        if (animateIn) {
            if (!add(cardWrap, p)) return
            cardShown = true
            card.translationY = ctx.dpf(if (wantTop) -40 else 40); card.alpha = 0f
            card.scaleX = 0.96f; card.scaleY = 0.96f
            card.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(EASE).setDuration(400).start()
        } else if (wantTop != cardAtTop) {
            card.animate().alpha(0f).setDuration(120).withEndAction {
                runCatching { wm.updateViewLayout(cardWrap, p) }
                card.translationY = ctx.dpf(if (wantTop) -24 else 24)
                card.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(300).start()
            }.start()
        }
        cardAtTop = wantTop
    }

    fun hideCard() {
        if (!cardShown) return
        cardShown = false
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
        fun r(p: Pill, last: Boolean = false) = row.addView(p, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)).apply { if (!last) marginEnd = ctx.dp(6) })
        r(btnAgain); r(btnMic); r(btnStop, last = true)
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        btnMic.setOnClickListener { onMic() }
        btnAgain.setOnClickListener { onAgain() }
        primary.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when (mode) { Mode.PAUSED -> onContinue?.invoke(); Mode.FINAL -> onFinalDone(); else -> onDoIt() }
        }
        btnStop.setOnClickListener { if (mode == Mode.DONE) hideCard() else onStop() }

        // Drag anywhere on the card (not the buttons) to move it; it snaps to the nearer edge and stays there.
        var downY = 0f
        var dragging = false
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (!dragging && abs(dy) > ctx.dp(8)) dragging = true
                    if (dragging) v.translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val h = ctx.resources.displayMetrics.heightPixels
                        val toTop = e.rawY < h / 2
                        Prefs.setCardPos(ctx, if (toTop) "top" else "bottom")
                        v.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        v.animate().translationY(0f).alpha(0f).setDuration(120).withEndAction {
                            cardAtTop = !toTop // force a re-place
                            place(animateIn = false)
                            v.alpha = 1f
                        }.start()
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
