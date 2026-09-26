package com.saathi.app.guide

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saathi's long-term memory: one small JSON file in app-private storage. Never leaves the phone.
 * Task in progress · skills done (fading help) · people · reminders · notes · labels learned on this phone.
 */
object Memory {
    private const val FILE = "saathi_memory.json"
    private var cache: JSONObject? = null
    private lateinit var file: File

    fun init(ctx: Context) {
        if (!::file.isInitialized) file = File(ctx.applicationContext.filesDir, FILE)
    }

    @Synchronized private fun root(): JSONObject =
        cache ?: runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }.also { cache = it }

    /** Temp file + rename, so a crash mid-write never corrupts memory. */
    @Synchronized private fun save() {
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(root().toString())
        tmp.renameTo(file)
    }

    @Synchronized private fun obj(key: String): JSONObject = root().optJSONObject(key) ?: JSONObject().also { root().put(key, it) }
    @Synchronized private fun arr(key: String): JSONArray = root().optJSONArray(key) ?: JSONArray().also { root().put(key, it) }

    // ── Task in progress ──
    data class SavedTask(val goal: String, val flowId: String?, val step: Int, val pkgs: List<String>, val at: Long)

    fun saveTask(goal: String, flowId: String?, step: Int, pkgs: Collection<String>) {
        root().put("task", JSONObject().put("goal", goal).put("flow", flowId ?: "").put("step", step)
            .put("pkgs", JSONArray(pkgs.toList())).put("at", System.currentTimeMillis()))
        save()
    }

    fun task(maxAgeMs: Long = 30 * 60_000L): SavedTask? {
        val t = root().optJSONObject("task") ?: return null
        val at = t.optLong("at")
        if (System.currentTimeMillis() - at > maxAgeMs) return null
        val pk = t.optJSONArray("pkgs") ?: JSONArray()
        return SavedTask(t.optString("goal"), t.optString("flow").ifBlank { null }, t.optInt("step", -1),
            (0 until pk.length()).map { pk.getString(it) }, at)
    }

    fun clearTask() { root().remove("task"); save() }

    // ── Skills done → fading help ──
    fun completed(skillId: String) {
        val o = obj("done"); o.put(skillId, o.optInt(skillId) + 1)
        val a = arr("recent"); a.put(JSONObject().put("id", skillId).put("at", System.currentTimeMillis()))
        while (a.length() > 20) a.remove(0)
        save()
    }
    fun timesDone(skillId: String): Int = obj("done").optInt(skillId)
    fun totalLearned(): Int = obj("done").let { o -> o.keys().asSequence().count { o.optInt(it) > 0 } }

    // ── People ──
    fun person(name: String) {
        if (name.isBlank() || name.length > 30) return
        val o = obj("people"); val key = name.trim().replaceFirstChar { it.uppercase() }
        o.put(key, o.optInt(key) + 1); save()
    }
    fun topPeople(n: Int = 3): List<String> = obj("people").let { o -> o.keys().asSequence().toList().sortedByDescending { o.optInt(it) }.take(n) }

    // ── Reminders & notes ──
    fun addReminder(text: String) {
        val a = arr("reminders")
        if ((0 until a.length()).any { a.getString(it) == text }) return
        a.put(text); save()
    }
    fun reminders(): List<String> = arr("reminders").let { a -> (0 until a.length()).map { a.getString(it) } }
    fun note(text: String) { arr("notes").put(JSONObject().put("t", text).put("at", System.currentTimeMillis())); save() }
    fun notes(): List<String> = arr("notes").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("t") } }

    // ── Labels learned on this phone (trap #14: OEM names differ) ──
    fun learnLabel(pkg: String, stepKey: String, label: String) {
        if (label.isBlank() || label.length > 60) return
        obj("labels").put("$pkg|$stepKey", label); save()
    }
    fun learnedLabel(pkg: String, stepKey: String): String? = obj("labels").optString("$pkg|$stepKey").ifBlank { null }

    fun forgetAll() { synchronized(this) { cache = JSONObject() }; save() }
}
