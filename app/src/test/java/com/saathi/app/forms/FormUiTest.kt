package com.saathi.app.forms

import com.saathi.app.forms.FormUi.Detail
import com.saathi.app.forms.FormUi.Dropped
import com.saathi.app.forms.FormUi.Frame
import com.saathi.app.guide.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FormUiTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    // ── the photo check ──

    @Test fun darkFramesAreRejected() {
        assertEquals(Frame.DARK, FormUi.frame(DoubleArray(1000) { 0.0 }))                   // lens covered / pocket
        val rnd = Random(1)
        assertEquals(Frame.DARK, FormUi.frame(DoubleArray(1000) { rnd.nextDouble() * 40 })) // night room: noisy but mean ≈ 20
        assertEquals(Frame.DARK, FormUi.frame(DoubleArray(10) { 24.9 }))
    }

    @Test fun flatFramesAreBlank() {
        assertEquals(Frame.BLANK, FormUi.frame(DoubleArray(1000) { 200.0 }))  // a wall / white table
        assertEquals(Frame.BLANK, FormUi.frame(DoubleArray(1000) { 255.0 }))  // blown out
        assertEquals(Frame.BLANK, FormUi.frame(DoubleArray(0)))
    }

    @Test fun aPrintedPageIsOk() {
        // White paper (230) with ~12% dark print (40): the shape of a real form at 160 px wide.
        val rnd = Random(2)
        val page = DoubleArray(160 * 213) { if (rnd.nextInt(100) < 12) 40.0 else 230.0 }
        assertEquals(Frame.OK, FormUi.frame(page))
        // A dim but readable photo (mean ≈ 60) is still OK: only really dark frames are refused.
        val dim = DoubleArray(160 * 213) { if (rnd.nextInt(100) < 12) 10.0 else 65.0 }
        assertEquals(Frame.OK, FormUi.frame(dim))
    }

    @Test fun lumaFromArgb() {
        assertEquals(0.0, FormUi.luminance(gray(0)), 0.01)
        assertEquals(255.0, FormUi.luminance(gray(255)), 0.01)
        assertEquals(76.2, FormUi.luminance(0xFFFF0000.toInt()), 0.1)   // pure red, Rec. 601
        assertEquals(149.7, FormUi.luminance(0xFF00FF00.toInt()), 0.1)  // pure green
        assertEquals(Frame.DARK, FormUi.frame(FormUi.luma(IntArray(100) { gray(10) })))
    }

    @Test fun framesSpeakAllThreeLanguages() {
        listOf(Frame.DARK, Frame.BLANK).forEach { f ->
            Lang.entries.forEach { l -> assertTrue("$f $l", FormUi.frameSay(f).getValue(l).isNotBlank()) }
        }
        assertTrue(FormUi.frameSay(Frame.DARK).getValue(Lang.EN).startsWith("It's too dark"))
    }

    // ── walking through the fields ──

    private val fields = PaperForm.analyse(listOf(
        Fx.line(80, 100, "Name: ______________"),
        Fx.line(80, 160, "Aadhaar No.: □□□□ □□□□ □□□□"),
        Fx.line(80, 220, "Mobile: ____________"),
    ), 1240, 1754, Fx.RAMESH, Fx.TODAY)

    @Test fun walkBackAndNextStayInBounds() {
        var w = FormUi.Walk(fields)
        assertTrue(w.isFirst); assertFalse(w.isLast)
        assertEquals(w, w.back())                          // Back on the first box does nothing
        w = w.next().next()
        assertTrue(w.isLast)
        assertEquals(w, w.next())                          // Next on the last box does nothing (the screen shows Done)
        assertEquals(1, w.back().index)
    }

    @Test fun progressInThreeLanguages() {
        val w = FormUi.Walk(fields).next()
        assertEquals("Field 2 of 3", w.progress(Lang.EN))
        assertEquals("3 में से ख़ाना 2", w.progress(Lang.HI))
        assertEquals("3 లో గడి 2", w.progress(Lang.TE))
    }

    @Test fun bigValueIsNeverShownForASecret() {
        val w = FormUi.Walk(fields)
        assertEquals("Ramesh Kumar Sharma", w.bigValue)
        assertNull(w.next().bigValue)                      // Aadhaar: nothing to copy
        assertEquals("9876543210", w.next().next().bigValue)
        assertNull(FormUi.Walk(emptyList()).bigValue)
        assertNull(FormUi.Walk(emptyList()).current)
    }

    // ── zooming the photo to a field ──

    @Test fun viewportFramesTheFieldInsideThePhoto() {
        val label = Box(80, 800, 300, 836); val write = Box(310, 790, 1100, 846)
        val v = FormUi.viewport(label, write, 1536, 2048, 1080, 900)
        assertTrue("inside: $v", v.l >= 0 && v.t >= 0 && v.r <= 1536 && v.b <= 2048)
        assertTrue("contains the label and the box: $v", v.l <= 80 && v.r >= 1100 && v.t <= 790 && v.b >= 846)
        assertEquals(1080f / 900f, v.w.toFloat() / v.h, 0.02f)       // the view's shape, so nothing is squashed
        assertTrue("keeps page context", v.w >= 1536 * 0.6f - 1)
    }

    @Test fun viewportAtTheEdgeIsShiftedIn() {
        val v = FormUi.viewport(Box(1300, 1990, 1450, 2030), Box(1460, 1985, 1530, 2040), 1536, 2048, 1080, 900)
        assertTrue(v.r <= 1536 && v.b <= 2048 && v.l >= 0 && v.t >= 0)
        assertTrue(v.r >= 1530 && v.b >= 2040)
    }

    @Test fun viewportFallsBackToTheWholePhoto() {
        // A box as big as the page can't be zoomed.
        assertEquals(Box(0, 0, 1000, 1000), FormUi.viewport(Box(0, 0, 900, 50), Box(0, 60, 1000, 990), 1000, 1000, 1080, 900))
        assertEquals(Box(0, 0, 1000, 1000), FormUi.viewport(Box(0, 0, 1, 1), Box(0, 0, 1, 1), 1000, 1000, 0, 0))
    }

    // ── "My details" ──

    @Test fun profileEmptiness() {
        assertTrue(FormUi.profileIsEmpty(FormProfile()))
        assertTrue(FormUi.profileIsEmpty(FormProfile(occupation = "Retired")))          // nothing a form mostly needs
        assertTrue(FormUi.profileIsEmpty(FormProfile(fullName = "1234 5678 9012")))     // a secret doesn't count
        assertFalse(FormUi.profileIsEmpty(FormProfile(fullName = "Kamala")))
        assertFalse(FormUi.profileIsEmpty(Fx.RAMESH))
    }

    @Test fun everyDetailRoundTrips() {
        var p = FormProfile()
        Detail.entries.forEach { d -> p = FormUi.set(p, d, "x-${d.name}") }
        Detail.entries.forEach { d -> assertEquals("x-${d.name}", FormUi.get(p, d)) }
        Detail.entries.forEach { d -> Lang.entries.forEach { l -> assertTrue(d.label.getValue(l).isNotBlank()) } }
    }

    @Test fun droppedTellsSecretsFromTypos() {
        val raw = Fx.RAMESH.copy(address2 = "Aadhaar 1234 5678 9012", occupation = "ABCDE1234F", mobile = "12345", email = "ramesh@", pinCode = "50001")
        val d = FormUi.dropped(raw)
        assertEquals(mapOf(Detail.ADDRESS2 to Dropped.SECRET, Detail.OCCUPATION to Dropped.SECRET,
            Detail.MOBILE to Dropped.INVALID, Detail.EMAIL to Dropped.INVALID, Detail.PIN to Dropped.INVALID), d)
        val say = FormUi.droppedSay(d)!!
        assertEquals("Saathi never stores Aadhaar, PAN or bank numbers, so I left that out. Please check: PIN code, Mobile number, Email.",
            say.getValue(Lang.EN))
        assertTrue(say.getValue(Lang.HI).startsWith("साथी आधार"))
        // an Aadhaar number typed as the mobile number is a secret, not a typo
        assertEquals(Dropped.SECRET, FormUi.dropped(FormProfile(mobile = "1234 5678 9012"))[Detail.MOBILE])
        // clean input: nothing to say
        assertTrue(FormUi.dropped(Fx.RAMESH).isEmpty())
        assertNull(FormUi.droppedSay(emptyMap()))
        // "+91 98765 43210" is a fine mobile number, not dropped
        assertTrue(FormUi.dropped(FormProfile(mobile = "+91 98765 43210")).isEmpty())
    }

    @Test fun screenWordsExistInAllLanguages() {
        listOf(FormUi.introSay(), FormUi.foundSay(7), FormUi.lastFieldSay(), FormUi.profileEmptySay()).forEach { s ->
            Lang.entries.forEach { l -> assertTrue(s.getValue(l).isNotBlank()) }
        }
        assertTrue(FormUi.foundSay(7).getValue(Lang.HI).contains("7"))
    }
}
