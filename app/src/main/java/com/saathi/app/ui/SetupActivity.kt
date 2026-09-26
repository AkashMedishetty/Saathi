package com.saathi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker

/**
 * First run, done together with a family member: four calm pages, one question each.
 * Welcome → who uses this phone → family contact → turn on the helper.
 * After this, changing the family contact needs the phone's screen lock (Settings).
 */
class SetupActivity : AppCompatActivity() {

    private var page = 0
    private lateinit var root: FrameLayout
    private var speaker: Speaker? = null
    private var lang = Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Memory.init(this)
        speaker = Speaker(this)
        root = FrameLayout(this).apply { setBackgroundColor(C.PAPER) }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (page > 0) { page--; render() } else finish() }
        })
        render()
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true; if (page == 3) render() }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    private fun render() {
        lang = Prefs.lang(this)
        val col = vbox(26, 20)
        // Progress: four dots.
        val dots = hbox()
        for (i in 0..3) dots.addView(View(this).apply { background = rounded(if (i <= page) C.MARIGOLD else C.PAPER_3, dpf(4)) },
            LinearLayout.LayoutParams(dp(if (i == page) 28 else 10), dp(8)).apply { marginEnd = dp(6) })
        col.add(dots, 18)

        when (page) {
            0 -> {
                val orb = OrbView(this)
                col.addView(orb, LinearLayout.LayoutParams(dp(180), dp(180)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(48) })
                col.add(display(s("Namaste.\nI'm Saathi.", "नमस्ते।\nमैं साथी हूँ।", "నమస్కారం.\nనేను సాథీ."), 40f).apply { gravity = Gravity.CENTER }, 28)
                col.add(body(s("I'll help you use your own phone, one step at a time, in your language. Everything stays on this phone.",
                    "मैं आपको आपका अपना फ़ोन चलाना सिखाऊँगा, एक-एक क़दम, आपकी भाषा में। सब कुछ इसी फ़ोन में रहता है।",
                    "మీ ఫోన్ వాడటం నేర్పిస్తాను, అడుగు అడుగునా, మీ భాషలో. అన్నీ ఈ ఫోన్‌లోనే ఉంటాయి."), 20f).apply { gravity = Gravity.CENTER }, 14)
                val langs = hbox().apply { gravity = Gravity.CENTER }
                Lang.entries.forEachIndexed { i, x -> langs.add(chip(x.label, x == lang) { Prefs.setLang(this, x); render() }, top = if (i == 0) 0 else 10, w = ViewGroup.LayoutParams.WRAP_CONTENT) }
                col.add(langs, 30)
                col.add(primaryButton(s("Let's begin", "शुरू करें", "మొదలుపెడదాం"), R.drawable.ic_chevron_right) { next() }, 36)
            }
            1 -> {
                col.add(display(s("Who uses\nthis phone?", "यह फ़ोन कौन\nचलाता है?", "ఈ ఫోన్ ఎవరు\nవాడతారు?"), 38f), 40)
                col.add(body(s("Their name", "उनका नाम", "వారి పేరు"), 16f), 30)
                col.add(field(Prefs.name(this), s("e.g. Kamala", "जैसे: कमला", "ఉదా: కమల")) { Prefs.setName(this, it) }, 8)
                col.add(body(s("How fast should I speak?", "मैं कितना तेज़ बोलूँ?", "నేను ఎంత వేగంగా మాట్లాడాలి?"), 16f), 30)
                col.add(SeekBar(this).apply {
                    max = 60; progress = ((Prefs.speechRate(context) - 0.6f) * 100).toInt().coerceIn(0, 60); minimumHeight = dp(56)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { if (u) Prefs.setSpeechRate(context, 0.6f + p / 100f) }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) { preview() }
                    })
                }, 6)
                col.add(primaryButton(s("Hear my voice", "मेरी आवाज़ सुनिए", "నా గొంతు వినండి"), R.drawable.ic_volume_up, bg = C.PAPER_2, fg = C.PINE_DEEP) { preview() }, 10)
                col.add(primaryButton(s("Next", "आगे", "తర్వాత"), R.drawable.ic_chevron_right) { next() }, 36)
            }
            2 -> {
                col.add(display(s("Who should I ask\nwhen you need help?", "मदद चाहिए तो\nकिससे पूछूँ?", "సహాయం కావాలంటే\nఎవరిని అడగాలి?"), 36f), 40)
                col.add(body(s("A son, daughter or someone you trust. Only they get “Ask family” messages. Changing this later needs the phone's screen lock, so no stranger can add themselves.",
                    "बेटा, बेटी या कोई भरोसेमंद। “परिवार से पूछें” संदेश सिर्फ़ उन्हीं को जाते हैं। बाद में बदलने के लिए फ़ोन का स्क्रीन लॉक चाहिए, ताकि कोई अनजान ख़ुद को न जोड़ सके।",
                    "కొడుకు, కూతురు లేదా నమ్మకమైన వ్యక్తి. “కుటుంబాన్ని అడగండి” సందేశాలు వారికే. తర్వాత మార్చాలంటే ఫోన్ స్క్రీన్ లాక్ కావాలి, కాబట్టి తెలియని వారు చేరలేరు."), 17f), 14)
                val cur = Prefs.contacts(this)
                val fields = (0 until 3).map { i ->
                    val n = field(cur.getOrNull(i)?.name ?: "", if (i == 0) s("Name, e.g. Rahul", "नाम, जैसे राहुल", "పేరు, ఉదా: రాహుల్") else s("Another person (optional)", "एक और व्यक्ति (वैकल्पिक)", "ఇంకొకరు (ఐచ్ఛికం)")) {}
                    val p = field(cur.getOrNull(i)?.phone ?: "", s("Phone number", "फ़ोन नंबर", "ఫోన్ నంబర్")) {}.apply { inputType = InputType.TYPE_CLASS_PHONE }
                    col.add(n, if (i == 0) 24 else 20); col.add(p, 8)
                    n to p
                }
                col.add(primaryButton(s("Next", "आगे", "తర్వాత"), R.drawable.ic_chevron_right) {
                    Prefs.setContacts(this, fields.map { (n, p) -> Prefs.Contact(n.text.toString().trim(), p.text.toString().trim()) })
                    Prefs.contacts(this).forEach { if (it.name.isNotBlank()) Memory.person(it.name) }
                    next()
                }, 36)
                col.add(body(s("Skip for now", "अभी छोड़ें", "ఇప్పుడు వద్దు"), 17f, C.PINE_DEEP, bold = true).apply {
                    gravity = Gravity.CENTER; minHeight = dp(52)
                }.pressable { next() }, 8)
            }
            else -> {
                val on = SaathiService.isEnabled(this)
                val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                col.add(display(s("Two switches,\nthen we're ready.", "दो स्विच,\nफिर हम तैयार।", "రెండు స్విచ్‌లు,\nఆపై సిద్ధం."), 38f), 40)
                col.add(step(on, s("Turn on the Saathi helper", "Saathi मदद चालू करें", "Saathi సహాయం ఆన్ చేయండి"),
                    s("So I can see where to point. I only read the screen to help; nothing leaves the phone.",
                        "ताकि मैं दिखा सकूँ कहाँ दबाना है। मैं सिर्फ़ मदद के लिए स्क्रीन पढ़ता हूँ; कुछ भी बाहर नहीं जाता।",
                        "ఎక్కడ నొక్కాలో చూపించడానికి. సహాయం కోసమే స్క్రీన్ చదువుతాను; ఏదీ బయటకు వెళ్ళదు.")) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, 28)
                col.add(step(mic, s("Let me hear you", "मुझे सुनने दीजिए", "నన్ను వినిపించనివ్వండి"),
                    s("So you can just talk to me.", "ताकि आप बस बोलकर बता सकें।", "మీరు మాట్లాడితే చాలు.")) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
                }, 12)
                col.add(primaryButton(s("Start using Saathi", "Saathi शुरू करें", "Saathi మొదలుపెట్టండి"), R.drawable.ic_check) {
                    Prefs.setSetupDone(this)
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }, 36)
            }
        }
        val scroll = ScrollView(this).apply { addView(col) }
        root.removeAllViews()
        root.addView(scroll)
        col.alpha = 0f; col.translationX = dpf(24)
        col.animate().alpha(1f).translationX(0f).setInterpolator(EASE).setDuration(360).start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); render()
    }

    private fun next() { page = (page + 1).coerceAtMost(3); render() }

    private fun preview() {
        val n = Prefs.name(this)
        speaker?.say(s("Hello${if (n.isNotBlank()) " $n ji" else ""}. This is how I will sound.",
            "नमस्ते${if (n.isNotBlank()) " $n जी" else ""}। मैं ऐसे बोलूँगा।",
            "నమస్కారం${if (n.isNotBlank()) " $n గారు" else ""}. నేను ఇలా మాట్లాడతాను."), lang)
    }

    /** A checklist row: a big tick when done, otherwise a button that opens the right system screen. */
    private fun step(done: Boolean, title: String, sub: String, onClick: () -> Unit): View {
        val box = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val top = hbox()
        val dot = FrameLayout(this).apply { background = rounded(if (done) C.LEAF else C.PAPER_2, dpf(18)) }
        dot.addView(android.widget.ImageView(this).apply {
            setImageResource(if (done) R.drawable.ic_check else R.drawable.ic_touch_app)
            imageTintList = android.content.res.ColorStateList.valueOf(if (done) C.WHITE else C.PINE_DEEP)
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        top.addView(dot, LinearLayout.LayoutParams(dp(36), dp(36)))
        top.add(body(title, 19f, C.INK, bold = true), top = 12, weight = 1f)
        box.add(top)
        box.add(body(sub, 16f), 8)
        if (!done) box.add(primaryButton(s("Turn on", "चालू करें", "ఆన్ చేయండి"), null, onClick = onClick), 12)
        return box
    }

    private fun field(value: String, hint: String, onDone: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 20f; typeface = Type.body(context); setTextColor(C.INK); setHintTextColor(C.MUTED)
        background = rounded(C.WHITE, dpf(20), C.LINE, dp(1)); setPadding(dp(18), dp(16), dp(18), dp(16)); isSingleLine = true
        // Save as they type: tapping "Next" doesn't take focus away from the field.
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { onDone(e.toString().trim()) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
    }
}
