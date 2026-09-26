package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.PlayStoreMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play Store journey on hand-built screens (no phone dump yet). */
class PlayStoreMapTest {
    private val pkg = PlayStoreMap.PKG
    private val route = AppMaps.routeById("playstore_install")!!
    private val slots = MapSlots.of(route, "install whatsapp")

    private val home = screen(text("Search apps & games", "150, 170 - 1100, 290"), btn("Signed in as Kamala", "1260, 160 - 1400, 300"),
        btn("Games", "0, 2900 - 480, 3100"), btn("Apps", "480, 2900 - 960, 3100"), btn("Search", "960, 2900 - 1440, 3100"))
    private val searching = screen(btn("Navigate up", "0, 160 - 160, 300", cls = "ImageButton"),
        n(1, "EditText", t = "whatsapp", flags = "CF", r = "160, 170 - 1300, 290"))
    /** A Sponsored look-alike on top, then a look-alike by another developer, then the real one. */
    private val results = screen(btn("Navigate up", "0, 160 - 160, 300", cls = "ImageButton"), text("whatsapp", "160, 170 - 1300, 290"),
        n(1, "LinearLayout", d = "Sponsored · WA Chat Tools · Toolbox Inc · 4.1 star", flags = "CF", r = "0, 350 - 1440, 620"),
        btn("Install", "1100, 420 - 1400, 540", depth = 2),
        n(1, "LinearLayout", d = "WhatsApp Tools for WA · Fake Apps Studio · 3.2 star", flags = "CF", r = "0, 620 - 1440, 890"),
        btn("Install", "1100, 690 - 1400, 810", depth = 2),
        n(1, "LinearLayout", d = "WhatsApp Messenger · WhatsApp LLC · 4.3 star · 10B+ downloads", flags = "CF", r = "0, 890 - 1440, 1160"),
        btn("Install", "1100, 960 - 1400, 1080", depth = 2))
    private val details = screen(btn("Navigate up", "0, 160 - 160, 300", cls = "ImageButton"), text("WhatsApp Messenger", "300, 400 - 1300, 500"),
        text("WhatsApp LLC", "300, 500 - 900, 570"), btn("Install", "60, 900 - 1380, 1040"), text("About this app", "60, 1600 - 800, 1680"))
    private val installing = screen(text("WhatsApp Messenger", "300, 400 - 1300, 500"), text("42% of 58 MB", "60, 900 - 900, 980"),
        btn("Cancel", "1000, 900 - 1380, 1040"))
    private val installed = screen(text("WhatsApp Messenger", "300, 400 - 1300, 500"), btn("Uninstall", "60, 900 - 700, 1040"), btn("Open", "740, 900 - 1380, 1040"))

    @Test fun slots() {
        assertEquals("whatsapp", slots["app"])
        assertEquals("WhatsApp LLC", slots["developer"])
        assertEquals(mapOf("app" to "whatsapp", "developer" to "WhatsApp LLC"), MapSlots.of(route, "व्हाट्सएप इंस्टॉल करो"))
        assertEquals("spotify", MapSlots.of(route, "download spotify from play store")["app"])
    }

    @Test fun theFullJourney() {
        assertEquals("ps_home", AppMaps.screenOf(pkg, home, slots))
        assertEquals(0, (AppMaps.next(route, pkg, home, 0, slots) as Decision.Glow).step)
        val t = AppMaps.next(route, pkg, searching, 1, slots) as Decision.Glow
        assertEquals("whatsapp", t.fill)
        // The results: not the ad, not the look-alike by another developer: the real WhatsApp LLC listing
        val p = AppMaps.next(route, pkg, results, 2, slots) as Decision.Glow
        assertEquals(2, p.step)
        assertTrue(p.node.label!!.contains("WhatsApp LLC"))
        assertEquals("Tap “whatsapp” by WhatsApp LLC.", p.say[Lang.EN])
        assertEquals("WhatsApp LLC वाला “whatsapp” दबाइए।", p.say[Lang.HI])
        assertFalse(p.risky)
        // Install: glow only
        val i = AppMaps.next(route, pkg, details, 3, slots) as Decision.Glow
        assertEquals("Install", i.node.label)
        assertTrue(i.risky)
        // Installing: wait
        val w = AppMaps.next(route, pkg, installing, 3, slots)
        assertTrue("$w", w is Decision.Wait)
        // Installed: Open, and the hand-over line names the app
        val o = AppMaps.next(route, pkg, installed, 3, slots) as Decision.Glow
        assertEquals("Open", o.node.label)
        assertEquals("Want me to show you how to use whatsapp?", AppMaps.fillIn(route.next.first(), slots)[Lang.EN])
    }

    @Test fun unknownDeveloperStillAvoidsAds() {
        val s = MapSlots.of(route, "install bhajan radio")
        assertEquals(null, s["developer"])
        val res = screen(text("bhajan radio", "160, 170 - 1300, 290"),
            n(1, "LinearLayout", d = "Ad · Bhajan Radio Pro · XYZ · 4.0 star", flags = "CF", r = "0, 350 - 1440, 620"),
            n(1, "LinearLayout", d = "Bhajan Radio · Devotional Apps · 4.5 star", flags = "CF", r = "0, 620 - 1440, 890"))
        val p = AppMaps.next(route, pkg, res, 2, s) as Decision.Glow
        assertTrue(p.node.label!!.startsWith("Bhajan Radio ·"))
        assertEquals("Tap “bhajan radio”.", p.say[Lang.EN])       // no "by …" when the developer is unknown
    }

    @Test fun uninstallIsRiskyTwice() {
        val r = AppMaps.routeById("playstore_uninstall")!!
        val s = MapSlots.of(r, "uninstall whatsapp")
        assertTrue((AppMaps.next(r, pkg, installed, 2, s) as Decision.Glow).risky)
        val confirm = screen(text("Do you want to uninstall this app?", "100, 1200 - 1300, 1300"), btn("Cancel", "100, 1400 - 700, 1520"), btn("Uninstall", "740, 1400 - 1340, 1520"))
        val c = AppMaps.next(r, pkg, confirm, 3, s) as Decision.Glow
        assertEquals(4, c.step); assertTrue(c.risky)
    }
}
