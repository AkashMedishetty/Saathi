package com.saathi.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A tiny view kit so every screen shares one system: generous spacing, big calm type,
 * one primary action per screen, springy touch feedback. (No XML layouts: faster to iterate on stage.)
 */

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    cornerRadius = radius; setColor(color); if (stroke != 0) setStroke(strokeW, stroke)
}

fun Context.vbox(padH: Int = 0, padV: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

fun Context.hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun LinearLayout.add(v: View, top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
    // Horizontal rows: weighted children take the rest; others wrap (a MATCH_PARENT sibling would eat it all, trap #20).
    val width = when {
        orientation != LinearLayout.HORIZONTAL -> w
        weight > 0f -> 0
        w == ViewGroup.LayoutParams.MATCH_PARENT -> ViewGroup.LayoutParams.WRAP_CONTENT
        else -> w
    }
    addView(v, LinearLayout.LayoutParams(width, h, weight).apply {
        if (orientation == LinearLayout.VERTICAL) topMargin = context.dp(top) else marginStart = context.dp(top)
    })
    return v
}

/** Serif display text (Tiro): greetings, instructions, the transcript. */
fun Context.display(text: String, size: Float, color: Int = C.INK) = TextView(this).apply {
    this.text = text; textSize = size; typeface = Type.display(context); setTextColor(color)
    setLineSpacing(0f, 1.08f); includeFontPadding = false
}

/** Body text (Hind). */
fun Context.body(text: String, size: Float = 17f, color: Int = C.MUTED, bold: Boolean = false) = TextView(this).apply {
    this.text = text; textSize = size; typeface = if (bold) Type.bold(context) else Type.body(context); setTextColor(color)
    // Hind has tall built-in line gaps; drop the font padding and keep lines close.
    includeFontPadding = false
    setLineSpacing(0f, 0.92f)
}

/** Small letter-spaced section label: "TODAY", "THINGS I CAN HELP WITH". */
fun Context.overline(text: String, color: Int = C.MUTED) = TextView(this).apply {
    this.text = text.uppercase(); textSize = 13f; letterSpacing = 0.12f; typeface = Type.bold(context); setTextColor(color)
}

/** Press feedback: shrink a touch, spring back. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(onClick: () -> Unit): View {
    isClickable = true
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2.5f)).setDuration(260).start()
        }
        false
    }
    setOnClickListener { onClick() }
    return this
}

/** The one primary button of a screen. */
fun Context.primaryButton(label: String, icon: Int? = null, bg: Int = C.PINE_DEEP, fg: Int = C.WHITE, onClick: () -> Unit): View =
    hbox().apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(64)
        setPadding(dp(22), 0, dp(22), 0)
        background = rounded(bg, dpf(32))
        icon?.let { addView(ImageView(context).apply { setImageResource(it); imageTintList = ColorStateList.valueOf(fg) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) }) }
        addView(body(label, 19f, fg, bold = true).apply { maxLines = 2; gravity = Gravity.CENTER })
        contentDescription = label
    }.pressable(onClick)

/** A quiet, pill-shaped chip. */
fun Context.chip(label: String, selected: Boolean, onClick: () -> Unit): View = body(label, 17f, if (selected) C.WHITE else C.PINE_DEEP, bold = true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(48); minWidth = dp(56)
    setPadding(dp(16), dp(6), dp(16), dp(6))
    background = rounded(if (selected) C.PINE_DEEP else C.PAPER_2, dpf(24))
}.pressable(onClick)

/** A white surface that groups rows. */
fun Context.surface() = vbox().apply {
    background = rounded(C.WHITE, dpf(26), C.LINE, dp(1))
    clipToOutline = true
}

/**
 * A big list row: icon tile · title · "what to say" · chevron. Min 76dp tall (easy for older fingers).
 */
fun Context.row(icon: Int, title: String, sub: String?, tint: Int = C.PINE, onClick: () -> Unit): View {
    val r = hbox().apply { setPadding(dp(16), dp(14), dp(14), dp(14)); minimumHeight = dp(76) }
    val tile = FrameLayout(this).apply { background = rounded(C.PAPER_2, dpf(16)) }
    tile.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(tint) },
        FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    r.addView(tile, LinearLayout.LayoutParams(dp(50), dp(50)))
    val texts = vbox()
    texts.addView(body(title, 19f, C.INK, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 0.95f); includeFontPadding = false })
    sub?.let { texts.addView(body(it, 15f, C.MUTED).apply { maxLines = 2; setLineSpacing(0f, 0.95f); includeFontPadding = false; setPadding(0, dp(4), 0, 0) }) }
    r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
    r.addView(ImageView(this).apply { setImageResource(com.saathi.app.R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(C.PAPER_3) },
        LinearLayout.LayoutParams(dp(24), dp(24)))
    r.contentDescription = listOfNotNull(title, sub).joinToString(". ")
    return r.pressable(onClick)
}

fun Context.divider(inset: Int = 80) = View(this).apply { setBackgroundColor(C.LINE) }.also {
    it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(inset) }
}
