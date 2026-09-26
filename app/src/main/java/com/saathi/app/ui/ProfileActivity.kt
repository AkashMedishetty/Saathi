package com.saathi.app.ui

import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.forms.FormProfile
import com.saathi.app.forms.FormUi
import com.saathi.app.forms.FormUi.Detail
import com.saathi.app.forms.Gender
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker
import java.time.LocalDate

/**
 * "My details": the only personal data the form helper uses ([FormProfile]). Opens behind the phone's screen lock,
 * stays on the phone, and never takes Aadhaar, PAN, bank or card numbers (the sanitiser empties them, and we say so).
 */
class ProfileActivity : AppCompatActivity() {
    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, ProfileActivity::class.java).apply {
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private lateinit var scroll: ScrollView
    private var lang = Lang.EN
    private var speaker: Speaker? = null
    private var draft = FormProfile()
    private val inputs = LinkedHashMap<Detail, EditText>()
    private var note: TextView? = null
    private var unlocked = false

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) open() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        speaker = Speaker(this)
        scroll = ScrollView(this).apply { setBackgroundColor(C.PAPER) }
        setContentView(scroll)
        withScreenLock()
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    /** Same pattern as SettingsActivity: the phone's PIN / pattern / fingerprint first, when the phone has one. */
    private fun withScreenLock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) { open(); return }
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent(
            s("Confirm it's you", "पुष्टि करें कि आप ही हैं", "మీరేనని నిర్ధారించండి"),
            s("Needed to see or change your details", "आपकी जानकारी देखने या बदलने के लिए", "మీ వివరాలు చూడటానికి లేదా మార్చడానికి"))
        if (i == null) { open(); return }
        unlock.launch(i)
    }

    private fun open() {
        unlocked = true
        draft = FormProfile.load(this)
        render()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun render(message: String? = null) {
        if (!unlocked) return
        val page = vbox(22, 18)
        val back = hbox().apply { minimumHeight = dp(56) }
        back.addView(ImageView(this).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(C.INK) },
            LinearLayout.LayoutParams(dp(28), dp(28)))
        back.add(body(s("Back", "वापस", "వెనక్కి"), 18f, C.INK, bold = true), top = 8)
        back.pressable { finish() }
        page.add(back, 8)
        page.add(display(s("My details", "मेरी जानकारी", "నా వివరాలు"), 38f), 10)
        page.add(body(s("I use these only to help you fill forms. They stay on this phone.",
            "मैं इन्हें सिर्फ़ फ़ॉर्म भरने में मदद के लिए इस्तेमाल करता हूँ। ये इसी फ़ोन में रहती हैं।",
            "వీటిని ఫారాలు నింపడంలో సహాయానికి మాత్రమే వాడతాను. ఇవి ఈ ఫోన్‌లోనే ఉంటాయి."), 17f), 8)
        page.add(body(s("Never type Aadhaar, PAN, bank or card numbers here.",
            "यहाँ आधार, पैन, बैंक या कार्ड नंबर कभी मत लिखिए।",
            "ఇక్కడ ఆధార్, పాన్, బ్యాంక్ లేదా కార్డ్ నంబర్లు ఎప్పుడూ రాయకండి."), 16f, C.VERMILION, bold = true), 8)

        inputs.clear()
        group(page, s("You", "आप", "మీరు"), listOf(Detail.FULL_NAME, Detail.SURNAME, Detail.FATHER, Detail.SPOUSE))
        page.add(dobAndGender(), 12)
        group(page, s("Address", "पता", "చిరునామా"), listOf(Detail.ADDRESS1, Detail.ADDRESS2, Detail.CITY, Detail.DISTRICT, Detail.STATE, Detail.PIN))
        group(page, s("Contact", "संपर्क", "సంప్రదింపు"), listOf(Detail.MOBILE, Detail.EMAIL, Detail.OCCUPATION))
        group(page, s("Nominee", "नामांकित व्यक्ति", "నామినీ"), listOf(Detail.NOMINEE, Detail.NOMINEE_REL))

        note = body(message ?: "", 17f, C.VERMILION, bold = true).apply { visibility = if (message == null) View.GONE else View.VISIBLE }
        page.add(note!!, 22)
        page.add(primaryButton(s("Save", "सेव करें", "సేవ్ చేయండి"), R.drawable.ic_check) { save() }, 14)
        page.add(primaryButton(s("Forget my details", "मेरी जानकारी मिटाएँ", "నా వివరాలు తొలగించు"), R.drawable.ic_close, bg = C.PAPER_2, fg = C.VERMILION) { forget() }, 12)
        page.add(View(this), 40)
        scroll.removeAllViews(); scroll.addView(page)
    }

    private fun group(page: LinearLayout, title: String, details: List<Detail>) {
        page.add(overline(title), 28)
        val card = surface().apply { setPadding(dp(18), dp(12), dp(18), dp(18)) }
        details.forEachIndexed { i, d ->
            card.add(body(d.label.pick(lang), 15f), if (i == 0) 4 else 16)
            val e = input(FormUi.get(draft, d), typeFor(d))
            e.contentDescription = d.label.pick(lang)
            inputs[d] = e
            card.add(e, 6)
        }
        page.add(card, 10)
    }

    private fun typeFor(d: Detail): Int = when (d) {
        Detail.MOBILE -> InputType.TYPE_CLASS_PHONE
        Detail.PIN -> InputType.TYPE_CLASS_NUMBER
        Detail.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        Detail.FULL_NAME, Detail.SURNAME, Detail.FATHER, Detail.SPOUSE, Detail.NOMINEE ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        Detail.ADDRESS1, Detail.ADDRESS2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }

    private fun input(value: String, type: Int) = EditText(this).apply {
        setText(value); inputType = type; textSize = 20f; typeface = Type.body(context); setTextColor(C.INK)
        background = rounded(C.PAPER, dpf(18), C.LINE, dp(1)); setPadding(dp(16), dp(14), dp(16), dp(14))
        minHeight = dp(56); isSingleLine = true
    }

    private fun dobAndGender(): View {
        val card = surface().apply { setPadding(dp(18), dp(12), dp(18), dp(18)) }
        card.add(body(s("Date of birth", "जन्म तिथि", "పుట్టిన తేదీ"), 15f), 4)
        val dobText = draft.dob?.let { "%02d / %02d / %d".format(it.dayOfMonth, it.monthValue, it.year) }
            ?: s("Choose the date", "तारीख़ चुनिए", "తేదీ ఎంచుకోండి")
        card.add(primaryButton(dobText, R.drawable.ic_history, bg = C.PAPER_2, fg = C.PINE_DEEP) { pickDob() }, 6)
        card.add(body(s("Gender", "लिंग", "లింగం"), 15f), 16)
        val chips = hbox()
        Gender.entries.forEachIndexed { i, g ->
            chips.add(chip(g.word(lang), draft.gender == g) {
                collect(); draft = draft.copy(gender = if (draft.gender == g) null else g); render()
            }, top = if (i == 0) 0 else 8, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.add(chips, 8)
        return card
    }

    private fun pickDob() {
        collect()
        val d = draft.dob ?: LocalDate.of(1955, 1, 1)
        DatePickerDialog(this, { _, y, m, day -> draft = draft.copy(dob = LocalDate.of(y, m + 1, day)); render() },
            d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
            // Spinner-style year scrolling is long for 1950s dates; the calendar header lets them tap the year.
            show()
        }
    }

    /** Typed text → the draft (not yet saved). */
    private fun collect() {
        inputs.forEach { (d, e) -> draft = FormUi.set(draft, d, e.text.toString().trim()) }
    }

    private fun save() {
        collect()
        val dropped = FormUi.dropped(draft)
        draft.save(this)                 // stores the sanitized copy
        draft = FormProfile.load(this)
        val msg = FormUi.droppedSay(dropped)
        if (msg == null) {
            val t = s("Saved. Now I can help you with forms.", "सेव हो गया। अब मैं फ़ॉर्म भरने में मदद कर सकता हूँ।",
                "సేవ్ అయింది. ఇప్పుడు ఫారాలు నింపడంలో సహాయం చేయగలను.")
            speaker?.say(t, lang)
            android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
            finish()
        } else {
            render(msg.pick(lang))
            speaker?.say(msg.pick(lang), lang)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun forget() {
        android.app.AlertDialog.Builder(this)
            .setTitle(s("Forget my details?", "मेरी जानकारी मिटाएँ?", "నా వివరాలు తొలగించాలా?"))
            .setMessage(s("Saathi will not know your details for forms any more.", "साथी को फ़ॉर्म के लिए आपकी जानकारी नहीं रहेगी।",
                "ఫారాల కోసం మీ వివరాలు సాథీకి ఇక తెలియవు."))
            .setPositiveButton(s("Forget", "मिटाएँ", "తొలగించు")) { _, _ -> FormProfile().save(this); draft = FormProfile(); render() }
            .setNegativeButton(s("Cancel", "रद्द करें", "రద్దు చేయండి"), null)
            .show()
    }
}
