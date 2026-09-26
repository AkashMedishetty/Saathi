package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The routing rules added after eval 1 (26 Sep): acts vs chats, learn mode, forms, Indic app names. */
class TonightRoutingTest {
    @Test fun commandsAreNotQuestions() {
        listOf("Make the text bigger", "Turn on Wi-Fi", "video call my son", "Back up my WhatsApp chats").forEach { assertFalse(it, IntentRouter.phrasedAsQuestion(it)) }
        listOf("what is the gold price today", "how to make upma", "will it rain today?").forEach { assertTrue(it, IntentRouter.phrasedAsQuestion(it)) }
    }

    @Test fun learnMode() {
        listOf("teach me how to use Spotify", "how do I change my ringtone", "show me how to crop a photo", "స్పాటిఫై ఎలా వాడాలి", "इंस्टाग्राम सिखा दो")
            .forEach { assertTrue(it, IntentRouter.wantsToLearn(it)) }
        listOf("play hanuman chalisa", "call my son", "open youtube").forEach { assertFalse(it, IntentRouter.wantsToLearn(it)) }
    }

    @Test fun formHelp() {
        listOf("help me fill this form", "fill the form", "फ़ॉर्म भरो", "ఫారం నింపు", "help me with this form").forEach { assertTrue(it, IntentRouter.isFormHelp(it)) }
        listOf("what is this form about", "open google forms app").forEach { assertFalse(it, IntentRouter.isFormHelp(it)) }
    }

    @Test fun indicAppNames() {
        assertTrue(AppLauncher.latinAppNames("ఇంస్టాగ్రామ్ ఎలా వాడాలి").contains("instagram"))
        assertTrue(AppLauncher.latinAppNames("यूट्यूब खोलो").contains("youtube"))
        assertTrue(AppLauncher.latinAppNames("వాట్సాప్ తెరువు").contains("whatsapp"))
        assertEquals("open the gallery", AppLauncher.latinAppNames("open the gallery"))
    }
}
