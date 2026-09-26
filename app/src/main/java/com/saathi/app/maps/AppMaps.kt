package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say
import com.saathi.app.guide.say
import com.saathi.app.maps.apps.DocsMap
import com.saathi.app.maps.apps.PhotosMap
import com.saathi.app.maps.apps.PlayStoreMap
import com.saathi.app.maps.apps.SettingsMap
import com.saathi.app.maps.apps.SpotifyMap
import com.saathi.app.maps.apps.YouTubeMap

/**
 * Deterministic navigation for the top apps. Pure: the caller converts the live accessibility tree to [Node]s.
 *
 * Per screen change: [next] recognises the screen, picks the LATEST step of the route that lives on it (people skip
 * ahead or already stand halfway) without going back behind the furthest step reached, and finds the step's target:
 * visible, on screen, not covered by a bar drawn on top of it, largest (or top-most for result lists).
 */
object AppMaps {
    /** All maps, in priority order for goal matching ties. */
    val all: List<AppMap> by lazy { listOf(YouTubeMap.map, SettingsMap.map, PhotosMap.map, PlayStoreMap.map, SpotifyMap.map, DocsMap.map) }

    private val byPkg by lazy { all.flatMap { m -> (listOf(m.pkg) + m.alsoPkgs).map { it to m } }.toMap() }

    fun mapFor(pkg: String): AppMap? = byPkg[pkg]

    // ───────────────────────── goals → route ─────────────────────────

    /** EN/HI/TE goal → best route: the longest goal-regex match wins; [Route.avoid] vetoes. No LLM. */
    fun route(goal: String): Route? {
        val g = goal.trim().lowercase()
        if (g.isEmpty()) return null
        var best: Route? = null; var bestLen = 0
        for (m in all) for (r in m.routes) {
            if (r.avoid.any { it.containsMatchIn(g) }) continue
            val len = r.goals.mapNotNull { it.find(g)?.value?.length }.maxOrNull() ?: continue
            if (len > bestLen) { best = r; bestLen = len }
        }
        return best
    }

    fun routeById(id: String): Route? = all.flatMap { it.routes }.firstOrNull { it.id == id }

    fun mapOf(r: Route): AppMap? = all.firstOrNull { m -> m.routes.any { it === r || it.id == r.id } }

    // ───────────────────────── screens ─────────────────────────

    /** Which of the app's screens this is: all `must` present, no `mustNot`; the most specific (most `must`) wins. */
    fun screenOf(pkg: String, nodes: List<Node>, slots: Map<String, String> = emptyMap()): String? {
        val m = mapFor(pkg) ?: return null
        val t = Tree(nodes)
        return m.screens.filter { s -> s.must.all { t.present(it, slots) } && s.mustNot.none { t.present(it, slots) } }
            .maxByOrNull { it.must.size }?.id
    }

    // ───────────────────────── the decision ─────────────────────────

    /**
     * [doneSteps] = the furthest step index already reached (0 at the start; pass the max `step` of earlier Glow /
     * Scroll decisions). [slots] = runtime values ("query" → "hanuman chalisa") used in selectors, speech and `fill`.
     */
    fun next(r: Route, pkg: String, nodes: List<Node>, doneSteps: Int, slots: Map<String, String> = emptyMap()): Decision {
        val app = mapFor(pkg)
        val routeApp = mapOf(r)
        val pkgsOf = { step: MapStep -> step.pkg ?: r.pkg }
        val samePkg = { a: String, b: String -> a == b || (mapFor(a) != null && mapFor(a) === mapFor(b)) }
        val routePkgs = (listOf(r.pkg) + r.steps.map(pkgsOf)).distinct()
        if (routePkgs.none { samePkg(it, pkg) } || app == null) return Decision.Unknown
        val t = Tree(nodes)

        val lastPkg = r.steps.lastOrNull()?.let(pkgsOf) ?: r.pkg
        if (r.done.isNotEmpty() && samePkg(lastPkg, pkg) && (!r.doneNeedsLastStep || doneSteps >= r.steps.lastIndex) &&
            r.done.all { t.present(it, slots) } && r.doneNot.none { t.present(it, slots) }) return Decision.Done

        val screenId = screenOf(pkg, nodes, slots) ?: return Decision.Unknown
        app.screens.first { it.id == screenId }.wait?.let { return Decision.Wait(it) }

        val here = r.steps.indices.filter { i ->
            val st = r.steps[i]
            st.appliesOn(screenId) && samePkg(pkgsOf(st), pkg) && (st.needsReached == null || doneSteps >= st.needsReached)
        }
        if (here.isEmpty()) {
            val expect = r.steps.getOrNull(doneSteps.coerceIn(0, r.steps.lastIndex))?.on ?: r.steps.first().on
            val back = t.find(BACK, slots)
            return Decision.WrongScreen(expect, (app.backHint ?: routeApp?.backHint ?: BACK_SAY), back?.node)
        }

        val forward = here.filter { it >= doneSteps }
        for (i in forward.sortedDescending()) glow(r, i, t, slots)?.let { return it }
        if (forward.isNotEmpty()) {
            val i = forward.min()
            return Decision.Scroll(i, fillIn(r.steps[i].scrollHint ?: SCROLL_SAY, slots))
        }
        // Only earlier steps live on this screen: the person went back. Guide them from here.
        for (i in here.sortedDescending()) glow(r, i, t, slots)?.let { return it }
        val i = here.max()
        return Decision.Scroll(i, fillIn(r.steps[i].scrollHint ?: SCROLL_SAY, slots))
    }

    private fun glow(r: Route, i: Int, t: Tree, slots: Map<String, String>): Decision.Glow? {
        val st = r.steps[i]
        val hit = t.find(st.target, slots) ?: return null
        val risky = st.risky || RISKY.matches(hit.node.label?.trim()?.lowercase() ?: "")
        return Decision.Glow(i, hit.node, fillIn(st.say, slots), st.why?.let { fillIn(it, slots) },
            st.fill?.let { slots[it] }, risky, hit.box)
    }

    /** The node a selector list picks on this screen (first selector with a visible match), for tests and callers. */
    fun find(sels: List<Sel>, nodes: List<Node>, slots: Map<String, String> = emptyMap()): Node? = Tree(nodes).find(sels, slots)?.node

    /**
     * "Type “{query}”." → "Type “hanuman chalisa”." A "[ … ]" segment is kept only when every slot inside it is known
     * ("Tap “{app}”[ by {developer}]."); a missing slot elsewhere is dropped with its quotes.
     */
    fun fillIn(s: Say, slots: Map<String, String>): Say = s.mapValues { (_, v0) ->
        val v = OPTIONAL.replace(v0) { m ->
            val inner = m.groupValues[1]
            if (SLOT.findAll(inner).all { slots[it.groupValues[1]] != null }) inner else ""
        }
        SLOT.replace(v) { m -> slots[m.groupValues[1]] ?: "" }.replace("“”", "").replace("\"\"", "").replace(Regex(" {2,}"), " ").trim()
    }

    private val OPTIONAL = Regex("\\[([^\\[\\]]*)\\]")

    private val SLOT = Regex("\\{(\\w+)\\}")

    /** Labels Saathi never taps even when a map forgets to mark them risky. */
    private val RISKY = Regex("(install|uninstall|update|update all|send|pay|pay now|buy|purchase|call|video call|voice call|" +
        "request.*|confirm.*|book .*|delete|post|share)")

    private val BACK = listOf(
        Sel(label = Regex("^(Navigate up|Back|Go back|Up|वापस|పైకి నావిగేట్ చేయి|వెనుకకు)$", RegexOption.IGNORE_CASE), clickable = true),
    )

    val BACK_SAY: Say = say("This is a different page. Tap the back arrow at the top left.",
        "यह दूसरा पेज है। ऊपर बाईं ओर पीछे वाला तीर दबाइए।",
        "ఇది వేరే పేజీ. పైన ఎడమవైపు వెనక్కి బాణం నొక్కండి.")

    val SCROLL_SAY: Say = say("Slowly scroll down to find it.", "धीरे से नीचे स्क्रॉल करके ढूँढिए।", "నెమ్మదిగా కిందకు స్క్రోల్ చేసి వెతకండి.")

    internal val LANGS = Lang.entries
}

/** One pass over a screen: parents, row text, and what is really visible. */
internal class Tree(val nodes: List<Node>) {
    data class Hit(val node: Node, val box: Box)

    private val parent = IntArray(nodes.size) { -1 }
    private val end = IntArray(nodes.size)
    val screen: Box

    init {
        val stack = ArrayDeque<Int>()
        for (i in nodes.indices) {
            while (stack.isNotEmpty() && nodes[stack.last()].depth >= nodes[i].depth) end[stack.removeLast()] = i
            parent[i] = stack.lastOrNull() ?: -1
            stack.addLast(i)
        }
        while (stack.isNotEmpty()) end[stack.removeLast()] = nodes.size
        // The screen: the root window's box, or everything that starts on screen.
        val onScreen = nodes.filter { it.box.l >= 0 && it.box.t >= 0 && !it.box.empty }
        screen = nodes.firstOrNull()?.box?.takeIf { !it.empty && it.l >= 0 && it.t >= 0 }
            ?: Box(0, 0, onScreen.maxOfOrNull { it.box.r } ?: 0, onScreen.maxOfOrNull { it.box.b } ?: 0)
    }

    /** The texts inside a node that has no label of its own: a list row → "hanuman chalisa". */
    fun rowText(i: Int): String {
        val parts = mutableListOf<String>()
        for (j in i + 1 until end[i]) {
            if (nodes[j].depth > nodes[i].depth + 5) continue
            nodes[j].label?.trim()?.takeIf { it.isNotEmpty() && it !in parts }?.let { parts += it }
            if (parts.size >= 6) break
        }
        return parts.joinToString(" · ")
    }

    private fun matches(i: Int, s: Sel, slots: Map<String, String>): Boolean {
        val n = nodes[i]
        if (s.resId != null && n.id != s.resId && n.resId != s.resId) return false
        if (s.editable != null && n.editable != s.editable) return false
        if (s.cls != null && !s.cls.containsMatchIn(n.cls)) return false
        if (s.below != null && screen.h > 0 && n.box.t < screen.t + screen.h * s.below) return false
        if (s.above != null && screen.h > 0 && n.box.t > screen.t + screen.h * s.above) return false
        val own = n.label?.trim()
        // A row's children's text stands in for its label only for a clickable row, never for a layout container.
        val text = own ?: if (n.clickable) rowText(i) else ""
        if (s.label != null && !s.label.containsMatchIn(text)) return false
        if (s.not != null && (s.not.containsMatchIn(text) || (n.clickable && s.not.containsMatchIn(rowText(i))))) return false
        if (s.slot != null) {
            val v = slots[s.slot]?.let(::norm)?.takeIf { it.isNotEmpty() } ?: return false
            val x = norm(if (n.editable) n.text ?: "" else text)
            when {
                s.slotExact -> if (x != v) return false
                s.slotLonger -> if (!x.contains(v) || x.length < v.length + 2) return false
                else -> if (!x.contains(v)) return false
            }
        }
        return true
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** A non-clickable match (a TextView in a row) glows as its clickable row. */
    private fun target(i: Int, s: Sel): Int? {
        val n = nodes[i]
        if (n.clickable || n.editable) return i
        var p = parent[i]; var k = 0
        while (p >= 0 && k < 4) { if (nodes[p].clickable) return p; p = parent[p]; k++ }
        return if (s.clickable == true) null else i
    }

    /** Is it there at all (for screen recognition)? Size > 0 and at least partly on screen. */
    fun present(s: Sel, slots: Map<String, String> = emptyMap()) = nodes.indices.any { i ->
        !nodes[i].box.empty && !nodes[i].box.intersect(screen).empty && matches(i, s, slots)
    }

    fun find(sels: List<Sel>, slots: Map<String, String>): Hit? {
        for (s in sels) {
            val hits = nodes.indices.filter { matches(it, s, slots) }.mapNotNull { target(it, s) }.distinct()
                .mapNotNull { i -> visible(i)?.let { i to it } }
            if (hits.isEmpty()) continue
            val (i, box) = when (s.pick) {
                Pick.LARGEST -> hits.maxBy { it.second.area }
                Pick.TOP -> hits.minWith(compareBy<Pair<Int, Box>> { it.second.t }.thenBy { it.second.l })
            }
            return Hit(nodes[i], box)
        }
        return null
    }

    /**
     * The part of node [i] a person can actually see and touch: clipped to the screen, minus clickable things drawn
     * over it (later in the tree, not its own children, e.g. YouTube's bottom tab bar over a result row). Null when
     * too little is left.
     */
    fun visible(i: Int): Box? {
        val n = nodes[i]
        if (n.box.empty || n.box.l < -2 || n.box.t < -2) return null
        val c = n.box.intersect(screen)
        if (c.w < MIN_SIDE || c.h < MIN_SIDE) return null
        val covers = (end[i] until nodes.size).map { nodes[it] }
            .filter { it.clickable && !it.box.empty }
            .map { it.box.intersect(c) }.filter { !it.empty }
        if (covers.isEmpty()) return c
        val g = GRID
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE; var seen = 0
        for (yi in 0 until g) for (xi in 0 until g) {
            val x = c.l + ((xi + 0.5) * c.w / g).toInt(); val y = c.t + ((yi + 0.5) * c.h / g).toInt()
            if (covers.any { x >= it.l && x < it.r && y >= it.t && y < it.b }) continue
            seen++; l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
        }
        if (seen < g * g / 4) return null
        // Grow the sampled points back to cell edges.
        val cw = c.w / g; val ch = c.h / g
        val v = Box((l - cw / 2).coerceAtLeast(c.l), (t - ch / 2).coerceAtLeast(c.t), (r + cw / 2 + 1).coerceAtMost(c.r), (b + ch / 2 + 1).coerceAtMost(c.b))
        return if (v.w < MIN_SIDE || v.h < MIN_SIDE) null else v
    }

    companion object {
        /** Anything smaller than this (px) is not a real target (a 48 dp button is ~170 px on the iQOO 15). */
        const val MIN_SIDE = 64
        const val GRID = 16
    }
}
