package com.saathi.app.guide

/**
 * The coach: for goals that need thinking, not tapping ("play Guntur Kaaram on the TV", "book me a train to Delhi").
 * Like a helpful grandchild: work out what's needed, check facts instead of assuming, ask the person when it's their
 * choice, then guide step by step: on the TV with the IR remote + camera, or on the phone.
 *
 * Gemma runs the conversation and picks ONE tool per turn; the Guide executes it and feeds the result back
 * (same live conversation → it remembers everything). Code keeps it safe: no passwords/OTP/PIN/payments, step cap.
 */
object Coach {
    const val SYSTEM =
        "You are Saathi, coaching an elderly person in India through a real task, one small step at a time, like a patient grandchild.\n" +
        "Think about what is needed. Check facts instead of assuming. Ask them when it is their choice or you need information.\n" +
        "Reply with ONE line: a tool and its argument, nothing else.\n" +
        "Tools:\n" +
        "ASK <short question for them>  (then wait for their answer)\n" +
        "LOOKUP <search words>  (search the internet; you get the results text)\n" +
        "OPEN <app name>  (open an app on their phone)\n" +
        "TV <HOME|UP|DOWN|LEFT|RIGHT|OK|BACK|POWER|VOL_UP|VOL_DOWN|MUTE|SOURCE|MENU>  (tell them to press that ONE button on their TV remote; " +
        "afterwards the camera looks at the TV again and you get what it shows)\n" +
        "LOOK_TV  (the phone camera looks at the TV; you get a description of what the TV shows)\n" +
        "GUIDE <what to do on the phone, e.g. 'book a sleeper ticket from Hyderabad to Delhi on 28 Sep on IRCTC'>  (on-screen step-by-step help takes over)\n" +
        "SAY <one short sentence to tell them>\n" +
        "DONE <one short closing sentence>\n" +
        "For a movie or show on the TV: first LOOKUP where it is streaming, then ASK if they have that app, and only then LOOK_TV and " +
        "guide one button at a time, checking the result each time. For other TV help: LOOK_TV first. " +
        "Rules: one small step per reply. Never ask for or type passwords, OTP, PIN or card numbers; they do payments themselves. " +
        "Short, warm, simple words. Don't repeat a step that already worked."

    const val EXAMPLE =
        "Example task: play Guntur Kaaram on the TV\n" +
        "LOOKUP where to watch Guntur Kaaram movie online India\n" +
        "Result: ... Guntur Kaaram streaming on Netflix ...\n" +
        "ASK It's on Netflix. Do you have Netflix on your TV?\n" +
        "Person: yes\n" +
        "LOOK_TV\n" +
        "Result: the TV shows a news channel\n" +
        "TV HOME\n" +
        "Result: after they pressed HOME, the TV shows: a home screen with app tiles YouTube, NETFLIX, prime video; YouTube is highlighted\n" +
        "TV RIGHT\n" +
        "Result: after they pressed RIGHT, the TV shows: NETFLIX is highlighted\n" +
        "TV OK\n" +
        "Result: after they pressed OK, the TV shows: Netflix home with a Search button at the top\n" +
        "SAY Netflix is opening. Now we'll search for the movie.\n" +
        "(and so on)\n\n"

    data class Call(val tool: String, val arg: String)

    /** Pure: the model's line → a tool call. Tolerates "TOOL: arg", quotes, extra lines. */
    fun parse(raw: String): Call? {
        val line = raw.lines().map { it.trim().trim('`') }.firstOrNull {
            Regex("^(ASK|LOOKUP|OPEN|TV|LOOK_TV|GUIDE|SAY|DONE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
        } ?: return null
        val tool = line.substringBefore(' ').substringBefore(':').uppercase()
        val arg = line.substringAfter(tool, "").removePrefix(":").trim().trim('"', '<', '>')
        return Call(tool, arg)
    }

    private val TV = Regex("(?i)\\btv\\b|टीवी|టీవీ")
    private val WATCH = Regex("(?i)\\b(play|watch|put|see|movie|film|serial|show|episode)\\b|देख|लगाओ|चलाओ|फ़िल्म|फिल्म|చూడ|పెట్టు|సినిమా")

    /** "play Guntur Kaaram on my TV", "guntur karam movie on tv". */
    fun isWatchOnTv(goal: String) = TV.containsMatchIn(goal) && WATCH.containsMatchIn(goal)

    /** The title with the filler words removed ("play guntur karam movie on my tv" → "guntur karam movie"). */
    fun title(goal: String): String =
        goal.replace(Regex("(?i)\\b(please|can you|could you|i want to|let's|play|watch|put|see|show me|on|in|my|the|a|tv|for me|now)\\b|टीवी|టీవీ|पर|में|లో"), " ")
            .replace(Regex("\\s+"), " ").trim().ifEmpty { goal }

    /** Coach-worthy goals: the TV, bookings, anything that needs checking and choices. */
    fun wants(goal: String, intent: String?): Boolean =
        Regex("(?i)\\b(on|in) (the |my )?tv\\b|टीवी|టీవీ|book|flight|bus|plan my|help me (plan|choose)").containsMatchIn(goal) ||
            intent == "book"
}
