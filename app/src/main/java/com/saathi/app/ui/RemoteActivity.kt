package com.saathi.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.saathi.app.R
import com.saathi.app.guide.IrRemote
import com.saathi.app.guide.IrRemote.Key
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.SaathiService

/** A TV remote with six huge buttons, using the phone's IR blaster. Works with no internet and no pairing. */
class RemoteActivity : AppCompatActivity() {
    private lateinit var scroll: ScrollView
    private var lang = Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scroll = ScrollView(this).apply { setBackgroundColor(C.PAPER) }
        setContentView(scroll)
        render()
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    private fun render() {
        lang = Prefs.lang(this)
        val page = vbox(22, 18)
        val back = hbox().apply { minimumHeight = dp(52) }
        back.addView(ImageView(this).apply { setImageResource(R.drawable.ic_arrow_back); imageTintList = ColorStateList.valueOf(C.INK) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        back.add(body(s("Back", "वापस", "వెనక్కి"), 18f, C.INK, bold = true), top = 8)
        back.pressable { finish() }
        page.add(back, 8)
        page.add(display(s("TV remote", "टीवी रिमोट", "టీవీ రిమోట్"), 40f), 10)
        page.add(body(if (IrRemote.available(this)) s("Point the top of the phone at the TV.", "फ़ोन का ऊपरी हिस्सा टीवी की ओर कीजिए।", "ఫోన్ పై భాగాన్ని టీవీ వైపు పెట్టండి.")
            else s("This phone has no IR blaster.", "इस फ़ोन में IR नहीं है।", "ఈ ఫోన్‌లో IR లేదు."), 18f), 8)

        val brands = hbox()
        IrRemote.Brand.entries.forEachIndexed { i, b ->
            brands.add(chip(b.label, IrRemote.brand(this) == b) { IrRemote.setBrand(this, b); render() }, top = if (i == 0) 0 else 10)
        }
        page.add(brands, 18)

        // Power: the one big round button.
        val power = FrameLayout(this).apply { background = rounded(C.VERMILION, dpf(60)); contentDescription = s("TV on / off", "टीवी चालू / बंद", "టీవీ ఆన్ / ఆఫ్") }
        power.addView(ImageView(this).apply { setImageResource(R.drawable.ic_tv); imageTintList = ColorStateList.valueOf(C.WHITE) }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        power.pressable { press(power, Key.POWER) }
        page.addView(power, LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(28) })
        page.add(body(s("On / Off", "चालू / बंद", "ఆన్ / ఆఫ్"), 17f, C.INK, bold = true).apply { gravity = Gravity.CENTER }, 8)

        // Volume and channel: two columns of tall pills.
        val cols = hbox().apply { gravity = Gravity.TOP }
        cols.add(column(s("Sound", "आवाज़", "సౌండ్"), Key.VOL_UP, Key.VOL_DOWN), weight = 1f)
        cols.add(column(s("Channel", "चैनल", "ఛానెల్"), Key.CH_UP, Key.CH_DOWN), top = 14, weight = 1f)
        page.add(cols, 28)
        page.add(primaryButton(s("Mute", "आवाज़ बंद", "మ్యూట్"), R.drawable.ic_volume_up, bg = C.PAPER_2, fg = C.PINE_DEEP) { press(scroll, Key.MUTE) }, 18)

        // Navigation: a big D-pad with OK in the middle, then Back · Home · Source.
        page.add(overline(s("Move around the TV", "टीवी में आगे-पीछे", "టీవీలో కదలండి")).apply { gravity = Gravity.CENTER }, 30)
        val pad = android.widget.GridLayout(this).apply { columnCount = 3; rowCount = 3 }
        fun cell(k: Key?, label: String, bg: Int = C.PINE_DEEP) {
            val v: View = if (k == null) View(this) else display(label, 30f, C.WHITE).apply {
                gravity = Gravity.CENTER; background = rounded(bg, dpf(24)); contentDescription = k.name
            }.also { tv -> tv.pressable { press(tv, k) } }
            pad.addView(v, android.widget.GridLayout.LayoutParams().apply { width = dp(96); height = dp(88); setMargins(dp(5), dp(5), dp(5), dp(5)) })
        }
        cell(null, ""); cell(Key.UP, "▲"); cell(null, "")
        cell(Key.LEFT, "◀"); cell(Key.OK, "OK", C.MARIGOLD); cell(Key.RIGHT, "▶")
        cell(null, ""); cell(Key.DOWN, "▼"); cell(null, "")
        page.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        val nav = hbox()
        listOf(Triple(Key.BACK, s("Back", "वापस", "వెనక్కి"), R.drawable.ic_arrow_back), Triple(Key.HOME, s("Home", "होम", "హోమ్"), R.drawable.ic_home),
            Triple(Key.SOURCE, s("Input", "इनपुट", "ఇన్‌పుట్"), R.drawable.ic_settings_remote)).forEachIndexed { i, (k, l, ic) ->
            nav.add(primaryButton(l, ic, bg = C.PAPER_2, fg = C.PINE_DEEP) { press(scroll, k) }, top = if (i == 0) 0 else 8, weight = 1f)
        }
        page.add(nav, 14)

        // Channel number pad
        page.add(overline(s("Channel number", "चैनल नंबर", "ఛానెల్ నంబర్")).apply { gravity = Gravity.CENTER }, 30)
        val nums = android.widget.GridLayout(this).apply { columnCount = 3 }
        (listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)).forEach { n ->
            val k = Key.valueOf("N$n")
            val v = display("$n", 30f, C.PINE_DEEP).apply { gravity = Gravity.CENTER; background = rounded(C.WHITE, dpf(22), C.LINE, dp(1)) }
            v.pressable { press(v, k) }
            nums.addView(v, android.widget.GridLayout.LayoutParams().apply {
                width = dp(96); height = dp(72); setMargins(dp(5), dp(5), dp(5), dp(5))
                if (n == 0) columnSpec = android.widget.GridLayout.spec(1)
            })
        }
        page.addView(nums, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        page.add(body(s("You can also say: “TV volume up”, “TV go down”, “TV OK”, “channel 25”", "आप बोल भी सकते हैं: “टीवी की आवाज़ बढ़ाओ”, “टीवी चैनल 25”", "ఇలా కూడా చెప్పవచ్చు: “టీవీ సౌండ్ పెంచు”, “టీవీ ఛానెల్ 25”"), 15f).apply { gravity = Gravity.CENTER }, 18)
        page.add(View(this), 40)
        scroll.removeAllViews(); scroll.addView(page)
    }

    private fun column(title: String, up: Key, down: Key): View = vbox().apply {
        add(overline(title).apply { gravity = Gravity.CENTER })
        add(big("+", up), 10)
        add(big("−", down), 10)
    }

    private fun big(label: String, key: Key): View {
        val v = display(label, 44f, C.WHITE).apply {
            gravity = Gravity.CENTER; minHeight = dp(96); background = rounded(C.PINE_DEEP, dpf(28)); contentDescription = key.name
        }
        return v.pressable { press(v, key) }
    }

    private fun press(v: View, key: Key) {
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        IrRemote.send(this, key)
    }
}
