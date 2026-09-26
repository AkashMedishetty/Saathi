package com.saathi.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The marigold spotlight: dims the screen, cuts a hole around the target and pulses a ring.
 * Drawn with layered strokes (no BlurMaskFilter, which forces a slow software layer).
 */
class GlowView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val marigold = Color.parseColor("#E8A33D")
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 20, 26, 24) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = marigold }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = marigold }
    private val hole = Path()
    private val box = RectF()
    private var target: Rect? = null
    private var pulse = 0f

    private val pulser = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }

    init {
        // Our own overlay must not feed events back into the screen reader.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun show(r: Rect?) {
        target = r
        if (r == null) pulser.cancel() else if (!pulser.isStarted && isAttachedToWindow) pulser.start()
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (target != null) pulser.start() }
    override fun onDetachedFromWindow() { pulser.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val r = target ?: return
        val pad = 10 * density
        val radius = 18 * density
        box.set(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)

        hole.reset()
        hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        hole.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.drawPath(hole, scrim)

        // Soft glow: a few widening, fading strokes.
        for (i in 3 downTo 1) {
            halo.strokeWidth = (4 + i * 5) * density
            halo.alpha = (40 / i)
            canvas.drawRoundRect(box, radius, radius, halo)
        }
        ring.strokeWidth = 3.5f * density
        ring.alpha = 255
        canvas.drawRoundRect(box, radius, radius, ring)

        // Outward pulse.
        val grow = pulse * 14 * density
        ring.strokeWidth = 2 * density
        ring.alpha = ((1 - pulse) * 200).toInt()
        canvas.drawRoundRect(box.left - grow, box.top - grow, box.right + grow, box.bottom + grow,
            radius + grow, radius + grow, ring)
    }
}
