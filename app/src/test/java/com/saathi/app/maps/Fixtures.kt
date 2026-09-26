package com.saathi.app.maps

import java.io.File

/**
 * Reads the real accessibility-tree dumps in fixtures/trees/<app>/<screen>.txt (format: docs/handoff/00-README.md).
 * Descriptions can contain newlines, so a line that doesn't start a node continues the previous one.
 */
object Fixtures {
    private val START = Regex("^( *)(\\S+) t=")
    private val LINE = Regex("^( *)(\\S+) t=(.*?) d=(.*?) id=(\\S+) ?([CSKF]*) ri=\\S+ acts=\\S* Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)\\s*$",
        RegexOption.DOT_MATCHES_ALL)

    val root: File = listOf(File("../fixtures/trees"), File("fixtures/trees"), File("../../fixtures/trees")).first { it.isDirectory }

    fun load(app: String, screen: String): List<Node> = parse(File(root, "$app/$screen.txt").readText())

    fun parse(src: String): List<Node> {
        val joined = mutableListOf<String>()
        for (l in src.split("\n")) {
            if (START.containsMatchIn(l) || joined.isEmpty()) joined += l
            else if (l.isNotBlank()) joined[joined.lastIndex] = joined.last() + "\n" + l
        }
        return joined.filter { it.isNotBlank() }.map { l ->
            val g = LINE.find(l)?.groupValues ?: error("bad fixture line: ${l.take(160)}")
            fun nul(s: String) = s.takeUnless { it == "null" }
            val flags = g[6]
            Node(resId = nul(g[5]), text = nul(g[3]), desc = nul(g[4]), cls = g[2],
                clickable = 'C' in flags, scrollable = 'S' in flags, checkable = 'K' in flags,
                box = Box(g[7].toInt(), g[8].toInt(), g[9].toInt(), g[10].toInt()), depth = g[1].length / 2)
        }
    }

    /** A hand-built node list for screens without a phone dump yet (indent = depth, 2 spaces). */
    fun tree(vararg lines: String): List<Node> = parse(lines.joinToString("\n"))
}
