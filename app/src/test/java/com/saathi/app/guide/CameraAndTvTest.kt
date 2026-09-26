package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thing is in their hand → camera reader; "watch X on TV" → the coach looks X up first. */
class CameraAndTvTest {
    @Test fun cameraRead() {
        listOf("read this letter for me", "what does this paper say", "what's written here", "read the bill",
            "क्या लिखा है", "यह पढ़ो", "ఏం రాసి ఉంది", "ఇది చదువు").forEach { assertEquals(it, "read_this", IntentRouter.cameraRead(it)) }
        listOf("scan my medicine strip", "दवा का पत्ता").forEach { assertEquals(it, "scan_medicine", IntentRouter.cameraRead(it)) }
        listOf("read the news", "will it rain today", "open whatsapp", "read my messages").forEach { assertEquals(it, null, IntentRouter.cameraRead(it)) }
    }

    @Test fun watchOnTv() {
        assertTrue(Coach.isWatchOnTv("play guntur karam movie on my tv"))
        assertTrue(Coach.isWatchOnTv("टीवी पर गुंटूर कारम लगाओ"))
        assertFalse(Coach.isWatchOnTv("the tv is not turning on"))
        assertEquals("guntur karam movie", Coach.title("play guntur karam movie on my tv"))
    }
}
