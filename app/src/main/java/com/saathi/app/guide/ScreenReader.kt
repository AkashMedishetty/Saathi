package com.saathi.app.guide

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class UiElement(
    val id: Int,
    val label: String,
    val role: String, // button | input | switch | slider | text
    val bounds: Rect,
    val enabled: Boolean,
    val password: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
    val node: AccessibilityNodeInfo?,
) {
    /** A row's title: "Display & touch · Dark theme, font size" → "Display & touch". */
    val title get() = label.substringBefore(" · ").trim()
}

data class Screen(val pkg: String, val elements: List<UiElement>, val allText: String) {
    val signature: Int = elements.joinToString("\n") { "${it.label}|${it.bounds.flattenToString()}|${it.enabled}|${it.checked}" }.hashCode()

    /** Numbered list for the LLM: `[3] button "Send Money"`. */
    fun forPrompt(max: Int = 35): String = elements.filter { it.role != "text" || it.label.length > 2 }
        .take(max)
        .joinToString("\n") { e ->
            val state = buildString {
                if (!e.enabled) append(" (disabled)")
                if (e.role == "switch") append(if (e.checked) " (on)" else " (off)")
            }
            "[${e.id}] ${e.role} \"${e.label.take(40)}\"$state"
        }

    /**
     * Title first (trap #12: subtitle keywords matched the wrong row). Subtitles only count for
     * role-specific steps, where the role already narrows it down.
     */
    fun find(targets: List<Regex>, role: String? = null): UiElement? {
        val cands = elements.filter { it.role != "text" && it.enabled && (role == null || it.role == role) }
        return cands.firstOrNull { e -> targets.any { it.containsMatchIn(e.title) } }
            ?: if (role != null) cands.firstOrNull { e -> targets.any { it.containsMatchIn(e.label) } } else null
    }

    fun byId(id: Int) = elements.firstOrNull { it.id == id }
    fun scrollable() = elements.firstOrNull { it.scrollable }
}

/** Accessibility tree → a flat, labelled list of what a person can see and touch. */
object ScreenReader {
    private const val MAX = 90

    fun read(root: AccessibilityNodeInfo?): Screen? {
        root ?: return null
        val out = mutableListOf<UiElement>()
        val all = StringBuilder()
        var scrollNode: AccessibilityNodeInfo? = null

        fun own(n: AccessibilityNodeInfo): String {
            // Password text is dots: use the hint so steps still match (trap #22).
            if (n.isPassword) return (n.hintText ?: "PIN / password").toString()
            return sequenceOf(n.text, n.contentDescription, n.hintText)
                .mapNotNull { it?.toString()?.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        }

        /** An unlabeled clickable row takes its children's text: "Display · Dark theme, font size". */
        fun subtree(n: AccessibilityNodeInfo, depth: Int = 0): String {
            if (depth > 3) return ""
            val parts = mutableListOf<String>()
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val t = own(c).ifEmpty { subtree(c, depth + 1) }
                if (t.isNotEmpty()) parts += t
                if (parts.sumOf { it.length } > 60) break
            }
            return parts.joinToString(" · ").take(80)
        }

        fun add(n: AccessibilityNodeInfo, label: String, role: String) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.width() <= 4 || r.height() <= 4) return
            out += UiElement(out.size + 1, label, role, r, n.isEnabled, n.isPassword, n.isChecked, n.isScrollable, n)
        }

        fun visit(n: AccessibilityNodeInfo?, depth: Int, insideInteractive: Boolean, parentLabel: String = "") {
            if (n == null || depth > 45 || out.size >= MAX || !n.isVisibleToUser) return
            val cls = n.className?.toString() ?: ""
            val text = own(n)
            if (text.isNotEmpty()) {
                all.append(text)
                if (n.isCheckable) all.append(if (n.isChecked) " [on]" else " [off]")
                all.append('\n')
            }
            if (n.isScrollable && scrollNode == null) scrollNode = n

            val role = when {
                n.isEditable -> "input"
                // OEM sliders (OriginOS) have no class name; they only expose a range + SET_PROGRESS.
                "SeekBar" in cls || "Slider" in cls || n.rangeInfo != null ||
                    n.actionList.any { it.id == android.R.id.accessibilityActionSetProgress } -> "slider"
                n.isCheckable || "Switch" in cls -> "switch"
                n.isClickable -> "button"
                else -> "text"
            }
            var inside = insideInteractive
            if (role != "text") {
                var label = text.ifEmpty { subtree(n) }
                if (label.isEmpty()) label = n.viewIdResourceName?.substringAfter('/')?.replace('_', ' ') ?: ""
                if (label.isEmpty() && role == "slider") label = "slider"
                // Nested clickables with the same label (button > layout > text) are one thing to a person.
                if (label.isNotEmpty() && label != parentLabel) add(n, label, role)
                inside = true
                for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1, true, label)
                return
            } else if (!insideInteractive && text.isNotEmpty() && n.childCount == 0) {
                // Text inside a button is already part of its label: don't list it twice.
                add(n, text.take(80), "text")
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1, inside, parentLabel)
        }

        visit(root, 0, false)
        scrollNode?.let { s ->
            val existing = out.indexOfFirst { it.node == s }
            if (existing >= 0) out[existing] = out[existing].copy(scrollable = true)
            else {
                val r = Rect().also { s.getBoundsInScreen(it) }
                out += UiElement(out.size + 1, "scrollable list", "text", r, true, false, false, true, s)
            }
        }
        return Screen(root.packageName?.toString() ?: "", out, all.toString())
    }
}
