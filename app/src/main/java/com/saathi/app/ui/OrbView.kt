package com.saathi.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Saathi's face: a small living light. Calm breathing when idle, a quicker shimmer while
 * thinking or listening, and it swells with the voice level. Used in the card, the bubble and Home.
 */
class OrbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class Mood { IDLE, ACTIVE, WARN, DONE }

    var mood = Mood.IDLE
        set(v) { field = v; invalidate() }
    private var level = 0f
    private var t = 0f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var built = -1f
    private var builtMood: Mood? = null

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        var last = 0L
        addUpdateListener {
            val now = it.currentPlayTime
            val dt = if (now >= last) now - last else now
            last = now
            t += dt.coerceAtMost(100) / 1000f
            invalidate()
        }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setLevel(v: Float) { level = level * 0.55f + v.coerceIn(0f, 1f) * 0.45f }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); loop.start() }
    override fun onDetachedFromWindow() { loop.cancel(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow) { if (!loop.isStarted) loop.start() } else loop.cancel()
    }

    private fun palette(): IntArray = when (mood) {
        Mood.WARN -> intArrayOf(0xFFFFB199.toInt(), C.VERMILION, 0xFF7A2415.toInt())
        Mood.DONE -> intArrayOf(0xFFB8F0CF.toInt(), C.LEAF, 0xFF174A31.toInt())
        else -> intArrayOf(C.MARIGOLD_HI, C.MARIGOLD, C.SAFFRON)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val max = min(width, height) / 2f
        val active = mood == Mood.ACTIVE
        val breath = if (active) 0.5f + 0.5f * sin(t * 7f) else 0.5f + 0.5f * sin(t * 2.2f)
        val r = max * (0.62f + 0.05f * breath + 0.16f * level)

        // Shaders are built once per size/mood at a reference radius; breathing is a canvas scale (no per-frame allocs).
        val r0 = max * 0.7f
        if (built != max || builtMood != mood) {
            val p = palette()
            body.shader = RadialGradient(cx - r0 * 0.3f, cy - r0 * 0.35f, r0 * 1.5f, p, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            halo.shader = RadialGradient(cx, cy, max, intArrayOf(p[0] and 0x88FFFFFF.toInt(), p[1] and 0x44FFFFFF, p[1] and 0x00FFFFFF), floatArrayOf(0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
            sheen.shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF, 0x33FFF1D6, 0x00FFFFFF), null)
            built = max; builtMood = mood
        }
        halo.alpha = ((if (active) 200 else 110) * (0.6f + 0.4f * breath)).toInt()
        c.drawCircle(cx, cy, max, halo)
        c.save()
        c.scale(r / r0, r / r0, cx, cy)
        c.drawCircle(cx, cy, r0, body)
        m.setRotate((t * if (active) 160f else 40f) % 360f, cx, cy)
        sheen.shader.setLocalMatrix(m)
        c.drawCircle(cx, cy, r0, sheen)
        c.restore()
    }
}
