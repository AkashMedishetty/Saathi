package com.saathi.app.maps

/** Builds hand-made screens in the dump format, for apps with no phone dump yet. */
object T {
    fun n(depth: Int, cls: String, t: String? = null, d: String? = null, id: String? = null, flags: String = "", r: String) =
        "  ".repeat(depth) + "$cls t=${t ?: "null"} d=${d ?: "null"} id=${id ?: "null"} $flags ri=null acts= Rect($r)"

    /** A full-screen root plus the given lines. */
    fun screen(vararg lines: String): List<Node> = Fixtures.tree(n(0, "FrameLayout", r = "0, 0 - 1440, 3168"), *lines)

    /** A clickable button at depth 1 with a label (content description). */
    fun btn(label: String, r: String, depth: Int = 1, cls: String = "Button") = n(depth, cls, d = label, flags = "CF", r = r)
    fun text(t: String, r: String, depth: Int = 1) = n(depth, "TextView", t = t, r = r)
}
