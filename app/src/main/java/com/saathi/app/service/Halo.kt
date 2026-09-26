package com.saathi.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.animation.LinearInterpolator
import com.saathi.app.ui.C
import com.saathi.app.ui.EASE
import com.saathi.app.ui.dpf
import kotlin.math.sin

/**
 * The pointer: a calm marigold ring around the thing to tap.
 *
 * It is drawn in FOUR slim windows placed around the target, never over it. A window that covers the tap point
 * (the old full-screen glow) makes Android flag the tap as "obscured by another app". Apps then ignored the tap
 * (YouTube search "did nothing"), and vivo switched Saathi off when a covered tap opened Settings (field test,
 * 26 Sep). With nothing over the target, the tap reaches the app exactly as if Saathi weren't there.
 *
 * Look: one crisp ring + a soft outer glow + a slow ripple. No dimming, no rainbow, no flying hand.
 * It fades while a finger is on the screen.
 */
class Halo(private val ctx: Context, private val wm: WindowManager) {
    private val gap = ctx.dpf(6)          // space between the target and the ring
    private val reach = ctx.dpf(26)       // how far the glow/ripple extends beyond the ring
    private val corner = ctx.dpf(16)
    private val strips = List(4) { Strip(ctx) }
    private val lps = arrayOfNulls<WLP>(4)
    private val attached = BooleanArray(4)

    private var ring: RectF? = null       // screen coords
    private var warn = false
    private var alpha = 0f                // overall fade 0..1
    private var touchFade = 1f            // 1 = normal, ~0.15 while a finger is down
    private var t = 0f
    @Volatile var suspended = false; private set

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            t += ((now - last).coerceIn(0, 100)) / 1000f; last = now
            strips.forEach { s -> s.invalidate() }
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationRepeat(a: android.animation.Animator) { last = 0 }
        })
    }
    private var fadeAnim: ValueAnimator? = null
    private var touchAnim: ValueAnimator? = null

    val showing get() = ring != null && !suspended

    /** Point at [target] (screen coordinates), or clear with null. */
    fun show(target: Rect?, warn: Boolean) {
        if (target == null || target.isEmpty) { clear(); return }
        val next = RectF(target).apply { inset(-gap, -gap) }
        val same = ring == next && this.warn == warn
        this.warn = warn
        if (same && alpha > 0.9f) return
        ring = next
        place(target)
        if (!suspended) attachAll()
        alpha = 0f
        fade(1f, 260)
        if (!loop.isStarted) loop.start()
    }

    fun clear() {
        if (ring == null) return
        fade(0f, 180) { detachAll(); ring = null; loop.cancel() }
    }

    /** A finger is on the screen: fade right down; back up when it lifts. */
    fun setMoving(moving: Boolean) {
        val to = if (moving) 0.15f else 1f
        if (touchFade == to) return
        touchAnim?.cancel()
        touchAnim = ValueAnimator.ofFloat(touchFade, to).apply {
            duration = if (moving) 90 else 240; interpolator = EASE
            addUpdateListener { touchFade = it.animatedValue as Float; strips.forEach { s -> s.invalidate() } }
            start()
        }
    }

    /** Remove every window at once (banking apps, lock screen, a tap heading into Settings); [resume] restores. */
    fun suspend() { suspended = true; detachAll() }
    fun resume() { if (!suspended) return; suspended = false; if (ring != null) attachAll() }

    fun setVisible(v: Boolean) = strips.forEach { it.visibility = if (v) View.VISIBLE else View.INVISIBLE }

    // ───────── layout: four windows around the target ─────────

    private fun place(target: Rect) {
        val m = ctx.resources.displayMetrics
        val W = m.widthPixels; val H = m.heightPixels
        val ext = (gap + reach).toInt()
        val l = target.left; val tp = target.top; val r = target.right; val b = target.bottom
        // top, bottom (full width of the halo), left, right (height of the target only): no overlap with the target.
        val boxes = listOf(
            Rect(l - ext, tp - ext, r + ext, tp),
            Rect(l - ext, b, r + ext, b + ext),
            Rect(l - ext, tp, l, b),
            Rect(r, tp, r + ext, b),
        ).map { Rect(it).apply { if (!intersect(0, 0, W, H)) setEmpty() } }
        boxes.forEachIndexed { i, box ->
            val p = lps[i] ?: WLP(0, 0, WLP.TYPE_ACCESSIBILITY_OVERLAY,
                WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCHABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }.also { lps[i] = it }
            p.x = box.left; p.y = box.top; p.width = box.width(); p.height = box.height()
            strips[i].box.set(box)
            if (attached[i]) {
                if (box.isEmpty) { runCatching { wm.removeView(strips[i]) }; attached[i] = false }
                else runCatching { wm.updateViewLayout(strips[i], p) }
            }
        }
    }

    private fun attachAll() {
        for (i in 0 until 4) {
            val p = lps[i] ?: continue
            if (!attached[i] && !strips[i].box.isEmpty) attached[i] = runCatching { wm.addView(strips[i], p) }
                .onFailure { com.saathi.app.DebugLog.w("halo", "add strip $i failed", it) }.isSuccess
        }
    }

    private fun detachAll() {
        for (i in 0 until 4) if (attached[i]) { runCatching { wm.removeView(strips[i]) }; attached[i] = false }
    }

    private fun fade(to: Float, ms: Long, end: (() -> Unit)? = null) {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(alpha, to).apply {
            duration = ms; interpolator = EASE
            addUpdateListener { alpha = it.animatedValue as Float; strips.forEach { s -> s.invalidate() } }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
            start()
        }
    }

    // ───────── drawing: every strip draws the same ring in screen space, clipped to itself ─────────

    private inner class Strip(c: Context) : View(c) {
        val box = Rect()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rr = RectF()
        private val loc = IntArray(2)

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }

        override fun onDraw(c: Canvas) {
            val r = ring ?: return
            val a = alpha * touchFade
            if (a < 0.01f) return
            c.save()
            getLocationOnScreen(loc) // where the system really put this window
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            val color = if (warn) C.VERMILION else C.MARIGOLD
            val rad = minOf(corner, r.height() / 2, r.width() / 2)
            // Soft outer glow: three widening, fading strokes, gently breathing.
            val breath = 0.5f + 0.5f * sin(t * 2.2f)
            for (k in 3 downTo 1) {
                val w = gap * k * (0.9f + 0.2f * breath)
                stroke.color = color; stroke.strokeWidth = w
                stroke.alpha = ((22 + 10 * (3 - k)) * a).toInt()
                rr.set(r); rr.inset(-w / 2, -w / 2)
                c.drawRoundRect(rr, rad + w / 2, rad + w / 2, stroke)
            }
            // The ring itself: crisp and solid.
            stroke.strokeWidth = gap * 0.55f; stroke.alpha = (255 * a).toInt()
            c.drawRoundRect(r, rad, rad, stroke)
            // One slow ripple outward, every 1.8 s.
            val ph = (t / 1.8f) % 1f
            val grow = ph * reach * 0.9f
            stroke.strokeWidth = gap * 0.35f; stroke.alpha = ((1 - ph) * 150 * a).toInt()
            rr.set(r); rr.inset(-grow, -grow)
            c.drawRoundRect(rr, rad + grow, rad + grow, stroke)
            c.restore()
        }
    }
}
