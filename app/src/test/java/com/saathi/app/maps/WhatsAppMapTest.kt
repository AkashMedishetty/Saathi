package com.saathi.app.maps

import com.saathi.app.guide.Lang
import com.saathi.app.maps.T.btn
import com.saathi.app.maps.T.n
import com.saathi.app.maps.T.screen
import com.saathi.app.maps.T.text
import com.saathi.app.maps.apps.WhatsAppMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WhatsApp on hand-built screens using WhatsApp's resource-ids (no phone dump: the demo phone isn't registered yet). */
class WhatsAppMapTest {
    private val pkg = WhatsAppMap.PKG
    private val tabs = arrayOf(btn("Chats", "0, 2900 - 360, 3100"), btn("Updates", "360, 2900 - 720, 3100"),
        btn("Communities", "720, 2900 - 1080, 3100"), btn("Calls", "1080, 2900 - 1440, 3100"))
    private val home = screen(n(1, "ImageView", d = "Search", id = "menuitem_search", flags = "CF", r = "1100, 160 - 1260, 300"),
        btn("More options", "1260, 160 - 1420, 300"),
        n(1, "LinearLayout", id = "contact_row_container", flags = "CF", r = "0, 400 - 1440, 600"),
        n(2, "TextView", t = "Family group", id = "conversations_row_contact_name", r = "200, 420 - 900, 500"), *tabs)
    private val search = screen(n(1, "EditText", t = "Rahul", id = "search_input", flags = "CF", r = "160, 170 - 1300, 290"),
        n(1, "LinearLayout", id = "contact_row_container", flags = "CF", r = "0, 400 - 1440, 600"),
        n(2, "TextView", t = "Rahul", id = "conversations_row_contact_name", r = "200, 420 - 900, 500"),
        n(1, "LinearLayout", id = "contact_row_container", flags = "CF", r = "0, 600 - 1440, 800"),
        n(2, "TextView", t = "Rahul Sharma (Office)", id = "conversations_row_contact_name", r = "200, 620 - 900, 700"))
    private fun chat(typed: String? = null) = screen(n(1, "TextView", t = "Rahul", id = "conversation_contact_name", flags = "C", r = "200, 170 - 800, 290"),
        btn("Video call", "1000, 160 - 1140, 300"), btn("Voice call", "1140, 160 - 1280, 300"),
        btn("Attach", "700, 2900 - 840, 3040"), n(1, "EditText", t = typed ?: "Message", id = "entry", flags = "CF", r = "100, 2900 - 700, 3040"),
        n(1, "ImageButton", d = "Send", id = "send", flags = "CF", r = "1260, 2900 - 1400, 3040"))

    private val slots = mapOf("contact" to "Rahul")

    @Test fun videoCallIsGlowOnly() {
        val r = AppMaps.routeById("wa_video_call")!!
        assertEquals("menuitem_search", (AppMaps.next(r, pkg, home, 0, slots) as Decision.Glow).node.id)
        val pick = AppMaps.next(r, pkg, search, 1, slots) as Decision.Glow
        assertEquals(2, pick.step)
        assertEquals(Box(0, 400, 1440, 600), pick.node.box)      // the "Rahul" row, top-most, not "Rahul Sharma (Office)"
        val v = AppMaps.next(r, pkg, chat(), 2, slots) as Decision.Glow
        assertEquals("Video call", v.node.label)
        assertTrue(v.risky)
        assertEquals("Tap the camera icon at the top to video call Rahul.", v.say[Lang.EN])
    }

    @Test fun messageTypesThenSendIsRisky() {
        val r = AppMaps.routeById("wa_message")!!
        val s = MapSlots.of(r, "message Rahul saying I will reach by 6")
        assertEquals("Rahul", s["contact"]); assertEquals("I will reach by 6", s["text"])
        val e = AppMaps.next(r, pkg, chat(), 2, s) as Decision.Glow
        assertEquals("entry", e.node.id); assertEquals("I will reach by 6", e.fill)
        assertEquals("Tap the box at the bottom and type your message. I can type: “I will reach by 6”.", e.say[Lang.EN])
        // after typing, Send; it is risky
        val send = AppMaps.next(r, pkg, chat("I will reach by 6"), 3, s) as Decision.Glow
        assertEquals("send", send.node.id); assertTrue(send.risky)
        // with no text slot, the optional part is dropped
        assertEquals("Tap the box at the bottom and type your message.", (AppMaps.next(r, pkg, chat(), 2, slots) as Decision.Glow).say[Lang.EN])
    }

    @Test fun contactOnTheHomeListSkipsTheSearch() {
        val r = AppMaps.routeById("wa_video_call")!!
        val g = AppMaps.next(r, pkg, home, 0, mapOf("contact" to "Family group")) as Decision.Glow
        assertEquals(2, g.step)
    }

    @Test fun sharedLocationOpensMaps() {
        val r = AppMaps.routeById("wa_open_location")!!
        val c = chat() + Fixtures.tree(n(1, "LinearLayout", d = "Location: Charminar, Hyderabad", flags = "CF", r = "100, 1200 - 1000, 1700"))
        val g = AppMaps.next(r, pkg, c, 2, slots) as Decision.Glow
        assertEquals("Location: Charminar, Hyderabad", g.node.label)
        // the attach menu's "Location" button is never the bubble
        assertTrue(g.node.label != "Location")
    }

    @Test fun businessAppUsesTheSameMap() {
        assertEquals("wa_home", AppMaps.screenOf("com.whatsapp.w4b", home))
    }
    @Test fun anotherPersonsChatIsWrongScreenForMessageAndCalls() {
        val wrong = chat().map { if (it.id == "conversation_contact_name") it.copy(text = "Priya") else it }
        for (id in listOf("wa_message", "wa_video_call", "wa_voice_call")) {
            val decision = AppMaps.next(AppMaps.routeById(id)!!, pkg, wrong, 2, slots)
            assertTrue("$id must not target Priya's chat: $decision", decision is Decision.WrongScreen)
        }
    }

    @Test fun lowMissedCallBubbleNeverBecomesVideoTarget() {
        val route = AppMaps.routeById("wa_video_call")!!
        // Real failure shape: the bubble can expose exactly 'Video call' with 'No answer' as a child.
        for (label in listOf("Video call", "Video call · No answer")) {
            val bubble = Fixtures.tree(
                n(1, "LinearLayout", d = label, flags = "CF", r = "100, 1600 - 1300, 2000"),
                n(2, "TextView", t = "No answer", r = "200, 1800 - 800, 1900"))
            val withTop = AppMaps.next(route, pkg, chat() + bubble, 2, slots) as Decision.Glow
            assertEquals(3, withTop.step)
            assertEquals("Video call", withTop.node.label)
            assertTrue(withTop.box.t < 500)
            assertTrue(withTop.risky)
            val withoutTop = chat().filterNot { it.label == "Video call" } + bubble
            val missing = AppMaps.next(route, pkg, withoutTop, 2, slots)
            assertTrue("Missing top-bar control must not glow the bubble: $missing", missing is Decision.Scroll)
        }
    }
}
