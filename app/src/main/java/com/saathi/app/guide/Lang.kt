package com.saathi.app.guide

import android.content.Context
import java.util.Locale

enum class Lang(val tag: String, val label: String) {
    EN("en-IN", "English"),
    HI("hi-IN", "हिंदी"),
    TE("te-IN", "తెలుగు");

    val locale: Locale get() = Locale.forLanguageTag(tag)
}

/** One sentence in all three languages. Hindi/Telugu speech always comes from these templates, never the LLM. */
typealias Say = Map<Lang, String>

fun say(en: String, hi: String, te: String): Say = mapOf(Lang.EN to en, Lang.HI to hi, Lang.TE to te)
fun Say.pick(l: Lang): String = this[l] ?: getValue(Lang.EN)

/** Small settings, all local. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("saathi", Context.MODE_PRIVATE)

    fun lang(c: Context): Lang = runCatching { Lang.valueOf(sp(c).getString("lang", "EN")!!) }.getOrDefault(Lang.EN)
    fun setLang(c: Context, l: Lang) = sp(c).edit().putString("lang", l.name).apply()
    fun name(c: Context): String = sp(c).getString("name", "") ?: ""
    fun setName(c: Context, v: String) = sp(c).edit().putString("name", v).apply()
    /**
     * Family contacts (up to 3), set in caregiver setup; editing later needs the screen lock.
     * The first one is "the" family contact for Ask family.
     */
    data class Contact(val name: String, val phone: String)

    fun contacts(c: Context): List<Contact> = runCatching {
        val a = org.json.JSONArray(sp(c).getString("contacts", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Contact(it.optString("n"), it.optString("p")) } }
    }.getOrDefault(emptyList()).ifEmpty {
        // migrate the single-contact prefs from earlier builds
        val n = sp(c).getString("family", "") ?: ""; val p = sp(c).getString("family_phone", "") ?: ""
        if (n.isNotBlank() || p.isNotBlank()) listOf(Contact(n, p)) else emptyList()
    }

    fun setContacts(c: Context, list: List<Contact>) {
        val a = org.json.JSONArray()
        list.filter { it.name.isNotBlank() || it.phone.isNotBlank() }.take(3).forEach { a.put(org.json.JSONObject().put("n", it.name).put("p", it.phone)) }
        sp(c).edit().putString("contacts", a.toString()).apply()
    }

    /** WhatsApp showed its "set up / sign in" screen: don't offer WhatsApp until we see it working. */
    fun waNotSetUp(c: Context) = sp(c).getBoolean("wa_not_set_up", false)
    // ── Saathi Pro (Pro build only): an OpenAI-compatible cloud brain, opt-in. Stored in the app's private storage. ──
    fun proOn(c: Context) = com.saathi.app.BuildConfig.PRO && sp(c).getBoolean("pro_on", false) && proKey(c).isNotBlank() && proModels(c).isNotEmpty()
    /** The Model box may list several, tried in order (the biggest first, a fast one as fallback). Free ones only. */
    fun proModels(c: Context) = proModel(c).split(',').map { it.trim() }.filter { it.isNotEmpty() && it.endsWith(":free") }
    fun setProOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("pro_on", v).apply()
    fun proUrl(c: Context) = sp(c).getString("pro_url", "https://openrouter.ai/api/v1") ?: ""
    fun proModel(c: Context) = sp(c).getString("pro_model", "qwen/qwen3.8-27b:free") ?: ""
    fun proKey(c: Context) = sp(c).getString("pro_key", "") ?: ""
    fun setPro(c: Context, url: String, model: String, key: String) =
        sp(c).edit().putString("pro_url", url.trim()).putString("pro_model", model.trim()).putString("pro_key", key.trim()).apply()
    /** The AI monitor strip (which engine decided each step, CPU / RAM / GPU). */
    fun aiMonitor(c: Context) = sp(c).getBoolean("ai_monitor", false)
    fun setAiMonitor(c: Context, v: Boolean) = sp(c).edit().putBoolean("ai_monitor", v).apply()
    fun setWaNotSetUp(c: Context, v: Boolean) = sp(c).edit().putBoolean("wa_not_set_up", v).apply()

    fun family(c: Context): String = contacts(c).firstOrNull()?.name ?: ""
    fun familyPhone(c: Context): String = contacts(c).firstOrNull()?.phone ?: ""
    fun setFamily(c: Context, v: String) = setContacts(c, listOf(Contact(v, familyPhone(c))) + contacts(c).drop(1))
    fun setFamilyPhone(c: Context, v: String) = setContacts(c, listOf(Contact(family(c), v)) + contacts(c).drop(1))

    /** Expert mode: less teaching, and Saathi does whole tasks (still stopping at anything risky). */
    fun expert(c: Context): Boolean = sp(c).getBoolean("expert", false)
    fun setExpert(c: Context, v: Boolean) = sp(c).edit().putBoolean("expert", v).apply()

    fun speechRate(c: Context): Float = sp(c).getFloat("rate", 0.88f)
    fun setSpeechRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()
    fun textScale(c: Context): Float = sp(c).getFloat("text_scale", 1f)
    fun setTextScale(c: Context, v: Float) = sp(c).edit().putFloat("text_scale", v).apply()
    /** Where the guide card sits: "auto" (away from the target), "top" or "bottom" (the person dragged it there). */
    fun cardPos(c: Context): String = sp(c).getString("card_pos", "auto") ?: "auto"
    fun setCardPos(c: Context, v: String) = sp(c).edit().putString("card_pos", v).apply()
    fun setupDone(c: Context): Boolean = sp(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context) = sp(c).edit().putBoolean("setup_done", true).apply()
    /** Press TV buttons through the phone's IR blaster (off: coach the person to press their own remote). */
    fun tvIr(c: Context): Boolean = sp(c).getBoolean("tv_ir", false)
    fun setTvIr(c: Context, v: Boolean) = sp(c).edit().putBoolean("tv_ir", v).apply()
    fun teach(c: Context): Boolean = sp(c).getBoolean("teach", true)
    fun setTeach(c: Context, v: Boolean) = sp(c).edit().putBoolean("teach", v).apply()
    fun dim(c: Context): Boolean = sp(c).getBoolean("dim", true)
    fun setDim(c: Context, v: Boolean) = sp(c).edit().putBoolean("dim", v).apply()
    fun scamGuard(c: Context): Boolean = sp(c).getBoolean("scam", true)
    fun setScamGuard(c: Context, v: Boolean) = sp(c).edit().putBoolean("scam", v).apply()
}
