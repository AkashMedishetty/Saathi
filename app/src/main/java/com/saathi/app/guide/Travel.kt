package com.saathi.app.guide

import android.content.Context
import java.util.Calendar

/**
 * IRCTC Tatkal: the most stressful phone task for elders (a window that opens at 10:00 AC / 11:00 non-AC, the day
 * before travel, and fills in minutes). Saathi (1) sets a "get ready" call 10 minutes early, (2) walks the Rail Connect
 * booking step by step (auto mode fills stations/quota fast), and (3) never types the password, captcha or UPI PIN,
 * and asks before Pay. Labels follow IRCTC Rail Connect; screens that differ fall back to the on-device planner.
 */
object Travel {
    const val IRCTC = "cris.org.in.prs.ima"

    private fun s3(en: String, hi: String, te: String) = say(en, hi, te)
    private val NONE = s3("", "", "")

    /** "to Delhi", "from Hyderabad to Delhi", "दिल्ली का", "ఢిల్లీకి" → (from, to). */
    fun stations(raw: String): Pair<String?, String?> {
        val from = Regex("(?i)from\\s+([\\p{L}\\p{M} ]+?)(?=\\s+to\\b|$)").find(raw)?.groupValues?.get(1)?.trim()
        val to = Regex("(?i)\\bto\\s+([\\p{L}\\p{M} ]+?)(?=\\s+(on|for|tomorrow|today|in)\\b|$)").find(raw)?.groupValues?.get(1)?.trim()
            ?: Regex("([\\p{L}\\p{M}]+)\\s+(का|के लिए)\\s+तत्काल").find(raw)?.groupValues?.get(1)
            ?: Regex("([\\p{L}\\p{M}]+?)(కి|కు)\\s+తత్కాల్").find(raw)?.groupValues?.get(1)
        return from?.takeIf { it.length > 1 } to to?.takeIf { it.length > 1 && !it.equals("book", true) }
    }

    fun tatkal(ctx: Context, s: Slots): Flow {
        val g = s.raw.lowercase()
        // "remind me / get ready for tatkal" → one-time calls 10 minutes before each window.
        if (Regex("remind|ready|prepare|alarm|याद|तैयार|గుర్తు|సిద్ధం").containsMatchIn(g)) {
            return Flow("tatkal_ready", null, emptyList(), null, NONE, NONE, action = { c ->
                Routines.add(c, 9, 50, "book a tatkal ticket (AC opens at 10)", "once")
                Routines.add(c, 10, 50, "book a tatkal ticket (sleeper opens at 11)", "once")
                s3("Done. At 9:50 and 10:50 I'll call you to open IRCTC and log in, before Tatkal opens.",
                    "ठीक है। 9:50 और 10:50 पर मैं याद दिलाऊँगा ताकि तत्काल खुलने से पहले IRCTC खोलकर लॉग इन कर लें।",
                    "సరే. 9:50, 10:50 కి గుర్తు చేస్తాను — తత్కాల్ తెరవక ముందే IRCTC లో లాగిన్ అవ్వండి.")
            })
        }
        val (from, to) = stations(s.raw)
        val window = window()
        val steps = listOf(
            Step("home", rx("^Book Ticket", "^Train Search", "^Plan My Journey", "^Book Your Ticket"),
                s3("Tap Book Ticket.", "'Book Ticket' दबाइए।", "'Book Ticket' నొక్కండి."),
                tip = s3("Tatkal opens at 10 AM for AC and 11 AM for sleeper, one day before travel. Be logged in 5 minutes early.",
                    "तत्काल AC के लिए सुबह 10 बजे और स्लीपर के लिए 11 बजे खुलता है, यात्रा से एक दिन पहले। 5 मिनट पहले लॉग इन रहिए।",
                    "తత్కాల్ AC కి ఉదయం 10, స్లీపర్‌కి 11 కి తెరుస్తుంది, ప్రయాణానికి ఒక రోజు ముందు. 5 నిమిషాలు ముందే లాగిన్ అవ్వండి.")),
            Step("from", rx("^From$", "^From Station", "^Source", "^Boarding"), s3("Tap From and choose where you start.", "'From' दबाकर कहाँ से जाना है चुनिए।", "'From' నొక్కి ఎక్కడి నుంచో ఎంచుకోండి.")),
            Step("from_type", rx("station name", "Enter station", "Search station", "Station Name/Code"), s3(
                if (from != null) "Type $from." else "Type your starting station.", if (from != null) "$from लिखिए।" else "अपना स्टेशन लिखिए।", if (from != null) "$from టైప్ చేయండి." else "మీ స్టేషన్ టైప్ చేయండి."),
                role = "input", fill = from, unlessVisible = rx("^To$", "^To Station")),
            Step("to", rx("^To$", "^To Station", "^Destination"), s3("Tap To and choose where you're going.", "'To' दबाकर कहाँ जाना है चुनिए।", "'To' నొక్కి ఎక్కడికో ఎంచుకోండి.")),
            Step("to_type", rx("station name", "Enter station", "Search station", "Station Name/Code"), s3(
                if (to != null) "Type $to." else "Type where you're going.", if (to != null) "$to लिखिए।" else "जहाँ जाना है, लिखिए।", if (to != null) "$to టైప్ చేయండి." else "ఎక్కడికో టైప్ చేయండి."),
                role = "input", fill = to, screenHas = Regex("(?i)\\bto\\b|destination")),
            Step("date", rx("^Journey Date", "^Date of Journey", "^Departure Date", "^Date$"), s3("Tap the date and choose tomorrow.", "तारीख़ दबाकर कल की तारीख़ चुनिए।", "తేదీ నొక్కి రేపటి తేదీ ఎంచుకోండి.")),
            Step("quota", rx("^Quota", "^GENERAL$", "^General$"), s3("Tap Quota.", "'Quota' दबाइए।", "'Quota' నొక్కండి.")),
            Step("tatkal", rx("^TATKAL$", "^Tatkal$"), s3("Choose TATKAL.", "'TATKAL' चुनिए।", "'TATKAL' ఎంచుకోండి.")),
            Step("search", rx("^Search Trains?$", "^Find Trains?$", "^Search$"), s3("Tap Search Trains.", "'Search Trains' दबाइए।", "'Search Trains' నొక్కండి.")),
            Step("class", rx("^3A", "^SL$", "^2A", "^CC$", "^Sleeper", "^AC 3 Tier", "^3E"), s3("Tap your class: SL is sleeper, 3A is AC.", "अपनी क्लास दबाइए: SL स्लीपर, 3A AC है।", "మీ క్లాస్ నొక్కండి: SL స్లీపర్, 3A AC."),
                screenHas = Regex("(?i)tatkal|TQWL|available|AVL")),
            Step("book", rx("^Book Now$", "^Book$"), s3("It shows seats. Tap Book Now.", "सीट दिख रही है। 'Book Now' दबाइए।", "సీట్లు ఉన్నాయి. 'Book Now' నొక్కండి.")),
            Step("password", rx("^Password"), s3("Type your IRCTC password yourself. I never type passwords.", "अपना IRCTC पासवर्ड ख़ुद लिखिए। मैं पासवर्ड कभी नहीं लिखता।", "మీ IRCTC పాస్‌వర్డ్ మీరే టైప్ చేయండి. నేను పాస్‌వర్డ్ టైప్ చేయను."), role = "input"),
            Step("passenger", rx("^Add Passenger", "^Add New Passenger", "^Add Existing", "^Master List"), s3("Tap Add Passenger and pick the person travelling.", "'Add Passenger' दबाकर यात्री चुनिए।", "'Add Passenger' నొక్కి ప్రయాణికుడిని ఎంచుకోండి.")),
            Step("review", rx("^Review Journey", "^Continue$", "^Proceed$", "^Next$"), s3("Check the names and tap Continue.", "नाम जाँचकर 'Continue' दबाइए।", "పేర్లు చూసి 'Continue' నొక్కండి.")),
            Step("captcha", rx("captcha", "Enter the characters", "Security"), s3("Type the letters you see in the picture. Only you can do this part.", "तस्वीर में दिख रहे अक्षर लिखिए। यह आप ही कर सकते हैं।", "బొమ్మలో కనిపించే అక్షరాలు టైప్ చేయండి. ఇది మీరే చేయాలి."), role = "input"),
            Step("pay", rx("^Pay & Book", "^Make Payment", "^Pay ", "^Proceed to Pay"), s3("Check the amount, then tap Pay. Type your UPI PIN yourself.", "रक़म जाँचकर 'Pay' दबाइए। UPI PIN आप ख़ुद डालिए।", "మొత్తం చూసి 'Pay' నొక్కండి. UPI PIN మీరే టైప్ చేయండి.")),
        )
        return Flow("irctc_tatkal", { c -> AppLauncher.launch(c, IRCTC) }, steps,
            { sc -> Regex("(?i)booking (is )?confirmed|PNR\\s*(No|Number)?\\s*[:#]?\\s*\\d").containsMatchIn(sc.allText) },
            s3("Booked! Your PNR is on the screen. I'll keep a note.", "टिकट बुक हो गया! PNR स्क्रीन पर है।", "టికెట్ బుక్ అయింది! PNR స్క్రీన్ మీద ఉంది."),
            s3("Let's book a Tatkal ticket${to?.let { " to $it" } ?: ""}. $window", "चलिए तत्काल टिकट बुक करते हैं${to?.let { " — $it" } ?: ""}। AC तत्काल 10 बजे, स्लीपर 11 बजे खुलता है।",
                "తత్కాల్ టికెట్ బుక్ చేద్దాం${to?.let { " — $it" } ?: ""}. AC తత్కాల్ 10కి, స్లీపర్ 11కి తెరుస్తుంది."),
            teach = true, llmGoal = "book a Tatkal train ticket${to?.let { " to $it" } ?: ""} on IRCTC")
    }

    /** A one-line status of the Tatkal windows right now. */
    private fun window(): String {
        val c = Calendar.getInstance(); val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return when {
            m < 600 -> "AC Tatkal opens at 10:00, sleeper at 11:00."
            m < 660 -> "AC Tatkal is open now; sleeper opens at 11:00."
            else -> "Tatkal is open now for tomorrow's trains."
        }
    }
}
