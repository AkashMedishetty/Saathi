package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words of a WhatsApp message, in all three languages (field: Hindi/Telugu messages were typed empty). */
class MessageTextTest {
    @Test fun english() {
        assertEquals("I reached home", SlotExtractor.from("send a whatsapp message to my son saying I reached home").text)
    }

    @Test fun hindi() {
        assertEquals("मैं घर पहुँच गया", SlotExtractor.from("बेटे को मैसेज भेजो कि मैं घर पहुँच गया").text)
        assertEquals("मैं घर पहुँच गया", SlotExtractor.from("बेटे को मैसेज भेजो मैं घर पहुँच गया").text)
    }

    @Test fun teluguWordsFirst() {
        assertEquals("నేను ఇంటికి చేరుకున్నాను", SlotExtractor.from("అబ్బాయికి నేను ఇంటికి చేరుకున్నాను అని మెసేజ్ పంపు").text)
    }

    @Test fun teluguMessageVerbFirst() {
        assertEquals("నేను ఇంటికి చేరుకున్నాను", SlotExtractor.from("అబ్బాయికి మెసేజ్ పంపు నేను ఇంటికి చేరుకున్నాను అని").text)
    }

    @Test fun noTextWhenNoneSaid() {
        assertEquals(null, SlotExtractor.from("message my daughter").text)
        assertEquals(null, SlotExtractor.from("బేటీకి మెసేజ్ పంపు").text)
    }
}
