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
        "TV <HOME|UP|DOWN|LEFT|RIGHT|OK|BACK|POWER|VOL_UP|VOL_DOWN|MUTE|SOURCE|MENU>  (press that button on their TV through the phone)\n" +
        "LOOK_TV  (they point the phone camera at the TV; you get a description of what the TV shows)\n" +
        "GUIDE <what to do on the phone, e.g. 'book a sleeper ticket from Hyderabad to Delhi on 28 Sep on IRCTC'>  (on-screen step-by-step help takes over)\n" +
        "SAY <one short sentence to tell them>\n" +
        "DONE <one short closing sentence>\n" +
        "Rules: one small step per reply. Never ask for or type passwords, OTP, PIN or card numbers; they do payments themselves. " +
        "Short, warm, simple words. Don't repeat a step that already worked."

    const val EXAMPLE =
        "Example task: play Guntur Kaaram on the TV\n" +
        "LOOKUP where to watch Guntur Kaaram movie online India\n" +
        "Result: ... Guntur Kaaram streaming on Netflix ...\n" +
        "ASK It's on Netflix. Do you have Netflix on your TV?\n" +
        "Person: yes\n" +
        "TV HOME\n" +
        "Result: pressed HOME\n" +
        "LOOK_TV\n" +
        "Result: the TV shows a home screen with app tiles; Netflix is the second tile, the first tile is highlighted\n" +
        "TV RIGHT\n" +
        "Result: pressed RIGHT\n" +
        "TV OK\n" +
        "Result: pressed OK\n" +
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

    /** Coach-worthy goals: the TV, bookings, anything that needs checking and choices. */
    fun wants(goal: String, intent: String?): Boolean =
        Regex("(?i)\\b(on|in) (the |my )?tv\\b|टीवी|టీవీ|book|ticket|tatkal|train|flight|bus|टिकट|ट्रेन|టికెట్|రైలు|plan my|help me (plan|choose)").containsMatchIn(goal) ||
            intent == "book"
}
