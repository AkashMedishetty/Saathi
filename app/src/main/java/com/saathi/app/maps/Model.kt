package com.saathi.app.maps

import com.saathi.app.guide.Say

/** Screen pixels. [r] and [b] are exclusive. Pure, so the engine runs in JVM tests. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    val area get() = w.toLong().coerceAtLeast(0) * h.toLong().coerceAtLeast(0)
    fun intersect(o: Box) = Box(maxOf(l, o.l), maxOf(t, o.t), minOf(r, o.r), minOf(b, o.b))
    val empty get() = w <= 0 || h <= 0
}

/**
 * One accessibility node, in pre-order (a parent comes right before its children; [depth] says how deep).
 * [label] = contentDescription, else text, as the handoff specifies.
 */
data class Node(
    val resId: String?,
    val text: String?,
    val desc: String?,
    val cls: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val box: Box,
    val depth: Int,
    /** An editable field (EditText / isEditable). */
    val editable: Boolean = cls.endsWith("EditText"),
    val checked: Boolean = false,
    val selected: Boolean = false,
) {
    val label: String? get() = desc?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() }
    /** "com.google.android.youtube:id/search_edit_text" → "search_edit_text". */
    val id: String? get() = resId?.substringAfter(":id/")?.substringAfter('/')
}

enum class Pick {
    /** The largest visible match (a button, a tab). */
    LARGEST,
    /** The top-most visible match (the first search result). */
    TOP,
}

/**
 * Selects nodes. Every given condition must hold. [label] is matched against the node's own label, or (when the node
 * has none, e.g. a list row) against the text of its descendants. [slot] requires the runtime value of that slot
 * (e.g. "query") in the label; [slotExact] makes that the whole label (a search suggestion "hanuman chalisa").
 */
data class Sel(
    val resId: String? = null,
    val label: Regex? = null,
    val clickable: Boolean? = null,
    /** Reject nodes whose label (or row text) matches this: "Sponsored", a search-history chip. */
    val not: Regex? = null,
    val editable: Boolean? = null,
    val cls: Regex? = null,
    val slot: String? = null,
    val slotExact: Boolean = false,
    val pick: Pick = Pick.LARGEST,
    /** Only nodes whose top is at least this far down the screen (fraction 0..1), e.g. "a row, not the search bar". */
    val below: Float? = null,
)

/** How to recognise a screen: all `must` selectors present, none of `mustNot`. [wait] marks a progress screen. */
data class ScreenDef(val id: String, val must: List<Sel>, val mustNot: List<Sel> = emptyList(), val wait: Say? = null)

data class MapStep(
    /** ScreenDef id where this step applies. */
    val on: String,
    /** What to glow (first selector that matches wins). */
    val target: List<Sel>,
    /** EN/HI/TE instruction. "{query}"-style slot names are filled in at decision time. */
    val say: Say,
    /** Teach mode: one line on why / what it is. */
    val why: Say? = null,
    /** Slot to type (inputs only). */
    val fill: String? = null,
    /** Send / Call / Pay / Install / Request: glow only, Saathi never taps it. */
    val risky: Boolean = false,
    /** Said when on this screen but the target is not visible. */
    val scrollHint: Say? = null,
    /** Another app this step lives in (a hop: Play Store → the new app, WhatsApp → Maps). Default: the route's. */
    val pkg: String? = null,
    /** Other screens where the same step applies (the Subscriptions tab is on Home, Results and You). */
    val alsoOn: List<String> = emptyList(),
) {
    fun appliesOn(screen: String) = on == screen || screen in alsoOn
}

data class Route(
    val id: String,
    val pkg: String,
    val goals: List<Regex>,
    val slots: List<String>,
    val steps: List<MapStep>,
    /** All must be present (on the route's last app) for the goal to be reached. */
    val done: List<Sel>,
    val doneSay: Say,
    /** Natural follow-ups to offer at the end ("Want me to show you how to use it?"). */
    val next: List<Say> = emptyList(),
    /** Goals that must NOT pick this route even if a goal regex matches ("video call" ≠ "watch a video"). */
    val avoid: List<Regex> = emptyList(),
    /** The screen where this route begins, and how to get there when the person is elsewhere in the app. */
    val start: Say? = null,
)

data class AppMap(
    val pkg: String,
    val name: String,
    val screens: List<ScreenDef>,
    val routes: List<Route>,
    /** Other package names of the same app (vivo vs Google builds). */
    val alsoPkgs: List<String> = emptyList(),
    /** Spoken on a mapped screen of this app that no step of the route uses. */
    val backHint: Say? = null,
)

sealed interface Decision {
    /**
     * Glow [box] (the visible part of [node]) and say [say]. [fill] = the text to type (the slot's value), for inputs.
     * [risky] = glow only: Saathi never taps it.
     */
    data class Glow(val step: Int, val node: Node, val say: Say, val why: Say?, val fill: String?, val risky: Boolean,
                    val box: Box = node.box) : Decision
    /** On the right screen, target off-screen. */
    data class Scroll(val step: Int, val hint: Say) : Decision
    /** A mapped screen of this app that the route doesn't use (an old sub-page): go back. [node] = the back arrow, if seen. */
    data class WrongScreen(val expect: String, val backHint: Say, val node: Node? = null) : Decision
    /** A progress screen: installing, loading, "Please wait". Just wait and say this. */
    data class Wait(val say: Say) : Decision
    data object Done : Decision
    /** Not a mapped screen → fall back to the planner. */
    data object Unknown : Decision
}
