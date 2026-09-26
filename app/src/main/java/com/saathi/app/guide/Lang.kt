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
    fun speechRate(c: Context): Float = sp(c).getFloat("rate", 0.88f)
    fun setSpeechRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()
    fun textScale(c: Context): Float = sp(c).getFloat("text_scale", 1f)
    fun setTextScale(c: Context, v: Float) = sp(c).edit().putFloat("text_scale", v).apply()
    /** Where the guide card sits: "auto" (away from the target), "top" or "bottom" (the person dragged it there). */
    fun cardPos(c: Context): String = sp(c).getString("card_pos", "auto") ?: "auto"
    fun setCardPos(c: Context, v: String) = sp(c).edit().putString("card_pos", v).apply()
    fun teach(c: Context): Boolean = sp(c).getBoolean("teach", true)
    fun setTeach(c: Context, v: Boolean) = sp(c).edit().putBoolean("teach", v).apply()
    fun dim(c: Context): Boolean = sp(c).getBoolean("dim", true)
    fun setDim(c: Context, v: Boolean) = sp(c).edit().putBoolean("dim", v).apply()
    fun scamGuard(c: Context): Boolean = sp(c).getBoolean("scam", true)
    fun setScamGuard(c: Context, v: Boolean) = sp(c).edit().putBoolean("scam", v).apply()
}
