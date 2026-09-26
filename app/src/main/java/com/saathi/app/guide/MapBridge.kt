package com.saathi.app.guide

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.saathi.app.maps.Box
import com.saathi.app.maps.Node

/**
 * The live accessibility tree as the app maps see it (maps.Node, pure Kotlin), plus the real nodes so a glowed target
 * can be tapped. Same walk as the fixtures (SaathiService.dumpTree), so fixture tests and the phone agree.
 */
object MapBridge {
    class Live(val nodes: List<Node>, val infos: List<AccessibilityNodeInfo>) {
        fun infoFor(n: Node): AccessibilityNodeInfo? = nodes.indexOfFirst { it === n }.takeIf { it >= 0 }?.let { infos[it] }
    }

    fun read(root: AccessibilityNodeInfo?): Live {
        val nodes = ArrayList<Node>(256); val infos = ArrayList<AccessibilityNodeInfo>(256)
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60 || nodes.size > 1500) return
            if (!n.isVisibleToUser) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            nodes += Node(n.viewIdResourceName, n.text?.toString(), n.contentDescription?.toString(), n.className?.toString()?.substringAfterLast('.') ?: "",
                n.isClickable, n.isScrollable, n.isCheckable, Box(r.left, r.top, r.right, r.bottom), depth,
                editable = n.isEditable, checked = n.isChecked, selected = n.isSelected)
            infos += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return Live(nodes, infos)
    }

    /** Slot values for a route from what they said ("search for X", "call Y", "take me to Z"). */
    fun slots(goal: String, s: Slots): Map<String, String> = buildMap {
        (s.query ?: SlotExtractor.searchPhrase(goal).takeIf { it.isNotBlank() })?.let { put("query", it) }
        s.contact?.let { put("contact", it); put("person", it) }
        s.text?.let { put("text", it) }
        s.place?.let { put("place", it); put("destination", it) }
    }

    fun uiElement(n: Node, info: AccessibilityNodeInfo?, role: String): UiElement =
        UiElement(-1, n.label ?: n.id ?: "", role, Rect(n.box.l, n.box.t, n.box.r, n.box.b), true, false, n.checked, n.scrollable, info)
}
