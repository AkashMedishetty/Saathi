package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.PhotosMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Photos: hand-built screens (no phone dump yet), shaped like Photos' accessibility labels. */
class PhotosMapTest {
    private val pkg = PhotosMap.PKG

    private val grid = screen(
        btn("Photo taken on 26 Sep 2026, 10:12:03 am", "0, 400 - 480, 880"),
        btn("Photo taken on 26 Sep 2026, 9:40:10 am", "480, 400 - 960, 880"),
        btn("Photo taken on 25 Sep 2026, 6:02:44 pm", "0, 880 - 480, 1360"),
        btn("Photos", "0, 2900 - 480, 3100"), btn("Collections", "480, 2900 - 960, 3100"), btn("Search", "960, 2900 - 1440, 3100"),
    )
    private val viewer = screen(btn("Share", "0, 2900 - 360, 3100"), btn("Edit", "360, 2900 - 720, 3100"),
        btn("Lens", "720, 2900 - 1080, 3100"), btn("Delete", "1080, 2900 - 1440, 3100"))
    private val editor = screen(btn("Save copy", "1000, 150 - 1400, 300"), btn("Suggestions", "0, 2700 - 360, 2850"),
        btn("Crop", "360, 2700 - 720, 2850"), btn("Adjust", "720, 2700 - 1080, 2850"), btn("Filters", "1080, 2700 - 1440, 2850"))
    private val crop = screen(btn("Rotate", "100, 2500 - 400, 2650"), btn("Aspect ratio", "500, 2500 - 900, 2650"),
        btn("Reset", "1000, 2500 - 1400, 2650"), btn("Done", "1100, 2900 - 1400, 3100"))
    private val adjust = screen(btn("Save copy", "1000, 150 - 1400, 300"), btn("Crop", "360, 2700 - 720, 2850"), btn("Adjust", "720, 2700 - 1080, 2850"),
        btn("Brightness", "0, 2400 - 360, 2550"), btn("Contrast", "360, 2400 - 720, 2550"))
    private val slider = adjust + Fixtures.tree(n(1, "SeekBar", flags = "F", r = "100, 2200 - 1340, 2330")).map { it }

    @Test fun screens() {
        assertEquals("ph_grid", AppMaps.screenOf(pkg, grid))
        assertEquals("ph_viewer", AppMaps.screenOf(pkg, viewer))
        assertEquals("ph_editor", AppMaps.screenOf(pkg, editor))
        assertEquals("ph_crop", AppMaps.screenOf(pkg, crop))
        assertEquals("ph_adjust", AppMaps.screenOf(pkg, adjust))
        assertEquals("ph_adjust_slider", AppMaps.screenOf(pkg, slider))
    }

    @Test fun cropWalkthrough() {
        val r = AppMaps.routeById("photos_crop")!!
        val a = AppMaps.next(r, pkg, grid, 0) as Decision.Glow
        assertEquals(0, a.step)
        assertEquals(Box(0, 400, 480, 880), a.node.box)            // the newest (top-left) photo
        assertEquals("Edit", (AppMaps.next(r, pkg, viewer, 1) as Decision.Glow).node.label)
        // In the editor, BEFORE cropping: "Crop", even though "Save copy" is already on screen
        val c = AppMaps.next(r, pkg, editor, 1) as Decision.Glow
        assertEquals("Crop", c.node.label)
        assertEquals("Drag the white corners to cut the edges. Then tap Done.", (AppMaps.next(r, pkg, crop, 2) as Decision.Glow).say[Lang.EN])
        // Back in the editor AFTER the crop: now "Save copy"
        val s = AppMaps.next(r, pkg, editor, 3) as Decision.Glow
        assertEquals("Save copy", s.node.label)
        assertEquals(4, s.step)
        // Back in the viewer after saving: done. (Not before: the viewer at the start is only step 1.)
        assertEquals(Decision.Done, AppMaps.next(r, pkg, viewer, 4))
        assertTrue(AppMaps.next(r, pkg, viewer, 1) is Decision.Glow)
    }

    @Test fun brightnessWalkthrough() {
        val r = AppMaps.routeById("photos_brightness")!!
        assertEquals("Adjust", (AppMaps.next(r, pkg, editor, 1) as Decision.Glow).node.label)
        assertEquals("Brightness", (AppMaps.next(r, pkg, adjust, 2) as Decision.Glow).node.label)
        val sl = AppMaps.next(r, pkg, slider, 3) as Decision.Glow
        assertEquals("SeekBar", sl.node.cls)
        assertEquals("Save copy", (AppMaps.next(r, pkg, editor, 4) as Decision.Glow).node.label)
    }

    @Test fun shareStopsAtWhatsApp() {
        val r = AppMaps.routeById("photos_share")!!
        val sheet = screen(text("Share to apps", "60, 1800 - 600, 1880"), btn("WhatsApp", "60, 1950 - 300, 2200"), btn("Gmail", "330, 1950 - 570, 2200"))
        assertEquals("Share", (AppMaps.next(r, pkg, viewer, 1) as Decision.Glow).node.label)
        assertEquals("WhatsApp", (AppMaps.next(r, pkg, sheet, 2) as Decision.Glow).node.label)
        // The viewer's "Delete" button is never a target of any Photos route
        for (route in PhotosMap.map.routes) {
            val d = AppMaps.next(route, pkg, viewer, 1)
            if (d is Decision.Glow) assertTrue(d.node.label != "Delete")
        }
    }
}
