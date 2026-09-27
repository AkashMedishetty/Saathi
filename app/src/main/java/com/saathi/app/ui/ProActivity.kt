package com.saathi.app.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.saathi.app.guide.Prefs

/**
 * Saathi Pro settings (Pro build only): the cloud brain for power-user tasks (video editing, an SSH terminal, a
 * workspace). Any OpenAI-compatible endpoint; OpenRouter by default. The person types their own key here; it stays in
 * the app's private storage and is never logged. Screen text is redacted before it's sent (llm.ProBrain).
 */
class ProActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(28), dp(22), dp(28)) }
        fun label(t: String, size: Float = 16f) = TextView(this).apply { text = t; textSize = size; setPadding(0, dp(14), 0, dp(4)) }
        fun field(v: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(v); this.hint = hint; textSize = 16f; isSingleLine = true
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        page.addView(label("Saathi Pro", 26f))
        page.addView(label("For harder, professional tasks (editing a video, setting up an SSH terminal, a workspace), Saathi can " +
            "ask a stronger cloud model for each step. Only the words on the screen are sent, with numbers, OTPs and IDs hidden. " +
            "Never inside money apps. The normal Saathi stays fully on the phone.", 15f))
        val on = CheckBox(this).apply { text = "Use the cloud brain for hard tasks"; textSize = 17f; isChecked = getSharedPreferences("saathi", MODE_PRIVATE).getBoolean("pro_on", false) }
        page.addView(on)
        page.addView(label("Address (OpenAI-compatible)"))
        val url = field(Prefs.proUrl(this), "https://openrouter.ai/api/v1"); page.addView(url)
        page.addView(label("Model (free: qwen/qwen3.8-27b:free, or google/gemma-4-31b-it:free)"))
        val model = field(Prefs.proModel(this), "qwen/qwen3.8-27b:free"); page.addView(model)
        page.addView(label("Your API key (stays on this phone)"))
        val key = field(Prefs.proKey(this), "sk-or-…", secret = true); page.addView(key)
        val status = label("", 14f)
        page.addView(Button(this).apply {
            text = "Save"; textSize = 17f
            setOnClickListener {
                // Free models only (a paid model could run up a bill): the name must end in ":free".
                if (!model.text.toString().trim().endsWith(":free")) { status.text = "Please choose a free model (its name ends in :free)."; return@setOnClickListener }
                Prefs.setPro(this@ProActivity, url.text.toString(), model.text.toString(), key.text.toString())
                Prefs.setProOn(this@ProActivity, on.isChecked)
                status.text = if (Prefs.proOn(this@ProActivity)) "Saved. Saathi Pro is on." else "Saved. Saathi Pro is off (switch on and add a key)."
            }
        })
        page.addView(status)
        setContentView(ScrollView(this).apply { addView(page); setBackgroundColor(0xFFF7F3EA.toInt()) })
        page.gravity = Gravity.TOP
    }
}
