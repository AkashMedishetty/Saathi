package com.saathi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.Skills
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.GlowView
import com.saathi.app.service.SaathiService

/**
 * The listening sheet: Saathi's assistant moment. Opens from Home's orb, the bubble (tap / hold),
 * the card's mic, or the system accessibility button. The screen edge glows, the orb breathes with
 * your voice, and your words appear as you speak. Anything said is handed to the guide.
 */
class AskActivity : AppCompatActivity(), VoiceInput.Listener {
    companion object {
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_TEXT = "text"
    }

    private lateinit var aura: GlowView
    private lateinit var sheet: LinearLayout
    private lateinit var orb: OrbView
    private lateinit var transcript: TextView
    private lateinit var hint: TextView
    private lateinit var speakBtn: TextView
    private lateinit var typeRow: LinearLayout
    private lateinit var input: EditText
    private lateinit var voice: VoiceInput
    private var lang = Lang.EN
    private var closing = false
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Memory.init(this)
        voice = VoiceInput(this)
        lang = Prefs.lang(this)
        build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = close() })
        intent.getStringExtra(EXTRA_TEXT)?.let { deliver(it); return }
        if (intent.getBooleanExtra(EXTRA_LISTEN, true)) sheet.post { listen() }
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() {
        SaathiService.ownUiOpen = false
        voice.stop()
        super.onPause()
        if (!isFinishing && !isChangingConfigurations && !asking) finish() // a sheet never lingers behind other apps
    }

    private fun build() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0x99081512.toInt())
        root.setOnClickListener { close() }
        aura = GlowView(this)
        root.addView(aura, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet = vbox(22, 14).apply {
            background = rounded(C.PAPER, dpf(36))
            elevation = dpf(28)
            isClickable = true // don't close when tapping inside
        }
        val handle = View(this).apply { background = rounded(C.PAPER_3, dpf(3)) }
        sheet.addView(handle, LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Language: three big chips, always visible.
        val langs = hbox()
        langs.add(overline("Saathi"), weight = 1f)
        Lang.entries.forEach { x ->
            val short = when (x) { Lang.EN -> "EN"; Lang.HI -> "हिं"; Lang.TE -> "తె" }
            langs.add(chip(short, x == lang) { Prefs.setLang(this, x); recreate() }, top = 8, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        sheet.add(langs, 12)

        orb = OrbView(this)
        sheet.addView(orb, LinearLayout.LayoutParams(dp(150), dp(150)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        orb.pressable { if (voice.listening) voice.stopListening() else listen() }
        orb.contentDescription = say("Tap and speak", "छूकर बोलिए", "తాకి మాట్లాడండి").pick(lang)

        transcript = display(say("What would you like to do?", "आप क्या करना चाहते हैं?", "మీరు ఏం చేయాలనుకుంటున్నారు?").pick(lang), 29f).apply {
            gravity = Gravity.CENTER
        }
        sheet.add(transcript, 6)
        hint = body(say("Say: “Video call my son”", "कहिए: “बेटे को वीडियो कॉल करो”", "ఇలా చెప్పండి: “కొడుకుకి వీడియో కాల్ చేయి”").pick(lang), 17f).apply { gravity = Gravity.CENTER }
        sheet.add(hint, 8)

        // Primary: speak. Secondary: type.
        val actions = hbox()
        speakBtn = body("", 19f, C.WHITE, bold = true).apply {
            gravity = Gravity.CENTER; minHeight = dp(64); background = rounded(C.PINE_DEEP, dpf(32))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(C.WHITE)
            compoundDrawablePadding = dp(8); setPadding(dp(22), 0, dp(22), 0)
        }
        speakBtn.pressable { if (voice.listening) voice.stopListening() else listen() }
        actions.add(speakBtn, weight = 1f)
        val kb = FrameLayout(this).apply { background = rounded(C.PAPER_2, dpf(32)); contentDescription = say("Type instead", "लिखकर बताइए", "టైప్ చేయండి").pick(lang) }
        kb.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_keyboard); imageTintList = android.content.res.ColorStateList.valueOf(C.PINE_DEEP) },
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        kb.pressable { showTyping() }
        actions.addView(kb, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(10) })
        sheet.add(actions, 20)

        typeRow = hbox().apply { visibility = View.GONE }
        input = EditText(this).apply {
            textSize = 19f; typeface = Type.body(context); setTextColor(C.INK); setHintTextColor(C.MUTED)
            hint = say("Type what you need…", "क्या करना है, लिखिए…", "ఏం కావాలో టైప్ చేయండి…").pick(lang)
            background = rounded(C.WHITE, dpf(22), C.LINE, dp(1)); setPadding(dp(18), dp(14), dp(18), dp(14))
            imeOptions = EditorInfo.IME_ACTION_GO; isSingleLine = true
            setOnEditorActionListener { _, _, _ -> text.toString().trim().takeIf { it.isNotEmpty() }?.let { deliver(it) }; true }
        }
        typeRow.add(input, weight = 1f)
        sheet.add(typeRow, 12)

        // Suggestions: what they did before first, then everyday examples. Vertical, never a carousel.
        sheet.add(overline(say("Or tap one", "या इनमें से चुनिए", "లేదా ఒకటి ఎంచుకోండి").pick(lang)), 22)
        val recent = Memory.topPeople(1).firstOrNull()
        val picks = listOfNotNull(
            recent?.let { say("Video call $it", "$it को वीडियो कॉल करो", "$it కి వీడియో కాల్ చేయి") },
            Skills.byId("youtube")?.example,
            Skills.byId("medicine")?.example,
        ).take(3)
        val list = surface()
        picks.forEachIndexed { i, sy ->
            if (i > 0) list.addView(divider(20))
            list.addView(body("“${sy.pick(lang)}”", 18f, C.INK).apply {
                setPadding(dp(20), dp(16), dp(20), dp(16)); minHeight = dp(60)
            }.pressable { deliver(sy.pick(lang)) })
        }
        sheet.add(list, 10)

        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false }
        scroll.addView(sheet)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), dp(60), dp(10), dp(10))
        })
        setContentView(root)
        setSpeakLabel(false)

        sheet.translationY = dpf(80); sheet.alpha = 0f
        sheet.animate().translationY(0f).alpha(1f).setInterpolator(EASE).setDuration(420).start()
    }

    private fun setSpeakLabel(listening: Boolean) {
        speakBtn.text = if (listening) say("Listening…", "सुन रहा हूँ…", "వింటున్నాను…").pick(lang)
            else say("Tap and speak", "छूकर बोलिए", "తాకి మాట్లాడండి").pick(lang)
        orb.mood = if (listening) OrbView.Mood.ACTIVE else OrbView.Mood.IDLE
        aura.setAura(listening)
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            asking = true; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (!voice.available()) { showTyping(); return }
        transcript.text = say("I'm listening…", "मैं सुन रहा हूँ…", "నేను వింటున్నాను…").pick(lang)
        setSpeakLabel(true)
        voice.start(lang, this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        asking = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) listen() else showTyping()
    }

    override fun onPartial(text: String) { transcript.text = text }
    override fun onLevel(level: Float) { orb.setLevel(level); aura.setLevel(level) }
    override fun onFinal(text: String?) {
        setSpeakLabel(false)
        if (text.isNullOrBlank()) {
            transcript.text = say("I didn't catch that. Tap the light and try again.", "सुनाई नहीं दिया। रोशनी छूकर फिर बोलिए।", "వినబడలేదు. వెలుగును తాకి మళ్ళీ చెప్పండి.").pick(lang)
            return
        }
        transcript.text = text
        sheet.postDelayed({ deliver(text) }, 450)
    }

    private fun showTyping() {
        typeRow.visibility = View.VISIBLE
        input.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(input, 0)
    }

    /** Hand the request to the guide (it runs in the accessibility service), then get out of the way. */
    private fun deliver(text: String) {
        voice.stop()
        val svc = SaathiService.instance
        if (svc == null) {
            transcript.text = say("First, turn on the Saathi helper.", "पहले Saathi मदद चालू कीजिए।", "ముందు Saathi సహాయం ఆన్ చేయండి.").pick(lang)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        SaathiService.ownUiOpen = false
        closing = true
        sheet.animate().translationY(dpf(60)).alpha(0f).setDuration(220).withEndAction {
            finish(); overridePendingTransition(0, android.R.anim.fade_out)
            svc.guide.handleUtterance(text)
        }.start()
    }

    private fun close() {
        if (closing) return
        closing = true
        voice.stop()
        sheet.animate().translationY(dpf(80)).alpha(0f).setInterpolator(EASE).setDuration(240).withEndAction { finish(); overridePendingTransition(0, 0) }.start()
    }
}
