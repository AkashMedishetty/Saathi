package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.apps.YouTubeMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMapTest {
    private val pkg = YouTubeMap.PKG
    private fun yt(s: String) = Fixtures.load("youtube", s)
    private val search = AppMaps.routeById("yt_search")!!
    private val q = mapOf("query" to "hanuman chalisa")

    @Test fun fixturesParse() {
        for (s in listOf("home", "home2", "search_empty", "search_typed", "results", "results_playlist", "results_deeplink", "subscriptions", "you")) {
            val n = yt(s)
            assertTrue("$s parsed", n.size > 20)
        }
        // wrapped descriptions are joined back together
        assertTrue(yt("results").any { it.desc?.contains("\n") == true && it.desc!!.startsWith("Sponsored") })
    }

    @Test fun screensAreRecognised() {
        assertEquals("yt_home", AppMaps.screenOf(pkg, yt("home")))
        assertEquals("yt_search", AppMaps.screenOf(pkg, yt("search_empty")))
        assertEquals("yt_search", AppMaps.screenOf(pkg, yt("search_typed")))
        assertEquals("yt_results_for", AppMaps.screenOf(pkg, yt("results"), q))
        assertEquals("yt_results", AppMaps.screenOf(pkg, yt("results"), mapOf("query" to "old telugu songs melodies 1970")))
        assertEquals("yt_results_for", AppMaps.screenOf(pkg, yt("results_playlist"), mapOf("query" to "old telugu songs")))
        assertEquals("yt_subs", AppMaps.screenOf(pkg, yt("subscriptions")))
        assertEquals("yt_you", AppMaps.screenOf(pkg, yt("you")))
        assertNull(AppMaps.screenOf("com.example.unknown", yt("home")))
    }

    @Test fun searchWalksFromHomeToTheFirstRealResult() {
        // 1. Home: the search icon (the toolbar magnifying glass, the largest match)
        val a = AppMaps.next(search, pkg, yt("home"), 0, q) as Decision.Glow
        assertEquals(0, a.step)
        assertTrue(a.node.label, a.node.label == "Search" || a.node.label == "Search YouTube")
        assertEquals("Tap the magnifying glass at the top to search.", a.say[Lang.EN])
        // 2. Search box, empty: type the query (the history list has no exact "hanuman chalisa")
        val b = AppMaps.next(search, pkg, yt("search_empty"), 1, q) as Decision.Glow
        assertEquals(2, b.step)
        assertEquals("search_edit_text", b.node.id)
        assertEquals("hanuman chalisa", b.fill)
        assertEquals("Type “hanuman chalisa”. Or tap Do it and I'll type it.", b.say[Lang.EN])
        assertEquals("“hanuman chalisa” लिखिए। या 'आप कर दो' दबाइए, मैं लिख दूँगा।", b.say[Lang.HI])
        // 3. Typed: the suggestion row that says exactly "hanuman chalisa" (the top row, not "hanuman chalisa fast")
        val c = AppMaps.next(search, pkg, yt("search_typed"), 2, q) as Decision.Glow
        assertEquals(3, c.step)
        assertEquals(Box(0, 323, 1440, 503), c.node.box)
        assertTrue(c.node.clickable)
        // 4. Results: the first real video, skipping the "Sponsored" ad above it
        val d = AppMaps.next(search, pkg, yt("results"), 3, q) as Decision.Glow
        assertEquals(4, d.step)
        assertTrue(d.node.desc!!.endsWith("play video"))
        assertFalse(d.node.desc!!.contains("Sponsored"))
        assertEquals(1851, d.node.box.t)
        // The glow stops at the bottom tab bar drawn over the row
        assertTrue("glow ${d.box} stays above the tab bar", d.box.b <= 2830 && d.box.t >= 1851)
        assertFalse(d.risky)
    }

    @Test fun playlistResultsCount() {
        val d = AppMaps.next(search, pkg, yt("results_playlist"), 3, mapOf("query" to "old telugu songs")) as Decision.Glow
        assertTrue(d.node.desc!!.startsWith("Playlist - Old Melody Songs Telugu"))
    }

    @Test fun latestStepWinsWhenPeopleSkipAhead() {
        // They typed and pressed search themselves: straight to the result, from step 0.
        val d = AppMaps.next(search, pkg, yt("results"), 0, q) as Decision.Glow
        assertEquals(4, d.step)
        // Already on the typed screen at the start: the suggestion, not "tap search"
        assertEquals(3, (AppMaps.next(search, pkg, yt("search_typed"), 0, q) as Decision.Glow).step)
    }

    @Test fun resultsForSomethingElseAskForANewSearch() {
        val d = AppMaps.next(search, pkg, yt("results"), 0, mapOf("query" to "sai baba bhajan")) as Decision.Glow
        assertEquals(1, d.step)
        assertEquals("search_query", d.node.id)
        assertEquals("Tap the search bar at the top to search for “sai baba bhajan”.", d.say[Lang.EN])
    }

    @Test fun neverGoesBackBehindTheFurthestStep() {
        // They reached the result list (step 4), then the list scrolled so no real result is visible:
        val adOnly = yt("results").filterNot { it.desc?.endsWith("play video") == true }
        val s = AppMaps.next(search, pkg, adOnly, 4, q)
        assertTrue("$s", s is Decision.Scroll)
        assertEquals(4, (s as Decision.Scroll).step)
        assertEquals("Slowly scroll down to the first video.", s.hint[Lang.EN])
    }

    @Test fun subscriptions() {
        val r = AppMaps.routeById("yt_subscriptions")!!
        val a = AppMaps.next(r, pkg, yt("home"), 0) as Decision.Glow
        assertEquals("Subscriptions", a.node.label)
        assertEquals(Box(864, 2830, 1152, 3010), a.node.box)
        // from the You tab and from results too
        assertEquals("Subscriptions", (AppMaps.next(r, pkg, yt("you"), 0) as Decision.Glow).node.label)
        assertEquals("Subscriptions", (AppMaps.next(r, pkg, yt("results"), 0) as Decision.Glow).node.label)
        assertEquals(Decision.Done, AppMaps.next(r, pkg, yt("subscriptions"), 0))
    }

    @Test fun historyGoesThroughYou() {
        val r = AppMaps.routeById("yt_history")!!
        assertEquals("You", (AppMaps.next(r, pkg, yt("home"), 0) as Decision.Glow).node.label)
        // The You page of this new account has no History row yet: scroll hint, never a wrong tap.
        val s = AppMaps.next(r, pkg, yt("you"), 1)
        assertTrue("$s", s is Decision.Scroll)
    }

    @Test fun wrongScreenAndOtherApps() {
        // The search route on the You page: step 0 applies there (the search icon)
        assertEquals(0, (AppMaps.next(search, pkg, yt("you"), 0, q) as Decision.Glow).step)
        // The like route on the home page: not a screen it uses → go back
        val like = AppMaps.routeById("yt_like")!!
        val w = AppMaps.next(like, pkg, yt("home"), 0)
        assertTrue("$w", w is Decision.WrongScreen)
        assertEquals("yt_watch", (w as Decision.WrongScreen).expect)
        // Another app entirely
        assertEquals(Decision.Unknown, AppMaps.next(search, "com.android.chrome", Fixtures.load("chrome", "home"), 0, q))
    }

    @Test fun offScreenAndTinyNodesAreNeverChosen() {
        val t = Tree(yt("results"))
        // the third result row is almost entirely behind the tab bar / off the bottom
        val third = t.nodes.indexOfFirst { it.box.t == 3121 && it.cls == "Button" }
        assertTrue(third >= 0)
        assertNull(t.visible(third))
        // negative coordinates (vivo) are rejected
        val neg = listOf(Node(null, null, "Search", "ImageView", true, false, false, Box(-500, 100, -300, 300), 0))
        assertNull(AppMaps.find(listOf(lbl("^Search$")), listOf(Node(null, null, null, "FrameLayout", false, false, false, Box(0, 0, 1440, 3168), 0)) + neg.map { it.copy(depth = 1) }))
    }

    @Test fun riskyLabelsAreGlowOnlyEvenIfAMapForgets() {
        val nodes = Fixtures.tree(
            "FrameLayout t=null d=null id=null  ri=null acts= Rect(0, 0 - 1440, 3000)",
            "  Button t=Install d=null id=null CF ri=null acts= Rect(100, 500 - 700, 640)",
        )
        val r = Route("t", pkg, emptyList(), emptyList(),
            listOf(MapStep("yt_home", listOf(lbl("^Install$")), com.saathi.app.guide.say("a", "b", "c"))), emptyList(), com.saathi.app.guide.say("", "", ""))
        val home = yt("home") + nodes.drop(1).map { it.copy(depth = 3) }
        val g = AppMaps.next(r, pkg, home, 0) as Decision.Glow
        assertTrue(g.risky)
    }

    @Test fun rowTextMatchesUnlabelledRows() {
        val t = Tree(yt("search_typed"))
        val row = t.nodes.indexOfFirst { it.box == Box(0, 503, 1440, 683) }
        assertTrue(t.rowText(row).startsWith("hanuman chalisa fast"))
        assertNotNull(AppMaps.find(listOf(Sel(resId = "text", slot = "query", slotExact = true)), yt("search_typed"), mapOf("query" to "Hanuman  Chalisa FAST")))
    }
}
