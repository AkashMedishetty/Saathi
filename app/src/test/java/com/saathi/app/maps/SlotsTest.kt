package com.saathi.app.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotsTest {
    private fun q(goal: String) = MapSlots.of(AppMaps.routeById("yt_search")!!, goal)["query"]

    @Test fun youtubeQueries() {
        assertEquals("hanuman chalisa", q("play hanuman chalisa on youtube"))
        assertEquals("old telugu songs", q("search old telugu songs on YouTube"))
        assertEquals("हनुमान चालीसा", q("हनुमान चालीसा लगाओ यूट्यूब पर"))
        assertEquals("ఘంటసాల పాటలు", q("యూట్యూబ్ లో ఘంటసాల పాటలు పెట్టు"))
    }

    @Test fun settingsTerms() {
        assertEquals("ringtone", SettingsTerms.termFor("change my ringtone"))
        assertEquals("font size", SettingsTerms.termFor("अक्षर बड़े करो"))
        assertEquals("brightness", SettingsTerms.termFor("స్క్రీన్ వెలుతురు పెంచు"))
        assertEquals("wallpaper", SettingsTerms.termFor("वॉलपेपर बदलो"))
        assertEquals("screen timeout", SettingsTerms.termFor("my screen turns off too fast"))
        assertNull(SettingsTerms.termFor("play a song"))
    }
}
