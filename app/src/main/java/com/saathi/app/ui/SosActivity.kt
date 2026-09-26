package com.saathi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.guide.FamilyHelp
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker

/**
 * Emergency: one screen, huge buttons, no thinking needed. Call each family contact, call 112, or prefill
 * "I need help" to family (Saathi still never sends by itself). Opened by voice ("help!", "बचाओ", "కాపాడండి") or Home.
 */
class SosActivity : AppCompatActivity() {
    private var speaker: Speaker? = null
    private var lang = Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        speaker = Speaker(this)
        val page = vbox(22, 26).apply { setBackgroundColor(C.PAPER) }
        page.add(overline(s("Emergency", "आपातकाल", "అత్యవసరం"), C.VERMILION), 30)
        page.add(display(s("Who should I call?", "किसे फ़ोन करूँ?", "ఎవరికి ఫోన్ చేయాలి?"), 40f), 10)

        val contacts = Prefs.contacts(this).filter { it.phone.isNotBlank() }
        contacts.forEachIndexed { i, c ->
            page.add(primaryButton(s("Call ${c.name.ifBlank { c.phone }}", "${c.name.ifBlank { c.phone }} को फ़ोन करें", "${c.name.ifBlank { c.phone }} కి ఫోన్ చేయండి"),
                R.drawable.ic_call, bg = if (i == 0) C.LEAF else C.PINE_DEEP) { call(c.phone) }, if (i == 0) 26 else 12)
        }
        if (contacts.isEmpty()) page.add(body(s("No family numbers yet. Add them in Saathi Settings.", "अभी परिवार के नंबर नहीं हैं। Saathi सेटिंग में जोड़िए।", "ఇంకా కుటుంబ నంబర్లు లేవు. Saathi సెట్టింగ్స్‌లో చేర్చండి."), 18f, C.INK), 20)
        page.add(primaryButton(s("Call 112 · Police & Ambulance", "112 पर फ़ोन · पुलिस और एम्बुलेंस", "112 కి ఫోన్ · పోలీస్ & అంబులెన్స్"), R.drawable.ic_sos, bg = C.VERMILION) { call("112") }, 18)
        page.add(primaryButton(s("Message family: I need help", "परिवार को संदेश: मदद चाहिए", "కుటుంబానికి సందేశం: సహాయం కావాలి"), R.drawable.ic_sms, bg = C.PAPER_2, fg = C.PINE_DEEP) {
            val who = Prefs.name(this).ifBlank { s("Your mother/father", "आपके माता/पिता", "మీ అమ్మ/నాన్న") }
            val msg = s("$who needs help urgently. Please call now. — Saathi", "$who को तुरंत मदद चाहिए। अभी फ़ोन कीजिए। — Saathi", "$who కి వెంటనే సహాయం కావాలి. ఇప్పుడే ఫోన్ చేయండి. — Saathi")
            FamilyHelp.intent(this, msg)?.let { runCatching { startActivity(it) } }
        }, 12)
        page.add(body(s("Close", "बंद करें", "మూసివేయి"), 18f, C.PINE_DEEP, bold = true).apply { gravity = Gravity.CENTER; minHeight = dp(56) }.pressable { finish() }, 16)
        page.add(View(this), 30)
        setContentView(ScrollView(this).apply { setBackgroundColor(C.PAPER); addView(page) })
        speaker?.say(s("Don't worry. Tap a big button and I'll call.", "घबराइए मत। बड़ा बटन दबाइए, मैं फ़ोन लगा दूँगा।", "భయపడకండి. పెద్ద బటన్ నొక్కండి, నేను ఫోన్ చేస్తాను."), lang)
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    /** Direct call when allowed (one tap matters in an emergency), else the dialer prefilled. */
    private fun call(num: String) {
        val direct = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$num"))
        runCatching { startActivity(i) }.onFailure { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) } }
    }
}
