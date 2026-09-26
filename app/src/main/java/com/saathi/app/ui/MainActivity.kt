package com.saathi.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.saathi.app.R
import com.saathi.app.llm.NpuProbe
import com.saathi.app.service.SaathiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/** Placeholder home for the skeleton; the editorial Home arrives in P0-5. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val serif = ResourcesCompat.getFont(this, R.font.tiro_deva)
        val body = ResourcesCompat.getFont(this, R.font.hind)

        val title = TextView(this).apply {
            text = "Saathi"
            textSize = 40f
            typeface = serif
            setTextColor(ContextCompat.getColor(context, R.color.ink))
        }
        status = TextView(this).apply {
            textSize = 18f
            typeface = body
            setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
            setPadding(0, pad / 2, 0, pad)
        }
        val turnOn = Button(this).apply {
            text = "Turn on the helper"
            typeface = Typeface.create(body, Typeface.BOLD)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(status); addView(turnOn)
        })
        probe(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        probe(intent)
    }

    /** Debug: adb shell am start -n com.saathi.app/.ui.MainActivity --es probe NPU [--es model text] */
    private fun probe(intent: Intent) {
        intent.getStringExtra("probe")?.let { backend ->
            val which = intent.getStringExtra("model") ?: "vision"
            status.text = "Testing $which model on $backend…"
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { NpuProbe.run(applicationContext, backend, which, intent.getBooleanExtra("vis", true)) }
                status.text = r
            }
        }
    }

    override fun onPause() {
        SaathiService.ownUiOpen = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        SaathiService.ownUiOpen = true
        if (intent.hasExtra("probe")) return
        status.text = if (SaathiService.isEnabled(this)) "● Helper on · works without internet" else "○ Helper off"
    }
}
