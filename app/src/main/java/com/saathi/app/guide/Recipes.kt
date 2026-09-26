package com.saathi.app.guide

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Teach Saathi once": a family member does a task while Saathi watches (accessibility click events: labels only,
 * never pixels). The recording becomes a guide the elder can follow next time, in any app, with no code from us.
 *
 * A recipe = name + the app + the ordered labels that were tapped (with the screen they were on).
 * Replaying = a Flow whose steps are those labels; the guide's matching ("latest visible step wins", learned labels,
 * scroll hints, planner fallback) makes it robust to small layout changes.
 */
object Recipes {
    data class Tap(val label: String, val role: String, val pkg: String)
    data class Recipe(val name: String, val pkg: String, val taps: List<Tap>, val at: Long)

    private fun sp(c: Context) = c.getSharedPreferences("saathi_recipes", Context.MODE_PRIVATE)

    // ── recording ──
    @Volatile var recording: String? = null; private set
    private val buffer = mutableListOf<Tap>()
    private var startPkg: String? = null

    fun startRecording(name: String) { recording = name; buffer.clear(); startPkg = null }

    /** Called for every click the person makes while recording. */
    fun onClick(label: String?, role: String, pkg: String) {
        if (recording == null) return
        val l = label?.trim()?.takeIf { it.length in 1..60 } ?: return
        if (pkg.contains("launcher") || pkg == "com.saathi.app" || pkg == "com.android.systemui") { if (buffer.isEmpty()) return }
        if (startPkg == null) startPkg = pkg
        if (buffer.lastOrNull()?.label == l) return // double taps
        buffer += Tap(l, role, pkg)
    }

    /** Stop and save. Returns the recipe (null if nothing useful was recorded). */
    fun stopRecording(c: Context): Recipe? {
        val name = recording ?: return null
        recording = null
        val taps = buffer.toList(); buffer.clear()
        if (taps.isEmpty()) return null
        val r = Recipe(name, startPkg ?: taps.first().pkg, taps, System.currentTimeMillis())
        save(c, all(c).filterNot { it.name.equals(name, true) } + r)
        return r
    }

    fun cancelRecording() { recording = null; buffer.clear() }

    // ── storage ──
    fun all(c: Context): List<Recipe> = runCatching {
        val a = JSONArray(sp(c).getString("list", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i); val t = o.getJSONArray("taps")
            Recipe(o.getString("name"), o.getString("pkg"),
                (0 until t.length()).map { j -> t.getJSONObject(j).let { Tap(it.getString("l"), it.optString("r", "button"), it.optString("p")) } },
                o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Recipe>) {
        val a = JSONArray()
        list.forEach { r ->
            val t = JSONArray(); r.taps.forEach { t.put(JSONObject().put("l", it.label).put("r", it.role).put("p", it.pkg)) }
            a.put(JSONObject().put("name", r.name).put("pkg", r.pkg).put("taps", t).put("at", r.at))
        }
        sp(c).edit().putString("list", a.toString()).apply()
    }

    fun remove(c: Context, name: String) = save(c, all(c).filterNot { it.name.equals(name, true) })

    /** The recipe whose name best matches what they asked (word overlap). */
    fun find(c: Context, goal: String): Recipe? {
        val stop = setOf("the", "my", "a", "an", "to", "on", "in", "me", "please", "for")
        fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 2 && it !in stop }.toSet()
        val w = words(goal)
        // Most of the recipe's own words must be in the request ("open youtube" ≠ "open my youtube subscriptions").
        return all(c).map { r -> val rw = words(r.name); r to (if (rw.isEmpty()) 0.0 else rw.count { it in w }.toDouble() / rw.size) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second }?.first
    }

    /** A recording as a guided flow: each tap becomes a step, matched by its label. */
    fun toFlow(r: Recipe): Flow = Flow(
        id = "recipe_${r.name.lowercase().replace(Regex("\\W+"), "_")}",
        launch = { c -> AppLauncher.launch(c, r.pkg) },
        steps = r.taps.mapIndexed { i, t ->
            Step("r$i", listOf(Regex("^" + Regex.escape(t.label.substringBefore(" · ").take(40)), RegexOption.IGNORE_CASE)),
                say("Tap “${t.label.take(40)}”.", "“${t.label.take(40)}” दबाइए।", "“${t.label.take(40)}” నొక్కండి."),
                role = if (t.role == "input") "input" else null)
        },
        isDone = null,
        doneSay = say("Done — just like you were shown!", "हो गया — जैसे सिखाया था!", "అయింది — నేర్పినట్టే!"),
        start = say("I learned this one: “${r.name}”. Follow the glow.", "यह मैंने सीखा है: “${r.name}”। चमक देखिए।", "ఇది నేను నేర్చుకున్నాను: “${r.name}”. మెరుపు చూడండి."),
        teach = true, llmGoal = r.name, appPkg = r.pkg,
    )
}
