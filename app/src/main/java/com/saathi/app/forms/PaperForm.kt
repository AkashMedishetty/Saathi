package com.saathi.app.forms

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paper forms: OCR lines of a photo → the boxes to fill, in reading order, with what to write in each.
 *
 * How a line is read:
 *  - it is cut at blank runs (`____`, `....`, `□□□`) and at colons: "Name: ______ Age: ___" is two fields;
 *  - when handwriting sits between two labels ("Name: Ramesh Age:") the second label is the longest word suffix that
 *    is a known label, so "Ramesh" is seen as the first field's (filled) answer;
 *  - section headings switch who the boxes are about: "Nominee details" (nominee), "Joint holder / Witness /
 *    Guardian" (someone else), "For office use only" (skipped entirely);
 *  - a box is skipped when it already has writing in it (text to the right on the same row, or right below).
 * The write area is the blank to the right of the label on the same row, or below it when the label runs to the edge.
 */
object PaperForm {

    private enum class Section { SELF, NOMINEE, OTHER, OFFICE }

    private val FILL = Regex("(?:[_.…·\\-—–□☐▢◻■|\\[\\]⎕/]\\s?){3,}")
    // Common OCR readings of empty character boxes. Letter-like runs need whole-token boundaries:
    // "I I I I" and "LLLL" are grids; "Lillian", "Bill" and a single "I" are not grid runs.
    private val GRID = Regex("(?<![\\p{L}\\p{M}\\p{N}])(?:I(?:\\s+I){2,}|L{4,}|(?:口\\s*){3,}|" +
        "(?:\\|\\s*){3,}|(?:\\[\\s*\\]\\s*){2,}|(?:[□☐▢◻■⎕]\\s*){3,})(?![\\p{L}\\p{M}\\p{N}])")
    private fun gridOnly(t: String) = t.isBlank() || GRID.matches(t.trim())
    private val FILL_CHARS = Regex("[_.…·\\-—–□☐▢◻■|\\[\\]⎕/\\\\()]")
    private val COLON = Regex("[:：;]-?")
    private val PAREN = Regex("\\([^)]*\\)")
    private val DATE_WORDS = Regex("(?i)(?<![a-z])(dd|mm|yyyy|yy|mmm|d|m|y)(?![a-z])|दिन|माह|महीना|वर्ष|साल")
    private val HEADER_WORD = Regex("(?i)details|particulars|information|\\binfo\\b|section|use only|विवरण|जानकारी|उपयोग|हेतु")
    private val SEC_OFFICE = Regex("(?i)(office|bank|official|branch|departmental|internal) use|for (office|bank|official|departmental)|" +
        // Filled by anyone who isn't them ("TO BE FILLED BY THE DISTRICT CHILD PROTECTION OFFICER (DCPO)", field 07:20).
        "to be (filled|completed|signed) (in )?by (?!(the )?(applicant|candidate|you|parent|guardian|student|patient|pensioner|customer|account holder|member))|" +
        "certifier|certified by|certification by|attested by|attestation|for use by|verification by|recommend(ed|ation) (by|of)|" +
        "do not write (in|below)|space for (office|bank)|" +
        "कार्यालय|केवल बैंक|बैंक के उपयोग|कार्यालय उपयोग|అధికారిక|కార్యాలయ")
    /** Labels only staff fill, wherever they sit (field 07:15: the helper guided "for office use" boxes). */
    private val STAFF = Regex("(?i)\\b(officer|official|verified by|verifying|sanction|approved by|received by|checked by|entered by|" +
        "authori[sz]ed signatory|signature of (the )?(officer|manager|clerk|authori)|stamp|seal|branch (code|manager|name)|clerk|" +
        "dealing (assistant|hand)|ref(erence)? no|file no|receipt no|account opened|customer id|cif|certifier|dcpo|attested)\\b|अधिकारी|मुहर|అధికారి|ముద్ర")
    private val SEC_NOMINEE = Regex("(?i)nominee|nomination|नामांकन|नामांकित|नामिती|नॉमिनी")
    private val SEC_OTHER = Regex("(?i)joint|second (holder|applicant)|2nd (holder|applicant)|guardian|witness|introducer|" +
        "reference|emergency contact|attendant|गवाह|अभिभावक|संयुक्त")
    private val SEC_SELF = Regex("(?i)applicant|personal|patient|customer|your details|pensioner|account holder|member details|" +
        "declaration|व्यक्तिगत|आवेदक|पेंशनभोगी|मरीज|ग्राहक|घोषणा")

    private val GENDER_OPTS = listOf(
        Gender.MALE to Regex("(?i)\\bmale\\b|(?<![a-z])m(?![a-z])|पुरुष"),
        Gender.FEMALE to Regex("(?i)\\bfemale\\b|(?<![a-z])f(?![a-z])|महिला|स्त्री"),
        Gender.OTHER to Regex("(?i)\\bother\\b|transgender|अन्य|तृतीय"),
    )

    /** Say this when [analyse] finds nothing (a blurry photo, or Telugu print, which ML Kit cannot read). */
    fun nothingFound(): Say = FormSay.nothingFound()

    /**
     * [w] × [h] is the photo size; boxes are in photo pixels. [today] fills "Age" and "Date" boxes.
     */
    fun analyse(lines: List<OcrLine>, w: Int, h: Int, p: FormProfile, today: LocalDate = LocalDate.now()): List<PaperField> {
        // Blank-text lines stay (an empty box grid has no text, Codex); OCR look-alike letters ("CERTІFІER" with a
        // Cyrillic І) are normalised to Latin first.
        val src = lines.filter { it.box.w > 0 && it.box.h > 0 }.map { it.copy(text = latin(it.text)) }
        if (src.none { it.text.isNotBlank() }) return emptyList()
        val profile = p.sanitized()
        val rows = rowsOf(src)
        val contentR = min(w, src.maxOf { it.box.r })
        val contentL = max(0, src.minOf { it.box.l })
        val lineH = src.map { it.box.h }.sorted()[src.size / 2]

        val segsByLine = src.associateWith { segments(it.text) }
        val postal = segsByLine.values.flatten().any { Labels.isAddress(it.cls) }
        val hasMiddle = segsByLine.values.flatten().any { it.cls?.key == FieldKey.MIDDLE_NAME }

        // Office zones by POSITION: an office-use heading owns the area below it in its column (a side panel or a boxed
        // block) until the next heading that isn't office-use. Reading order alone missed side panels.
        val officeZones = src.filter { SEC_OFFICE.containsMatchIn(it.text) }.map { hd ->
            val left = if (hd.box.l > w * 0.35) hd.box.l - lineH * 2 else 0
            val right = if (hd.box.r < w * 0.6 && hd.box.l < w * 0.35) (w * 0.62).toInt() else w
            val end = src.filter { it.box.t > hd.box.b && it.box.l >= left - lineH && it.box.l < right &&
                (header(it.text).let { s -> s != null && s != Section.OFFICE } || SEC_SELF.containsMatchIn(it.text)) }.minOfOrNull { it.box.t } ?: h
            Box(left, hd.box.t, right, end)
        }
        val inOffice = { b: Box -> val cx = (b.l + b.r) / 2; val cy = (b.t + b.b) / 2
            officeZones.any { z -> cx in z.l..z.r && cy in z.t..z.b } }

        val out = mutableListOf<PaperField>()
        var section = Section.SELF
        for ((ri, row) in rows.withIndex()) {
            for ((li, line) in row.withIndex()) {
                val hd = header(line.text)
                if (hd != null) { section = hd; continue }
                if (section == Section.OFFICE && !SEC_SELF.containsMatchIn(line.text)) continue
                if (inOffice(line.box)) continue
                val segs = segsByLine.getValue(line).filter { it.isField }
                for ((si, seg) in segs.withIndex()) {
                    // "Signature of Applicant" after the nominee block: the boxes are about the person again.
                    if (section != Section.SELF && SEC_SELF.containsMatchIn(line.text.substring(seg.labelStart, seg.labelEnd))) section = Section.SELF
                    if (STAFF.containsMatchIn(line.text.substring(seg.labelStart, seg.labelEnd))) continue
                    val next = segs.getOrNull(si + 1)
                    field(seg, next, line, row.drop(li + 1), rows.getOrNull(ri + 1), rows.drop(ri + 1),
                        section, postal, hasMiddle, profile, today, w, h, contentL, contentR, lineH)?.let { out += it }
                }
            }
        }
        return out
    }

    // ───────────────────────── one field ─────────────────────────

    private fun field(
        seg: Seg, nextSeg: Seg?, line: OcrLine, rightOnRow: List<OcrLine>, nextRow: List<OcrLine>?, rowsBelow: List<List<OcrLine>>,
        section: Section, postal: Boolean, hasMiddle: Boolean, p: FormProfile, today: LocalDate,
        w: Int, h: Int, contentL: Int, contentR: Int, lineH: Int,
    ): PaperField? {
        val text = line.text
        val labelText = text.substring(seg.labelStart, seg.labelEnd).trim().trimEnd(':', '：', ';', '-', ' ')
        val raw = seg.cls?.let { Labels.resolve(it, postal, section == Section.NOMINEE) }
        val rem = text.substring(seg.remStart, seg.remEnd)
        val lb = line.box
        val labelBox = Box(charLeft(line, seg.labelStart), lb.t, charRight(line, seg.labelEnd - 1), lb.b)
        val gap = (lineH * 0.25f).roundToInt()

        // Gender printed as options: point at the right one to tick.
        if (raw == FieldKey.GENDER && section == Section.SELF) {
            val opts = GENDER_OPTS.mapNotNull { (g, re) -> re.find(rem)?.let { g to it } }
            if (opts.size >= 2) {
                val mine = opts.firstOrNull { it.first == p.gender }
                val box = if (mine != null) {
                    val s = seg.remStart + mine.second.range.first; val e = seg.remStart + mine.second.range.last
                    Box(charLeft(line, s) - gap, lb.t - gap, charRight(line, e) + gap, lb.b + gap)
                } else Box(charLeft(line, seg.remStart), lb.t, charRight(line, seg.remEnd - 1), lb.b)
                return PaperField(FieldKey.GENDER, labelText, labelBox, box.clip(w, h), mine?.second?.value,
                    mine?.let { FormSay.tick(it.second.value) } ?: FormSay.tickYours(), false)
            }
        }

        if (isFilled(rem)) return null
        val hasFill = FILL.containsMatchIn(rem)

        // Prefer observed grid bounds to an estimated blank extending to the page edge. This handles
        // a grid in the same OCR line, a separate OCR line on the same row, or the next row below.
        if (seg.cls != null) {
            val inline = GRID.find(rem)?.takeIf { gridOnly(rem) }?.let { match ->
                val first = seg.remStart + match.range.first
                val last = seg.remStart + match.range.last
                val (a, b) = trim(text, first, last + 1)
                Box(charLeft(line, a), lb.t, charRight(line, b - 1), lb.b)
            }
            val neighbor = rightOnRow.firstOrNull { it.box.l >= labelBox.r }
            if (inline == null && nextSeg == null && neighbor != null && !isPlaceholder(neighbor.text) &&
                segments(neighbor.text).none { it.isField } && header(neighbor.text) == null) return null
            val beside = neighbor?.takeIf { nextSeg == null && gridOnly(it.text) }?.box
            val columnEnd = nextSeg?.let { charLeft(line, it.labelStart) }
                ?: rightOnRow.firstOrNull { segments(it.text).any { seg -> seg.isField } }?.box?.l ?: w
            val below = if (nextSeg == null && inline == null && beside == null) nextRow.orEmpty()
                .filter { gridOnly(it.text) && it.box.t >= lb.b && it.box.t - lb.b <= lineH * 2 &&
                    it.box.l >= labelBox.l - gap && it.box.l < columnEnd && it.box.r <= columnEnd }
                .minByOrNull { it.box.l - labelBox.l }?.box else null
            val grid = (inline ?: beside ?: below)?.clip(w, h)
            if (grid != null && grid.w > 0 && grid.h > 0)
                return build(raw, seg, labelText, labelBox, grid, rem, section, hasMiddle, p, today)
        }

        // Where the blank to the right ends: the next label on this line, the next thing on this row, or the edge.
        val rightLimit = when {
            nextSeg != null -> charLeft(line, nextSeg.labelStart)
            else -> {
                val nb = rightOnRow.firstOrNull { it.box.l >= labelBox.r }
                when {
                    nb == null -> {
                        // OCR often sees only the label, not the empty boxes. Infer a right-hand area
                        // only for a known field; never reinterpret nearby handwriting as blank space.
                        val writingBelow = nextRow.orEmpty().any { b ->
                            b.box.t - lb.b in 0..lineH * 2 && b.box.r > lb.l && b.box.l < contentR &&
                                !isPlaceholder(b.text) && segments(b.text).none { it.isField } && header(b.text) == null
                        }
                        if (seg.cls != null && rem.isBlank() && writingBelow) return null
                        if (seg.cls != null && rem.isBlank()) max(contentR, w - max(gap, contentL)) else contentR
                    }
                    isPlaceholder(nb.text) -> max(nb.box.r, contentR.takeIf { rightOnRow.size == 1 } ?: nb.box.r)
                    segments(nb.text).any { it.isField } || header(nb.text) != null -> nb.box.l
                    else -> return null // handwriting next to the label: already filled
                }
            }
        }
        val startX = labelBox.r + gap
        // Printed blanks mark exactly where to write; otherwise the blank runs up to the next thing on the row.
        val endX = if (hasFill) charRight(line, seg.remEnd - 1) else rightLimit - gap
        val room = endX - startX
        val minRoom = max(lineH * 5 / 2, (contentR - contentL) / 10)

        var box: Box = if (room >= minRoom || (hasFill && room > lineH)) {
            Box(startX, lb.t - lineH / 5, endX, lb.b + lineH / 5)
        } else {
            // Nothing to the right: write on the line below. Skip if there is already writing there.
            val below = nextRow.orEmpty().filter { it.box.r > lb.l && it.box.l < contentR }
            if (below.any { b -> !isPlaceholder(b.text) && segments(b.text).none { it.isField } && header(b.text) == null }) return null
            val top = lb.b + lineH / 6
            val maxH = if (raw == FieldKey.ADDRESS) lineH * 7 / 2 else lineH * 8 / 5
            val nextTop = nextRow?.filter { !isPlaceholder(it.text) }?.minOfOrNull { it.box.t }
            var bottom = min(nextTop?.let { it - lineH / 10 } ?: h, top + maxH)
            if (bottom - top < lineH * 3 / 5) bottom = top + lineH * 6 / 5
            Box(lb.l, top, contentR, bottom)
        }
        // A multi-line address: absorb the blank ruled lines right below it.
        if (raw == FieldKey.ADDRESS || raw == FieldKey.ADDRESS1) {
            for (r in rowsBelow) {
                if (r.isEmpty() || !r.all { isPlaceholder(it.text) }) break
                box = box.copy(b = max(box.b, r.maxOf { it.box.b } + lineH / 5))
            }
        }
        box = box.clip(w, h)
        if (box.w <= 0 || box.h <= 0) return null

        return build(raw, seg, labelText, labelBox, box, rem, section, hasMiddle, p, today)
    }

    private fun build(
        raw: FieldKey?, seg: Seg, labelText: String, labelBox: Box, writeBox: Box, rem: String,
        section: Section, hasMiddle: Boolean, p: FormProfile, today: LocalDate,
    ): PaperField {
        // Someone else's box: say whose it is, never fill it from the person's own profile.
        if (section != Section.SELF) {
            val nomineeKey = section == Section.NOMINEE && raw in setOf(FieldKey.FULL_NAME, FieldKey.NOMINEE_NAME, FieldKey.NOMINEE_RELATION)
            if (!nomineeKey) {
                val say = if (section == Section.NOMINEE) FormSay.forNominee(labelText) else FormSay.forOtherPerson(labelText)
                return PaperField(raw?.takeIf { it.sensitive }, labelText, labelBox, writeBox, null, say, raw?.sensitive == true)
            }
        }
        val key = if (section == Section.NOMINEE && raw == FieldKey.FULL_NAME) FieldKey.NOMINEE_NAME else raw
        if (key == null) return PaperField(null, labelText, labelBox, writeBox, null, FormSay.unknown(labelText), false)
        if (key.sensitive) return PaperField(key, labelText, labelBox, writeBox, null, FormSay.paperSecret(key), true)

        val lang = if (DEVANAGARI.containsMatchIn(labelText)) Lang.HI else Lang.EN
        val dates = DateFmt.find(rem) ?: DateFmt.find(seg.labelRaw) ?: DateFmt.DEFAULT
        val value = p.valueFor(key, today, lang, dates, hasMiddleBox = hasMiddle, spacedDates = true)
            ?.takeUnless { key != FieldKey.MOBILE && FormProfile.looksSecret(it) }
        val say = if (value != null) FormSay.paperWrite(key, value) else FormSay.paperMissing(key)
        return PaperField(key, labelText, labelBox, writeBox, value, say, false)
    }

    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
    private val TITLE = Regex("(?i)\\b(bank|form|application|government|govt|certificate|india|limited|ltd|hospital|clinic|" +
        "office|department|ministry|corporation|society|trust|scheme|jeevan|pramaan|registration)\\b|प्रमाण|पत्र|आवेदन|सरकार|बैंक|भारत|विभाग|अस्पताल")

    // ───────────────────────── reading a line ─────────────────────────

    /** A label inside a line: [labelStart, labelEnd) is the label (with its colon); [remStart, remEnd) what follows. */
    internal class Seg(val labelStart: Int, val labelEnd: Int, var remStart: Int, var remEnd: Int,
                       val cls: Labels.Cls?, val labelRaw: String, val marked: Boolean) {
        private val words = labelRaw.trim().split(Regex("\\s+")).count { it.any(Char::isLetter) }
        /** A known label, or a short unknown one that is clearly asking for something (colon or blank after it). */
        val isField: Boolean get() = cls != null || (marked && words in 1..4)
    }

    internal fun segments(s: String): List<Seg> {
        val out = mutableListOf<Seg>()
        var cur = 0
        val fills = (FILL.findAll(s).map { it.range } + GRID.findAll(s).map { it.range })
            .sortedBy { it.first }.fold(mutableListOf<IntRange>()) { ranges, range ->
                if (ranges.isNotEmpty() && range.first <= ranges.last().last) {
                    val last = ranges.removeAt(ranges.lastIndex)
                    ranges += last.first..max(last.last, range.last)
                } else ranges += range
                ranges
            }
        val chunks = mutableListOf<Triple<Int, Int, Int>>() // text start, text end, remainder end (after the fill)
        for (f in fills) { chunks += Triple(cur, f.first, f.last + 1); cur = f.last + 1 }
        if (cur < s.length) chunks += Triple(cur, s.length, s.length)
        for ((a, b, remEnd) in chunks) {
            if (s.substring(a, b).none { it.isLetterOrDigit() }) { out.lastOrNull()?.let { it.remEnd = remEnd }; continue }
            val fill = remEnd > b
            val colons = COLON.findAll(s.substring(a, b)).map { it.range.first + a to it.range.last + 1 + a }.toList()
            if (colons.isEmpty()) {
                val (ls, le) = trim(s, a, b)
                val lab = s.substring(ls, le)
                val words = lab.split(Regex("\\s+")).size
                // Without a colon or a blank after it, only a short line that is not a heading can be a label
                // ("STATE BANK OF INDIA" and "CITY GENERAL HOSPITAL" are titles, not State / City boxes).
                val cls = if (words <= (if (fill) 7 else 5) && (fill || !TITLE.containsMatchIn(lab))) Labels.classify(lab) else null
                if (cls != null || fill) out += Seg(ls, le, le, remEnd, cls, lab, fill)
                continue
            }
            for ((k, c) in colons.withIndex()) {
                val partStart = if (k == 0) a else colons[k - 1].second
                val (ls, cls) = labelIn(s, partStart, c.first, first = k == 0) ?: continue
                out.lastOrNull()?.takeIf { it.remEnd > ls && it.remStart <= ls }?.remEnd = ls
                val remE = colons.getOrNull(k + 1)?.first ?: remEnd
                out += Seg(ls, c.second, c.second, remE, cls, s.substring(ls, c.first), true)
            }
        }
        return out
    }

    /**
     * The label that ends at [end]. Start from the shortest word suffix that is a known label and grow it only while
     * the extra word is label vocabulary ("of", "का", "permanent"…) or changes what the label means ("Name" →
     * "Father's Name"). So in "Name: Ramesh Kumar Age:" the second label is "Age", not "Ramesh Kumar Age".
     */
    private fun labelIn(s: String, start: Int, end: Int, first: Boolean): Pair<Int, Labels.Cls?>? {
        val (ts, te) = trim(s, start, end)
        if (ts >= te) return null
        val starts = Regex("\\S+").findAll(s.substring(ts, te)).map { it.range.first + ts }.toList()
        if (first && Labels.normalise(s.substring(ts, te)).split(' ').size <= 7) Labels.classify(s.substring(ts, te))?.let { return ts to it }
        var best: Pair<Int, Labels.Cls>? = null
        for (n in 1..min(6, starts.size)) {
            val st = starts[starts.size - n]
            val c = Labels.classify(s.substring(st, te))
            val b = best
            if (b == null) { if (c != null) best = st to c; continue }
            if (c == null) break
            val word = s.substring(st, starts.getOrNull(starts.size - n + 1) ?: te).trim().lowercase().trim('.', ',', '\'', '/')
            if (c != b.second || word in CONNECTORS) best = st to c else break
        }
        best?.let { return it }
        return when {
            starts.size <= 4 && first -> ts to null
            starts.size <= 3 -> ts to null
            else -> null
        }
    }

    private val CONNECTORS = setOf("of", "the", "date", "name", "no", "number", "your", "applicant's", "applicant", "full",
        "permanent", "present", "correspondence", "residential", "communication", "mobile", "contact", "e-mail", "email",
        "father's", "mother's", "husband's", "spouse's", "nominee's", "first", "last", "middle", "given", "complete",
        "का", "की", "के", "नाम", "पूरा", "स्थायी", "वर्तमान", "आवेदक", "संख्या", "नंबर")

    private fun trim(s: String, a: Int, b: Int): Pair<Int, Int> {
        var x = a; var y = b
        while (x < y && s[x].isWhitespace()) x++
        while (y > x && s[y - 1].isWhitespace()) y--
        return x to y
    }

    /** True when the text after a label is real writing, not blanks, a format hint or printed options. */
    internal fun isFilled(rem: String): Boolean {
        val left = rem.replace(GRID, " ").replace(PAREN, " ").replace(DATE_WORDS, " ").replace(FILL_CHARS, " ")
        return left.count { it.isLetterOrDigit() } >= 2
    }

    /** A line that is only ruled blanks / character boxes / a date layout. */
    internal fun isPlaceholder(t: String) = !isFilled(t)

    private val LOOKALIKE = mapOf('І' to 'I', 'і' to 'i', 'Ї' to 'I', 'ї' to 'i', 'О' to 'O', 'о' to 'o', 'А' to 'A', 'а' to 'a',
        'Е' to 'E', 'е' to 'e', 'С' to 'C', 'с' to 'c', 'Р' to 'P', 'р' to 'p', 'Т' to 'T', 'К' to 'K', 'Х' to 'X', 'х' to 'x',
        'Н' to 'H', 'В' to 'B', 'М' to 'M', 'у' to 'y', 'Ү' to 'Y', 'Ѕ' to 'S', 'ѕ' to 's', 'Ј' to 'J', 'ј' to 'j',
        'Ι' to 'I', 'Ο' to 'O', 'Α' to 'A', 'Ε' to 'E', 'Τ' to 'T', 'Κ' to 'K', 'Χ' to 'X', 'Ν' to 'N', 'Β' to 'B', 'Μ' to 'M', 'Ρ' to 'P', 'ο' to 'o')
    /** Cyrillic / Greek look-alikes → Latin (only when the line is mostly Latin, so real Hindi/Telugu is untouched). */
    internal fun latin(t: String): String {
        if (t.none { it in LOOKALIKE }) return t
        val latin = t.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (latin >= t.count { it.isLetter() } / 3) t.map { LOOKALIKE[it] ?: it }.joinToString("") else t
    }

    private fun header(t: String): Section? {
        // Office-use headings are often long or dotted ("FOR OFFICE USE ONLY ....... do not write below"): check them first.
        if (SEC_OFFICE.containsMatchIn(t) && t.trim().split(Regex("\\s+")).size <= 14) return Section.OFFICE
        if (FILL.containsMatchIn(t)) return null
        val n = t.trim()
        if (n.split(Regex("\\s+")).size > 8) return null
        val bare = !n.trimEnd().endsWith(":") && Labels.classify(n) == null
        val headerish = HEADER_WORD.containsMatchIn(n) || bare
        return when {
            !headerish -> null
            SEC_NOMINEE.containsMatchIn(n) -> Section.NOMINEE
            SEC_OTHER.containsMatchIn(n) -> Section.OTHER
            SEC_SELF.containsMatchIn(n) -> Section.SELF
            else -> null
        }
    }

    // ───────────────────────── geometry ─────────────────────────

    private fun rowsOf(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.box.t }) {
            val row = rows.lastOrNull()?.takeIf { r -> r.first().box.rowOverlap(l.box) >= 0.5f }
            if (row != null) row += l else rows += mutableListOf(l)
        }
        return rows.map { r -> r.sortedBy { it.box.l } }
    }

    /** Character [i]'s left edge: exact from the OCR words when we have them, else proportional along the line. */
    internal fun charLeft(line: OcrLine, i: Int): Int = charEdge(line, i.coerceIn(0, max(0, line.text.length - 1)), left = true)
    internal fun charRight(line: OcrLine, i: Int): Int = charEdge(line, i.coerceIn(0, max(0, line.text.length - 1)), left = false)

    private fun charEdge(line: OcrLine, i: Int, left: Boolean): Int {
        val b = line.box
        val n = max(1, line.text.length)
        val prop = b.l + (b.w * (if (left) i else i + 1).toFloat() / n).roundToInt()
        if (line.words.isEmpty()) return prop
        var from = 0
        val spans = line.words.map { wd ->
            val at = line.text.indexOf(wd.text, from)
            if (at < 0) return prop
            from = at + wd.text.length
            Triple(at, at + wd.text.length, wd.box)
        }
        spans.firstOrNull { i >= it.first && i < it.second }?.let { (s, e, wb) ->
            val k = (if (left) i - s else i - s + 1).toFloat() / (e - s)
            return wb.l + (wb.w * k).roundToInt()
        }
        // Between words (a space or a colon OCR kept outside the words).
        return if (left) spans.firstOrNull { it.first > i }?.third?.l ?: b.r
        else spans.lastOrNull { it.second <= i }?.third?.r ?: b.l
    }
}
