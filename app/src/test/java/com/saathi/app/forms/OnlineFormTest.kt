package com.saathi.app.forms

import com.saathi.app.forms.FieldKey.*
import com.saathi.app.forms.Fx.en
import com.saathi.app.forms.Fx.field
import com.saathi.app.forms.Fx.hi
import com.saathi.app.forms.Fx.te
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineFormTest {
    private fun plan(fields: List<FieldNode>, p: FormProfile = Fx.RAMESH) = OnlineForm.plan(fields, p, Fx.TODAY)

    /** IRCTC-like registration. Given out of order: the plan must follow the screen. */
    private val irctc = listOf(
        field("Password", row = 1, password = true, inputType = Fx.TEXT_PASSWORD),
        field("User Name", hint = "User Name", resId = "userName", row = 0),
        field("Confirm Password", row = 2, password = true, inputType = Fx.TEXT_PASSWORD),
        field("Security Answer", row = 3),
        field("First Name", resId = "firstName", row = 4, col = 0),
        field("Middle Name", resId = "middleName", row = 4, col = 1),
        field("Last Name", resId = "lastName", row = 5),
        field("Date of Birth", hint = "DD-MM-YYYY", resId = "dob", row = 6),
        field("Email", row = 7, inputType = Fx.EMAIL),
        field("Mobile", hint = "Mobile Number", resId = "mobileNo", row = 8, inputType = Fx.PHONE),
        field("Flat/Door/Block No.", row = 9),
        field("Area/Locality", row = 10),
        field("Pincode", row = 11, inputType = Fx.NUMBER),
        field("City/Town", row = 12),
        field("State", row = 13),
        field(null, hint = "Enter Captcha", resId = "captcha", row = 14),
    )

    @Test fun irctcMappingAndOrder() {
        val p = plan(irctc)
        assertEquals(listOf(USERNAME, PASSWORD, PASSWORD, SECURITY_ANSWER, FIRST_NAME, MIDDLE_NAME, LAST_NAME, DOB, EMAIL,
            MOBILE, ADDRESS1, ADDRESS2, PIN_CODE, CITY, STATE, CAPTCHA), p.map { it.key })
        assertEquals(listOf(null, null, null, null, "Ramesh", "Kumar", "Sharma", "12-05-1956", "ramesh.sharma56@gmail.com",
            "9876543210", "12-4-56, Gandhi Road", "Ameerpet", "500016", "Hyderabad", "Telangana", null), p.map { it.value })
        assertEquals(listOf(false, true, true, true), p.take(4).map { it.sensitive })
    }

    @Test fun irctcWords() {
        val p = plan(irctc).associateBy { it.node.label }
        assertEquals("This box is for your first name: Ramesh. Press “Do it for me” and I'll type it.", en(p.getValue("First Name").say))
        assertEquals("इस ख़ाने में पहला नाम लिखना है: Ramesh। “आप कर दो” दबाइए, मैं लिख दूँगा।", hi(p.getValue("First Name").say))
        assertEquals("ఈ గడిలో మీ మొదటి పేరు రాయాలి: Ramesh. “మీరే చేయండి” నొక్కండి, నేను టైప్ చేస్తాను.", te(p.getValue("First Name").say))
        assertEquals("Type your password yourself. Don't tell anyone.", en(p.getValue("Password").say))
        assertEquals("Choose a user name and type it here. Write it in your diary too.", en(p.getValue("User Name").say))
        assertEquals("Type the letters you see in the picture.", en(plan(irctc).last().say))
        assertEquals("This box is for your mobile number: 98765 43210. Press “Do it for me” and I'll type it.",
            en(p.getValue("Mobile").say))
    }

    @Test fun nameSplitWithoutAMiddleBox() {
        val p = plan(listOf(field("First Name", row = 0), field("Last Name", row = 1)))
        assertEquals(listOf("Ramesh Kumar", "Sharma"), p.map { it.value })
    }

    /** A hospital appointment form: an OTP field, a vague date, optional Aadhaar, a long helpful hint. */
    private val hospital = listOf(
        field("Patient Name", row = 0),
        field("Age", row = 1, col = 0, inputType = Fx.NUMBER),
        field("Gender", row = 1, col = 1),
        field("Mobile Number", hint = "OTP will be sent to this number", row = 2, inputType = Fx.PHONE),
        field(null, hint = "Enter OTP", row = 3, inputType = Fx.NUMBER),
        field("Preferred date", hint = "YYYY-MM-DD", row = 4),
        field("Aadhaar Number (optional)", row = 5, inputType = Fx.NUMBER),
        field("Aadhaar-linked mobile number", row = 6, inputType = Fx.PHONE),
        field("Emergency contact number", row = 7, inputType = Fx.PHONE),
    )

    @Test fun hospitalAppointment() {
        val p = plan(hospital)
        assertEquals(listOf(FULL_NAME, AGE, GENDER, MOBILE, OTP, null, AADHAAR, MOBILE, null), p.map { it.key })
        assertEquals(listOf("Ramesh Kumar Sharma", "70", "Male", "9876543210", null, null, null, "9876543210", null), p.map { it.value })
        assertEquals("Type the OTP yourself. Never tell it to anyone, even on a call.", en(p[4].say))
        assertTrue(p[4].sensitive)
        assertTrue(p[6].sensitive)
        // An appointment date is not the date of birth: Saathi doesn't guess
        assertEquals("I'm not sure what this box is for: “Preferred date”. Please type it yourself.", en(p[5].say))
    }

    /** A Google-account-like sign-up. Password flag missing (as some web views do): the inputType still catches it. */
    private val google = listOf(
        field("First name", row = 0),
        field("Surname (optional)", row = 1),
        field("Day", row = 2, col = 0, inputType = Fx.NUMBER),
        field("Year", row = 2, col = 1, inputType = Fx.NUMBER),
        field("Create a Gmail address", row = 3),
        field("Password", row = 4, inputType = Fx.TEXT_WEB_PASSWORD),
        field("Confirm", row = 5, inputType = Fx.TEXT_WEB_PASSWORD),
        field("Phone number", row = 6, inputType = Fx.PHONE),
        field("Recovery email address (optional)", row = 7, inputType = Fx.EMAIL),
        field(null, hint = "Enter code", row = 8, inputType = Fx.NUMBER),
    )

    @Test fun googleSignUp() {
        val p = plan(google)
        assertEquals(listOf(FIRST_NAME, LAST_NAME, null, null, USERNAME, PASSWORD, PASSWORD, MOBILE, EMAIL, OTP), p.map { it.key })
        assertEquals("Ramesh Kumar", p[0].value)
        assertEquals("Sharma", p[1].value)
        // Separate Day / Year boxes are not filled (documented: split date boxes are unsupported).
        assertNull(p[2].value); assertNull(p[3].value)
        assertTrue(p[5].sensitive && p[6].sensitive && p[9].sensitive)
    }

    @Test fun cardFormFillsOnlyTheName() {
        val p = plan(listOf(
            field("Name on card", row = 0), field("Card Number", row = 1, inputType = Fx.NUMBER),
            field("Expiry (MM/YY)", row = 2, col = 0), field("CVV", row = 2, col = 1, inputType = Fx.NUMBER_PASSWORD),
            field("UPI ID", row = 3), field("UPI PIN", row = 4, inputType = Fx.NUMBER_PASSWORD)))
        assertEquals(listOf("Ramesh Kumar Sharma", null, null, null, null, null), p.map { it.value })
        assertEquals(listOf(false, true, true, true, true, true), p.map { it.sensitive })
    }

    @Test fun dateFormatsFollowTheHint() {
        fun dob(hint: String?) = plan(listOf(field("Date of Birth", hint = hint, row = 0))).single().value
        assertEquals("12/05/1956", dob(null))
        assertEquals("12-05-1956", dob("DD-MM-YYYY"))
        assertEquals("05/12/1956", dob("MM/DD/YYYY"))
        assertEquals("1956-05-12", dob("YYYY-MM-DD"))
        assertEquals("12-May-1956", dob("DD-MMM-YYYY"))
    }

    @Test fun barePinIsPostalOnlyOnAnAddressForm() {
        val address = plan(listOf(field("Address", row = 0), field("PIN", row = 1, inputType = Fx.NUMBER)))
        assertEquals(PIN_CODE, address[1].key)
        assertEquals("500016", address[1].value)
        val payment = plan(listOf(field("Amount", row = 0), field("PIN", row = 1, inputType = Fx.NUMBER)))
        assertEquals(SECRET_PIN, payment[1].key)
        assertNull(payment[1].value)
    }

    @Test fun hindiAndTeluguLabels() {
        val p = plan(listOf(field("नाम", row = 0), field("मोबाइल नंबर", row = 1), field("పేరు", row = 2), field("ఓటీపీ", row = 3)))
        assertEquals(listOf(FULL_NAME, MOBILE, FULL_NAME, OTP), p.map { it.key })
        assertNull(p[3].value)
    }

    @Test fun missingValueAndUnlabelledField() {
        val p = plan(listOf(field("Email", row = 0), field(null, row = 1)), Fx.RAMESH.copy(email = ""))
        assertNull(p[0].value)
        assertEquals("This box is for your email. I don't have it saved, so please type it.", en(p[0].say))
        assertNull(p[1].key)
        assertEquals("I can't tell what this box is for. Ask your family if you're unsure.", en(p[1].say))
        // an unlabelled email-type box is still recognised
        assertEquals(EMAIL, plan(listOf(field(null, row = 0, inputType = Fx.EMAIL))).single().key)
    }

    @Test fun passwordFlagAlwaysWins() {
        // Mislabelled or misleading fields: the password flag / id make them secret, never filled.
        val p = plan(listOf(
            field("Full name", row = 0, password = true),
            field("Mobile number", resId = "otp_input", row = 1),
            field("Email", row = 2, inputType = Fx.TEXT_PASSWORD),
            field("PIN code", row = 3, inputType = Fx.NUMBER_PASSWORD),
        ))
        p.forEach { assertTrue("${it.node.label} sensitive", it.sensitive); assertNull(it.node.label, it.value) }
        p.forEach { assertTrue(it.key!!.sensitive) }
    }

    @Test fun readingOrderByRowsThenLeftToRight() {
        val a = field("Age", row = 1, col = 1); val b = field("Name", row = 0); val c = field("Gender", row = 1, col = 0)
        assertEquals(listOf(b, c, a), plan(listOf(a, b, c)).map { it.node })
    }

    /** A Chrome web form as the accessibility tree dump shows it: labels are separate TextViews, not labeledBy. */
    private val chromeForm = """
FrameLayout t=null d=null id=null  ri=null acts=4,8,64 Rect(0, 0 - 1440, 3000)
  WebView t=Book appointment d=null id=null S ri=null acts=4,8,64 Rect(0, 300 - 1440, 3000)
    TextView t=Full name d=null id=null  ri=null acts=4,8,64 Rect(60, 400 - 400, 460)
    EditText t=null d=null id=null CF ri=null acts=1,4,8,16,64 Rect(60, 470 - 1380, 590)
    TextView t=Mobile d=null id=null  ri=null acts=4,8,64 Rect(60, 640 - 300, 700)
    EditText t=null d=null id=null CF ri=null acts=1,4,8,16,64 Rect(60, 710 - 1380, 830)
    TextView t=PIN code d=null id=null  ri=null acts=4,8,64 Rect(60, 900 - 340, 1020)
    EditText t=null d=null id=pincode CF ri=null acts=1,4,8,16,64 Rect(360, 900 - 1380, 1020)
    TextView t=OTP d=null id=null  ri=null acts=4,8,64 Rect(60, 1080 - 200, 1140)
    EditText t=null d=null id=null CF ri=null acts=1,4,8,16,64 Rect(60, 1150 - 1380, 1270)
    Button t=Submit d=null id=null CF ri=null acts=1,4,8,16,64 Rect(60, 1400 - 1380, 1520)
""".trimIndent()

    @Test fun labelFinderOnATreeFixture() {
        val fields = TreeFixture.fields(TreeFixture.parse(chromeForm))
        assertEquals(listOf("Full name", "Mobile", "PIN code", "OTP"), fields.map { it.label })
        val p = plan(fields)
        assertEquals(listOf(FULL_NAME, MOBILE, PIN_CODE, OTP), p.map { it.key })
        assertNull(p.last().value)
    }

    @Test fun labelFinderPrefersTheNearestAndSkipsAcrossInputs() {
        val f1 = Box(60, 100, 600, 160); val f2 = Box(60, 260, 600, 320)
        val texts = listOf("Name" to Box(60, 40, 200, 90), "Email" to Box(60, 200, 200, 250))
        assertEquals("Name", LabelFinder.find(f1, texts, listOf(f1, f2)))
        assertEquals("Email", LabelFinder.find(f2, texts, listOf(f1, f2)))
        // no text above f2 except across f1 → nothing
        assertNull(LabelFinder.find(f2, listOf("Name" to Box(60, 40, 200, 90)), listOf(f1, f2)))
        // left on the same row wins when it is nearer
        val f3 = Box(300, 400, 900, 460)
        assertEquals("City", LabelFinder.find(f3, listOf("City" to Box(100, 405, 280, 455), "far" to Box(300, 300, 500, 340)), listOf(f3)))
    }

    @Test fun passwordInputTypes() {
        assertTrue(OnlineForm.isPasswordType(Fx.TEXT_PASSWORD))
        assertTrue(OnlineForm.isPasswordType(Fx.TEXT_WEB_PASSWORD))
        assertTrue(OnlineForm.isPasswordType(0x91)) // visible password
        assertTrue(OnlineForm.isPasswordType(Fx.NUMBER_PASSWORD))
        assertFalse(OnlineForm.isPasswordType(Fx.TEXT))
        assertFalse(OnlineForm.isPasswordType(Fx.EMAIL))
        assertFalse(OnlineForm.isPasswordType(Fx.NUMBER))
        assertFalse(OnlineForm.isPasswordType(null))
    }
}
