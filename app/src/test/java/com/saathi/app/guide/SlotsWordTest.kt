package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Field 09:43: "to" inside "photo" made "of" the contact, and "send it to my son" came out empty. */
class SlotsWordTest {
    @Test fun screenshotHasNoContact() = assertNull(SlotExtractor.from("photo of the current screen and send this photo to whatsapp").contact)
    @Test fun photoToMySon() = assertEquals("Ravi", SlotExtractor.from("take a photo and send it to my son on whatsapp", "Ravi").contact)
    @Test fun voiceNoteToAkash() = assertEquals("akash", SlotExtractor.from("send a voice note to akash on whatsapp").contact)
    @Test fun messageAkshay() = assertEquals("akshay", SlotExtractor.from("send a whatsapp message to akshay saying he left his charger").contact)
}
