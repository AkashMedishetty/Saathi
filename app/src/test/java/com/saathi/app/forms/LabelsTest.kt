package com.saathi.app.forms

import com.saathi.app.forms.FieldKey.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    private fun key(t: String, postal: Boolean = true, nominee: Boolean = false) =
        Labels.classify(t)?.let { Labels.resolve(it, postal, nominee) }

    @Test fun englishLabels() {
        val cases = mapOf(
            "Full Name" to FULL_NAME, "1. Name of the Applicant (in capitals):" to FULL_NAME, "Applicant's Name" to FULL_NAME,
            "First Name" to FIRST_NAME, "Given name" to FIRST_NAME, "Middle Name" to MIDDLE_NAME,
            "Last Name" to LAST_NAME, "Surname" to LAST_NAME,
            "Father's Name" to FATHER_NAME, "Father’s Name" to FATHER_NAME, "Husband's Name" to SPOUSE_NAME,
            "Father's / Husband's Name" to RELATIVE_NAME, "S/o, W/o, D/o" to RELATIVE_NAME, "Guardian's name" to RELATIVE_NAME,
            "Date of Birth" to DOB, "DOB" to DOB, "D.O.B." to DOB, "Birth date" to DOB, "Age" to AGE, "Gender" to GENDER, "Sex" to GENDER,
            "Address" to ADDRESS, "Permanent Address" to ADDRESS, "Address with PIN code" to ADDRESS,
            "Address Line 1" to ADDRESS1, "Flat/Door/Block No." to ADDRESS1, "Address Line 2" to ADDRESS2, "Area/Locality" to ADDRESS2,
            "City/Town" to CITY, "Village" to CITY, "District" to DISTRICT, "State" to STATE,
            "Pincode" to PIN_CODE, "PIN Code" to PIN_CODE, "Postal code" to PIN_CODE,
            "Mobile No." to MOBILE, "Phone number" to MOBILE, "Contact No" to MOBILE, "E-mail" to EMAIL, "Email address" to EMAIL,
            "Occupation" to OCCUPATION, "Name of Nominee" to NOMINEE_NAME, "Nominee's relationship" to NOMINEE_RELATION,
            "Place" to PLACE, "Date" to DATE, "Date:" to DATE, "Signature" to SIGNATURE, "Thumb impression" to SIGNATURE,
            "User Name" to USERNAME, "Create a Gmail address" to USERNAME, "Enter Captcha" to CAPTCHA,
            "Enter the code shown in the image" to CAPTCHA,
        )
        val wrong = cases.filter { (t, k) -> key(t) != k }.map { (t, k) -> "$t → ${key(t)} (want $k)" }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun hindiLabels() {
        val cases = mapOf(
            "नाम" to FULL_NAME, "आवेदक का नाम" to FULL_NAME, "पिता का नाम" to FATHER_NAME, "पति का नाम" to SPOUSE_NAME,
            "पिता/पति का नाम" to RELATIVE_NAME, "जन्म तिथि" to DOB, "जन्म की तारीख" to DOB, "उम्र" to AGE, "लिंग" to GENDER,
            "पता" to ADDRESS, "पिन कोड" to PIN_CODE, "मोबाइल नंबर" to MOBILE, "ईमेल" to EMAIL, "ज़िला" to DISTRICT, "जिला" to DISTRICT,
            "राज्य" to STATE, "शहर" to CITY, "व्यवसाय" to OCCUPATION, "नामांकित व्यक्ति का नाम" to NOMINEE_NAME,
            "हस्ताक्षर" to SIGNATURE, "अंगूठे का निशान" to SIGNATURE, "आधार संख्या" to AADHAAR, "पैन" to PAN,
            "खाता संख्या" to ACCOUNT_NUMBER, "दिनांक" to DATE, "स्थान" to PLACE,
        )
        val wrong = cases.filter { (t, k) -> key(t) != k }.map { (t, k) -> "$t → ${key(t)} (want $k)" }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun secretsAreSensitive() {
        val cases = mapOf(
            "OTP" to OTP, "Enter OTP" to OTP, "Mobile OTP" to OTP, "Email OTP" to OTP, "One Time Password" to OTP,
            "Enter 6-digit code" to OTP, "Password" to PASSWORD, "Confirm Password" to PASSWORD, "पासवर्ड" to PASSWORD,
            "CVV" to CVV, "Card Number" to CARD_NUMBER, "Debit card number" to CARD_NUMBER, "Expiry (MM/YY)" to EXPIRY,
            "UPI PIN" to SECRET_PIN, "MPIN" to SECRET_PIN, "Enter PIN" to SECRET_PIN, "ATM PIN" to SECRET_PIN, "4-digit PIN" to SECRET_PIN,
            "UPI ID" to UPI_ID, "Aadhaar Number" to AADHAAR, "Aadhar No" to AADHAAR, "PAN" to PAN, "PAN Card No." to PAN,
            "Account Number" to ACCOUNT_NUMBER, "A/c No." to ACCOUNT_NUMBER, "Bank account" to ACCOUNT_NUMBER,
            "IFSC Code" to IFSC, "Security answer" to SECURITY_ANSWER, "Mother's maiden name" to SECURITY_ANSWER,
        )
        val wrong = cases.filter { (t, k) -> key(t) != k }.map { (t, k) -> "$t → ${key(t)} (want $k)" }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
        cases.values.forEach { assertTrue("$it must be sensitive", it.sensitive) }
    }

    @Test fun orderResolvesTheTrickyOnes() {
        // names that mention an ID are still names
        assertEquals(FULL_NAME, key("Name as per Aadhaar"))
        assertEquals(FULL_NAME, key("Account holder name"))
        assertEquals(FULL_NAME, key("Name on card"))
        // a mobile number linked to Aadhaar is a mobile number, not an Aadhaar number
        assertEquals(MOBILE, key("Aadhaar-linked mobile number"))
        // other people's details and non-person names have no key (never filled from the profile)
        assertNull(key("Mother's Name"))
        assertNull(key("Bank Name"))
        assertNull(key("Name of the Branch"))
        assertNull(key("Emergency contact number"))
        assertNull(key("Alternate mobile"))
        assertNull(key("Nominee's address"))
        assertNull(key("Ration card number"))
        assertNull(key("PPO No."))
        assertNull(key("Relationship"))                         // outside a nominee section
        assertEquals(NOMINEE_RELATION, key("Relationship", nominee = true))
        // a bare PIN: postal on an address form, secret anywhere else
        assertEquals(PIN_CODE, key("PIN", postal = true))
        assertEquals(SECRET_PIN, key("PIN", postal = false))
        assertEquals(SECRET_PIN, key("UPI PIN", postal = true)) // never postal, whatever the form
        // not labels at all
        assertNull(Labels.classify("Submit"))
        assertNull(Labels.classify(""))
    }

    @Test fun normaliseAndIdWords() {
        assertEquals("father's name", Labels.normalise("2. Father’s Name (in capitals) :-"))
        assertEquals("name", Labels.normalise("(a) Name *"))
        assertEquals("mobile no", Labels.idWords("com.irctc:id/mobileNo"))
        assertEquals("et first name", Labels.idWords("et_first_name"))
        assertEquals(OTP, key(Labels.idWords("otp_input")))
        assertEquals(PIN_CODE, key(Labels.idWords("pinCode")))
    }
}
