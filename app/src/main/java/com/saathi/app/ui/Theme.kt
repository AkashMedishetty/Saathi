package com.saathi.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs

/**
 * Design tokens. Warm, calm, high-contrast: a paper-white surface, deep pine ink,
 * and one signature light: the marigold glow.
 */
object C {
    const val PAPER = 0xFFFBF8F2.toInt()      // card / page surface
    const val PAPER_2 = 0xFFF1EBE0.toInt()    // quiet fills (secondary buttons)
    const val PAPER_3 = 0xFFE4DACB.toInt()    // inactive dots, dividers
    const val INK = 0xFF15201E.toInt()
    const val MUTED = 0xFF5B6561.toInt()
    const val LINE = 0x1A15201E
    const val PINE = 0xFF1F4D45.toInt()
    const val PINE_DEEP = 0xFF0F2B27.toInt()
    const val MARIGOLD = 0xFFE8A33D.toInt()
    const val MARIGOLD_HI = 0xFFFFC66E.toInt()
    const val SAFFRON = 0xFFF07A3A.toInt()    // warm end of the glow sweep
    const val ROSE = 0xFFE9667A.toInt()       // a touch of pink so the glow feels alive, not flat
    const val VERMILION = 0xFFB3412A.toInt()
    const val LEAF = 0xFF2E7550.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SCRIM = 0xFF0B1A17.toInt()
}

/** Material "emphasized" easing: fast out, soft landing. */
val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

/** Tiro (serif with real Devanagari / Telugu forms) for display, Hind for body. Picked per language. */
object Type {
    private val cache = HashMap<Int, Typeface>()
    private fun f(ctx: Context, id: Int) = cache.getOrPut(id) { ResourcesCompat.getFont(ctx, id) ?: Typeface.DEFAULT }
    private fun te(ctx: Context) = Prefs.lang(ctx) == Lang.TE

    fun display(ctx: Context): Typeface = f(ctx, if (te(ctx)) R.font.tiro_telugu else R.font.tiro_deva)
    fun body(ctx: Context): Typeface = f(ctx, if (te(ctx)) R.font.hind_guntur else R.font.hind)
    fun bold(ctx: Context): Typeface = f(ctx, if (te(ctx)) R.font.hind_guntur_semibold else R.font.hind_semibold)
}
