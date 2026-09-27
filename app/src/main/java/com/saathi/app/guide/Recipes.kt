package com.saathi.app.guide

import android.content.Context
import com.saathi.app.policy.ActionPolicy
import com.saathi.app.policy.ActionRequest
import com.saathi.app.policy.Kind
import com.saathi.app.policy.Mode
import com.saathi.app.policy.Redactor
import com.saathi.app.policy.Verdict
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Teach once: ordered labelled taps and the last safe value of each labelled input. */
object Recipes {
    // Default preserves existing callers and recipes saved before text recording was introduced.
    data class Tap(val label: String, val role: String, val pkg: String, val fill: String? = null)
    data class Recipe(val name: String, val pkg: String, val taps: List<Tap>, val at: Long)

    private fun sp(c: Context) = c.getSharedPreferences("saathi_recipes", Context.MODE_PRIVATE)
    @Volatile var recording: String? = null; private set
    private val buffer = mutableListOf<Tap>()

    @Synchronized fun startRecording(name: String) { recording = name; buffer.clear() }

    private fun identity(label: String) = label.trim().lowercase(Locale.ROOT)
    private fun ignoredPackage(pkg: String) = pkg.isBlank() || pkg.contains("launcher") ||
        pkg == "com.saathi.app" || pkg == "com.android.systemui"

    /** Called for clicks; input labels must be stable hints/descriptions, never the entered value. */
    @Synchronized fun onClick(label: String?, role: String, pkg: String) {
        if (recording == null) return
        val l = label?.trim()?.takeIf { it.length in 1..60 } ?: return
        if (ignoredPackage(pkg)) return
        if (Redactor.forMemory(l).isEmpty()) return
        if (role == "password" || (role == "input" && !safeText(l, "text", pkg, false))) return
        if (role == "input" && buffer.any { it.role == "input" && it.pkg == pkg && identity(it.label) == identity(l) }) return
        val tap = Tap(l, role, pkg)
        if (buffer.lastOrNull() == tap) return
        buffer += tap
    }

    /**
     * Debounced TYPE_VIEW_TEXT_CHANGED. Caller MUST exclude password nodes before using this overload.
     * Use the four-argument overload when the source's isPassword flag is available. A null/blank label
     * cannot identify a field safely; use its stable hint/description, not source.text or the typed value.
     */
    fun onText(label: String?, text: String, pkg: String) = onText(label, text, pkg, isPassword = false)

    @Synchronized fun onText(label: String?, text: String, pkg: String, isPassword: Boolean) {
        if (recording == null || ignoredPackage(pkg)) return
        val l = label?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val index = buffer.indexOfFirst { it.role == "input" && it.pkg == pkg && identity(it.label) == identity(l) }
        // Drop the previous value too: a debounced partial OTP must not survive a rejected final edit.
        if (l.length > 60 || !safeText(l, text, pkg, isPassword)) {
            if (index >= 0) buffer.removeAt(index)
            return
        }
        val input = Tap(l, "input", pkg, text)
        if (index >= 0) buffer[index] = input else buffer += input
    }

    private fun safeText(label: String, text: String, pkg: String, isPassword: Boolean): Boolean {
        if (text.isBlank() || text.length > 10_000 || Redactor.forMemory(label).isEmpty() ||
            Redactor.forMemory(text).isEmpty()) return false
        // Redactor identifies labelled secrets and long account numbers. Also reject short bare numeric
        // edits and embedded 4–8 digit code-shaped runs, including Hindi/Telugu digits and separated codes.
        val digits = text.map { if (it.isDigit()) Character.digit(it, 10).toString() else it.toString() }.joinToString("")
        if (digits.trim().matches(Regex("[0-9][0-9\\s-]*")) ||
            Regex("(?<![\\p{L}\\p{N}])[0-9](?:[ -]?[0-9]){3,7}(?![\\p{L}\\p{N}])").containsMatchIn(digits)) return false
        return ActionPolicy.check(ActionRequest(Kind.TYPE, pkg, label, "input", isPassword, text,
            screenText = "", mode = Mode.DO_IT_ONCE)) == Verdict.Allow
    }

    /** Pure drain shared with JVM tests; rejected text never enters the returned/persisted recipe. */
    @Synchronized internal fun finishRecording(at: Long): Recipe? {
        val name = recording ?: return null
        recording = null
        val taps = buffer.toList(); buffer.clear()
        if (taps.isEmpty()) return null
        return Recipe(name, taps.first().pkg, taps, at)
    }

    fun stopRecording(c: Context): Recipe? {
        val r = finishRecording(System.currentTimeMillis()) ?: return null
        save(c, all(c).filterNot { it.name.equals(r.name, true) } + r)
        return r
    }

    @Synchronized fun cancelRecording() { recording = null; buffer.clear() }

    // Optional "f" is absent on old tap-only recipes. Recheck saved fills before exposing them for replay.
    fun all(c: Context): List<Recipe> = runCatching {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val t = o.getJSONArray("taps")
            Recipe(o.getString("name"), o.getString("pkg"),
                (0 until t.length()).mapNotNull { j -> t.getJSONObject(j).let {
                    safeStoredTap(Tap(it.getString("l"), it.optString("r", "button"), it.optString("p"),
                        if (it.has("f") && !it.isNull("f")) it.getString("f") else null))
                } }, o.optLong("at"))
        }.filter { it.taps.isNotEmpty() }
    }.getOrDefault(emptyList())

    internal fun safeStoredTap(t: Tap): Tap? = if (t.fill == null) t else
        t.takeIf { it.role == "input" && safeText(it.label, it.fill!!, it.pkg, false) }

    private fun save(c: Context, list: List<Recipe>) {
        val a = JSONArray()
        list.forEach { r ->
            val t = JSONArray()
            r.taps.mapNotNull(::safeStoredTap).forEach {
                val value = JSONObject().put("l", it.label).put("r", it.role).put("p", it.pkg)
                if (it.fill != null) value.put("f", it.fill)
                t.put(value)
            }
            a.put(JSONObject().put("name", r.name).put("pkg", r.pkg).put("taps", t).put("at", r.at))
        }
        sp(c).edit().putString("list", a.toString()).apply()
    }

    fun remove(c: Context, name: String) = save(c, all(c).filterNot { it.name.equals(name, true) })

    private val stop = setOf("the", "my", "a", "an", "to", "on", "in", "me", "please", "for", "your", "and")
    private val word = Regex("[\\p{L}\\p{M}]+")
    private fun words(text: String) = word.findAll(text.lowercase(Locale.ROOT)).map { it.value }
        .filter { it.length >= 2 && it !in stop }.toList()
    private fun stem(w: String): String = when {
        w in setOf("taking", "taken") -> "take"
        w in setOf("making", "made") -> "make"
        w in setOf("writing", "written", "wrote") -> "write"
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("ing") && w.length > 5 -> w.dropLast(3).let {
            if (it.length > 2 && it.last() == it[it.lastIndex - 1]) it.dropLast(1) else it
        }
        w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length > 3 -> w.dropLast(1)
        else -> w
    }
    private fun nameWords(text: String): Set<String> {
        val tokens = words(text).map(::stem).toSet()
        // In note-taking names these are light verbs, so 'take notes', 'make a note', and 'notes' agree.
        return if ("note" in tokens) tokens - setOf("take", "make", "create", "write", "jot") else tokens
    }

    fun find(c: Context, goal: String): Recipe? = find(all(c), goal)

    internal fun find(recipes: List<Recipe>, goal: String): Recipe? {
        val requested = nameWords(goal)
        return recipes.map { r ->
            val own = nameWords(r.name)
            Triple(r, if (own.isEmpty()) 0.0 else own.count { it in requested }.toDouble() / own.size, own.size)
        }.filter { it.second >= 0.75 }
            .sortedWith(compareByDescending<Triple<Recipe, Double, Int>> { it.second }.thenByDescending { it.third })
            .firstOrNull()?.first
    }

    /** Ordered exact, badge-free, then significant-word fallbacks. Unicode boundaries avoid partial words. */
    private fun targets(label: String): List<Regex> {
        val clean = label
            .replace(Regex("[,·•]\\s*\\p{N}+[+]?\\s*(unread(?: messages?)?|new(?: messages?)?|notifications?|items?)?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:\\(|\\[)\\s*\\p{N}+[+]?\\s*(?:\\)|\\])"), "")
            .replace(Regex("(?<![\\p{L}\\p{M}])\\p{N}+[+]?(?![\\p{L}\\p{M}])"), "")
            .replace(Regex("\\b(unread|notifications?|badges?)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ").trim(' ', ',', '·', '•', '-', ':')
        val result = mutableListOf(Regex("^" + Regex.escape(label) + "$", RegexOption.IGNORE_CASE))
        if (clean.isNotEmpty()) result += Regex("^" + Regex.escape(clean) + "(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
        val significant = words(clean).take(3)
        if (significant.isNotEmpty()) result += Regex("(?<![\\p{L}\\p{M}\\p{N}])" +
            significant.joinToString("[^\\p{L}\\p{M}\\p{N}]+") { Regex.escape(it) } +
            "(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
        return result
    }

    fun toFlow(r: Recipe): Flow = Flow(
        id = "recipe_${r.name.lowercase(Locale.ROOT).replace(Regex("\\W+"), "_")}",
        launch = { c -> AppLauncher.launch(c, r.pkg) },
        steps = r.taps.mapNotNull(::safeStoredTap).mapIndexed { i, t ->
            Step("r$i", targets(t.label),
                if (t.fill != null) say("Type the text you showed me in “${t.label.take(40)}”.",
                    "“${t.label.take(40)}” में सिखाया हुआ पाठ लिखिए।", "“${t.label.take(40)}” లో నేర్పిన వచనం టైప్ చేయండి.")
                else say("Tap “${t.label.take(40)}”.", "“${t.label.take(40)}” दबाइए।", "“${t.label.take(40)}” నొక్కండి."),
                role = if (t.role == "input") "input" else null, fill = t.fill)
        },
        isDone = null,
        doneSay = say("Done — just like you were shown!", "हो गया — जैसे सिखाया था!", "అయింది — నేర్పినట్టే!"),
        start = say("I learned this one: “${r.name}”. Follow the glow.", "यह मैंने सीखा है: “${r.name}”। चमक देखिए।", "ఇది నేను నేర్చుకున్నాను: “${r.name}”. మెరుపు చూడండి."),
        teach = true, llmGoal = r.name, appPkg = r.pkg,
    )
}
