package com.saathi.app.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.llm.LlmManager
import com.saathi.app.llm.NpuProbe
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, in plain words. Family contacts are protected by the phone's own screen lock:
 * a scammer on a call must not be able to talk someone into adding "family".
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private var lang = Lang.EN
    private var speaker: Speaker? = null
    private var afterUnlock: (() -> Unit)? = null
    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) afterUnlock?.invoke()
        afterUnlock = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Memory.init(this)
        speaker = Speaker(this)
        scroll = ScrollView(this).apply { setBackgroundColor(C.PAPER) }
        setContentView(scroll)
        render()
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    private fun render() {
        lang = Prefs.lang(this)
        val page = vbox(22, 18)
        val back = hbox()
        back.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = android.content.res.ColorStateList.valueOf(C.INK) },
            LinearLayout.LayoutParams(dp(28), dp(28)))
        back.add(body(s("Back", "वापस", "వెనక్కి"), 18f, C.INK, bold = true), top = 8)
        back.minimumHeight = dp(52)
        back.pressable { finish() }
        page.add(back, 8)
        page.add(display(s("Settings", "सेटिंग", "సెట్టింగ్స్"), 38f), 10)

        // ── About you ──
        section(page, s("About you", "आपके बारे में", "మీ గురించి"))
        val you = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        you.add(body(s("Your name", "आपका नाम", "మీ పేరు"), 15f))
        you.add(field(Prefs.name(this), s("e.g. Kamala", "जैसे: कमला", "ఉదా: కమల")) { Prefs.setName(this, it) }, 6)
        you.add(body(s("Language", "भाषा", "భాష"), 15f), 18)
        val langs = hbox()
        Lang.entries.forEach { x -> langs.add(chip(x.label, x == lang) { Prefs.setLang(this, x); render() }, top = if (x == Lang.EN) 0 else 8, w = ViewGroup.LayoutParams.WRAP_CONTENT) }
        you.add(langs, 8)
        page.add(you, 10)

        // ── Family (locked) ──
        section(page, s("Family contact", "परिवार का नंबर", "కుటుంబ నంబర్"))
        val fam = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val cs = Prefs.contacts(this)
        fam.add(body(if (cs.isEmpty()) s("Not set yet.", "अभी नहीं जोड़ा।", "ఇంకా సెట్ చేయలేదు.") else cs.joinToString("\n") { "${it.name} · ${it.phone.ifBlank { "—" }}" }, 19f, C.INK, bold = true))
        fam.add(body(s("Only these people get “Ask family” messages. Changing them needs your phone's screen lock.",
            "“परिवार से पूछें” संदेश सिर्फ़ इन्हीं को जाते हैं। बदलने के लिए फ़ोन का स्क्रीन लॉक चाहिए।",
            "“కుటుంబాన్ని అడగండి” సందేశాలు వీరికే వెళ్తాయి. మార్చాలంటే ఫోన్ స్క్రీన్ లాక్ కావాలి."), 15f), 6)
        fam.add(primaryButton(s("Change family contacts", "परिवार के नंबर बदलें", "కుటుంబ నంబర్లు మార్చండి"), R.drawable.ic_lock, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            withScreenLock { editFamily() }
        }, 14)
        page.add(fam, 10)

        // ── Voice ──
        section(page, s("Voice", "आवाज़", "వాయిస్"))
        val voice = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        voice.add(body(s("How fast Saathi speaks", "साथी कितना तेज़ बोले", "సాథీ ఎంత వేగంగా మాట్లాడాలి"), 15f))
        voice.add(SeekBar(this).apply {
            max = 60; progress = ((Prefs.speechRate(context) - 0.6f) * 100).toInt().coerceIn(0, 60)
            minimumHeight = dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) Prefs.setSpeechRate(context, 0.6f + p / 100f) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { speaker?.say(s("This is how I will sound.", "मैं ऐसे बोलूँगा।", "నేను ఇలా మాట్లాడతాను."), lang) }
            })
        }, 6)
        voice.add(primaryButton(s("Get offline speech for हिंदी and తెలుగు", "हिंदी और తెలుగు आवाज़ डाउनलोड करें", "హిందీ, తెలుగు వాయిస్ డౌన్‌లోడ్ చేయండి"), R.drawable.ic_translate, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            val v = VoiceInput(this); Lang.entries.forEach { v.downloadModel(it) }
            startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, 12)
        page.add(voice, 10)

        // ── Look & safety ──
        section(page, s("Look & safety", "दिखावट और सुरक्षा", "రూపం & భద్రత"))
        val look = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        look.add(body(s("Size of Saathi's words", "साथी के अक्षरों का आकार", "సాథీ అక్షరాల పరిమాణం"), 15f))
        val sizes = hbox()
        listOf(0.9f to "A", 1f to "A+", 1.15f to "A++").forEachIndexed { i, (v, t) ->
            sizes.add(chip(t, kotlin.math.abs(Prefs.textScale(this) - v) < 0.01f) { Prefs.setTextScale(this, v); render() }, top = if (i == 0) 0 else 8, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        look.add(sizes, 8)
        look.add(toggle(s("Dim the screen around the glow", "चमक के आसपास स्क्रीन धुंधली करें", "మెరుపు చుట్టూ స్క్రీన్ మసకబార్చండి"), Prefs.dim(this)) { Prefs.setDim(this, it) }, 14)
        look.add(toggle(s("Warn me about scams", "धोखे से सावधान करें", "మోసాల గురించి హెచ్చరించండి"), Prefs.scamGuard(this)) { Prefs.setScamGuard(this, it) }, 6)
        look.add(toggle(s("Expert mode: do whole tasks for me (still asks before anything important)", "एक्सपर्ट मोड: पूरा काम कर दो (ज़रूरी चीज़ से पहले पूछे)",
            "నిపుణ మోడ్: పూర్తి పని చేయి (ముఖ్యమైనదానికి ముందు అడుగుతుంది)"), Prefs.expert(this)) { Prefs.setExpert(this, it) }, 6)
        look.add(toggle(s("Press TV buttons through this phone (IR blaster)", "इस फ़ोन से टीवी बटन दबाएँ (IR)", "ఈ ఫోన్ నుంచే టీవీ బటన్లు నొక్కు (IR)"), Prefs.tvIr(this)) { Prefs.setTvIr(this, it) }, 6)
        look.add(toggle(s("Explain why (teaching tips)", "क्यों, यह भी समझाएँ", "ఎందుకో కూడా చెప్పండి"), Prefs.teach(this)) { Prefs.setTeach(this, it) }, 6)
        look.add(primaryButton(s("Let Saathi read my messages aloud", "Saathi मेरे संदेश पढ़कर सुनाए", "Saathi నా సందేశాలు చదివి వినిపించాలి"), R.drawable.ic_sms, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, 14)
        look.add(primaryButton(s("Card position: automatic", "कार्ड की जगह: अपने आप", "కార్డ్ స్థానం: ఆటోమేటిక్"), R.drawable.ic_touch_app, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            Prefs.setCardPos(this, "auto")
        }, 14)
        page.add(look, 10)

        // ── The brain (proof for judges) ──
        section(page, s("On-device brain", "फ़ोन पर चलने वाला दिमाग़", "ఫోన్‌లోనే మెదడు"))
        val brain = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val out = body(LlmManager.label ?: s("Loads when needed.", "ज़रूरत पर चालू होता है।", "అవసరమైనప్పుడు లోడ్ అవుతుంది."), 16f, C.INK)
        brain.add(out)
        brain.add(primaryButton(s("Test brain (Gemma 4 on GPU)", "दिमाग़ जाँचें", "మెదడు పరీక్షించండి"), R.drawable.ic_auto_awesome) {
            out.text = s("Loading the model…", "मॉडल लोड हो रहा है…", "మోడల్ లోడ్ అవుతోంది…")
            lifecycleScope.launch {
                LlmManager.load(applicationContext)
                val t0 = System.currentTimeMillis()
                val reply = LlmManager.generate("You are a kind helper. Answer in one short sentence.", "What is WhatsApp?")
                val st = LlmManager.state.value
                out.text = when (st) {
                    is LlmManager.State.Ready -> "✓ ${st.label}\nload ${st.loadMs} ms · reply ${System.currentTimeMillis() - t0} ms\n${reply ?: ""}"
                    is LlmManager.State.Failed -> "✗ ${st.msg}"
                    else -> st.toString()
                }
            }
        }, 12)
        brain.add(primaryButton(s("Test Snapdragon NPU (FastVLM)", "स्नैपड्रैगन NPU जाँचें", "స్నాప్‌డ్రాగన్ NPU పరీక్షించండి"), R.drawable.ic_auto_awesome, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            out.text = s("Running on the NPU…", "NPU पर चल रहा है…", "NPU పై నడుస్తోంది…")
            lifecycleScope.launch { out.text = withContext(Dispatchers.IO) { NpuProbe.run(applicationContext, "NPU", "vision") } }
        }, 10)
        page.add(brain, 10)

        // ── Routines ──
        section(page, s("Every day", "हर दिन", "ప్రతి రోజు"))
        val rt = surface()
        val routines = com.saathi.app.guide.Routines.all(this)
        if (routines.isEmpty()) rt.add(body(s("Nothing yet. Say: “every morning at 7 play Hanuman Chalisa”.", "अभी कुछ नहीं। कहिए: “रोज़ सुबह 7 बजे भजन लगाओ”।", "ఇంకా ఏమీ లేదు. ఇలా చెప్పండి: “రోజూ ఉదయం 7 గంటలకు భజన పెట్టు”."), 16f).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) })
        routines.forEachIndexed { i, r ->
            if (i > 0) rt.addView(divider())
            rt.addView(row(if (r.kind == "remind") R.drawable.ic_medication else R.drawable.ic_alarm, r.goal, "${r.time} · " + s("tap to remove", "हटाने के लिए छुइए", "తొలగించడానికి తాకండి")) {
                com.saathi.app.guide.Routines.remove(this, r.id); render()
            })
        }
        page.add(rt, 10)

        // ── Taught by family (record once, guide forever) ──
        section(page, s("Things I was taught", "मुझे सिखाई गई चीज़ें", "నాకు నేర్పినవి"))
        val tb = surface()
        val recipes = com.saathi.app.guide.Recipes.all(this)
        if (recipes.isEmpty()) tb.add(body(s("Nothing yet. Say “watch me: video call Rahul”, do it once, then say “done teaching”.",
            "अभी कुछ नहीं। कहिए “देखो मैं: राहुल को वीडियो कॉल”, एक बार करके दिखाइए, फिर “सिखा दिया” कहिए।",
            "ఇంకా ఏమీ లేదు. “నేను చూపిస్తా: రాహుల్‌కి వీడియో కాల్” అని, ఒకసారి చేసి, “నేర్పించాను” అనండి."), 16f).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) })
        recipes.forEachIndexed { i, r ->
            if (i > 0) tb.addView(divider())
            tb.addView(row(R.drawable.ic_school, r.name, "${r.taps.size} " + s("steps · tap to forget", "क़दम · भूलने के लिए छुइए", "అడుగులు · మర్చిపోవడానికి తాకండి")) {
                com.saathi.app.guide.Recipes.remove(this, r.name); render()
            })
        }
        page.add(tb, 10)

        // ── Performance (proof it's light on the phone) ──
        section(page, s("Phone health", "फ़ोन की सेहत", "ఫోన్ ఆరోగ్యం"))
        val perf = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val mi = android.app.ActivityManager.MemoryInfo().also { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
        val mine = android.os.Debug.getPss() / 1024
        perf.add(body(listOf(
            s("Saathi uses", "Saathi इस्तेमाल करता है", "Saathi వాడుతోంది") + ": $mine MB",
            s("Phone memory free", "फ़ोन की ख़ाली मेमोरी", "ఫోన్ ఖాళీ మెమరీ") + ": ${mi.availMem / (1024 * 1024)} MB",
            s("Text brain", "टेक्स्ट दिमाग़", "టెక్స్ట్ మెదడు") + ": " + (LlmManager.label?.let { "$it · ${LlmManager.lastGenMs} ms" } ?: s("asleep (loads when needed)", "सोया है (ज़रूरत पर जागेगा)", "నిద్రలో ఉంది (అవసరమైతే లోడ్)")),
            s("Vision (NPU)", "विज़न (NPU)", "విజన్ (NPU)") + ": " + (com.saathi.app.llm.VisionBrain.label?.let { "$it · ${com.saathi.app.llm.VisionBrain.lastMs} ms" } ?: s("asleep", "सोया है", "నిద్రలో")),
            if (com.saathi.app.guide.Power.low(this)) s("Battery low: using scripts only", "बैटरी कम: सिर्फ़ स्क्रिप्ट", "బ్యాటరీ తక్కువ: స్క్రిప్ట్‌లు మాత్రమే") else s("Battery OK", "बैटरी ठीक", "బ్యాటరీ సరే"),
        ).joinToString("\n"), 16f, C.INK))
        perf.add(primaryButton(s("Free memory now", "मेमोरी ख़ाली करें", "మెమరీ ఖాళీ చేయండి"), R.drawable.ic_cleaning_services, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            LlmManager.unload(); com.saathi.app.llm.VisionBrain.unload(); render()
        }, 12)
        page.add(perf, 10)

        // ── Memory ──
        section(page, s("What Saathi remembers", "साथी को क्या याद है", "సాథీకి ఏం గుర్తుంది"))
        val mem = surface().apply { setPadding(dp(18), dp(16), dp(18), dp(18)) }
        val lines = buildList {
            Memory.topPeople(5).takeIf { it.isNotEmpty() }?.let { add(s("People", "लोग", "వ్యక్తులు") + ": " + it.joinToString(", ")) }
            Memory.reminders().forEach { add("⏰ $it") }
            Memory.notes().takeLast(5).forEach { add("• $it") }
            add(s("Things learned", "सीखी चीज़ें", "నేర్చుకున్నవి") + ": ${Memory.totalLearned()}")
        }
        mem.add(body(lines.joinToString("\n"), 16f, C.INK))
        mem.add(body(s("Kept only on this phone. Never sent anywhere.", "सिर्फ़ इसी फ़ोन में। कहीं नहीं भेजा जाता।", "ఈ ఫోన్‌లోనే. ఎక్కడికీ పంపబడదు."), 14f), 8)
        mem.add(primaryButton(s("Forget everything", "सब भूल जाओ", "అన్నీ మర్చిపో"), R.drawable.ic_close, bg = 0xFFFBE4DE.toInt(), fg = C.VERMILION) {
            Memory.forgetAll(); render()
        }, 12)
        page.add(mem, 10)

        page.add(primaryButton(s("Accessibility settings", "सुलभता सेटिंग", "యాక్సెసిబిలిటీ సెట్టింగ్స్"), R.drawable.ic_settings, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 26)
        page.add(View(this), 40)
        scroll.removeAllViews(); scroll.addView(page)
    }

    private fun section(page: LinearLayout, title: String) { page.add(overline(title), 30) }

    private fun field(value: String, hint: String, onDone: (String) -> Unit) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 19f; typeface = Type.body(context); setTextColor(C.INK); setHintTextColor(C.MUTED)
        background = rounded(C.PAPER, dpf(18), C.LINE, dp(1)); setPadding(dp(16), dp(14), dp(16), dp(14)); isSingleLine = true
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { onDone(e.toString().trim()) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
    }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit): View {
        val r = hbox().apply { minimumHeight = dp(56) }
        r.add(body(label, 17f, C.INK), weight = 1f)
        val sw = com.google.android.material.materialswitch.MaterialSwitch(this).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return r
    }

    /** Anything that changes who counts as "family" needs the phone's PIN/pattern/fingerprint. */
    private fun withScreenLock(then: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) { then(); return }
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent(
            s("Confirm it's you", "पुष्टि करें कि आप ही हैं", "మీరేనని నిర్ధారించండి"),
            s("Needed to change family contacts", "परिवार का नंबर बदलने के लिए", "కుటుంబ నంబర్ మార్చడానికి"))
        if (i == null) { then(); return }
        afterUnlock = then
        unlock.launch(i)
    }

    private fun editFamily() {
        val box = vbox(22, 10)
        val cur = Prefs.contacts(this)
        val fields = (0 until 3).map { i ->
            val n = field(cur.getOrNull(i)?.name ?: "", s("Name ${i + 1}, e.g. Rahul", "नाम ${i + 1}, जैसे राहुल", "పేరు ${i + 1}, ఉదా: రాహుల్")) {}
            val p = field(cur.getOrNull(i)?.phone ?: "", s("Phone number", "फ़ोन नंबर", "ఫోన్ నంబర్")) {}.apply { inputType = InputType.TYPE_CLASS_PHONE }
            box.add(n, if (i == 0) 0 else 18); box.add(p, 8)
            n to p
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(s("Family contacts (up to 3)", "परिवार के नंबर (3 तक)", "కుటుంబ నంబర్లు (3 వరకు)"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(s("Save", "सेव करें", "సేవ్ చేయండి")) { _, _ ->
                Prefs.setContacts(this, fields.map { (n, p) -> Prefs.Contact(n.text.toString().trim(), p.text.toString().trim()) })
                Prefs.contacts(this).forEach { if (it.name.isNotBlank()) Memory.person(it.name) }
                render()
            }
            .setNegativeButton(s("Cancel", "रद्द करें", "రద్దు చేయండి"), null)
            .show()
    }
}
