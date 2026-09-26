package com.saathi.app.forms

import com.saathi.app.guide.Say

/**
 * Pure geometry, so the logic runs in JVM tests (android.graphics.Rect is an all-zero stub there).
 * Coordinates are photo pixels (paper forms) or screen pixels (online forms). [r] and [b] are exclusive, like Rect.
 */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2

    /** Vertical overlap as a fraction of the shorter box: 1.0 = same row. */
    fun rowOverlap(o: Box): Float {
        val ov = minOf(b, o.b) - maxOf(t, o.t)
        val m = minOf(h, o.h)
        return if (m <= 0) 0f else ov.coerceAtLeast(0) / m.toFloat()
    }

    fun clip(w: Int, h: Int) = Box(l.coerceIn(0, w), t.coerceIn(0, h), r.coerceIn(0, w), b.coerceIn(0, h))
}

/** One word of OCR text (ML Kit `Text.Element`). Optional, but it makes label/blank geometry exact. */
data class OcrWord(val text: String, val box: Box)

/** One OCR line (ML Kit `Text.Line`), in photo pixels. Pass [words] when you have them. */
data class OcrLine(val text: String, val box: Box, val words: List<OcrWord> = emptyList())

/**
 * One editable field from the accessibility tree.
 *  - [label]: the visible label (labeledBy, or the text just above / to the left; see [LabelFinder]).
 *  - [hint]: hintText / placeholder. [resId]: viewIdResourceName without the package.
 *  - [inputType]: AccessibilityNodeInfo.getInputType(), null if unknown.
 */
data class FieldNode(
    val label: String?,
    val hint: String?,
    val resId: String?,
    val box: Box,
    val password: Boolean,
    val inputType: Int?,
)

/** One box on a paper form: where the label is, where to write, and what to say. */
data class PaperField(
    val key: FieldKey?,
    val label: String,
    val labelBox: Box,
    val writeBox: Box,
    /** What to write. Always null when [sensitive]. */
    val value: String?,
    val say: Say,
    /** Secret or personal-ID box (Aadhaar, PAN, account, signature…): Saathi never has a value for it. */
    val sensitive: Boolean,
)

/** One step of an online form: glow [node], say [say], and "Do it" may type [value] (null = the person types). */
data class FillPlan(
    val node: FieldNode,
    val key: FieldKey?,
    /** Text for ACTION_SET_TEXT. Always null when [sensitive]. */
    val value: String?,
    val say: Say,
    /** Never filled: glow only, "type this yourself". */
    val sensitive: Boolean,
)
