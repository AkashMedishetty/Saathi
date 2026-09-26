package com.saathi.app.forms

import com.saathi.app.forms.FieldKey.*
import com.saathi.app.forms.Fx.CW
import com.saathi.app.forms.Fx.LH
import com.saathi.app.forms.Fx.en
import com.saathi.app.forms.Fx.hi
import com.saathi.app.forms.Fx.line
import com.saathi.app.forms.Fx.te
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperFormTest {
    private val W = 1240
    private val H = 1754

    /** An English bank account-opening form, laid out as ML Kit returns it (one OCR line per printed run). */
    private val bank = listOf(
        line(300, 60, "STATE BANK OF INDIA"),
        line(200, 110, "ACCOUNT OPENING FORM - SAVINGS"),
        line(80, 170, "PERSONAL DETAILS"),
        line(80, 230, "1. Full Name (in capitals): __________________________"),
        line(80, 290, "2. Father's / Husband's Name:"),
        line(80, 350, "3. Date of Birth: D D M M Y Y Y Y"),
        line(660, 350, "Gender: Male / Female / Other"),
        line(80, 410, "4. Mobile No.: __________"),
        line(660, 410, "E-mail:"),
        line(80, 470, "5. Permanent Address with PIN code (in capital letters):"),
        line(80, 530, "____________________________________________"),
        line(80, 590, "____________________________________________"),
        line(80, 650, "6. Aadhaar No.: □□□□ □□□□ □□□□"),
        line(660, 650, "PAN:"),
        line(80, 710, "7. Occupation: Retired"),
        line(80, 770, "NOMINEE DETAILS"),
        line(80, 830, "Name of Nominee: ______________"),
        line(660, 830, "Relationship:"),
        line(80, 890, "Nominee's Address:"),
        line(80, 950, "FOR BANK USE ONLY"),
        line(80, 1010, "Account No.: ________"),
        line(660, 1010, "IFSC:"),
        line(80, 1100, "Signature of Applicant: ____________"),
        line(660, 1100, "Date:"),
        line(660, 1160, "Place:"),
    )

    private fun analyse(lines: List<OcrLine>, p: FormProfile = Fx.RAMESH) = PaperForm.analyse(lines, W, H, p, Fx.TODAY)

    @Test fun bankFormFieldsInOrder() {
        val f = analyse(bank)
        assertEquals(
            listOf(FULL_NAME, RELATIVE_NAME, DOB, GENDER, MOBILE, EMAIL, ADDRESS, AADHAAR, PAN,
                NOMINEE_NAME, NOMINEE_RELATION, null, SIGNATURE, DATE, PLACE),
            f.map { it.key })
        // "Occupation: Retired" is already written: skipped. Office-use boxes: skipped.
        assertFalse(f.any { it.label.contains("Occupation") || it.label.contains("Account") || it.label.contains("IFSC") })
    }

    @Test fun bankFormValuesAndWords() {
        val f = analyse(bank).associateBy { it.label }
        assertEquals("Ramesh Kumar Sharma", f.getValue("1. Full Name (in capitals)").value)
        assertEquals("Write your name here: Ramesh Kumar Sharma.", en(f.getValue("1. Full Name (in capitals)").say))
        assertEquals("Suresh Chandra Sharma", f.getValue("2. Father's / Husband's Name").value)
        assertEquals("12 / 05 / 1956", f.getValue("3. Date of Birth").value)
        assertEquals("Write your date of birth here: 12 / 05 / 1956.", en(f.getValue("3. Date of Birth").say))
        assertEquals("यहाँ जन्म तिथि लिखिए: 12 / 05 / 1956।", hi(f.getValue("3. Date of Birth").say))
        assertEquals("ఇక్కడ మీ పుట్టిన తేదీ రాయండి: 12 / 05 / 1956.", te(f.getValue("3. Date of Birth").say))
        assertEquals("9876543210", f.getValue("4. Mobile No.").value)
        assertEquals("Write your mobile number here: 98765 43210.", en(f.getValue("4. Mobile No.").say))
        assertEquals("ramesh.sharma56@gmail.com", f.getValue("E-mail").value)
        assertEquals("12-4-56, Gandhi Road, Ameerpet, Hyderabad, Telangana - 500016",
            f.getValue("5. Permanent Address with PIN code (in capital letters)").value)
        assertEquals("Kamala Devi", f.getValue("Name of Nominee").value)
        assertEquals("Wife", f.getValue("Relationship").value)
        assertEquals("27 / 09 / 2026", f.getValue("Date").value)
        assertEquals("Hyderabad", f.getValue("Place").value)
    }

    @Test fun genderOptionsPointAtTheRightWord() {
        val g = analyse(bank).single { it.key == GENDER }
        assertEquals("Male", g.value)
        assertEquals("Tick “Male”.", en(g.say))
        // "Gender: Male / Female / Other" starts at x=660: "Male" is characters 8..11
        val maleL = 660 + 8 * CW; val maleR = 660 + 12 * CW
        assertTrue("box ${g.writeBox} should hug Male", g.writeBox.l in maleL - 12..maleL && g.writeBox.r in maleR..maleR + 12)
        // Unknown gender: glow the whole options area, no value
        val g2 = analyse(bank, Fx.RAMESH.copy(gender = null)).single { it.key == GENDER }
        assertNull(g2.value)
        assertEquals("Tick your gender.", en(g2.say))
        // Female: point at "Female", not at the "male" inside it
        val g3 = analyse(bank, Fx.RAMESH.copy(gender = Gender.FEMALE)).single { it.key == GENDER }
        assertEquals("Female", g3.value)
    }

    @Test fun secretsHaveNoValueAndTheRightWords() {
        val f = analyse(bank)
        val aadhaar = f.single { it.key == AADHAAR }
        assertTrue(aadhaar.sensitive)
        assertNull(aadhaar.value)
        assertEquals("Write your Aadhaar number yourself. Copy it from your card. Don't tell anyone.", en(aadhaar.say))
        assertEquals("आधार नंबर ख़ुद लिखिए। अपने कार्ड से देखकर लिखिए। किसी को मत बताइए।", hi(aadhaar.say))
        val sign = f.single { it.key == SIGNATURE }
        assertTrue(sign.sensitive)
        assertEquals("Sign here.", en(sign.say))
        f.filter { it.sensitive }.forEach { assertNull(it.label, it.value) }
    }

    @Test fun nomineeSectionNeverUsesTheApplicantsDetails() {
        val addr = analyse(bank).single { it.label == "Nominee's Address" }
        assertNull(addr.key)
        assertNull(addr.value)
        assertEquals("This box is for your nominee: “Nominee's Address”. Write their details here.", en(addr.say))
    }

    @Test fun writeBoxGeometry() {
        val f = analyse(bank).associateBy { it.label }
        // Right of the label, over the printed blank: "1. Full Name (in capitals): " is 28 chars, blank runs to char 54
        val name = f.getValue("1. Full Name (in capitals)")
        assertTrue(name.writeBox.l > 80 + 27 * CW && name.writeBox.l < 80 + 30 * CW)
        assertEquals(80 + 54 * CW, name.writeBox.r)
        assertTrue(name.writeBox.t <= 230 && name.writeBox.b >= 230 + LH)
        // Right of the label, up to the next field on the same row (Gender at x=660)
        val dob = f.getValue("3. Date of Birth")
        assertTrue(dob.writeBox.l > 80 + 17 * CW && dob.writeBox.r < 660)
        // A label reaching the edge writes BELOW, over the two ruled lines
        val addr = f.getValue("5. Permanent Address with PIN code (in capital letters)")
        assertEquals(80, addr.writeBox.l)
        assertTrue("below the label", addr.writeBox.t >= 470 + LH)
        assertTrue("covers both ruled lines", addr.writeBox.b >= 590 + LH)
        assertTrue("stops before the Aadhaar row", addr.writeBox.b < 650 + LH)
        // Every box on the page, and never on top of its own label
        for (x in analyse(bank)) {
            val b = x.writeBox
            assertTrue("$b in page", b.l >= 0 && b.t >= 0 && b.r <= W && b.b <= H && b.w > 0 && b.h > 0)
            assertFalse("${x.label}: write box overlaps label", b.l < x.labelBox.r - 2 && b.t < x.labelBox.b - 2 && b.b > x.labelBox.t + 2 && x.key != GENDER)
        }
    }

    @Test fun inputOrderDoesNotMatter() {
        assertEquals(analyse(bank).map { it.label }, analyse(bank.shuffled(java.util.Random(7))).map { it.label })
    }

    @Test fun missingProfileValuesSayWriteItYourself() {
        val f = analyse(bank, Fx.RAMESH.copy(email = "", fatherName = "", spouseName = "")).associateBy { it.label }
        val email = f.getValue("E-mail")
        assertNull(email.value)
        assertEquals("Write your email here. I don't have it saved.", en(email.say))
        assertNull(f.getValue("2. Father's / Husband's Name").value)
    }

    /** A Hindi pension life certificate (जीवन प्रमाण पत्र) with some boxes already filled in by hand. */
    private val pension = listOf(
        line(420, 60, "जीवन प्रमाण पत्र"),
        line(80, 130, "पेंशनभोगी का विवरण"),
        line(80, 190, "नाम: ____________________"),
        line(80, 250, "पिता/पति का नाम:"),
        line(80, 310, "जन्म तिथि: दिन/माह/वर्ष"),
        line(80, 370, "PPO संख्या: ____________"),
        line(80, 430, "मोबाइल नंबर: 98765 43210"),
        line(80, 490, "पता:"),
        line(80, 550, "पिन कोड: □□□□□□"),
        line(80, 610, "आधार संख्या: □□□□ □□□□ □□□□"),
        line(80, 670, "बैंक खाता संख्या:"),
        line(80, 760, "हस्ताक्षर / अंगूठे का निशान: __________"),
        line(80, 820, "दिनांक:"),
        line(660, 820, "स्थान:"),
    )

    @Test fun hindiLifeCertificate() {
        val f = analyse(pension)
        assertEquals(listOf(FULL_NAME, RELATIVE_NAME, DOB, null, ADDRESS, PIN_CODE, AADHAAR, ACCOUNT_NUMBER, SIGNATURE, DATE, PLACE),
            f.map { it.key })
        val m = f.associateBy { it.key }
        assertEquals("यहाँ नाम लिखिए: Ramesh Kumar Sharma।", hi(m.getValue(FULL_NAME).say))
        assertEquals("12 / 05 / 1956", m.getValue(DOB).value)
        assertEquals("500016", m.getValue(PIN_CODE).value)
        assertNull(m.getValue(ACCOUNT_NUMBER).value)
        assertEquals("बैंक खाता संख्या ख़ुद लिखिए। पासबुक से देखकर लिखिए। किसी को मत बताइए।", hi(m.getValue(ACCOUNT_NUMBER).say))
        assertEquals("यहाँ हस्ताक्षर कीजिए।", hi(m.getValue(SIGNATURE).say))
        // PPO number: a real box, but not in the profile
        val ppo = f.single { it.key == null }
        assertNull(ppo.value)
        assertFalse(ppo.sensitive)
        assertEquals("मुझे पक्का नहीं पता कि यहाँ क्या लिखना है: “PPO संख्या”। शक हो तो परिवार से पूछिए।", hi(ppo.say))
        // the handwritten mobile number is already filled: skipped
        assertFalse(f.any { it.label.startsWith("मोबाइल") })
    }

    @Test fun hindiGenderWordOnAHindiForm() {
        val f = analyse(listOf(line(80, 100, "लिंग:"), line(80, 160, "पता:")))
        assertEquals("पुरुष", f.first().value)
    }

    /**
     * A hospital registration form: two labels and handwriting on one line, a label that runs to the edge with
     * handwriting underneath, and OCR words (exact geometry) instead of proportional estimates.
     */
    private val hospital = listOf(
        line(300, 60, "CITY GENERAL HOSPITAL", true),
        line(260, 110, "PATIENT REGISTRATION FORM", true),
        line(40, 180, "Patient Name / मरीज़ का नाम: Ramesh Age: ____ Sex: M / F", true),
        line(40, 240, "Address for correspondence (as in your ration card or Aadhaar):", true),
        line(50, 300, "H.No 12-4-56, Gandhi Road, Ameerpet", true),
        line(40, 360, "Mobile / मोबाइल: ____________", true),
        line(640, 360, "Emergency contact number: ______", true),
        line(40, 420, "Referring Doctor: __________", true),
        line(640, 420, "ABHA / Health ID: ________", true),
        line(40, 520, "Signature of patient / attendant: __________", true),
    )

    @Test fun hospitalForm() {
        val f = analyse(hospital)
        // Name (handwritten "Ramesh") and the address (handwriting below) are already filled.
        assertEquals(listOf(AGE, GENDER, MOBILE, null, null, null, SIGNATURE), f.map { it.key })
        val m = f.associateBy { it.label }
        assertEquals("70", m.getValue("Age").value)
        assertEquals("M", m.getValue("Sex").value)
        assertEquals("Tick “M”.", en(m.getValue("Sex").say))
        assertEquals("9876543210", m.getValue("Mobile / मोबाइल").value)
        // someone else's number / IDs Saathi doesn't have: no value, not a secret
        listOf("Emergency contact number", "Referring Doctor", "ABHA / Health ID").forEach {
            assertNull(it, m.getValue(it).value); assertFalse(it, m.getValue(it).sensitive)
        }
        // the Age blank is only as wide as its printed blank, and ends before "Sex"
        val age = m.getValue("Age")
        val sexX = 40 + "Patient Name / मरीज़ का नाम: Ramesh Age: ____ ".length * CW
        assertTrue("${age.writeBox} before Sex at $sexX", age.writeBox.r <= sexX)
    }

    @Test fun wordGeometryIsExact() {
        // OCR words with real (non-uniform) widths: the label box must follow them, not the character count.
        val l = OcrLine("Name: ______", Box(100, 50, 700, 90),
            listOf(OcrWord("Name:", Box(100, 50, 190, 90)), OcrWord("______", Box(220, 50, 700, 90))))
        val f = PaperForm.analyse(listOf(l), 800, 200, Fx.RAMESH, Fx.TODAY).single()
        assertEquals(190, f.labelBox.r)
        assertEquals(700, f.writeBox.r)
        assertTrue(f.writeBox.l in 190..230)
    }

    @Test fun writeBoxIsClippedToThePhoto() {
        // A label at the very bottom-right: the write area below it would leave the photo.
        val f = PaperForm.analyse(listOf(line(80, 1700, "Permanent address for all correspondence:")),
            1200, 1754, Fx.RAMESH, Fx.TODAY).single()
        assertTrue(f.writeBox.b <= 1754 && f.writeBox.r <= 1200 && f.writeBox.h > 0)
    }

    @Test fun declarationsAndTitlesAreNotFields() {
        val f = analyse(listOf(
            line(80, 100, "APPLICATION FOR NEW LPG CONNECTION"),
            line(80, 160, "I hereby declare that the name and address given above are true to the best of my knowledge."),
            line(80, 220, "Name: ________"),
        ))
        assertEquals(listOf(FULL_NAME), f.map { it.key })
    }

    @Test fun nothingReadable() {
        assertTrue(analyse(emptyList()).isEmpty())
        assertTrue(analyse(listOf(line(80, 100, "   "))).isEmpty())
        // Telugu print comes back from ML Kit as nothing useful; Saathi says so honestly in all three languages.
        val s = PaperForm.nothingFound()
        assertTrue(en(s).contains("Telugu"))
        assertTrue(hi(s).contains("तेलुगु"))
        assertTrue(te(s).contains("తెలుగు"))
    }

    @Test fun segmentsSplitOneLineIntoFields() {
        val segs = PaperForm.segments("Name: Ramesh Age: ____ Sex: M / F").filter { it.isField }
        assertEquals(listOf(FULL_NAME, AGE, GENDER), segs.map { it.cls?.key })
        assertTrue(PaperForm.isFilled(" Ramesh "))
        assertFalse(PaperForm.isFilled(" ____ (in capitals)"))
        assertFalse(PaperForm.isFilled(" DD/MM/YYYY "))
        assertFalse(PaperForm.isFilled(" दिन/माह/वर्ष"))
        assertFalse(PaperForm.isFilled(" □□□□ □□□□ □□□□"))
    }
}
