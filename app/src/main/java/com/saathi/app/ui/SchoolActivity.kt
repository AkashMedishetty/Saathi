package com.saathi.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.Say
import com.saathi.app.guide.Skills
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.SaathiService

/**
 * Phone School: the skills as a small course. Progress comes from how often each was done:
 * 0 = new, 1–2 = practising, 3+ = "You can do this!" (and the guide starts letting them try first: fading help).
 * This is the clearest "teach, don't replace" part of Saathi.
 */
class SchoolActivity : AppCompatActivity() {

    data class Week(val title: Say, val lessons: List<String>)

    companion object {
        val WEEKS = listOf(
            Week(say("Week 1 · Calls & WhatsApp", "हफ़्ता 1 · कॉल और WhatsApp", "వారం 1 · కాల్స్ & WhatsApp"), listOf("call", "wa_video", "wa_message", "wa_photo")),
            Week(say("Week 2 · Watch & capture", "हफ़्ता 2 · देखना और फोटो", "వారం 2 · చూడటం & ఫోటోలు"), listOf("youtube", "camera", "learn_photo")),
            Week(say("Week 3 · Stay safe", "हफ़्ता 3 · सुरक्षित रहें", "వారం 3 · సురక్షితంగా ఉండండి"), listOf("read_this", "medicine", "real_upi")),
            Week(say("Week 4 · Fix it yourself", "हफ़्ता 4 · ख़ुद ठीक करें", "వారం 4 · మీరే సరిచేయండి"), listOf("font", "wifi", "bluetooth", "storage")),
        )
        val ALL get() = WEEKS.flatMap { it.lessons }
        /** The first lesson not yet mastered. */
        fun next(): String? = ALL.firstOrNull { Memory.timesDone(it) < 3 }
    }

    private lateinit var scroll: ScrollView
    private var lang = Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Memory.init(this)
        scroll = ScrollView(this).apply { setBackgroundColor(C.PAPER) }
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true; render() }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    private fun render() {
        lang = Prefs.lang(this)
        val page = vbox(22, 18)
        val back = hbox().apply { minimumHeight = dp(52) }
        back.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = android.content.res.ColorStateList.valueOf(C.INK) },
            LinearLayout.LayoutParams(dp(28), dp(28)))
        back.add(body(s("Back", "वापस", "వెనక్కి"), 18f, C.INK, bold = true), top = 8)
        back.pressable { finish() }
        page.add(back, 8)
        page.add(display(s("Phone School", "फ़ोन पाठशाला", "ఫోన్ బడి"), 40f), 10)

        val mastered = ALL.count { Memory.timesDone(it) >= 3 }
        page.add(body(s("You can do $mastered of ${ALL.size} on your own.", "आप ${ALL.size} में से $mastered ख़ुद कर सकते हैं।", "${ALL.size} లో $mastered మీరే చేయగలరు."), 19f, C.INK), 8)
        // Progress bar
        val bar = LinearLayout(this).apply { background = rounded(C.PAPER_3, dpf(6)) }
        bar.addView(View(this).apply { background = rounded(C.MARIGOLD, dpf(6)) },
            LinearLayout.LayoutParams(0, dp(12), mastered.toFloat().coerceAtLeast(0.001f)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(12), (ALL.size - mastered).toFloat().coerceAtLeast(0.001f)))
        page.add(bar, 12, h = dp(12))

        next()?.let { id -> Skills.byId(id) }?.let { sk ->
            page.add(overline(s("Next lesson", "अगला पाठ", "తదుపరి పాఠం")), 28)
            page.add(primaryButton(sk.title.pick(lang), sk.icon) { run(sk.example.pick(lang)) }, 10)
        }

        for (w in WEEKS) {
            page.add(overline(w.title.pick(lang)), 30)
            val box = surface()
            w.lessons.mapNotNull { Skills.byId(it) }.forEachIndexed { i, sk ->
                if (i > 0) box.addView(divider())
                val n = Memory.timesDone(sk.id)
                val status = when {
                    n >= 3 -> s("You can do this!", "आप यह कर सकते हैं!", "మీరు ఇది చేయగలరు!")
                    n > 0 -> s("Practising · done $n×", "अभ्यास · $n बार", "సాధన · $n సార్లు")
                    else -> s("New", "नया", "కొత్తది")
                }
                box.addView(row(sk.icon, sk.title.pick(lang), status, if (n >= 3) C.LEAF else C.PINE) { run(sk.example.pick(lang)) })
            }
            page.add(box, 10)
        }
        page.add(body(s("Each lesson is the real thing, on your own phone. After three times, I'll let you try first and only help if you need me.",
            "हर पाठ असली काम है, आपके अपने फ़ोन पर। तीन बार के बाद मैं पहले आपको करने दूँगा, ज़रूरत हो तभी मदद करूँगा।",
            "ప్రతి పాఠం మీ ఫోన్‌లో నిజమైన పనే. మూడు సార్లు తర్వాత ముందు మిమ్మల్నే ప్రయత్నించనిస్తాను."), 15f).apply { gravity = Gravity.START }, 26)
        page.add(View(this), 40)
        scroll.removeAllViews(); scroll.addView(page)
    }

    private fun run(goal: String) {
        val svc = SaathiService.instance ?: return
        SaathiService.ownUiOpen = false
        svc.guide.handleUtterance(goal)
    }
}
