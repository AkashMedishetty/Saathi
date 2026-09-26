package com.saathi.app.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model output that must never reach the person (field log 18:27, FastVLM on a pamphlet). */
class TemplatesTest {
    @Test fun loops() {
        assertTrue(Templates.garbled("What is the device? 1000 1200 1300 1400 1500 1600 1700 1800 1900 2000 2100 2200 2300 2400 2500"))
        assertTrue(Templates.garbled("the the the the the the the the the the the the the"))
        assertFalse(Templates.garbled("This is an electricity bill for ₹1,240. Please pay it by 5 October 2026 at the office or online."))
        assertFalse(Templates.garbled("It's a letter from your bank saying your new debit card is on its way. Nothing to pay."))
    }
}
