package com.saathi.app.maps

import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.MapsMap
import com.saathi.app.maps.apps.UberMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Maps + Uber on hand-built screens (no phone dumps yet). */
class MapsAndCabTest {
    private val home = screen(n(1, "TextView", t = "Search here", id = "search_omnibox_text_box", flags = "CF", r = "40, 160 - 1300, 300"),
        btn("Explore", "0, 2900 - 480, 3100"), btn("You", "480, 2900 - 960, 3100"), btn("Contribute", "960, 2900 - 1440, 3100"))
    private val searching = screen(n(1, "EditText", t = "charminar", id = "search_omnibox_edit_text", flags = "CF", r = "40, 160 - 1300, 300"),
        btn("Charminar, Char Kaman, Ghansi Bazaar, Hyderabad", "0, 400 - 1440, 600"), btn("Charminar bus stop", "0, 600 - 1440, 800"))
    private val place = screen(text("Charminar", "60, 1800 - 900, 1900"), btn("Directions", "60, 2000 - 400, 2140"), btn("Start", "420, 2000 - 700, 2140"),
        btn("Save", "720, 2000 - 1000, 2140"))
    private val directions = screen(btn("Driving mode", "0, 400 - 280, 520"), btn("Two-wheeler mode", "280, 400 - 560, 520"),
        btn("Transit mode", "560, 400 - 840, 520"), btn("Ride services mode", "840, 400 - 1120, 520"), btn("Start", "60, 2800 - 500, 2940"))
    private val rides = screen(btn("Ride services mode", "840, 400 - 1120, 520"), btn("Uber Go, ₹180", "0, 1800 - 1440, 2000"), btn("Rapido Auto, ₹120", "0, 2000 - 1440, 2200"))

    @Test fun directions() {
        val r = AppMaps.routeById("maps_directions")!!
        val s = MapSlots.of(r, "directions to Charminar")
        assertEquals("Charminar", s["place"])
        val pkg = MapsMap.PKG
        assertEquals("search_omnibox_text_box", (AppMaps.next(r, pkg, home, 0, s) as Decision.Glow).node.id)
        val sug = AppMaps.next(r, pkg, searching, 1, s) as Decision.Glow
        assertEquals("Charminar, Char Kaman, Ghansi Bazaar, Hyderabad", sug.node.label)
        assertEquals("Directions", (AppMaps.next(r, pkg, place, 2, s) as Decision.Glow).node.label)
        assertEquals("Start", (AppMaps.next(r, pkg, directions, 3, s) as Decision.Glow).node.label)
    }

    @Test fun cabFromMapsHandsOverToUber() {
        val r = AppMaps.routeById("maps_cab")!!
        val s = mapOf("place" to "Charminar")
        assertEquals("Ride services mode", (AppMaps.next(r, MapsMap.PKG, directions, 3, s) as Decision.Glow).node.label)
        assertEquals("Uber Go, ₹180", (AppMaps.next(r, MapsMap.PKG, rides, 4, s) as Decision.Glow).node.label)
    }

    @Test fun uberBookingIsGlowOnlyAtEveryCommitment() {
        val r = AppMaps.routeById("uber_cab")!!
        val s = MapSlots.of(r, "book a cab to Charminar")
        assertEquals("Charminar", s["place"])
        val pkg = UberMap.PKG
        val uHome = screen(btn("Where to?", "40, 1500 - 1400, 1650"))
        val uSearch = screen(n(1, "EditText", t = "Charminar", flags = "CF", r = "40, 300 - 1400, 440"), btn("Charminar · Hyderabad, Telangana", "0, 600 - 1440, 800"))
        val uRides = screen(btn("Uber Go · 4 min · ₹180", "0, 1800 - 1440, 2000"), btn("Premier · 6 min · ₹260", "0, 2000 - 1440, 2200"),
            btn("Choose Uber Go", "40, 2800 - 1400, 2960"))
        assertEquals("Where to?", (AppMaps.next(r, pkg, uHome, 0, s) as Decision.Glow).node.label)
        assertEquals("Charminar · Hyderabad, Telangana", (AppMaps.next(r, pkg, uSearch, 1, s) as Decision.Glow).node.label)
        val ride = AppMaps.next(r, pkg, uRides, 2, s) as Decision.Glow
        assertEquals("Uber Go · 4 min · ₹180", ride.node.label); assertTrue(ride.risky)
        val confirm = AppMaps.next(r, pkg, uRides, 3, s) as Decision.Glow
        assertEquals("Choose Uber Go", confirm.node.label); assertTrue(confirm.risky)
        val finding = screen(text("Finding your ride", "60, 1800 - 900, 1900"), btn("Cancel ride", "60, 2800 - 700, 2940"))
        assertTrue(AppMaps.next(r, pkg, finding, 5, s) is Decision.Wait)
    }

    @Test fun places() {
        assertEquals("railway station", MapSlots.placeFor("take me to the railway station"))
        assertEquals("Apollo Hospital", MapSlots.placeFor("book a cab to Apollo Hospital"))
        assertEquals("चारमीनार", MapSlots.placeFor("चारमीनार का रास्ता"))
        assertEquals("చార్మినార్", MapSlots.placeFor("చార్మినార్ కి దారి"))
    }
}
