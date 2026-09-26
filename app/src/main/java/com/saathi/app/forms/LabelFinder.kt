package com.saathi.app.forms

/**
 * Finds the visible label of an input box from the text around it, for fields that have no `labeledBy`
 * (most web forms in Chrome, many apps). Pure, so it is unit-tested against tree fixtures.
 *
 * Candidates are texts to the LEFT on the same row, or ABOVE with horizontal overlap, not inside another input and
 * with no other input in between. The nearest one wins.
 */
object LabelFinder {
    fun find(field: Box, texts: List<Pair<String, Box>>, inputs: List<Box>): String? {
        val others = inputs.filter { it != field }
        fun insideInput(b: Box) = others.any { i -> b.cx in i.l until i.r && b.cy in i.t until i.b } ||
            (b.cx in field.l until field.r && b.cy in field.t until field.b)
        val cands = texts.filter { (t, b) -> t.isNotBlank() && t.length <= 80 && !insideInput(b) }

        val left = cands.filter { (_, b) -> b.rowOverlap(field) >= 0.5f && b.r <= field.l + field.h / 2 && field.l - b.r <= field.w }
            .minByOrNull { (_, b) -> field.l - b.r }
        val above = cands.filter { (_, b) ->
            b.b <= field.t + field.h / 4 && field.t - b.b <= field.h * 2 && b.l < field.r && b.r > field.l &&
                // no other input sits between the text and this field
                others.none { i -> i.t >= b.b && i.b <= field.t && i.l < field.r && i.r > field.l }
        }.minByOrNull { (_, b) -> field.t - b.b }

        val dl = left?.let { field.l - it.second.r }
        val da = above?.let { field.t - it.second.b }
        return when {
            left != null && above != null -> if (dl!! <= da!!) left.first else above.first
            left != null -> left.first
            else -> above?.first
        }?.trim()
    }
}
