package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.apps.SettingsMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings has no phone dump yet: these trees are hand-built in the dump format from what Saathi saw on this phone
 * (vivo OriginOS: "Search settings", "Display, brightness & eye protection", result rows with a breadcrumb).
 */
class SettingsMapTest {
    private val pkg = SettingsMap.PKG
    private fun n(depth: Int, cls: String, t: String? = null, d: String? = null, id: String? = null, flags: String = "", r: String) =
        "  ".repeat(depth) + "$cls t=${t ?: "null"} d=${d ?: "null"} id=${id ?: "null"} $flags ri=null acts= Rect($r)"

    private val home = Fixtures.tree(
        n(0, "FrameLayout", r = "0, 0 - 1440, 3168"),
        n(1, "LinearLayout", id = "search_bar", flags = "CF", r = "45, 300 - 1395, 450"),
        n(2, "TextView", t = "Search settings", r = "150, 330 - 900, 420"),
        n(1, "RecyclerView", flags = "SF", r = "0, 500 - 1440, 3000"),
        n(2, "LinearLayout", flags = "CF", r = "0, 520 - 1440, 700"),
        n(3, "TextView", t = "Wi-Fi", r = "200, 560 - 600, 640"),
        n(2, "LinearLayout", flags = "CF", r = "0, 700 - 1440, 880"),
        n(3, "TextView", t = "Bluetooth", r = "200, 740 - 600, 820"),
        n(2, "LinearLayout", flags = "CF", r = "0, 880 - 1440, 1060"),
        n(3, "TextView", t = "Display, brightness & eye protection", r = "200, 920 - 1300, 1000"),
    )

    private fun search(typed: String?) = Fixtures.tree(*listOfNotNull(
        n(0, "FrameLayout", r = "0, 0 - 1440, 3168"),
        n(1, "ImageButton", d = "Navigate up", flags = "CF", r = "0, 143 - 180, 323"),
        n(1, "EditText", t = typed ?: "Search", id = "search_src_text", flags = "CF", r = "180, 173 - 1260, 293"),
        // the search-history chip: just the word, with a remove icon
        n(1, "LinearLayout", flags = "CF", r = "45, 360 - 700, 480"),
        n(2, "TextView", t = "ringtone", r = "80, 380 - 400, 460"),
        n(2, "ImageView", d = "Remove", flags = "C", r = "560, 380 - 680, 460"),
        typed?.let { n(1, "LinearLayout", flags = "CF", r = "0, 560 - 1440, 780") },
        typed?.let { n(2, "TextView", t = "Incoming call ringtone", r = "200, 590 - 1200, 670") },
        typed?.let { n(2, "TextView", t = "Sound & vibration", r = "200, 680 - 900, 750") },
        typed?.let { n(1, "LinearLayout", flags = "CF", r = "0, 780 - 1440, 1000") },
        typed?.let { n(2, "TextView", t = "Notification ringtone", r = "200, 810 - 1200, 890") },
    ).toTypedArray())

    private val ringtonePage = Fixtures.tree(
        n(0, "FrameLayout", r = "0, 0 - 1440, 3168"),
        n(1, "ImageButton", d = "Navigate up", flags = "CF", r = "0, 143 - 180, 323"),
        n(1, "TextView", t = "Incoming call ringtone", r = "200, 180 - 1000, 290"),
        n(1, "LinearLayout", flags = "CF", r = "0, 400 - 1440, 560"),
        n(2, "TextView", t = "Flow", r = "200, 440 - 600, 520"),
    )

    private val oldSubPage = Fixtures.tree(
        n(0, "FrameLayout", r = "0, 0 - 1440, 3168"),
        n(1, "ImageButton", d = "Navigate up", flags = "CF", r = "0, 143 - 180, 323"),
        n(1, "TextView", t = "Display, brightness & eye protection", r = "200, 180 - 1300, 290"),
        n(1, "LinearLayout", flags = "CF", r = "0, 400 - 1440, 560"),
        n(2, "TextView", t = "Wallpaper", r = "200, 440 - 600, 520"),
    )

    private val ringtone = AppMaps.routeById("settings_ringtone")!!
    private val slots = MapSlots.of(ringtone, "change my ringtone")

    @Test fun screens() {
        assertEquals("set_home", AppMaps.screenOf(pkg, home))
        assertEquals("set_search", AppMaps.screenOf(pkg, search(null), slots))
        assertEquals("set_search_typed", AppMaps.screenOf(pkg, search("ringtone"), slots))
        assertEquals("set_page", AppMaps.screenOf(pkg, ringtonePage, slots))
        // the Pixel/AOSP search page lives in another package: same map
        assertEquals("set_search", AppMaps.screenOf("com.google.android.settings.intelligence", search(null), slots))
    }

    @Test fun ringtoneRouteNeverPicksTheHistoryChip() {
        assertEquals("ringtone", slots["term"])
        val a = AppMaps.next(ringtone, pkg, home, 0, slots) as Decision.Glow
        assertEquals(0, a.step); assertEquals("search_bar", a.node.id)
        val b = AppMaps.next(ringtone, pkg, search(null), 1, slots) as Decision.Glow
        assertEquals(1, b.step); assertTrue(b.node.editable); assertEquals("ringtone", b.fill)
        assertEquals("Type “ringtone”. Or tap Do it and I'll type it.", b.say[Lang.EN])
        // Typed: the history chip "ringtone" (exact word) is on screen too, but the result row wins.
        val c = AppMaps.next(ringtone, pkg, search("ringtone"), 1, slots) as Decision.Glow
        assertEquals(2, c.step)
        assertEquals(Box(0, 560, 1440, 780), c.node.box)
        assertEquals("Tap the result that says “ringtone”.", c.say[Lang.EN])
        // Before the last step is reached, a page that mentions the topic is NOT done
        assertTrue(AppMaps.next(ringtone, pkg, ringtonePage, 0, slots) is Decision.WrongScreen)
        assertEquals(Decision.Done, AppMaps.next(ringtone, pkg, ringtonePage, 2, slots))
    }

    @Test fun anOldSubPageAsksToGoBack() {
        val w = AppMaps.next(ringtone, pkg, oldSubPage, 0, slots) as Decision.WrongScreen
        assertEquals("Navigate up", w.node?.label)
        assertTrue(w.backHint[Lang.EN]!!.startsWith("This is an older Settings page"))
    }

    @Test fun noResultYetMeansLookAtTheList() {
        // typed "ringtone" but the results haven't loaded: only the history chip → a scroll/look hint, not the chip
        val s = AppMaps.next(ringtone, pkg, search("ringtone").take(6), 1, slots)
        assertTrue("$s", s is Decision.Scroll)
        assertEquals(2, (s as Decision.Scroll).step)
    }

    @Test fun genericSettingsSearchUsesThePersonsWords() {
        val r = AppMaps.route("open the settings for vibration")!!
        assertEquals("settings_search", r.id)
        assertEquals("vibration", MapSlots.of(r, "open the settings for vibration")["term"])
        val r2 = AppMaps.route("change the setting for call waiting")!!
        assertEquals("call waiting", MapSlots.of(r2, "change the setting for call waiting")["term"])
    }

    /** Field (vivo, 02:37): the main page stays in the tree under the search results; the result must still glow. */
    @Test fun vivoSearchResultUnderMainPageGlows() {
        val font = AppMaps.routeById("settings_font")!!
        val nodes = Fixtures.load("settings", "vivo_search_results_font")
        val d = AppMaps.next(font, pkg, nodes, 1, MapSlots.of(font, "अक्षर बड़े करो"))
        assertTrue("$d", d is Decision.Glow && d.step == 2)
    }
}
