package com.saathi.app.policy

import com.saathi.app.guide.Lang
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RedactorTest(private val text: String, private val privateValue: String) {
    @Test fun secretsNeverReachAnySink() {
        assertFalse(Redactor.forLog(text), Redactor.forLog(text).contains(privateValue))
        assertEquals("", Redactor.forMemory(text))
        Lang.entries.forEach { lang -> assertFalse(Redactor.forSpeech(text, lang), Redactor.forSpeech(text, lang).contains(privateValue)) }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "private example {index}") fun cases() = listOf(
            arrayOf("Your OTP is 654321. Do not share it.", "654321"),
            arrayOf("654321 is your OTP for login", "654321"),
            arrayOf("ओटीपी: ६५४३२१ किसी को मत बताना", "६५४३२१"),
            arrayOf("మీ ఓటీపీ ౬౫౪౩౨౧ ఎవరికీ చెప్పకండి", "౬౫౪౩౨౧"),
            arrayOf("CVV 456", "456"), arrayOf("सीवीवी ४५६", "४५६"), arrayOf("సీవీవీ ౪౫౬", "౪౫౬"),
            arrayOf("UPI PIN: 6789", "6789"), arrayOf("पिन ६७८९", "६७८९"), arrayOf("పిన్ ౬౭౮౯", "౬౭౮౯"),
            arrayOf("Aadhaar 2345 6789 0123", "2345 6789 0123"), arrayOf("आधार २३४५ ६७८९ ०१२३", "२३४५ ६७८९ ०१२३"),
            arrayOf("ఆధార్ ౨౩౪౫ ౬౭౮౯ ౦౧౨౩", "౨౩౪౫ ౬౭౮౯ ౦౧౨౩"),
            arrayOf("Card 4111-1111-1111-1111", "4111-1111-1111-1111"),
            arrayOf("कार्ड नंबर 4111111111111111", "4111111111111111"),
            arrayOf("కార్డు నంబర్ 4111 1111 1111 1111", "4111 1111 1111 1111"),
            arrayOf("PAN ABCDE1234F", "ABCDE1234F"), arrayOf("पैन abcde1234f", "abcde1234f"), arrayOf("పాన్ ABCDE1234F", "ABCDE1234F"),
            arrayOf("Account number 123456789", "123456789"), arrayOf("खाता संख्या 123456789", "123456789"),
            arrayOf("ఖాతా సంఖ్య 123456789", "123456789"), arrayOf("IFSC SBIN0001234", "SBIN0001234"),
            arrayOf("Bank code hdfc0001234", "hdfc0001234"), arrayOf("Password: secret-phrase!", "secret-phrase"),
            arrayOf("पासवर्ड: सुरक्षितशब्द", "सुरक्षितशब्द"), arrayOf("పాస్‌వర్డ్: రహస్యపదం", "రహస్యపదం"),
            arrayOf("234567890123", "234567890123"), arrayOf("OTP 1234, CVV 789", "789"),
            arrayOf("Use 123456 as your verification code", "123456"),
            arrayOf("OTP for " + "online service ".repeat(8) + "123456", "123456"),
            arrayOf("Password: secret.phrase!", "secret.phrase"),
            arrayOf("a/c ending XX4321", "4321"), arrayOf("PIN is 1234. OTP is 567890.", "567890"),
        )
    }
}

class RedactorSafeTest {
    @Test fun ordinaryMessagesKeepTheirMeaning() {
        listOf("Meet me at 6:30", "Hyderabad is 26 degrees", "Gold price ₹1,240 per gram", "The year is 2026",
            "कल घर आना", "రేపు ఇంటికి రండి", "Spin the wheel", "The picnic is ready", "My accountant is here").forEach {
            assertEquals(it, it, Redactor.forLog(it))
            assertEquals(it, it, Redactor.forMemory(it))
            assertEquals(it, it, Redactor.forSpeech(it))
        }
    }
    @Test fun multipleSecretsAndIdempotentMasking() {
        val s = "OTP 123456. Card 4111 1111 1111 1111. PAN ABCDE1234F."
        val masked = Redactor.forLog(s)
        listOf("123456", "4111", "ABCDE1234F").forEach { assertFalse(masked.contains(it)) }
        assertEquals(masked, Redactor.forLog(masked))
    }
    @Test fun numericOnlySinksFailClosed() {
        assertEquals("", Redactor.forMemory("123456"))
        assertFalse(Redactor.forLog("123456").contains("123456"))
        assertFalse(Redactor.forSpeech("123456").contains("123456"))
    }
}
