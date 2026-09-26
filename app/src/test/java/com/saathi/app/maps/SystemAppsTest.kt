package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.apps.ChromeMap
import com.saathi.app.maps.apps.ClockMap
import com.saathi.app.maps.apps.InstagramMap
import com.saathi.app.maps.apps.MessagesMap
import com.saathi.app.maps.apps.PhoneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chrome, Phone, Messages and Clock: their first screens are REAL phone dumps; the rest is hand-built. */
class SystemAppsTest {
    @Test fun chromeNotificationPromptIsDeclined() {
        val prompt = Fixtures.load("chrome", "home")
        assertEquals("ch_prompt", AppMaps.screenOf(ChromeMap.PKG, prompt))
        val r = AppMaps.routeById("chrome_search")!!
        val g = AppMaps.next(r, ChromeMap.PKG, prompt, 0, mapOf("query" to "upma recipe")) as Decision.Glow
        assertEquals("No thanks", g.node.text)
        assertEquals("negative_button", g.node.id)
    }

    @Test fun chromeCookiesPreferReject() {
        val r = AppMaps.routeById("chrome_dismiss")!!
        val banner = screen(n(1, "TextView", t = "We use cookies to improve your experience", r = "60, 2000 - 1380, 2150"),
            btn("Accept all", "60, 2300 - 700, 2440"), btn("Reject all", "740, 2300 - 1380, 2440"))
        assertEquals("Reject all", (AppMaps.next(r, ChromeMap.PKG, banner, 0) as Decision.Glow).node.label)
        val acceptOnly = screen(n(1, "TextView", t = "This site uses cookies", r = "60, 2000 - 1380, 2150"), btn("Got it", "60, 2300 - 1380, 2440"))
        assertEquals("Got it", (AppMaps.next(r, ChromeMap.PKG, acceptOnly, 0) as Decision.Glow).node.label)
    }

    @Test fun phoneStartsFromTheRealDialer() {
        val dial = Fixtures.load("contacts", "home")
        assertEquals("ph_dial", AppMaps.screenOf(PhoneMap.PKG, dial))
        val r = AppMaps.routeById("phone_call")!!
        val s = MapSlots.of(r, "call Rahul")
        assertEquals("Rahul", s["contact"])
        val g = AppMaps.next(r, PhoneMap.PKG, dial, 0, s) as Decision.Glow
        assertEquals("Contacts", g.node.desc)
        assertEquals(Box(480, 2744, 960, 3010), g.node.box)
        // The dialer's own green "Dial" button is always risky
        val detail = dial + Fixtures.tree(n(1, "Button", d = "Message", flags = "CF", r = "100, 1000 - 400, 1150"))
        val call = AppMaps.next(r, PhoneMap.PKG, screen(btn("Call mobile", "100, 800 - 700, 950"), btn("Message", "740, 800 - 1340, 950")), 3, s) as Decision.Glow
        assertTrue(call.risky)
        assertTrue(detail.isNotEmpty())
    }

    @Test fun messagesFirstRunContinues() {
        val welcome = Fixtures.load("messages", "home")
        assertEquals("msg_welcome", AppMaps.screenOf(MessagesMap.PKG, welcome))
        val r = AppMaps.routeById("messages_read_latest")!!
        val g = AppMaps.next(r, MessagesMap.PKG, welcome, 0) as Decision.Glow
        assertEquals("continue_as_button", g.node.id)
    }

    @Test fun clockTurnsOffTheRightAlarm() {
        val alarms = Fixtures.load("clock", "home")
        // the empty-class node lines are parsed as their own nodes
        assertTrue(alarms.any { it.id == "alarm_item_content" && it.desc!!.startsWith("On,8:00") })
        val r = AppMaps.routeById("clock_alarm_off")!!
        val s = MapSlots.of(r, "turn off the 8 am alarm")
        assertEquals("8:00", s["time"])
        val g = AppMaps.next(r, ClockMap.PKG, alarms, 0, s) as Decision.Glow
        assertEquals("bar_onff_layout", g.node.id)
        assertEquals(Box(1146, 819, 1357, 1052), g.node.box)          // the 8:00 card's switch, not 8:30's
        assertEquals("Tap the switch next to the 8:00 alarm to turn it off.", g.say[Lang.EN])
        // 8:30
        val g2 = AppMaps.next(r, ClockMap.PKG, alarms, 0, MapSlots.of(r, "switch off the 8:30 alarm")) as Decision.Glow
        assertEquals(Box(1146, 1280, 1357, 1513), g2.node.box)
        // the new-alarm route glows the real plus button
        val add = AppMaps.next(AppMaps.routeById("clock_alarm_new")!!, ClockMap.PKG, alarms, 0) as Decision.Glow
        assertEquals("bt_add_alarm", add.node.id)
    }

    @Test fun instagramPostIsRisky() {
        val r = AppMaps.routeById("instagram_post")!!
        val cap = screen(n(1, "EditText", t = "Write a caption...", flags = "CF", r = "60, 600 - 1380, 800"), btn("Share", "60, 2800 - 1380, 2960"))
        assertTrue((AppMaps.next(r, InstagramMap.PKG, cap, 2) as Decision.Glow).risky)
    }
}

class CameraMapTest {
    @Test fun selfieSwitchesFirst() {
        val r = AppMaps.routeById("camera_selfie")!!
        val back = T.screen(T.btn("Switch to front camera", "1100, 2700 - 1300, 2900"), T.btn("Shutter", "560, 2650 - 880, 2970"))
        val front = T.screen(T.btn("Switch to rear camera", "1100, 2700 - 1300, 2900"), T.btn("Shutter", "560, 2650 - 880, 2970"))
        assertEquals("Switch to front camera", (AppMaps.next(r, com.saathi.app.maps.apps.CameraMap.PKG, back, 0) as Decision.Glow).node.label)
        assertEquals("Shutter", (AppMaps.next(r, com.saathi.app.maps.apps.CameraMap.PKG, front, 0) as Decision.Glow).node.label)
    }
}
