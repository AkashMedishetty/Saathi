package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.SpotifyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify on hand-built screens (no phone dump yet). */
class SpotifyMapTest {
    private val pkg = SpotifyMap.PKG
    private val tabs = arrayOf(btn("Home", "0, 2900 - 480, 3100"), btn("Search", "480, 2900 - 960, 3100"), btn("Your Library", "960, 2900 - 1440, 3100"))
    private val home = screen(text("Good evening", "60, 200 - 800, 300"), *tabs)
    private val searchTab = screen(btn("What do you want to listen to?", "40, 300 - 1400, 440"), *tabs)
    private val searching = screen(n(1, "EditText", t = "What do you want to listen to?", flags = "CF", r = "160, 170 - 1300, 290"))
    private val results = screen(n(1, "EditText", t = "kishore kumar", flags = "CF", r = "160, 170 - 1300, 290"),
        btn("Kishore Kumar · Artist", "0, 400 - 1440, 600"), btn("Ek Ladki Bhigi Bhagi Si · Song · Kishore Kumar", "0, 600 - 1440, 800"))
    private val nowPlaying = screen(text("Ek Ladki Bhigi Bhagi Si", "60, 1900 - 1100, 2000"), btn("Add to Liked Songs", "1200, 1900 - 1380, 2060"),
        btn("Enable shuffle", "60, 2300 - 240, 2480"), btn("Previous", "300, 2300 - 480, 2480"), btn("Pause", "600, 2280 - 840, 2520"),
        btn("Next", "960, 2300 - 1140, 2480"))
    private val playlist = screen(text("Old Hindi Hits", "60, 700 - 900, 800"), btn("Download", "60, 900 - 240, 1080"),
        btn("Shuffle play", "1000, 880 - 1380, 1100"), *tabs)

    @Test fun playASong() {
        val r = AppMaps.routeById("spotify_play")!!
        val s = MapSlots.of(r, "play kishore kumar on spotify")
        assertEquals("kishore kumar", s["query"])
        assertEquals("Search", (AppMaps.next(r, pkg, home, 0, s) as Decision.Glow).node.label)
        assertEquals("What do you want to listen to?", (AppMaps.next(r, pkg, searchTab, 1, s) as Decision.Glow).node.label)
        assertEquals("kishore kumar", (AppMaps.next(r, pkg, searching, 2, s) as Decision.Glow).fill)
        val pick = AppMaps.next(r, pkg, results, 2, s) as Decision.Glow
        assertEquals("Kishore Kumar · Artist", pick.node.label)
        assertEquals(Decision.Done, AppMaps.next(r, pkg, nowPlaying, 3, s))
    }

    @Test fun likeShuffleDownload() {
        assertEquals("Add to Liked Songs", (AppMaps.next(AppMaps.routeById("spotify_like")!!, pkg, nowPlaying, 0) as Decision.Glow).node.label)
        assertEquals("Enable shuffle", (AppMaps.next(AppMaps.routeById("spotify_shuffle")!!, pkg, nowPlaying, 0) as Decision.Glow).node.label)
        val d = AppMaps.next(AppMaps.routeById("spotify_download")!!, pkg, playlist, 1) as Decision.Glow
        assertEquals("Download", d.node.label)
        assertTrue(d.why!![Lang.EN]!!.contains("Premium"))
    }
}
