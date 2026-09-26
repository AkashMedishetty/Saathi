package com.saathi.app.forms

import com.saathi.app.guide.Lang
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The one promise that matters most: secrets are never given a value, never typed, never spoken. */
class FormSafetyTest {
    private val secrets = listOf("1234 5678 9012", "ABCDE1234F", "4111111111111111", "SBIN0001234", "123456789012")

    /** Someone typed secrets into every text box in Settings. */
    private val poisoned = FormProfile(
        fullName = "Ramesh 1234 5678 9012", fatherName = "ABCDE1234F", spouseName = "4111111111111111",
        address1 = "SBIN0001234", address2 = "a/c 123456789012", city = "Hyderabad", state = "Telangana",
        occupation = "PAN ABCDE1234F", nomineeName = "Kamala", mobile = "123456789012", email = "x@y.in", pinCode = "500016",
    )

    private val labels = listOf(
        "Name", "Full name", "First name", "Last name", "Father's name", "Address", "Address line 1", "Area", "City", "State",
        "PIN", "PIN code", "Mobile", "Email", "Occupation", "Nominee", "DOB", "Age", "Password", "OTP", "Enter OTP", "CVV",
        "Card number", "Account number", "IFSC", "Aadhaar number", "PAN", "UPI ID", "UPI PIN", "MPIN", "Security answer",
        "Signature", "Expiry", "Verification code", "Confirm password", null,
    )
    private val types = listOf(null, Fx.TEXT, Fx.TEXT_PASSWORD, Fx.TEXT_WEB_PASSWORD, Fx.NUMBER, Fx.NUMBER_PASSWORD, Fx.PHONE, Fx.EMAIL)
    private val ids = listOf(null, "name", "otp", "et_otp", "mpin", "password", "cvv", "mobile", "aadhaar_no", "pin")

    @Test fun randomOnlineFormsNeverLeakASecret() {
        val rnd = Random(42)
        repeat(400) { round ->
            val fields = (0 until 1 + rnd.nextInt(8)).map { i ->
                Fx.field(labels[rnd.nextInt(labels.size)], hint = labels[rnd.nextInt(labels.size)].takeIf { rnd.nextBoolean() },
                    resId = ids[rnd.nextInt(ids.size)], row = i, password = rnd.nextInt(5) == 0, inputType = types[rnd.nextInt(types.size)])
            }
            for (p in listOf(Fx.RAMESH, poisoned)) for (step in OnlineForm.plan(fields, p, Fx.TODAY)) {
                val n = step.node
                if (n.password || OnlineForm.isPasswordType(n.inputType)) assertTrue("round $round: $n", step.sensitive)
                if (step.sensitive) assertNull("round $round: $n", step.value)
                if (step.key?.sensitive == true) assertTrue(step.sensitive)
                noSecretIn(step.value, "round $round value")
                Lang.entries.forEach { noSecretIn(step.say.getValue(it), "round $round say") }
            }
        }
    }

    @Test fun paperFormsNeverLeakASecret() {
        val lines = labels.filterNotNull().mapIndexed { i, l -> Fx.line(80, 60 + i * 60, "$l: ________") }
        for (p in listOf(Fx.RAMESH, poisoned)) for (f in PaperForm.analyse(lines, 1240, 3000, p, Fx.TODAY)) {
            if (f.sensitive) assertNull(f.label, f.value)
            if (f.key?.sensitive == true) assertTrue(f.label, f.sensitive)
            noSecretIn(f.value, f.label)
            Lang.entries.forEach { noSecretIn(f.say.getValue(it), f.label) }
        }
    }

    @Test fun thePoisonedProfileIsCleaned() {
        val s = poisoned.sanitized()
        assertTrue(s.toMap().values.none { v -> secrets.any { it in v } })
        assertTrue(s.mobile.isEmpty())                 // a 12-digit number is not a mobile
        assertTrue(s.city == "Hyderabad" && s.pinCode == "500016")
    }

    private fun noSecretIn(t: String?, where: String) {
        t ?: return
        secrets.forEach { assertTrue("$where contains $it: $t", it !in t) }
        assertTrue("$where has a 12+ digit run: $t", !Regex("\\d{12,}").containsMatchIn(t.replace(" ", "")))
    }
}
