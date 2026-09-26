package com.saathi.app.forms

import com.saathi.app.guide.Lang
import com.saathi.app.guide.Say
import java.time.LocalDate

/** Shared test data: one realistic profile, a fixed "today", and helpers to lay out OCR lines like a real photo. */
object Fx {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 27)

    val RAMESH = FormProfile(
        fullName = "Ramesh Kumar Sharma",
        fatherName = "Suresh Chandra Sharma",
        spouseName = "Kamala Devi",
        dob = LocalDate.of(1956, 5, 12),
        gender = Gender.MALE,
        address1 = "12-4-56, Gandhi Road",
        address2 = "Ameerpet",
        city = "Hyderabad",
        district = "Hyderabad",
        state = "Telangana",
        pinCode = "500016",
        mobile = "9876543210",
        email = "ramesh.sharma56@gmail.com",
        occupation = "Retired teacher",
        nomineeName = "Kamala Devi",
        nomineeRelation = "Wife",
    )

    const val CW = 18   // printed character width in photo pixels
    const val LH = 36   // printed line height

    /** An OCR line whose box is exactly as wide as its text at [CW] px per character. */
    fun line(x: Int, y: Int, text: String, withWords: Boolean = false): OcrLine {
        val box = Box(x, y, x + text.length * CW, y + LH)
        if (!withWords) return OcrLine(text, box)
        val words = Regex("\\S+").findAll(text).map { m ->
            OcrWord(m.value, Box(x + m.range.first * CW, y, x + (m.range.last + 1) * CW, y + LH))
        }.toList()
        return OcrLine(text, box, words)
    }

    fun en(s: Say) = s.getValue(Lang.EN)
    fun hi(s: Say) = s.getValue(Lang.HI)
    fun te(s: Say) = s.getValue(Lang.TE)

    fun field(label: String?, hint: String? = null, resId: String? = null, row: Int, col: Int = 0,
              password: Boolean = false, inputType: Int? = null) =
        FieldNode(label, hint, resId, Box(40 + col * 700, 200 + row * 160, 40 + col * 700 + 640, 200 + row * 160 + 120), password, inputType)

    // android.text.InputType values used in tests
    const val TEXT = 0x1
    const val TEXT_PASSWORD = 0x81
    const val TEXT_WEB_PASSWORD = 0xe1
    const val NUMBER = 0x2
    const val NUMBER_PASSWORD = 0x12
    const val EMAIL = 0x21
    const val PHONE = 0x3
}

/**
 * Parser for the accessibility-tree fixture format in docs/handoff/00-README.md:
 * `<Class> t=<text> d=<desc> id=<id> <flags> ri=<range> acts=<ids> Rect(l, t - r, b)`, two spaces per depth.
 */
object TreeFixture {
    data class Node(val cls: String, val text: String?, val desc: String?, val id: String?, val flags: String, val box: Box, val depth: Int)

    private val LINE = Regex("^( *)(\\S+) t=(.*?) d=(.*?) id=(\\S+) ?([CSKF]*) ri=\\S+ acts=\\S* Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)$")

    fun parse(src: String): List<Node> = src.lines().filter { it.isNotBlank() }.map { l ->
        val m = LINE.find(l) ?: error("bad fixture line: $l")
        val g = m.groupValues
        fun nul(s: String) = s.takeUnless { it == "null" }
        Node(g[2], nul(g[3]), nul(g[4]), nul(g[5]), g[6], Box(g[7].toInt(), g[8].toInt(), g[9].toInt(), g[10].toInt()), g[1].length / 2)
    }

    /** The same mapping FormNodes.fields does on the phone, minus the Android types (hint/password/inputType are not in the dump). */
    fun fields(nodes: List<Node>): List<FieldNode> {
        val inputs = nodes.filter { it.cls == "EditText" }
        val texts = nodes.filter { it.cls != "EditText" }.mapNotNull { n -> (n.text ?: n.desc)?.let { it to n.box } }
        val boxes = inputs.map { it.box }
        return inputs.map { n -> FieldNode(LabelFinder.find(n.box, texts, boxes), n.text, n.id, n.box, false, null) }
    }
}
