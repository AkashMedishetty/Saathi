package com.saathi.app.guide

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every phrase a grandparent might say (EN / HI / TE) → the skill it must reach. `./gradlew testDebugUnitTest` */
class RoutingTest {
    private val cases = listOf(
        "make text bigger" to "font",
        "I can't read the letters" to "font",
        "अक्षर बड़े करो" to "font",
        "అక్షరాలు పెద్దవి చేయి" to "font",
        "video call my son on whatsapp" to "wa_video",
        "video call Rahul" to "wa_video",
        "बेटे को वीडियो कॉल करो" to "wa_video",
        "కొడుకుకి వీడియో కాల్ చేయి" to "wa_video",
        "send a whatsapp message to Priya saying I reached home" to "wa_message",
        "message my daughter" to "wa_message",
        "बेटी को मैसेज भेजो" to "wa_message",
        "send a photo to my son on whatsapp" to "wa_photo",
        "बेटे को फोटो भेजो" to "wa_photo",
        "call my son" to "call",
        "बेटे को फ़ोन करो" to "call",
        "కొడుకుకి ఫోన్ చేయి" to "call",
        "play Hanuman Chalisa on YouTube" to "youtube",
        "play a bhajan" to "youtube",
        "भजन लगाओ" to "youtube",
        "పాట పెట్టు" to "youtube",
        "watch a movie on Netflix" to "ott",
        "I want to watch my serial on hotstar" to "ott",
        "remind me to take BP medicine at 8 am" to "medicine",
        "दवा की याद दिलाओ" to "medicine",
        "మందు గుర్తు చేయి" to "medicine",
        "set an alarm for 6 am" to "alarm",
        "सुबह 6 बजे का अलार्म लगाओ" to "alarm",
        "take me to the nearest hospital" to "maps",
        "अस्पताल का रास्ता दिखाओ" to "maps",
        "take a selfie" to "camera",
        "फोटो खींचो" to "camera",
        "turn on the torch" to "torch",
        "टॉर्च जलाओ" to "torch",
        "my phone is silent" to "volume",
        "I can't hear anything" to "volume",
        "आवाज़ नहीं आ रही" to "volume",
        "teach me to edit a photo" to "learn_photo",
        "how do I trim a video" to "learn_video",
        "turn on wifi" to "wifi",
        "वाईफाई चालू करो" to "wifi",
        "internet is not working" to "internet",
        "connect my earphones" to "bluetooth",
        "battery finishes quickly" to "battery",
        "my phone storage is full" to "storage",
        "back up my phone" to "backup",
        "backup whatsapp chats" to "backup",
        "open google pay" to "real_upi",
        "make the screen brighter" to "brightness",
        "take me to the home screen" to "home",
        "होम स्क्रीन पर ले चलो" to "home",
        "read this letter for me" to "read_this",
        "turn the tv volume up" to "tv",
        "टीवी बंद करो" to "tv",
        "open phone school" to "phone_school",
        "यह काग़ज़ पढ़कर सुनाओ" to "read_this",
        "scan my medicine strip" to "scan_medicine",
        "दवा का पत्ता स्कैन करो" to "scan_medicine",
    )

    @Test fun everyPhraseRoutesToTheRightSkill() {
        val wrong = cases.mapNotNull { (goal, want) ->
            val got = Skills.match(goal)?.id
            if (got != want) "\"$goal\" → $got (want $want)" else null
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test fun familyHelpIsRedacted() {
        val m = FamilyHelp.message("Kamala", "Settings", "Account 1234 5678, pay ₹5,000 to x@y.com", "send 500 rupees http://bit.ly/x", false, Lang.EN)
        assert(!Regex("\\d{3,}").containsMatchIn(m)) { m }
        assert("x@y.com" !in m && "bit.ly" !in m) { m }
        val bank = FamilyHelp.message("Kamala", "GPay", "Balance 12,000", "pay", true, Lang.EN)
        assert("GPay" !in bank && "12" !in bank) { bank }
        assert(IntentRouter.isFamilyHelp("ask my son for help"))
        assert(IntentRouter.isFamilyHelp("बेटे से पूछो"))
    }

    @Test fun messageScamsAreCaught() {
        assertEquals("otp", MessageScam.check("Dear customer, share the OTP with our executive to stop the charge")?.id)
        assertEquals("kyc", MessageScam.check("Your SBI KYC is pending, account will be blocked today. Update now")?.id)
        assertEquals("electricity", MessageScam.check("Dear consumer your electricity power will be disconnected tonight at 9.30pm")?.id)
        assertEquals("link", MessageScam.check("Check this bit.ly/3xYz")?.id)
        assertEquals(null, MessageScam.check("Reached home safely. Call you later, Ma"))
        assert(IntentRouter.isReadMessages("read my messages"))
    }

    @Test fun irPatternsAreStandard() {
        val p = IrRemote.pattern(0x20DF10EFL, samsung = false)
        assertEquals(9000, p[0]); assertEquals(4500, p[1]); assertEquals(67, p.size)
        assertEquals(IrRemote.Key.VOL_UP, IrRemote.keyFor("tv volume up"))
        assertEquals(IrRemote.Key.POWER, IrRemote.keyFor("टीवी बंद करो"))
    }

    @Test fun slotsPullOutPeopleTimesAndPlaces() {
        assertEquals("Rahul", SlotExtractor.from("video call Rahul on WhatsApp").contact)
        assertEquals("Rahul", SlotExtractor.from("call my son", family = "Rahul").contact)
        assertEquals("Rahul", SlotExtractor.from("बेटे को वीडियो कॉल करो", family = "Rahul").contact)
        assertEquals(20, SlotExtractor.from("remind me at 8 pm").hour)
        assertEquals(8, SlotExtractor.from("सुबह 8 बजे दवा").hour)
        assertEquals(21, SlotExtractor.from("रात 9 बजे अलार्म").hour)
        assertEquals("हनुमान चालीसा", SlotExtractor.searchPhrase("हनुमान चालीसा लगाओ यूट्यूब पर"))
        assertEquals("Hanuman Chalisa", SlotExtractor.searchPhrase("Hanuman Chalisa on YouTube"))
        assertEquals("nearest hospital", SlotExtractor.from("take me to the nearest hospital").place)
    }
}
