package com.saathi.app.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DatesAndProfileTest {
    private val dob = LocalDate.of(1956, 5, 12)

    @Test fun dateLayoutsFollowTheHint() {
        fun f(hint: String) = DateFmt.find(hint)!!.format(dob)
        assertEquals("12/05/1956", f("DD/MM/YYYY"))
        assertEquals("12-05-1956", f("dd-mm-yyyy"))
        assertEquals("05/12/1956", f("MM/DD/YYYY"))
        assertEquals("1956-05-12", f("YYYY-MM-DD"))
        assertEquals("12.05.56", f("DD.MM.YY"))
        assertEquals("12-May-1956", f("DD-MMM-YYYY"))
        assertEquals("12-May-1956", f("dd-mon-yyyy"))
        assertEquals("12051956", f("D D M M Y Y Y Y"))           // one character per box
        assertEquals("12 05 1956", f("DD MM YYYY"))
        assertEquals("12/05/1956", f("Date of birth (dd/mm/yyyy)"))
        assertEquals("12/05/1956", f("दिन/माह/वर्ष"))
    }

    @Test fun paperDatesAreSpacedByGroup() {
        assertEquals("12 / 05 / 1956", DateFmt.find("D D M M Y Y Y Y")!!.spaced(dob))
        assertEquals("05 / 12 / 1956", DateFmt.find("mm/dd/yyyy")!!.spaced(dob))
    }

    @Test fun wordsAreNotDateLayouts() {
        assertNull(DateFmt.find("Date of birth"))
        assertNull(DateFmt.find("My name"))
        assertNull(DateFmt.find("Mobile number"))
        assertNull(DateFmt.find(null))
    }

    @Test fun age() {
        assertEquals(70, DateFmt.age(dob, Fx.TODAY))
        assertEquals(69, DateFmt.age(dob, LocalDate.of(2026, 5, 11)))
        assertEquals(70, DateFmt.age(dob, LocalDate.of(2026, 5, 12)))
    }

    @Test fun nameSplit() {
        val p = Fx.RAMESH
        assertEquals(FormProfile.NameParts("Ramesh Kumar", null, "Sharma"), p.nameParts(hasMiddleBox = false))
        assertEquals(FormProfile.NameParts("Ramesh", "Kumar", "Sharma"), p.nameParts(hasMiddleBox = true))
        // Telugu order: family name first, when the person saved it
        val t = FormProfile(fullName = "Medishetty Akash Kumar", surname = "Medishetty")
        assertEquals(FormProfile.NameParts("Akash Kumar", null, "Medishetty"), t.nameParts(false))
        // titles are not names; a single name has no surname
        assertEquals(FormProfile.NameParts("Lakshmi", null, "Devi"), FormProfile(fullName = "Smt. Lakshmi Devi").nameParts(false))
        assertEquals(FormProfile.NameParts("Kamala", null, null), FormProfile(fullName = "Kamala").nameParts(false))
        assertEquals(FormProfile.NameParts(null, null, null), FormProfile().nameParts(false))
    }

    @Test fun fullAddressSkipsARepeatedDistrict() {
        assertEquals("12-4-56, Gandhi Road, Ameerpet, Hyderabad, Telangana - 500016", Fx.RAMESH.fullAddress())
        assertEquals("Guntur, Andhra Pradesh",
            FormProfile(city = "Guntur", district = "guntur", state = "Andhra Pradesh").fullAddress())
        assertNull(FormProfile().fullAddress())
    }

    @Test fun valuesNeverExistForSensitiveKeys() {
        FieldKey.entries.filter { it.sensitive }.forEach {
            assertNull("$it", Fx.RAMESH.valueFor(it, Fx.TODAY))
        }
        assertNull(Fx.RAMESH.valueFor(FieldKey.USERNAME, Fx.TODAY))
        assertNull(Fx.RAMESH.valueFor(FieldKey.CAPTCHA, Fx.TODAY))
        assertEquals("70", Fx.RAMESH.valueFor(FieldKey.AGE, Fx.TODAY))
        assertEquals("27/09/2026", Fx.RAMESH.valueFor(FieldKey.DATE, Fx.TODAY))
        assertEquals("पुरुष", Fx.RAMESH.valueFor(FieldKey.GENDER, Fx.TODAY, com.saathi.app.guide.Lang.HI))
        assertEquals("Suresh Chandra Sharma", Fx.RAMESH.valueFor(FieldKey.RELATIVE_NAME, Fx.TODAY))
        assertEquals("Kamala Devi", FormProfile(spouseName = "Kamala Devi").valueFor(FieldKey.RELATIVE_NAME, Fx.TODAY))
    }

    @Test fun sanitizeDropsSecretsTypedIntoTheWrongBox() {
        val bad = Fx.RAMESH.copy(
            address2 = "Aadhaar 1234 5678 9012",
            occupation = "ABCDE1234F",
            nomineeRelation = "4111-1111-1111-1111",
            fatherName = "SBIN0001234",
            city = "Acct 123456789012",
        ).sanitized()
        assertEquals("", bad.address2)
        assertEquals("", bad.occupation)
        assertEquals("", bad.nomineeRelation)
        assertEquals("", bad.fatherName)
        assertEquals("", bad.city)
        // normal values survive
        assertEquals("12-4-56, Gandhi Road", bad.address1)
        assertEquals("Ramesh Kumar Sharma", bad.fullName)
    }

    @Test fun mobilePinAndEmailAreValidated() {
        assertEquals("9876543210", FormProfile.normaliseMobile("+91 98765-43210"))
        assertEquals("9876543210", FormProfile.normaliseMobile("098765 43210"))
        assertEquals("", FormProfile.normaliseMobile("12345"))
        assertEquals("", FormProfile.normaliseMobile("123456789012")) // an Aadhaar number is not a mobile
        val p = FormProfile(pinCode = "500 016", email = "not an email", mobile = "9876543210").sanitized()
        assertEquals("500016", p.pinCode)
        assertEquals("", p.email)
        assertEquals("", FormProfile(pinCode = "050016").sanitized().pinCode)
    }

    @Test fun mapRoundTrip() {
        val back = FormProfile.fromMap(Fx.RAMESH.toMap())
        assertEquals(Fx.RAMESH, back)
        // no key for any secret exists in storage
        val keys = Fx.RAMESH.toMap().keys
        listOf("aadhaar", "pan", "account", "ifsc", "card", "otp", "password", "pin_secret").forEach { k ->
            assert(keys.none { it.contains(k) }) { k }
        }
        // junk in storage doesn't crash and is dropped
        val junk = FormProfile.fromMap(mapOf("dob" to "yesterday", "gender" to "X", "mobile" to "abc"))
        assertEquals(FormProfile(), junk)
    }
}
