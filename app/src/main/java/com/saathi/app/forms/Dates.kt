package com.saathi.app.forms

import java.time.LocalDate
import java.time.Period

/**
 * A date layout read off a form: "DD-MM-YYYY", "mm/dd/yyyy", "YYYY-MM-DD", "DD-MMM-YYYY", character boxes
 * "D D M M Y Y Y Y", or Hindi "दिन/माह/वर्ष". [sep] is what goes between the parts when typing ("" for boxes).
 */
data class DateFmt(val parts: List<Part>, val sep: String) {
    enum class Unit { D, M, Y }
    data class Part(val unit: Unit, val len: Int)

    /** Typed into an online field: exactly the layout the hint asks for. */
    fun format(d: LocalDate): String = parts.joinToString(sep) { fmt(d, it) }

    /** Written on paper, and spoken: "12 / 05 / 1956" in the form's order (separate boxes get separate groups). */
    fun spaced(d: LocalDate): String = parts.joinToString(" / ") { fmt(d, it) }

    private fun fmt(d: LocalDate, p: Part): String = when (p.unit) {
        Unit.D -> if (p.len == 1) d.dayOfMonth.toString() else "%02d".format(d.dayOfMonth)
        Unit.M -> when (p.len) {
            1 -> d.monthValue.toString()
            3 -> MONTHS[d.monthValue - 1]
            else -> "%02d".format(d.monthValue)
        }
        Unit.Y -> if (p.len == 2) "%02d".format(d.year % 100) else d.year.toString()
    }

    companion object {
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** Indian forms default to day first. */
        val DEFAULT = DateFmt(listOf(Part(Unit.D, 2), Part(Unit.M, 2), Part(Unit.Y, 4)), "/")

        private val HINDI = Regex("(दिन|दि)\\s*[/\\-.\\s]\\s*(माह|महीना|मास|मा)\\s*[/\\-.\\s]\\s*(वर्ष|साल|व)")
        /** A run of d/m/y letters with separators, not inside a word ("dd/mm/yyyy", "D D M M Y Y Y Y", "dd-mon-yyyy"). */
        private val CANDIDATE = Regex("(?i)(?<![a-z])(?:dd?|mm?m?|mon|yy(?:yy)?|[dmy])(?:[ /.\\-]*(?:dd?|mm?m?|mon|yy(?:yy)?|[dmy])){2,9}(?![a-z])")

        /** The layout named in [text], or null if it names none. */
        fun find(text: String?): DateFmt? {
            if (text.isNullOrBlank()) return null
            if (HINDI.containsMatchIn(text)) return DEFAULT
            for (m in CANDIDATE.findAll(text)) parse(m.value)?.let { return it }
            return null
        }

        private fun parse(raw: String): DateFmt? {
            val s = raw.lowercase().replace("mon", "mmm")
            val explicit = s.firstOrNull { it == '/' || it == '-' || it == '.' }
            val sep: String
            val letters: String
            if (explicit != null) {
                sep = explicit.toString(); letters = s.filter { it in "dmy" + explicit }
            } else {
                val pieces = s.trim().split(Regex("\\s+"))
                // "D D M M Y Y Y Y" = one box per character → no separator between groups when typing.
                sep = if (pieces.size > 1 && pieces.all { it.length == 1 }) "" else if (pieces.size > 1) " " else ""
                letters = s.filter { it in "dmy" }
            }
            val runs = mutableListOf<Part>()
            for (c in letters.filter { it in "dmy" }) {
                val u = when (c) { 'd' -> Unit.D; 'm' -> Unit.M; else -> Unit.Y }
                if (runs.isNotEmpty() && runs.last().unit == u) runs[runs.lastIndex] = runs.last().copy(len = runs.last().len + 1)
                else runs += Part(u, 1)
            }
            if (runs.size != 3 || runs.map { it.unit }.toSet().size != 3) return null
            val ok = runs.all { p ->
                when (p.unit) { Unit.D -> p.len in 1..2; Unit.M -> p.len in 1..3; Unit.Y -> p.len == 2 || p.len == 4 }
            }
            return if (ok) DateFmt(runs, sep) else null
        }

        /** Whole years, for "Age" boxes. */
        fun age(dob: LocalDate, today: LocalDate): Int = Period.between(dob, today).years
    }
}
