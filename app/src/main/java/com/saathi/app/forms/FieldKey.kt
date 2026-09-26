package com.saathi.app.forms

import com.saathi.app.guide.Say
import com.saathi.app.guide.say

/**
 * What a form box asks for. [sensitive] keys are never stored, never given a value and never typed by Saathi.
 * [what] is the spoken name, with its possessive ("your name" / "మీ పేరు"); Hindi has none so the sentence
 * stays grammatical for masculine and feminine nouns ("यहाँ नाम लिखिए", "यहाँ जन्म तिथि लिखिए").
 */
enum class FieldKey(val sensitive: Boolean, val what: Say) {
    FULL_NAME(false, say("your name", "नाम", "మీ పేరు")),
    FIRST_NAME(false, say("your first name", "पहला नाम", "మీ మొదటి పేరు")),
    MIDDLE_NAME(false, say("your middle name", "बीच का नाम", "మీ మధ్య పేరు")),
    LAST_NAME(false, say("your surname", "उपनाम", "మీ ఇంటి పేరు")),
    FATHER_NAME(false, say("your father's name", "पिता का नाम", "మీ తండ్రి పేరు")),
    SPOUSE_NAME(false, say("your husband's or wife's name", "पति या पत्नी का नाम", "మీ భర్త లేదా భార్య పేరు")),
    /** "Father's / Husband's name", "S/o, W/o, D/o", "Guardian": father's name, else the spouse's. */
    RELATIVE_NAME(false, say("your father's or husband's name", "पिता या पति का नाम", "మీ తండ్రి లేదా భర్త పేరు")),
    DOB(false, say("your date of birth", "जन्म तिथि", "మీ పుట్టిన తేదీ")),
    AGE(false, say("your age", "उम्र", "మీ వయస్సు")),
    GENDER(false, say("your gender", "लिंग", "మీ లింగం")),
    ADDRESS(false, say("your address", "पता", "మీ చిరునామా")),
    ADDRESS1(false, say("your house number and street", "मकान नंबर और गली", "మీ ఇంటి నంబరు, వీధి")),
    ADDRESS2(false, say("your area", "इलाका", "మీ ప్రాంతం")),
    CITY(false, say("your city or town", "शहर", "మీ ఊరు")),
    DISTRICT(false, say("your district", "ज़िला", "మీ జిల్లా")),
    STATE(false, say("your state", "राज्य", "మీ రాష్ట్రం")),
    PIN_CODE(false, say("your PIN code", "पिन कोड", "మీ పిన్ కోడ్")),
    MOBILE(false, say("your mobile number", "मोबाइल नंबर", "మీ మొబైల్ నంబర్")),
    EMAIL(false, say("your email", "ईमेल", "మీ ఈమెయిల్")),
    OCCUPATION(false, say("your occupation", "काम या व्यवसाय", "మీ వృత్తి")),
    NOMINEE_NAME(false, say("your nominee's name", "नामांकित व्यक्ति का नाम", "మీ నామినీ పేరు")),
    NOMINEE_RELATION(false, say("your nominee's relation to you", "नामांकित व्यक्ति से रिश्ता", "మీ నామినీతో బంధుత్వం")),
    /** "Place" next to the signature: the city. */
    PLACE(false, say("the place", "स्थान", "స్థలం")),
    /** "Date" next to the signature: today. */
    DATE(false, say("today's date", "आज की तारीख", "ఈ రోజు తేదీ")),
    /** Login name for a new account: the person chooses it. */
    USERNAME(false, say("a user name", "यूज़र नेम", "యూజర్ నేమ్")),
    /** The letters in the picture: the person types them. */
    CAPTCHA(false, say("the letters in the picture", "तस्वीर वाले अक्षर", "బొమ్మలోని అక్షరాలు")),

    // ── Never stored, never filled ──
    SIGNATURE(true, say("your signature", "हस्ताक्षर", "మీ సంతకం")),
    AADHAAR(true, say("your Aadhaar number", "आधार नंबर", "మీ ఆధార్ నంబర్")),
    PAN(true, say("your PAN number", "पैन नंबर", "మీ పాన్ నంబర్")),
    ACCOUNT_NUMBER(true, say("your bank account number", "बैंक खाता संख्या", "మీ బ్యాంక్ ఖాతా నంబర్")),
    IFSC(true, say("the IFSC code", "IFSC कोड", "IFSC కోడ్")),
    CARD_NUMBER(true, say("your card number", "कार्ड नंबर", "మీ కార్డ్ నంబర్")),
    CVV(true, say("the CVV", "CVV", "CVV")),
    EXPIRY(true, say("the card's expiry date", "कार्ड की समाप्ति तिथि", "కార్డ్ గడువు తేదీ")),
    UPI_ID(true, say("your UPI ID", "UPI ID", "మీ UPI ID")),
    OTP(true, say("the OTP", "OTP", "OTP")),
    SECRET_PIN(true, say("your PIN", "PIN", "మీ PIN")),
    PASSWORD(true, say("your password", "पासवर्ड", "మీ పాస్‌వర్డ్")),
    SECURITY_ANSWER(true, say("the secret answer", "गुप्त उत्तर", "రహస్య జవాబు")),
}
