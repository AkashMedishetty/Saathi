package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One-time reminders: time + what, in EN / HI / TE. Now = 18:00. */
class RemindersTest {
    private fun p(t: String) = Reminders.parse(t, 18, 0)?.let { (due, _, what) -> "%02d:%02d|%s".format(due / 60, due % 60, what) }

    @Test fun english() {
        assertEquals("20:00|take my tablet", p("remind me at 8 pm to take my tablet"))
        assertEquals("20:00|take my tablet", p("remind me to take my tablet at 8"))
        assertEquals("18:10|switch off the gas", p("remind me in 10 minutes to switch off the gas"))
        assertEquals("19:00|call Rahul", p("remind me in an hour to call Rahul"))
        assertEquals("07:30|go for a walk", p("remind me at 7:30 am to go for a walk"))
    }

    @Test fun hindiTelugu() {
        assertEquals("21:00|दवा", p("रात 9 बजे दवा की याद दिलाना"))
        assertEquals("18:10|गैस बंद करने", p("10 मिनट में गैस बंद करने की याद दिलाना"))
        assertEquals("18:10", p("10 నిమిషాల్లో గ్యాస్ ఆపమని గుర్తు చేయి")?.substringBefore('|'))
    }

    @Test fun notOneTime() {
        assertNull(p("remind me every day at 8 pm to take my tablet")) // daily routine
        assertNull(p("set an alarm for 6 am"))
        assertNull(p("remind me to call Rahul")) // no time: the reminder skill asks
    }
}
