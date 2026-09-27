package com.saathi.app.forms

import org.junit.Assert.*
import org.junit.Test

class PaperBoxesTest {
    private val labelBox = Box(40, 100, 220, 140)
    private val gridBox = Box(250, 100, 850, 140)
    private val junk = listOf("| | | |", "I I I I", "口口口", "[ ][ ]", "LLLL")
    private fun analyse(lines: List<OcrLine>) = PaperForm.analyse(lines, 1000, 700, Fx.RAMESH, Fx.TODAY)

    @Test fun fiveGridReadingsOnSameRowUseTheGridBox() {
        for (grid in junk) {
            val fields = analyse(listOf(OcrLine("Name in capitals", labelBox), OcrLine(grid, gridBox)))
            assertEquals(grid, 1, fields.size)
            val field = fields.single()
            assertEquals(FieldKey.FULL_NAME, field.key)
            assertEquals("Ramesh Kumar Sharma", field.value)
            assertEquals(grid, gridBox, field.writeBox)
        }
    }

    @Test fun fiveGridReadingsInsideOneOcrLineUseWordGeometry() {
        for (grid in junk) {
            val label = "Name in capitals:"
            val field = analyse(listOf(OcrLine("$label $grid", Box(40, 100, 850, 140),
                listOf(OcrWord(label, labelBox), OcrWord(grid, gridBox))))).single()
            assertEquals(grid, FieldKey.FULL_NAME, field.key)
            assertEquals(labelBox, field.labelBox)
            assertEquals(grid, gridBox, field.writeBox)
        }
    }

    @Test fun gridBelowTheLabelTakesPriorityOverEmptySpaceToTheRight() {
        val below = Box(40, 165, 850, 205)
        for (grid in junk) {
            val field = analyse(listOf(OcrLine("Account No.", labelBox), OcrLine(grid, below))).single()
            assertEquals(grid, below, field.writeBox)
            assertEquals(FieldKey.ACCOUNT_NUMBER, field.key)
            assertTrue(field.sensitive)
            assertNull(field.value)
            assertTrue(Fx.en(field.say).contains("yourself"))
        }
    }

    @Test fun noOcrForBoxesInfersAnAreaToTheRightOfKnownLabel() {
        val f = analyse(listOf(OcrLine("Name in capitals", labelBox))).single()
        assertEquals(FieldKey.FULL_NAME, f.key)
        assertTrue(f.writeBox.l > labelBox.r)
        assertTrue(f.writeBox.r > 850 && f.writeBox.r <= 1000)
        assertTrue(f.writeBox.t <= labelBox.t && f.writeBox.b >= labelBox.b)
    }

    @Test fun emptyOcrSpanWithBoundsUsesThoseBounds() {
        val f = analyse(listOf(OcrLine("Name in capitals", labelBox), OcrLine("", gridBox))).single()
        assertEquals(gridBox, f.writeBox)
    }

    @Test fun postalPinGridCanUseProfileButBarePinRemainsSecret() {
        val postal = analyse(listOf(OcrLine("PIN code", labelBox), OcrLine("口口口", gridBox))).single()
        assertEquals(FieldKey.PIN_CODE, postal.key)
        assertEquals("500016", postal.value)
        assertFalse(postal.sensitive)
        val secret = analyse(listOf(OcrLine("PIN", labelBox), OcrLine("口口口", gridBox))).single()
        assertEquals(FieldKey.SECRET_PIN, secret.key)
        assertNull(secret.value)
        assertTrue(secret.sensitive)
    }

    @Test fun aadhaarAccountAndSignatureKeepManualPrivacyRulesForEveryGrid() {
        for ((label, key) in listOf("Aadhaar No." to FieldKey.AADHAAR,
            "Account No." to FieldKey.ACCOUNT_NUMBER, "Signature" to FieldKey.SIGNATURE)) {
            for (grid in junk) {
                val f = analyse(listOf(OcrLine(label, labelBox), OcrLine(grid, gridBox))).single()
                assertEquals(key, f.key)
                assertTrue(f.sensitive)
                assertNull(f.value)
                assertEquals(if (key == FieldKey.SIGNATURE) "Sign here." else Fx.en(FormSay.paperSecret(key)), Fx.en(f.say))
            }
        }
    }

    @Test fun realWritingAndSimilarWordsAreNotGridJunk() {
        for (text in listOf("Lillian", "BILL", "III", "ILL", "I am ill", "Ramesh", "123456", "口口口 Sharma")) {
            assertTrue(text, analyse(listOf(OcrLine("Name:", labelBox), OcrLine(text, gridBox))).isEmpty())
        }
    }

    @Test fun realWritingMixedWithGridOnOneLineStillCountsAsFilled() {
        for (grid in junk) {
            val line = OcrLine("Name: Ramesh $grid", Box(40, 100, 850, 140))
            assertTrue(grid, analyse(listOf(line)).isEmpty())
        }
    }

    @Test fun handwritingBelowLabelIsNotOverwritten() {
        val lines = listOf(OcrLine("Name:", labelBox), OcrLine("Ramesh Sharma", Box(40, 165, 850, 205)))
        assertTrue(analyse(lines).isEmpty())
    }

    @Test fun adjacentFieldsDoNotStealEachOthersGrid() {
        val a = Box(230, 100, 450, 140)
        val b = Box(700, 100, 950, 140)
        val fields = analyse(listOf(OcrLine("Name:", labelBox), OcrLine("I I I I", a),
            OcrLine("PIN code:", Box(500, 100, 680, 140)), OcrLine("口口口", b)))
        assertEquals(listOf(FieldKey.FULL_NAME, FieldKey.PIN_CODE), fields.map { it.key })
        assertEquals(listOf(a, b), fields.map { it.writeBox })
    }

    @Test fun distantGridIsNotClaimedAsTheNextLine() {
        val f = analyse(listOf(OcrLine("Name", labelBox), OcrLine("LLLL", Box(40, 500, 850, 540)))).single()
        assertTrue(f.writeBox.b < 500)
    }

    @Test fun emptyLinesAloneDoNotInventFields() {
        assertTrue(analyse(listOf(OcrLine("", gridBox))).isEmpty())
        assertTrue(analyse(listOf(OcrLine("LLLL", gridBox))).isEmpty())
    }
    @Test fun gridBelowDoesNotHideHandwritingBesideLabel() {
        val lines = listOf(OcrLine("Name:", labelBox), OcrLine("Ramesh Sharma", gridBox),
            OcrLine("LLLL", Box(40, 165, 850, 205)))
        assertTrue(analyse(lines).isEmpty())
    }

    @Test fun inlineGridWithoutColonStillFindsKnownLabel() {
        for (grid in junk) {
            val field = analyse(listOf(OcrLine("Name in capitals $grid", Box(40, 100, 850, 140),
                listOf(OcrWord("Name in capitals", labelBox), OcrWord(grid, gridBox))))).single()
            assertEquals(FieldKey.FULL_NAME, field.key)
            assertEquals(gridBox, field.writeBox)
        }
    }

    @Test fun gridBoxesAreClippedAtImageEdge() {
        val field = analyse(listOf(OcrLine("Name", labelBox), OcrLine("口口口", Box(250, 100, 1100, 140)))).single()
        assertEquals(Box(250, 100, 1000, 140), field.writeBox)
    }

    @Test fun officeUseGridRemainsExcluded() {
        val fields = analyse(listOf(OcrLine("For office use only", Box(40, 30, 600, 70)),
            OcrLine("Account No.", labelBox), OcrLine("LLLL", gridBox)))
        assertTrue(fields.isEmpty())
    }
    @Test fun followingFieldGridIsNotBorrowedByEarlierInlineLabel() {
        val f = analyse(listOf(OcrLine("Name:                  PIN code:", Box(40, 100, 680, 140),
            listOf(OcrWord("Name:", labelBox), OcrWord("PIN code:", Box(500, 100, 680, 140)))),
            OcrLine("口口口", Box(700, 100, 950, 140))))
        assertEquals(listOf(FieldKey.FULL_NAME, FieldKey.PIN_CODE), f.map { it.key })
        assertTrue(f.first().writeBox.r < 500)
        assertEquals(Box(700, 100, 950, 140), f.last().writeBox)
    }
}
