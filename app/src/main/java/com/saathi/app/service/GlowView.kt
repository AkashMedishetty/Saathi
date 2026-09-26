package com.saathi.app.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.saathi.app.R
import com.saathi.app.ui.C
import com.saathi.app.ui.EASE
import com.saathi.app.ui.dpf
import kotlin.math.sin

/**
 * Saathi's light. Two looks, one full-screen, non-touchable view:
 *  - Spotlight: the screen dims except the target, which gets a luminous marigold ring that slowly
 *    turns, breathes, and a pointer chip that bobs toward it.
 *  - Aura: while Saathi listens or thinks, a soft living glow runs around the screen edge.
 * Everything is drawn with layered strokes + a rotating sweep gradient (GPU-friendly; no BlurMaskFilter,
 * trap #29). The loop only runs while something is visible.
 */
class GlowView(ctx: Context) : View(ctx) {

    var dim = true

    // spotlight
    private var from: RectF? = null
    private var to: RectF? = null
    private var move = 1f
    private var warn = false
    private var moving = false
    private var spot = 0f

    // aura
    private var aura = 0f
    private var auraLevel = 0f // 0..1 voice level makes it swell

    private var t = 0f // seconds, drives rotation/breathing
    private val loc = IntArray(2)
    private val pad = ctx.dpf(9)
    private val radius = ctx.dpf(20)
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hole = Path()
    private val sweepColors = intArrayOf(C.MARIGOLD, C.MARIGOLD_HI, C.SAFFRON, C.ROSE, C.SAFFRON, C.MARIGOLD)
    private val shaderM = Matrix()
    private val hand = ContextCompat.getDrawable(ctx, R.drawable.ic_touch_app)!!.mutate().apply { setTint(C.PINE_DEEP) }
    private var screenCorner = ctx.dpf(36)

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            t += ((now - last).coerceIn(0, 100)) / 1000f
            last = now
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() { override fun onAnimationRepeat(a: Animator) { last = 0 } })
    }
    private var moveAnim: ValueAnimator? = null
    private var spotAnim: ValueAnimator? = null
    private var auraAnim: ValueAnimator? = null

    init {
        // Our own overlay must never feed events back into the screen reader (trap #9).
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 31) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.let { if (it > 0) screenCorner = it.toFloat() }
        }
    }

    override fun onDetachedFromWindow() { loop.cancel(); super.onDetachedFromWindow() }

    // ───────── API ─────────

    fun setTarget(r: Rect?, warn: Boolean) {
        this.warn = warn
        if (r == null) { animSpot(0f); return }
        val next = RectF(r)
        if (to == next && spot > 0.9f) return
        from = if (spot < 0.05f) next else current() ?: next
        to = next
        moveAnim?.cancel()
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (from == next) 0 else 420; interpolator = EASE
            addUpdateListener { move = it.animatedValue as Float; invalidate() }
            start()
        }
        animSpot(1f)
    }

    /** The person is touching/scrolling: soften everything and hide the pointer until it settles. */
    fun setMoving(m: Boolean) { if (m != moving) { moving = m; invalidate() } }

    fun setAura(on: Boolean) {
        auraAnim?.cancel()
        auraAnim = ValueAnimator.ofFloat(aura, if (on) 1f else 0f).apply {
            duration = if (on) 500 else 380; interpolator = EASE
            addUpdateListener { aura = it.animatedValue as Float; ensureLoop() }
            start()
        }
        ensureLoop()
    }

    fun setLevel(level: Float) { auraLevel = auraLevel * 0.6f + level.coerceIn(0f, 1f) * 0.4f }

    private fun animSpot(target: Float) {
        spotAnim?.cancel()
        spotAnim = ValueAnimator.ofFloat(spot, target).apply {
            duration = if (target > spot) 320 else 240; interpolator = EASE
            addUpdateListener { spot = it.animatedValue as Float; ensureLoop() }
            start()
        }
        ensureLoop()
    }

    private fun ensureLoop() {
        val needed = spot > 0.01f || aura > 0.01f
        if (needed && !loop.isStarted && isAttachedToWindow) loop.start()
        if (!needed && loop.isStarted) { loop.cancel(); from = null; to = null }
        invalidate()
    }

    private fun current(): RectF? {
        val a = from ?: return to
        val b = to ?: return null
        return RectF(a.left + (b.left - a.left) * move, a.top + (b.top - a.top) * move,
            a.right + (b.right - a.right) * move, a.bottom + (b.bottom - a.bottom) * move)
    }

    // ───────── drawing ─────────

    override fun onDraw(c: Canvas) {
        if (aura > 0.01f) drawAura(c)
        if (spot > 0.01f) drawSpot(c)
    }

    private fun sweep(cx: Float, cy: Float, speedDeg: Float) {
        val s = SweepGradient(cx, cy, sweepColors, null)
        shaderM.setRotate((t * speedDeg) % 360f, cx, cy)
        s.setLocalMatrix(shaderM)
        ring.shader = s
    }

    private fun drawAura(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        sweep(w / 2, h / 2, 70f)
        val breath = 0.85f + 0.15f * sin(t * 2.4f) + auraLevel * 0.5f
        val r = RectF(0f, 0f, w, h)
        // Bloom: wide faint strokes inward, then a crisp bright line at the very edge.
        for (k in 5 downTo 1) {
            val sw = pad * k * 1.3f * breath
            ring.strokeWidth = sw
            ring.alpha = ((14 + 60 / k) * aura).toInt().coerceIn(0, 255)
            c.drawRoundRect(RectF(r).apply { inset(sw / 2 - pad * 0.3f, sw / 2 - pad * 0.3f) }, screenCorner, screenCorner, ring)
        }
        ring.strokeWidth = pad * 0.55f
        ring.alpha = (255 * aura).toInt()
        c.drawRoundRect(RectF(r).apply { inset(pad * 0.2f, pad * 0.2f) }, screenCorner, screenCorner, ring)
        ring.shader = null
    }

    private fun drawSpot(c: Canvas) {
        val target = current() ?: return
        getLocationOnScreen(loc)
        val r = RectF(target).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()); inset(-pad, -pad) }
        val a = spot * if (moving) 0.35f else 1f
        val rad = minOf(radius, r.height() / 2)

        if (dim && !moving) {
            scrim.color = C.SCRIM; scrim.alpha = (120 * a).toInt()
            hole.reset(); hole.fillType = Path.FillType.EVEN_ODD
            hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            hole.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.drawPath(hole, scrim)
        }

        val breath = 0.5f + 0.5f * sin(t * 3.2f)
        if (warn) { ring.shader = null; ring.color = C.VERMILION } else sweep(r.centerX(), r.centerY(), 120f)
        // Halo: widening, fading strokes.
        for (k in 4 downTo 1) {
            val sw = pad * k * (0.8f + 0.25f * breath)
            ring.strokeWidth = sw
            ring.alpha = ((50 / k + 8) * a).toInt()
            c.drawRoundRect(RectF(r).apply { inset(-sw / 2, -sw / 2) }, rad + sw / 2, rad + sw / 2, ring)
        }
        // Crisp ring.
        ring.strokeWidth = pad * 0.42f
        ring.alpha = (255 * a).toInt()
        c.drawRoundRect(r, rad, rad, ring)
        // One soft ripple outward every cycle.
        val ph = (t * 0.7f) % 1f
        val grow = ph * pad * 3.2f
        ring.strokeWidth = pad * 0.3f
        ring.alpha = ((1 - ph) * 170 * a).toInt()
        c.drawRoundRect(RectF(r).apply { inset(-grow, -grow) }, rad + grow, rad + grow, ring)
        ring.shader = null

        if (!warn && !moving && move >= 1f) drawPointer(c, r, a)
    }

    /** A marigold chip with a hand, bobbing toward the target from below (or above if there's no room). */
    private fun drawPointer(c: Canvas, r: RectF, a: Float) {
        val size = pad * 5.2f
        val gap = pad * 1.6f
        val bob = sin(t * 4f) * pad * 0.7f
        val below = r.bottom + gap + size < height * 0.62f
        val cx = r.centerX()
        val cy = if (below) r.bottom + gap + size / 2 + bob else r.top - gap - size / 2 - bob
        chip.color = C.MARIGOLD; chip.alpha = (255 * a).toInt()
        chip.setShadowLayer(pad, 0f, pad * 0.3f, 0x55000000) // HW-accelerated for shapes since API 28
        c.drawCircle(cx, cy, size / 2, chip)
        val ins = (size * 0.22f).toInt()
        hand.alpha = (255 * a).toInt()
        hand.setBounds((cx - size / 2 + ins).toInt(), (cy - size / 2 + ins).toInt(), (cx + size / 2 - ins).toInt(), (cy + size / 2 - ins).toInt())
        c.save()
        if (!below) c.rotate(180f, cx, cy)
        hand.draw(c)
        c.restore()
    }
}
