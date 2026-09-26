package com.saathi.app.guide

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saathi.app.service.SaathiService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Routines: repeated daily help. "Every morning at 7 play Hanuman Chalisa", "remind me to call Rahul every day at 8 pm",
 * and Saathi's own spoken medicine reminders. At the time, Saathi asks (card + voice) and runs the goal only on "Yes".
 * Stored in app-private prefs; scheduled with inexact alarms (no special permission), re-armed after reboot.
 */
object Routines {
    data class Routine(val id: Int, val hour: Int, val minute: Int, val goal: String, val kind: String /* "do" | "remind" (daily) | "once" (offer) | "note" (one-time reminder) */) {
        val time get() = Skills.fmt(hour, minute)
    }

    private fun sp(c: Context) = c.getSharedPreferences("saathi_routines", Context.MODE_PRIVATE)

    fun all(c: Context): List<Routine> = runCatching {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Routine(it.getInt("id"), it.getInt("h"), it.getInt("m"), it.getString("g"), it.optString("k", "do")) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Routine>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute).put("g", it.goal).put("k", it.kind)) }
        sp(c).edit().putString("list", a.toString()).apply()
    }

    fun add(c: Context, hour: Int, minute: Int, goal: String, kind: String): Routine {
        val list = all(c).filterNot { it.goal.equals(goal, true) && it.hour == hour && it.minute == minute }
        val r = Routine((list.maxOfOrNull { it.id } ?: 0) + 1, hour, minute, goal, kind)
        save(c, list + r)
        schedule(c, r)
        return r
    }

    fun remove(c: Context, id: Int) {
        all(c).firstOrNull { it.id == id }?.let { cancel(c, it) }
        save(c, all(c).filterNot { it.id == id })
    }

    fun rescheduleAll(c: Context) = all(c).forEach { schedule(c, it) }

    private fun pi(c: Context, r: Routine) = PendingIntent.getBroadcast(c, 7000 + r.id,
        Intent(c, RoutineReceiver::class.java).putExtra("id", r.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context, r: Routine) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 5_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Reminders must be on time (vivo delays inexact alarms by minutes); offers can be a little late.
        val exact = r.kind != "do" && (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(c, r))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi(c, r))
    }

    private fun cancel(c: Context, r: Routine) { c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, r)) }

    /** Pure: "every morning at 7 play bhajan" / "रोज़ सुबह 7 बजे भजन लगाओ" → (hour, minute, goal) or null. */
    fun parse(text: String): Triple<Int, Int, String>? {
        val g = text.trim()
        val every = Regex("(?i)\\b(every ?day|every morning|every evening|every night|daily|each day)\\b|रोज़|रोज|हर दिन|प्रतिदिन|రోజూ|ప్రతి రోజు")
        if (!every.containsMatchIn(g)) return null
        val s = SlotExtractor.from(g)
        var h = s.hour ?: when {
            Regex("(?i)morning|सुबह|ఉదయం").containsMatchIn(g) -> 8
            Regex("(?i)evening|शाम|సాయంత్రం").containsMatchIn(g) -> 18
            Regex("(?i)night|रात|రాత్రి").containsMatchIn(g) -> 21
            else -> return null
        }
        if (s.hour != null && h < 12 && Regex("(?i)evening|night|शाम|रात|సాయంత్రం|రాత్రి").containsMatchIn(g)) h += 12
        val goal = g.replace(every, " ")
            .replace(Regex("(?i)\\b(at\\s+)?\\d{1,2}([:.]\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.|baje|बजे|గంటలకు)?"), " ")
            .replace(Regex("(?i)\\b(in the )?(morning|evening|night)\\b|सुबह|शाम|रात|ఉదయం|సాయంత్రం|రాత్రి|\\bat\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().trim(',', '.')
        if (goal.length < 3) return null
        return Triple(h, s.minute ?: 0, goal)
    }
}

/**
 * One-time reminders, set by Saathi itself (offline, spoken at the time): "remind me at 8 pm to take my tablet",
 * "remind me in 10 minutes to switch off the gas", "शाम 6 बजे दवा की याद दिलाना", "10 నిమిషాల్లో గ్యాస్ ఆపమని గుర్తు చేయి".
 */
object Reminders {
    private val ASK = Regex("(?i)\\bremind(er)?\\b|याद दिलाना|याद दिलाओ|याद दिला|గుర్తు చేయి|గుర్తు చెయ్యి|గుర్తు చేయండి")
    private val IN = Regex("(?i)\\b(?:in|after)\\s+(\\d{1,3}|half an?|an?|one)\\s*(min(?:ute)?s?|hours?|hrs?)\\b|(\\d{1,3})\\s*(मिनट|घंटे|घंटा)\\s*(?:में|बाद)|(\\d{1,3})\\s*(నిమిషాల|గంటల)")
    private val AT = Regex("(?i)(?:\\bat\\s+|\\b)(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'?clock|baje|बजे|గంటలకు)?")

    /** Pure: (minutes from midnight of the due time, delay in minutes or null, what to say) or null if not a one-time reminder. */
    fun parse(text: String, nowHour: Int, nowMinute: Int): Triple<Int, Int, String>? {
        val g = text.trim()
        if (!ASK.containsMatchIn(g) || Routines.parse(g) != null) return null
        val now = nowHour * 60 + nowMinute
        var due: Int? = null
        var used: String? = null
        IN.find(g)?.let { m ->
            val v = m.groupValues
            val (n, unit) = when {
                v[1].isNotEmpty() -> v[1] to v[2]
                v[3].isNotEmpty() -> v[3] to v[4]
                else -> v[5] to v[6]
            }
            val num = when { n.startsWith("half", true) -> 30; n.equals("a", true) || n.equals("an", true) || n.equals("one", true) -> 1; else -> n.toIntOrNull() ?: 1 }
            val mins = if (Regex("(?i)^h|घंट|గంట").containsMatchIn(unit) && !n.startsWith("half", true)) num * 60 else num
            due = now + mins; used = m.value
        }
        if (due == null) {
            val m = AT.findAll(g).firstOrNull { it.groupValues[3].isNotEmpty() || it.value.trimStart().startsWith("at", true) } ?: return null
            var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h > 23 || min > 59) return null
            val ap = m.groupValues[3].lowercase()
            when {
                ap.startsWith("p") && h < 12 -> h += 12
                ap.startsWith("a") && h == 12 -> h = 0
                Regex("(?i)evening|night|tonight|शाम|रात|సాయంత్రం|రాత్రి").containsMatchIn(g) && h < 12 -> h += 12
                Regex("(?i)morning|सुबह|ఉదయం").containsMatchIn(g) -> {}
                !ap.startsWith("a") && h in 1..11 && h * 60 + min <= now && (h + 12) * 60 + min > now -> h += 12 // "at 8" at 6 pm → 8 pm
            }
            due = h * 60 + min; used = m.value
        }
        val filler = "(?:me|please|to|about|at|in|that|today|tonight|this evening|this morning|की|को|कि|మని|అని|,)"
        val what = g.replace(used!!, " ").replace(ASK, " ")
            .replace(Regex("(?i)(?<![\\p{L}\\p{M}])(सुबह|शाम|रात|ఉదయం|సాయంత్రం|రాత్రి|tonight|today)(?![\\p{L}\\p{M}])"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("(?i)^(?:$filler(?![\\p{L}\\p{M}])\\s*)+"), "")
            .replace(Regex("(?i)(?:\\s*(?<![\\p{L}\\p{M}])$filler)+$"), "")
            .trim().trim(',', '.', ':').ifEmpty { "your reminder" }
        return Triple(((due!! % 1440) + 1440) % 1440, (due!! - now).coerceAtLeast(0), what)
    }
}

/** Fires at the routine's time: ask via the running service, then re-arm for tomorrow. */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) { Routines.rescheduleAll(c); return }
        val r = Routines.all(c).firstOrNull { it.id == i.getIntExtra("id", -1) } ?: return
        if (r.kind == "once" || r.kind == "note") Routines.remove(c, r.id) else Routines.schedule(c, r)
        SaathiService.instance?.guide?.routineDue(r)
    }
}
