package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslitTest {
    @Test fun teluguAkash() = assertEquals("akash", Translit.latin("ఆకాష్"))
    @Test fun teluguAkshay() = assertEquals("akshay", Translit.latin("అక్షయ్"))
    @Test fun teluguRamesh() = assertEquals("ramesh", Translit.latin("రమేష్"))
    @Test fun hindiAkash() = assertEquals("akash", Translit.latin("आकाश"))
    @Test fun latinUntouched() = assertEquals("Akash", Translit.latin("Akash"))
}
