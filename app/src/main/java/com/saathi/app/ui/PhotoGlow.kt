package com.saathi.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * The frozen photo with Saathi's glow drawn on the real-world button to press ("tap here" on the microwave),
 * or around the amount/due date on a bill. Boxes are in photo pixels; we map them to the view (fit-centre).
 */
class PhotoGlow(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var boxes: List<Rect> = emptyList()
    private var t = 0f
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint().apply { color = 0x66000000 }
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t += 0.016f; invalidate() }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun show(b: Bitmap, targets: List<Rect>) { bmp = b; boxes = targets; visibility = VISIBLE; if (!anim.isStarted) anim.start(); invalidate() }
    fun clear() { bmp = null; boxes = emptyList(); anim.cancel(); visibility = GONE }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat())
        val ox = (width - b.width * s) / 2f; val oy = (height - b.height * s) / 2f
        c.drawColor(0xFF000000.toInt())
        c.save(); c.translate(ox, oy); c.scale(s, s); c.drawBitmap(b, 0f, 0f, img); c.restore()
        if (boxes.isEmpty()) return
        val pad = context.dpf(10)
        val breath = 0.5f + 0.5f * sin(t * 3.5f)
        for (r in boxes) {
            val rf = RectF(ox + r.left * s - pad, oy + r.top * s - pad, ox + r.right * s + pad, oy + r.bottom * s + pad)
            val rad = context.dpf(16)
            for (k in 4 downTo 1) {
                ring.color = C.MARIGOLD; ring.strokeWidth = pad * k * (0.7f + 0.3f * breath); ring.alpha = 40 / k + 10
                c.drawRoundRect(RectF(rf).apply { inset(-ring.strokeWidth / 2, -ring.strokeWidth / 2) }, rad, rad, ring)
            }
            ring.strokeWidth = pad * 0.45f; ring.alpha = 255; ring.color = C.MARIGOLD_HI
            c.drawRoundRect(rf, rad, rad, ring)
        }
    }
}
