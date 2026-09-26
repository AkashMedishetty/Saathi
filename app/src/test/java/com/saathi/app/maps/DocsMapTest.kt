package com.saathi.app.maps

import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.DocsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Docs on hand-built screens (no phone dump yet). */
class DocsMapTest {
    private val pkg = DocsMap.PKG
    private val home = screen(text("Recent", "60, 400 - 400, 480"), btn("Search", "1100, 160 - 1260, 300"),
        btn("Letter to bank, Document, Opened 26 Sep", "0, 520 - 1440, 720"), btn("Recipe notes, Document, Opened 20 Sep", "0, 720 - 1440, 920"),
        btn("Create new document", "1180, 2700 - 1400, 2920"))
    private val createMenu = screen(btn("Choose template", "100, 2300 - 1300, 2440"), btn("New document", "100, 2460 - 1300, 2600"))
    private val editor = screen(btn("Undo", "700, 160 - 860, 300"), btn("More options", "1260, 160 - 1420, 300"),
        n(1, "EditText", flags = "CF", r = "60, 600 - 1380, 2700"))
    private val menu = screen(btn("Share & export", "600, 600 - 1400, 740"), btn("Print", "600, 740 - 1400, 880"))
    private val export = screen(btn("Share", "600, 600 - 1400, 740"), btn("Send a copy", "600, 740 - 1400, 880"), btn("Save as Word (.docx)", "600, 880 - 1400, 1020"))

    @Test fun newDocument() {
        val r = AppMaps.routeById("docs_new")!!
        assertEquals("Create new document", (AppMaps.next(r, pkg, home, 0) as Decision.Glow).node.label)
        assertEquals("New document", (AppMaps.next(r, pkg, createMenu, 1) as Decision.Glow).node.label)
        val body = AppMaps.next(r, pkg, editor, 2) as Decision.Glow
        assertTrue(body.node.editable)
    }

    @Test fun saveAsWord() {
        val r = AppMaps.routeById("docs_save_docx")!!
        assertEquals("Letter to bank, Document, Opened 26 Sep", (AppMaps.next(r, pkg, home, 0) as Decision.Glow).node.label)
        assertEquals("More options", (AppMaps.next(r, pkg, editor, 1) as Decision.Glow).node.label)
        assertEquals("Share & export", (AppMaps.next(r, pkg, menu, 2) as Decision.Glow).node.label)
        // "Save as Word (.docx)" before "Send a copy"
        assertEquals("Save as Word (.docx)", (AppMaps.next(r, pkg, export, 3) as Decision.Glow).node.label)
    }
}
