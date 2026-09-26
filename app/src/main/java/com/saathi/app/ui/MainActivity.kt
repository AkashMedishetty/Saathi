package com.saathi.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saathi.app.R
import com.saathi.app.guide.Cat
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.Skills
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.llm.LlmManager
import com.saathi.app.llm.NpuProbe
import com.saathi.app.service.SaathiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home. One idea per screen: a warm greeting, one living light to talk to, what matters today,
 * and everything Saathi can help with, as big calm rows. Rebuilt on every resume (cheap), so it
 * always reflects memory, language and helper state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var scroll: ScrollView
    private var lang = Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Memory.init(this)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setBackgroundColor(C.PAPER) }
        setContentView(scroll)
        probe(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        probe(intent)
    }

    override fun onResume() {
        super.onResume()
        SaathiService.ownUiOpen = true
        if (intent.hasExtra("probe")) return
        if (!Prefs.setupDone(this)) { startActivity(Intent(this, SetupActivity::class.java)); return }
        render()
    }

    override fun onPause() {
        SaathiService.ownUiOpen = false
        super.onPause()
    }

    private fun render() {
        lang = Prefs.lang(this)
        val page = vbox(22, 18)

        // ── Top bar: date · settings ──
        val top = hbox()
        val date = SimpleDateFormat("EEEE, d MMMM", lang.locale).format(Date())
        top.add(overline(date), weight = 1f)
        val gear = FrameLayout(this).apply { background = rounded(C.PAPER_2, dpf(24)); contentDescription = say("Settings", "सेटिंग", "సెట్టింగ్స్").pick(lang) }
        gear.addView(ImageView(this).apply { setImageResource(R.drawable.ic_settings); imageTintList = ColorStateList.valueOf(C.PINE_DEEP) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        gear.pressable { startActivity(Intent(this, SettingsActivity::class.java)) }
        top.addView(gear, LinearLayout.LayoutParams(dp(52), dp(52)))
        page.add(top, 4)

        // ── Greeting ──
        val name = Prefs.name(this)
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greet = when {
            h < 12 -> say("Good morning", "सुप्रभात", "శుభోదయం")
            h < 17 -> say("Good afternoon", "नमस्ते", "నమస్కారం")
            else -> say("Good evening", "शुभ संध्या", "శుభ సాయంత్రం")
        }.pick(lang)
        page.add(display(if (name.isBlank()) "$greet." else "$greet,\n$name ji.", 38f), 26)

        // ── Helper status + language (one segmented control) ──
        val on = SaathiService.isEnabled(this)
        page.add(statusLine(on), 14)
        val seg = hbox().apply { background = rounded(C.PAPER_2, dpf(28)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        Lang.entries.forEach { x ->
            val sel = x == lang
            seg.add(body(x.label, 18f, if (sel) C.WHITE else C.PINE_DEEP, bold = true).apply {
                gravity = Gravity.CENTER; minHeight = dp(48)
                background = if (sel) rounded(C.PINE_DEEP, dpf(24)) else null
                contentDescription = x.label
            }.pressable { Prefs.setLang(this, x); render() }, weight = 1f)
        }
        page.add(seg, 16)

        // ── The light: tap and speak ──
        val hero = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val orb = OrbView(this)
        hero.addView(orb, LinearLayout.LayoutParams(dp(250), dp(250)))
        orb.pressable { openAsk(true) }
        orb.contentDescription = say("Tap and speak to Saathi", "छूकर साथी से बोलिए", "తాకి సాథీతో మాట్లాడండి").pick(lang)
        hero.add(body(say("Tap the light and speak", "रोशनी छूकर बोलिए", "వెలుగును తాకి మాట్లాడండి").pick(lang), 20f, C.INK, bold = true).apply { gravity = Gravity.CENTER }, 6)
        hero.add(body(say("in English, हिंदी or తెలుగు", "हिंदी, English या తెలుగు में", "తెలుగు, हिंदी లేదా English లో").pick(lang), 16f).apply { gravity = Gravity.CENTER }, 2)
        page.add(hero, 18)
        page.add(primaryButton(say("Type instead", "लिखकर बताइए", "టైప్ చేయండి").pick(lang), R.drawable.ic_keyboard, bg = C.PAPER_2, fg = C.PINE_DEEP) { openAsk(false) }, 18)

        // ── Today ──
        val today = today()
        if (today.childCount > 0) {
            page.add(overline(say("Today", "आज", "ఈరోజు").pick(lang)), 34)
            page.add(today, 10)
        }

        // ── Everything Saathi can help with, by group ──
        for (cat in Cat.entries) {
            val skills = Skills.all.filter { it.cat == cat }
            if (skills.isEmpty()) continue
            page.add(overline(cat.label.pick(lang)), 34)
            val box = surface()
            skills.forEachIndexed { i, sk ->
                if (i > 0) box.addView(divider())
                box.addView(row(sk.icon, sk.title.pick(lang), "“${sk.example.pick(lang)}”") { run(sk.example.pick(lang)) })
            }
            page.add(box, 10)
        }

        // ── Privacy promise ──
        val foot = hbox().apply { gravity = Gravity.TOP }
        foot.addView(ImageView(this).apply { setImageResource(R.drawable.ic_verified_user); imageTintList = ColorStateList.valueOf(C.LEAF) },
            LinearLayout.LayoutParams(dp(22), dp(22)))
        val brain = LlmManager.label?.let { " · $it" } ?: ""
        foot.add(body(say(
            "Private by design. Saathi has no internet permission; everything it hears and sees stays on this phone.$brain",
            "पूरी तरह निजी। Saathi के पास इंटरनेट की अनुमति नहीं है; जो सुनता-देखता है, इसी फ़ोन में रहता है।$brain",
            "పూర్తిగా ప్రైవేట్. Saathi కి ఇంటర్నెట్ అనుమతి లేదు; వినేది, చూసేది ఈ ఫోన్‌లోనే ఉంటుంది.$brain").pick(lang), 14f), top = 10, weight = 1f)
        page.add(foot, 36)
        page.add(View(this), 40)

        scroll.removeAllViews()
        scroll.addView(page)
        // Gentle staggered entrance.
        for (i in 0 until page.childCount) page.getChildAt(i).apply {
            alpha = 0f; translationY = dpf(14)
            animate().alpha(1f).translationY(0f).setStartDelay(40L * minOf(i, 8)).setInterpolator(EASE).setDuration(380).start()
        }
    }

    private fun statusLine(on: Boolean): View {
        if (on) return hbox().apply {
            background = rounded(0x1A2E7550, dpf(20)); setPadding(dp(14), dp(10), dp(16), dp(10))
            addView(View(context).apply { background = rounded(C.LEAF, dpf(5)) }, LinearLayout.LayoutParams(dp(10), dp(10)))
            add(body(say("Helper on · works offline", "मदद चालू · बिना इंटरनेट", "సహాయం ఆన్ · ఆఫ్‌లైన్").pick(lang), 16f, C.LEAF, bold = true), top = 10)
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        return vbox(18, 16).apply {
            background = rounded(0xFFFFF1DC.toInt(), dpf(24))
            add(body(say("Saathi's helper is off. Turn it on so I can show you where to tap.",
                "Saathi की मदद बंद है। चालू कीजिए ताकि मैं दिखा सकूँ कहाँ दबाना है।",
                "Saathi సహాయం ఆఫ్‌లో ఉంది. ఎక్కడ నొక్కాలో చూపించడానికి ఆన్ చేయండి.").pick(lang), 17f, C.INK))
            add(primaryButton(say("Turn on the helper", "मदद चालू करें", "సహాయం ఆన్ చేయండి").pick(lang), R.drawable.ic_touch_app) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, 12)
        }
    }

    /** What matters now: an unfinished task, reminders Saathi set, people, how much they've learned. */
    private fun today(): LinearLayout {
        val box = surface()
        fun add(v: View) { if (box.childCount > 0) box.addView(divider()); box.addView(v) }
        Memory.task()?.let { t -> add(row(R.drawable.ic_history, say("Continue", "जारी रखें", "కొనసాగించండి").pick(lang), "“${t.goal}”") { run(t.goal) }) }
        Memory.reminders().takeLast(3).forEach { r -> add(row(R.drawable.ic_notifications_active, r, null, C.SAFFRON) { }) }
        Memory.topPeople(2).forEach { p ->
            add(row(R.drawable.ic_videocam, say("Video call $p", "$p को वीडियो कॉल", "$p కి వీడియో కాల్").pick(lang), null) {
                run(say("video call $p", "$p को वीडियो कॉल करो", "$p కి వీడియో కాల్ చేయి").pick(lang))
            })
        }
        SchoolActivity.next()?.let { Skills.byId(it) }?.let { sk ->
            add(row(R.drawable.ic_school, say("Next lesson: ${sk.title.pick(Lang.EN)}", "अगला पाठ: ${sk.title.pick(Lang.HI)}", "తదుపరి పాఠం: ${sk.title.pick(Lang.TE)}").pick(lang),
                say("Phone School", "फ़ोन पाठशाला", "ఫోన్ బడి").pick(lang), C.MARIGOLD) { startActivity(Intent(this, SchoolActivity::class.java)) })
        }
        val n = Memory.totalLearned()
        if (n > 0) add(row(R.drawable.ic_school, say(if (n == 1) "You've learned 1 thing" else "You've learned $n things", "आपने $n चीज़ें सीखीं", "మీరు $n విషయాలు నేర్చుకున్నారు").pick(lang),
            say("Well done! I'll help less as you learn.", "शाबाश! जितना सीखेंगे, मैं उतना कम दिखाऊँगा।", "భలే! మీరు నేర్చుకున్న కొద్దీ నేను తక్కువ చూపిస్తాను.").pick(lang), C.LEAF) { })
        return box
    }

    private fun run(goal: String) {
        val svc = SaathiService.instance
        if (svc == null) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        SaathiService.ownUiOpen = false
        svc.guide.handleUtterance(goal)
    }

    private fun openAsk(listen: Boolean) {
        startActivity(Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_LISTEN, listen))
        overridePendingTransition(0, 0)
    }

    /** Debug: adb shell am start -n com.saathi.app/.ui.MainActivity --es probe NPU [--es model text] [--ez vis false] */
    private fun probe(intent: Intent) {
        val backend = intent.getStringExtra("probe") ?: return
        val which = intent.getStringExtra("model") ?: "vision"
        val out = TextView(this).apply { textSize = 18f; setTextColor(C.INK); setPadding(dp(24), dp(80), dp(24), dp(24)) }
        out.text = "Testing $which model on $backend…"
        scroll.removeAllViews(); scroll.addView(out)
        lifecycleScope.launch {
            out.text = withContext(Dispatchers.IO) { NpuProbe.run(applicationContext, backend, which, intent.getBooleanExtra("vis", true)) }
        }
    }
}
