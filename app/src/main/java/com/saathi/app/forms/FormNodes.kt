package com.saathi.app.forms

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.text.Text

/**
 * Android → the pure form model. Thin on purpose: all decisions live in [PaperForm], [OnlineForm] and [LabelFinder],
 * which are unit-tested. This file is NOT unit-tested (Android types are stubs on the JVM).
 */
object FormNodes {

    private fun Rect.box() = Box(left, top, right, bottom)

    /** ML Kit result → OCR lines with their words, in photo pixels. Lines without a bounding box are dropped. */
    fun ocrLines(t: Text): List<OcrLine> = t.textBlocks.flatMap { it.lines }.mapNotNull { ln ->
        val b = ln.boundingBox ?: return@mapNotNull null
        OcrLine(ln.text, b.box(), ln.elements.mapNotNull { e -> e.boundingBox?.let { OcrWord(e.text, it.box()) } })
    }

    /**
     * The editable fields on screen, each with the node to act on. Match a [FillPlan] back to its node by identity:
     * `fields.first { it.first === plan.node }.second`.
     */
    fun fields(root: AccessibilityNodeInfo?): List<Pair<FieldNode, AccessibilityNodeInfo>> {
        root ?: return emptyList()
        val inputs = mutableListOf<AccessibilityNodeInfo>()
        val texts = mutableListOf<Pair<String, Box>>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || !n.isVisibleToUser) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (n.isEditable) inputs += n
            else (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it to r.box() }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val boxes = inputs.map { n -> Rect().also { n.getBoundsInScreen(it) }.box() }
        return inputs.mapIndexed { i, n ->
            val hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null
            val label = n.labeledBy?.text?.toString()?.takeIf { it.isNotBlank() }
                ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it != hint }
                ?: LabelFinder.find(boxes[i], texts, boxes)
            FieldNode(label = label, hint = hint, resId = n.viewIdResourceName?.substringAfter('/'), box = boxes[i],
                password = n.isPassword, inputType = n.inputType) to n
        }
    }
}
