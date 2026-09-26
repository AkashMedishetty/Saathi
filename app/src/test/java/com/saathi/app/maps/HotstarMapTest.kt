package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.apps.HotstarMap
import org.junit.Assert.*
import org.junit.Test

/** Real vivo trees captured by the phone operator; these tests perform no device access. */
class HotstarMapTest {
    private val pkg = HotstarMap.PKG
    private val route = AppMaps.routeById("hs_watch")!!
    private val slots = mapOf("query" to "anupama")
    private fun tree(name: String) = Fixtures.load("hotstar", name)

    @Test fun fixtureParserRetainsComposeIdsWithSpaces() {
        val node = tree("search").single { it.id == "Not sure what to watch?" }
        assertEquals("Not sure what to watch?", node.text)
        assertFalse(node.clickable)
        assertFalse(node.editable)
    }

    @Test fun recognisesHomeSearchAndResults() {
        for (name in listOf("home", "search", "results")) {
            assertEquals(name, "hs_$name", AppMaps.screenOf(pkg, tree(name), slots))
        }
    }

    @Test fun homeGlowsSearchTabNotAnotherBottomTab() {
        val g = AppMaps.next(route, pkg, tree("home"), 0, slots) as Decision.Glow
        assertEquals(0, g.step)
        assertEquals("tag_bottom_menu_item_list", g.node.id)
        assertEquals("Search", g.node.label)
        assertEquals(Box(90, 2786, 510, 2966), g.node.box)
        assertNull(g.fill)
        assertFalse(g.risky)
        assertEquals("Tap Search at the bottom left.", g.say[Lang.EN])
    }

    @Test fun searchGlowsBarWithQueryFill() {
        val g = AppMaps.next(route, pkg, tree("search"), 0, slots) as Decision.Glow
        assertEquals(1, g.step)
        assertEquals("tag_search_bar", g.node.id)
        assertTrue(g.node.editable)
        assertEquals("anupama", g.fill)
        assertFalse(g.risky)
    }

    @Test fun resultsGlowHeroWatchButtonNotSearchOrPoster() {
        val g = AppMaps.next(route, pkg, tree("results"), 1, slots) as Decision.Glow
        assertEquals(2, g.step)
        assertEquals("tag_search_hero_cta_watch_button", g.node.id)
        assertEquals(Box(45, 1419, 889, 1599), g.node.box)
        assertNull(g.fill)
        assertFalse(g.risky)
    }

    @Test fun loginPageCannotProduceAMapFillOrDone() {
        assertEquals(Decision.Unknown, AppMaps.next(route, pkg, tree("player"), 2, slots))
    }
}
