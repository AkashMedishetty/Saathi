package com.saathi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saathi.app.R
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Memory
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.ScamGuard
import com.saathi.app.guide.Screen
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.llm.LlmManager
import com.saathi.app.llm.VisionBrain
import com.saathi.app.service.GlowView
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * "Read this for me" + magnifier, and "medicine strip → reminder".
 * The person points the camera at a bill, letter, sign or medicine strip and taps the big button.
 * Offline OCR (Latin + Devanagari) reads the words; FastVLM on the Snapdragon NPU explains the photo;
 * Gemma (text) or a template takes over if needed. Nothing leaves the phone.
 */
class ReadActivity : AppCompatActivity(), com.saathi.app.guide.TvSession.Screen {
    companion object { const val EXTRA_MODE = "mode"; const val MODE_READ = "read"; const val MODE_MEDICINE = "medicine"; const val MODE_OBJECT = "object"; const val MODE_TV = "tv" }

    private lateinit var preview: PreviewView
    private lateinit var aura: GlowView
    private lateinit var sheet: LinearLayout
    private lateinit var sheetText: TextView
    private lateinit var sheetSub: TextView
    private lateinit var sheetActions: LinearLayout
    private lateinit var controls: View
    private lateinit var frozen: PhotoGlow
    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var torch = false
    private var speaker: Speaker? = null
    private var lang = Lang.EN
    private var mode = MODE_READ
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_READ
        speaker = Speaker(this)
        Memory.init(this)
        build()
        if (mode == MODE_TV) {
            com.saathi.app.guide.TvSession.screen = this
            val first = intent.getStringExtra("instruction")
            sheet.post { if (first != null) instruct(first, false) else { showSheet(s("Keep the camera on your TV. I'm looking…", "कैमरा टीवी पर रखिए। मैं देख रहा हूँ…", "కెమెరాను టీవీ మీద ఉంచండి. చూస్తున్నాను…")); autoLook(2500) } }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 4)
        val hello = if (mode == MODE_TV) s("Point the camera at your TV and keep it there. I'll tell you which button to press on your remote.", "कैमरा टीवी की स्क्रीन की ओर करके बड़ा बटन दबाइए।", "కెమెరాను టీవీ స్క్రీన్ వైపు పెట్టి పెద్ద బటన్ నొక్కండి.")
        else if (mode == MODE_OBJECT) s("Point the camera at the machine or thing, and tap the big button. I'll tell you how to use it.",
            "मशीन या चीज़ की ओर कैमरा कीजिए और बड़ा बटन दबाइए। मैं बताऊँगा कैसे चलाते हैं।",
            "యంత్రం లేదా వస్తువు వైపు కెమెరా పెట్టి పెద్ద బటన్ నొక్కండి. ఎలా వాడాలో చెబుతాను.")
        else if (mode == MODE_MEDICINE) s("Hold the medicine strip flat, name side up, and tap the big button.",
            "दवा का पत्ता सीधा पकड़िए, नाम ऊपर, और बड़ा बटन दबाइए।", "మందుల స్ట్రిప్‌ను పేరు పైకి ఉండేలా పట్టుకుని పెద్ద బటన్ నొక్కండి.")
        else s("Point at the paper and tap the big button. I'll read it to you.",
            "काग़ज़ की ओर फ़ोन कीजिए और बड़ा बटन दबाइए। मैं पढ़कर सुनाऊँगा।", "కాగితం వైపు ఫోన్ పెట్టి పెద్ద బటన్ నొక్కండి. నేను చదివి వినిపిస్తాను.")
        speaker?.say(hello, lang)
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() {
        if (com.saathi.app.guide.TvSession.screen === this) com.saathi.app.guide.TvSession.screen = null
        handler.removeCallbacksAndMessages(null)
        speaker?.shutdown(); super.onDestroy()
    }

    // ── Live TV coaching: instruction → they press it on their remote → look again → next instruction ──
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun autoLook(ms: Long) { handler.removeCallbacksAndMessages(null); handler.postDelayed({ lookNow() }, ms) }

    override fun instruct(text: String, question: Boolean) = runOnUiThread {
        handler.removeCallbacksAndMessages(null)
        busy = false
        val actions = if (question) listOf(
            primaryButton(s("Yes", "हाँ", "అవును"), R.drawable.ic_check) { answer("yes") },
            primaryButton(s("No", "नहीं", "కాదు"), R.drawable.ic_close, bg = C.PAPER_2, fg = C.PINE_DEEP) { answer("no") },
            stopCoach())
        else listOf(
            primaryButton(s("Done, I pressed it", "दबा दिया", "నొక్కాను"), R.drawable.ic_check) { lookNow() },
            stopCoach())
        showSheet(text, if (question) "" else s("I'll look at the TV again in a moment.", "मैं थोड़ी देर में टीवी फिर देखूँगा।", "కొద్దిసేపట్లో టీవీని మళ్ళీ చూస్తాను."), actions)
        speaker?.say(text, lang)
        if (!question) autoLook(7000L + text.length * 40L)
    }

    override fun lookNow() = runOnUiThread { handler.removeCallbacksAndMessages(null); if (!busy) snap() }

    override fun end(text: String) = runOnUiThread {
        handler.removeCallbacksAndMessages(null)
        showSheet(text, "", listOf(close()))
        speaker?.say(text, lang)
        handler.postDelayed({ finish() }, 6000)
    }

    private fun answer(a: String) { showSheet(s("Okay…", "ठीक है…", "సరే…")); SaathiService.instance?.guide?.handleUtterance(a) }
    private fun stopCoach() = body(s("Stop", "रोकें", "ఆపండి"), 18f, C.PINE_DEEP, bold = true).apply { gravity = Gravity.CENTER; minHeight = dp(52) }
        .pressable { SaathiService.instance?.guide?.handleUtterance("stop"); finish() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else finish()
    }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)

    // ───────── UI ─────────

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frozen = PhotoGlow(this).apply { visibility = View.GONE }
        // The photo sits in the top part of the screen, above the answer sheet.
        root.addView(frozen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.52f).toInt(), Gravity.TOP))
        aura = GlowView(this)
        root.addView(aura, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top: close · title · torch
        val top = hbox().apply { setPadding(dp(18), dp(52), dp(18), 0) }
        top.addView(roundIcon(R.drawable.ic_close, s("Close", "बंद करें", "మూసివేయి")) { finish() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.add(overline(when (mode) {
            MODE_MEDICINE -> s("Medicine strip", "दवा का पत्ता", "మందుల స్ట్రిప్")
            MODE_OBJECT -> s("How do I use this?", "यह कैसे चलाएँ?", "ఇది ఎలా వాడాలి?")
            else -> s("Read this for me", "मेरे लिए पढ़ो", "నా కోసం చదువు")
        }, C.WHITE).apply {
            gravity = Gravity.CENTER }, weight = 1f)
        val torchBtn = roundIcon(R.drawable.ic_flashlight_off, s("Torch", "टॉर्च", "టార్చ్")) {}
        torchBtn.setOnClickListener {
            torch = !torch; camera?.cameraControl?.enableTorch(torch)
            ((torchBtn as FrameLayout).getChildAt(0) as ImageView).setImageResource(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        }
        top.addView(torchBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // One camera for everything: read a paper, a medicine strip, or "how do I use this?".
        modes = hbox().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        renderModes()
        // Four chips don't fit every screen: the row scrolls sideways.
        val modesScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; setPadding(0, dp(122), 0, 0); clipToPadding = false
            addView(modes)
        }
        root.addView(modesScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom: magnifier + shutter
        val bottom = vbox(24, 0).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), dp(40)) }
        val zoomRow = hbox()
        zoomRow.addView(body("A", 16f, C.WHITE, bold = true))
        zoomRow.add(SeekBar(this).apply {
            max = 100; minimumHeight = dp(48)
            thumbTintList = ColorStateList.valueOf(C.MARIGOLD); progressTintList = ColorStateList.valueOf(C.MARIGOLD)
            contentDescription = s("Magnifier", "आवर्धक", "భూతద్దం")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { camera?.cameraControl?.setLinearZoom(p / 100f) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, top = 8, weight = 1f)
        zoomRow.add(body("A", 26f, C.WHITE, bold = true), top = 8)
        bottom.add(zoomRow)
        bottom.add(body(s("Slide to make it bigger", "बड़ा करने के लिए खिसकाइए", "పెద్దది చేయడానికి జరపండి"), 15f, 0xCCFFFFFF.toInt()).apply { gravity = Gravity.CENTER }, 2)
        val shutter = FrameLayout(this).apply {
            background = rounded(C.WHITE, dpf(46), C.MARIGOLD, dp(6))
            contentDescription = s("Read it", "पढ़ो", "చదువు")
        }
        shutter.addView(ImageView(this).apply { setImageResource(R.drawable.ic_document_scanner); imageTintList = ColorStateList.valueOf(C.PINE_DEEP) },
            FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        shutter.pressable { snap() }
        bottom.addView(shutter, LinearLayout.LayoutParams(dp(92), dp(92)).apply { topMargin = dp(18) })
        controls = bottom
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Result sheet (hidden until we have something to say)
        sheet = vbox(22, 20).apply { background = rounded(C.PAPER, dpf(32)); elevation = dpf(24); visibility = View.GONE; isClickable = true }
        sheetText = display("", 24f)
        sheetSub = body("", 16f)
        sheetActions = vbox()
        sheet.add(sheetText); sheet.add(sheetSub, 10); sheet.add(sheetActions, 16)
        val sc = ScrollView(this).apply { addView(sheet) }
        root.addView(sc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(dp(10), dp(120), dp(10), dp(12)) })
        setContentView(root)
    }

    private lateinit var modes: LinearLayout

    private fun renderModes() {
        modes.removeAllViews()
        listOf(MODE_READ to s("Read", "पढ़ो", "చదువు"), MODE_MEDICINE to s("Medicine", "दवा", "మందు"), MODE_OBJECT to s("How to use", "कैसे चलाएँ", "ఎలా వాడాలి")).forEachIndexed { i, (m, label) ->
            modes.add(chip(label, m == mode) { mode = m; renderModes(); speaker?.say(label, lang) }, top = if (i == 0) 0 else 8)
        }
        // A paper form has its own guided screen (FormActivity: box by box, what to write).
        val formLabel = s("Fill a form", "फ़ॉर्म भरो", "ఫారం నింపు")
        modes.add(chip(formLabel, false) { speaker?.say(formLabel, lang); FormActivity.start(this); finish() }, top = 8)
    }

    private fun roundIcon(icon: Int, label: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = rounded(0x66000000, dpf(28)); contentDescription = label
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(C.WHITE) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    }.pressable(onClick)

    private fun showSheet(text: String, sub: String = "", actions: List<View> = emptyList()) {
        sheetText.text = text
        sheetSub.text = sub; sheetSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        sheetActions.removeAllViews()
        actions.forEachIndexed { i, v -> sheetActions.add(v, if (i == 0) 0 else 10) }
        controls.visibility = View.GONE
        if (sheet.visibility != View.VISIBLE) {
            sheet.visibility = View.VISIBLE; sheet.alpha = 0f; sheet.translationY = dpf(60)
            sheet.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(380).start()
        }
    }

    private fun reset() {
        frozen.clear()
        sheet.visibility = View.GONE; controls.visibility = View.VISIBLE; busy = false; aura.setAura(false)
    }

    // ───────── camera ─────────

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, capture)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun snap() {
        if (busy) return
        val cap = capture ?: return
        busy = true
        aura.setAura(true)
        showSheet(s("Reading…", "पढ़ रहा हूँ…", "చదువుతున్నాను…"))
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching { upright(image) }.getOrNull()
                image.close()
                if (bmp == null) { reset(); return }
                if (tooDark(bmp)) {
                    // A black or blank frame (lens covered, dark room): say so instead of "describing" nothing.
                    aura.setAura(false); busy = false
                    com.saathi.app.DebugLog.i("read", "too dark")
                    val t = s("It's too dark to read. Hold it in the light, a little further away, and try again.",
                        "बहुत अँधेरा है। रोशनी में, थोड़ा दूर रखकर फिर कोशिश कीजिए।", "చాలా చీకటిగా ఉంది. వెలుతురులో, కొంచెం దూరంగా పెట్టి మళ్ళీ ప్రయత్నించండి.")
                    showSheet(t, "", listOf(again(), close())); speaker?.say(t, lang); return
                }
                lifecycleScope.launch { understand(bmp) }
            }
            override fun onError(e: ImageCaptureException) { reset() }
        })
    }

    /** Mean luminance and spread on a 24×24 thumbnail: too dark, or one flat colour (lens covered). */
    private fun tooDark(b: Bitmap): Boolean {
        val t = Bitmap.createScaledBitmap(b, 24, 24, true)
        val px = IntArray(576).also { t.getPixels(it, 0, 24, 0, 0, 24, 24) }
        val lum = px.map { (0.299 * ((it shr 16) and 255) + 0.587 * ((it shr 8) and 255) + 0.114 * (it and 255)) }
        val mean = lum.average()
        val sd = kotlin.math.sqrt(lum.sumOf { (it - mean) * (it - mean) } / lum.size)
        return mean < 28 || sd < 6
    }

    private fun upright(img: ImageProxy): Bitmap {
        var b = img.toBitmap()
        val rot = img.imageInfo.rotationDegrees
        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val scale = 1280f / maxOf(b.width, b.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
    }

    // ───────── understanding ─────────

    private suspend fun understand(bmp: Bitmap) {
        val text = ocr(bmp)
        val lines = text?.textBlocks?.flatMap { it.lines }?.sortedBy { it.boundingBox?.top ?: 0 }.orEmpty()
        val plain = lines.joinToString("\n") { it.text }.trim()

        // Scam wording on the paper/SMS/letter?
        ScamGuard.check(Screen("camera", emptyList(), plain))?.let { alert ->
            aura.setAura(false)
            val t = alert.say.pick(lang)
            showSheet(t, plain.take(160), listOf(again(), close()))
            sheet.background = rounded(0xFFFBE4DE.toInt(), dpf(32))
            speaker?.say(t, lang); busy = false
            return
        }

        // The coach asked us to look at the TV: describe it (FastVLM on the NPU) and hand it back.
        if (mode == MODE_TV) {
            val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            val d = VisionBrain.describe(applicationContext, jpeg,
                "This is a photo of a TV screen. For someone using a TV remote, describe: which app or menu is showing, " +
                    "which item looks highlighted or selected, and the visible tiles, buttons or rows in order from left to right, top to bottom.")
                ?: plain.lines().take(12).joinToString("; ").ifBlank { "nothing readable" }
            aura.setAura(false)
            frozen.clear(); controls.visibility = View.VISIBLE
            showSheet(s("Got it. Thinking about the next step…", "समझ गया। अगला क़दम सोच रहा हूँ…", "అర్థమైంది. తర్వాతి అడుగు ఆలోచిస్తున్నాను…"))
            SaathiService.instance?.guide?.coachObserve(d)
            return
        }
        // A medicine strip in "Read" mode? Treat it as one (the person shouldn't have to pick the right mode).
        if (mode == MODE_READ && Regex("(?i)\\b\\d+\\s?mg\\b|tablets?\\s+i\\.?p|\\bcapsules?\\b|\\bI\\.P\\.|\\bRx\\b").containsMatchIn(plain)) mode = MODE_MEDICINE
        if (mode == MODE_MEDICINE) { medicine(lines.map { it.text to (it.boundingBox?.height() ?: 0) }); return }

        // Blurry / a screen / at an angle: OCR returns mostly non-words ("Ith lorly Androld Oompanlon…"). Explaining
        // that invents things (field test: "an old man named Oek wants money"). Ask for a better photo instead.
        // ML Kit's own confidence per line (0..1) is the stronger signal; the word-shape ratio backs it up.
        val conf = lines.map { it.confidence }.filter { it > 0f }.takeIf { it.isNotEmpty() }?.average() ?: 1.0
        com.saathi.app.DebugLog.i("read", "ocr ${plain.length} chars, confidence ${"%.2f".format(conf)}, word-like ${"%.2f".format(wordLikeRatio(plain))}")
        // Blurry = the words themselves look broken, or BOTH signals are weak. Field (23:03): clear print scored ML Kit
        // confidence 0.55-0.58 with 89-100 % real words and was wrongly called blurry.
        val wl = wordLikeRatio(plain)
        if (mode == MODE_READ && plain.length >= 30 && (wl < 0.55 || conf < 0.45 || (conf < 0.62 && wl < 0.8))) {
            aura.setAura(false); busy = false
            com.saathi.app.DebugLog.i("read", "blurry: word-like ${"%.2f".format(wordLikeRatio(plain))}")
            val t = s("The words are blurry. Hold the paper flat, a little closer, and keep the phone still.",
                "अक्षर धुंधले हैं। काग़ज़ सीधा रखिए, थोड़ा पास लाइए, और फ़ोन स्थिर रखिए।",
                "అక్షరాలు మసకగా ఉన్నాయి. కాగితం సమంగా పెట్టి, కొంచెం దగ్గరగా, ఫోన్ కదలకుండా పట్టుకోండి.")
            showSheet(t, "", listOf(again(), close())); speaker?.say(t, lang); return
        }

        // A paper with real words: explain THOSE words with the text brain (grounded; FastVLM only guesses at small print
        // and made things up in the field test). Objects / pictures / little text: FastVLM on the NPU, with the words as a hint.
        // Order for text: Gemma 4 if already loaded → Gemma 3 1B on the NPU (≈1 s) → FastVLM. Loops/garbage are rejected.
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val words = plain.replace(Regex("\\s+"), " ").trim()
        val textHeavy = mode == MODE_READ && words.length >= 40
        fun ok(t: String?) = t?.takeIf { it.isNotBlank() && !com.saathi.app.llm.Templates.garbled(it) }
        val explainSys = "You explain a paper to an elderly person in India in very simple English, in 3 short sentences: " +
            "1) what this paper is, 2) what matters in it (amount, due date, who sent it, what they want), 3) what the person should do next. " +
            "Use ONLY what the paper's text says; never invent names, amounts or dates. Bills: the amount and the due date. " +
            "Letters: who it is from and what they want. Medicine: its name. If it asks for an OTP, PIN, bank details or urgent payment, say it may be a scam."
        val explainUser = "The paper's text (read by the camera, may have small mistakes):\n${words.take(1500)}"
        var usedEngine = ""
        var llm: String? = null
        if (textHeavy) {
            // The proper text brain first: load it and give it a few seconds (field: "no text brain" → the photo model's
            // half sentences).
            if (!LlmManager.isReady) {
                LlmManager.loadAsync(applicationContext)
                var waited = 0
                while (!LlmManager.isReady && LlmManager.state.value !is LlmManager.State.Failed && waited < 8000) { kotlinx.coroutines.delay(250); waited += 250 }
            }
            com.saathi.app.llm.AiMeter.purpose = "explain paper"
            if (LlmManager.isReady) llm = ok(LlmManager.generate(explainSys, explainUser))?.also { usedEngine = "${LlmManager.label ?: "Gemma"} · ${LlmManager.lastGenMs} ms" }
            if (llm == null) {
                val t0 = android.os.SystemClock.elapsedRealtime()
                llm = ok(com.saathi.app.llm.FastBrain.generate(applicationContext, explainSys, explainUser))
                    ?.also { usedEngine = "Gemma 3 1B · Snapdragon NPU · ${android.os.SystemClock.elapsedRealtime() - t0} ms" }
            }
            com.saathi.app.DebugLog.i("read", "text ${words.length} chars → ${if (llm != null) usedEngine else "no text brain"}")
        }
        val hint = if (words.length >= 6) " Words printed on it: \"${words.take(300)}\"." else ""
        val vlm = if (llm != null) null else ok(if (mode == MODE_OBJECT) VisionBrain.describe(applicationContext, jpeg,
            "You help an elderly person use everyday things safely. Name the object in the photo, then give 2 or 3 very short, " +
                "simple steps to use it, based on the buttons or labels you can see. Mention one safety tip if it heats, cuts or uses electricity.$hint")
        else VisionBrain.describe(applicationContext, jpeg,
            "You help an elderly person. In at most 2 short, simple sentences, say what this paper is and what matters in it. " +
                "If it is a bill, say the amount and the due date. If it is a medicine, say its name. " +
                "If it is a letter, say who it is from and what they want. If it looks like a scam, say so clearly. Only say what you can see.$hint"))
        if (vlm != null) usedEngine = "FastVLM · Snapdragon NPU · ${VisionBrain.lastMs} ms"
        // Clean (no markdown, no "Okay! Let me explain…") and grounded: a text explanation must only use what's on
        // the paper (policy.AnswerCheck); otherwise we read the words themselves instead of guessing.
        val cleaned = (vlm ?: llm)?.let { clean(it) }
        val grounded = if (llm != null && cleaned != null && textHeavy)
            cleaned.takeIf { factsOnPaper(it, words) }
                .also { if (it == null) com.saathi.app.DebugLog.i("read", "explanation not supported by the paper → reading the words") }
        else cleaned
        val explained = grounded?.let { firstSentences(it, if (mode == MODE_OBJECT) 4 else 3) }
        aura.setAura(false)
        busy = false
        // "Tap here" on the real thing: glow the printed button the explanation talks about (object mode),
        // or the amount / due date on a bill (read mode). Uses OCR word boxes; nothing is guessed.
        val targets = pointAt(text, explained ?: plain)
        if (targets.second.isNotEmpty()) frozen.show(bmp, targets.second)
        val engine = usedEngine

        if (explained == null && plain.isBlank()) {
            val t = s("I couldn't see any words. Hold it closer, with more light, and try again.",
                "कोई अक्षर नहीं दिखे। पास लाकर, रोशनी में फिर कोशिश कीजिए।", "అక్షరాలు కనిపించలేదు. దగ్గరగా, వెలుతురులో మళ్ళీ ప్రయత్నించండి.")
            showSheet(t, "", listOf(again(), close())); speaker?.say(t, lang); return
        }
        // Speech: English explanation when EN; for HI/TE we read the words themselves (small models write broken Indic, trap #5).
        val pressLine = targets.first?.let { w -> if (mode == MODE_OBJECT) s("Press the glowing “$w” button.", "चमकते “$w” बटन को दबाइए।", "మెరుస్తున్న “$w” బటన్ నొక్కండి.")
            else s("I've circled the amount on the photo.", "मैंने फ़ोटो पर रक़म पर घेरा बनाया है।", "ఫోటోలో మొత్తం చుట్టూ గుర్తు పెట్టాను.") }
        val shown = (if (lang == Lang.EN) explained ?: plain.lines().take(6).joinToString("\n")
            else s("", "इस पर लिखा है:", "దీని మీద రాసి ఉంది:") + "\n" + plain.lines().take(6).joinToString("\n")) + (pressLine?.let { "\n\n$it" } ?: "")
        val preview = plain.lines().filter { it.isNotBlank() }.take(4).joinToString(" · ")
        val sub = if (lang == Lang.EN) (if (explained != null) preview else "") else (explained ?: "")
        // Keep the words (RAM only) so a follow-up question is answered from this paper.
        com.saathi.app.guide.Conversation.setPaper(plain.ifBlank { explained })
        showSheet(shown, listOf(sub, engine).filter { it.isNotBlank() }.joinToString("\n\n"),
            listOf(primaryButton(s("Ask about it", "इसके बारे में पूछें", "దీని గురించి అడగండి"), R.drawable.ic_mic) {
                    SaathiService.instance?.openAsk(listen = true) },
                primaryButton(s("Read it again", "फिर से पढ़ो", "మళ్ళీ చదువు"), R.drawable.ic_volume_up, bg = C.PAPER_2, fg = C.PINE_DEEP) { speaker?.say(shown, lang) },
                again(), close()))
        speaker?.say(shown, lang)
    }

    /** Medicine: the biggest line on the strip is usually the name. Confirm, then pick a time → daily reminder. */
    private fun medicine(lines: List<Pair<String, Int>>) {
        aura.setAura(false); busy = false
        val name = lines.filter { (t, _) -> t.count { it.isLetter() } >= 3 && !Regex("(?i)^(tablets?|capsules?|ip|usp|mfg|exp|batch|b\\.? ?no|each|film|coated)").containsMatchIn(t.trim()) }
            .maxByOrNull { it.second }?.first?.trim()?.take(30)
        if (name == null) {
            val t = s("I couldn't read the name. Turn the strip so the big letters face the camera.", "नाम नहीं पढ़ पाया। पत्ते को घुमाइए ताकि बड़े अक्षर कैमरे की ओर हों।", "పేరు చదవలేకపోయాను. పెద్ద అక్షరాలు కెమెరా వైపు ఉండేలా తిప్పండి.")
            showSheet(t, "", listOf(again(), close())); speaker?.say(t, lang); return
        }
        val q = s("Is it “$name”?", "क्या यह “$name” है?", "ఇది “$name” నా?")
        speaker?.say(q, lang)
        showSheet(q, s("I only read what's printed. I never give dosage advice.", "मैं सिर्फ़ छपा हुआ पढ़ता हूँ, ख़ुराक की सलाह नहीं देता।", "ముద్రించినదే చదువుతాను; మోతాదు సలహా ఇవ్వను."), listOf(
            primaryButton(s("Yes, that's it", "हाँ, यही है", "అవును, ఇదే"), R.drawable.ic_check) { pickTime(name) },
            again(), close()))
    }

    private fun pickTime(name: String) {
        val q = s("When do you take $name?", "$name कब लेते हैं?", "$name ఎప్పుడు వేసుకుంటారు?")
        speaker?.say(q, lang)
        val times = listOf(
            Triple(s("Morning · 8 AM", "सुबह · 8 बजे", "ఉదయం · 8"), 8, "8 am"),
            Triple(s("Afternoon · 2 PM", "दोपहर · 2 बजे", "మధ్యాహ్నం · 2"), 14, "2 pm"),
            Triple(s("Night · 9 PM", "रात · 9 बजे", "రాత్రి · 9"), 21, "9 pm"),
        )
        showSheet(q, "", times.map { (label, _, spoken) ->
            primaryButton(label, R.drawable.ic_alarm, bg = C.PAPER_2, fg = C.PINE_DEEP) {
                // Hand over to the guide: it opens the Clock with the alarm prefilled and walks them through Save.
                finish()
                SaathiService.instance?.guide?.handleUtterance("remind me to take $name at $spoken")
                    ?: startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM))
            }
        } + close())
    }

    private fun again() = primaryButton(s("Try another", "दूसरा पढ़ो", "ఇంకొకటి"), R.drawable.ic_photo_camera, bg = C.PAPER_2, fg = C.PINE_DEEP) {
        sheet.background = rounded(C.PAPER, dpf(32)); reset()
    }
    private fun close() = body(s("Close", "बंद करें", "మూసివేయి"), 18f, C.PINE_DEEP, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = dp(52)
    }.pressable { com.saathi.app.guide.Conversation.setPaper(null); finish() }

    private val BUTTON_WORDS = listOf("start", "power", "on/off", "on", "off", "stop", "cancel", "ok", "enter", "timer", "time", "clock", "temp",
        "menu", "mode", "set", "reset", "defrost", "reheat", "quick", "wash", "spin", "rinse", "eco", "auto", "fan", "swing", "cool", "heat",
        "play", "pause", "open", "lock", "unlock", "boil", "warm", "high", "low", "+30s", "+30 sec")

    /** Returns (word to say, boxes to glow). Object: the button word the explanation mentions first (else START/POWER). */
    private fun pointAt(t: Text?, explanation: String): Pair<String?, List<android.graphics.Rect>> {
        t ?: return null to emptyList()
        val elements = t.textBlocks.flatMap { b -> b.lines.flatMap { it.elements } }
        if (mode == MODE_OBJECT) {
            val buttons = elements.filter { e -> e.text.trim().lowercase().trim('.', ':') in BUTTON_WORDS && e.boundingBox != null }
            if (buttons.isEmpty()) return null to emptyList()
            val ex = explanation.lowercase()
            val pick = buttons.minByOrNull { e -> ex.indexOf(e.text.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
                ?.takeIf { ex.contains(it.text.lowercase()) }
                ?: buttons.firstOrNull { it.text.lowercase() in setOf("start", "power", "on/off") } ?: buttons.first()
            return pick.text.uppercase() to listOfNotNull(pick.boundingBox)
        }
        // Bill / letter: the line with the amount, and the due date line.
        val lines = t.textBlocks.flatMap { it.lines }
        val money = lines.firstOrNull { Regex("(?i)(₹|rs\\.?|inr|amount|total|payable)\\s*[:\\-]?\\s*[\\d,]+").containsMatchIn(it.text) }
        val due = lines.firstOrNull { Regex("(?i)due|last date|pay by|अंतिम तिथि").containsMatchIn(it.text) }
        val boxes = listOfNotNull(money?.boundingBox, due?.boundingBox)
        return (if (boxes.isEmpty()) null else "amount") to boxes
    }

    /** Small models ramble: keep the first [n] sentences. */
    /** Share of tokens that look like real words (letters, a vowel, sane length), in any script we read. */
    /**
     * A summary is never a copy of the paper, so grounding checks the FACTS: every number / amount / date in the
     * explanation must be on the paper (that's where an invented detail hurts), and it mustn't be garbled or medical /
     * money advice beyond what's written. Field (05:23): the copy-exact check rejected every explanation.
     */
    private fun factsOnPaper(expl: String, paper: String): Boolean {
        val digitsOnPaper = paper.replace(Regex("[^0-9]"), " ").split(' ').filter { it.isNotBlank() }.toSet()
        val paperDigitsJoined = paper.replace(Regex("[^0-9]"), "")
        val nums = Regex("\\d[\\d,./:-]*").findAll(expl).map { it.value.replace(Regex("[^0-9]"), "") }.filter { it.isNotEmpty() }.toList()
        val bad = nums.filter { n -> n !in digitsOnPaper && !paperDigitsJoined.contains(n) }
        if (bad.isNotEmpty()) com.saathi.app.DebugLog.i("read", "explanation has numbers not on the paper: $bad")
        return bad.isEmpty() && !com.saathi.app.llm.Templates.garbled(expl)
    }

    private fun wordLikeRatio(t: String): Double {
        val toks = t.split(Regex("[\\s·,;:|/]+")).filter { it.length >= 2 }
        if (toks.isEmpty()) return 1.0
        val vowel = Regex("(?i)[aeiouy]|[\\u0900-\\u097F]|[\\u0C00-\\u0C7F]")
        val good = toks.count { w ->
            val letters = w.count { it.isLetter() }
            (w.all { it.isDigit() || it in ".,-/₹%" }) || // numbers, amounts, dates are fine
                (letters >= w.length * 0.7 && w.length <= 16 && vowel.containsMatchIn(w) && !Regex("[a-z][A-Z][a-z]").containsMatchIn(w))
        }
        return good.toDouble() / toks.size
    }

    /** No markdown, no chatty preamble ("Okay! Let me explain this paper simply…"). */
    private fun clean(t: String): String = t
        .replace(Regex("\\*\\*|__|`|#+ "), "")
        .replace(Regex("(?m)^\\s*[*•-]\\s+"), "")
        .replace(Regex("(?i)^\\s*(okay|ok|sure|alright|here'?s|let me)[^.:!]*[.:!]\\s*"), "")
        .replace(Regex("(?i)\\b(what it is|what matters|what to do( next)?)\\s*:\\s*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun firstSentences(t: String, n: Int): String {
        val parts = Regex("(?<=[.!?।])\\s+").split(t.trim())
        return parts.take(n).joinToString(" ").take(260)
    }

    private suspend fun ocr(bmp: Bitmap): Text? = withContext(Dispatchers.Default) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        try { ocrRaw(bmp) } finally { com.saathi.app.llm.AiMeter.record("CPU", "ML Kit OCR", "read paper", android.os.SystemClock.elapsedRealtime() - t0) }
    }

    private suspend fun ocrRaw(bmp: Bitmap): Text? = withContext(Dispatchers.Default) {
        val img = InputImage.fromBitmap(bmp, 0)
        val latin = run(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), img)
        if (lang == Lang.HI || (latin?.text?.length ?: 0) < 8) run(TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()), img)?.takeIf { (it.text.length) > (latin?.text?.length ?: 0) } ?: latin
        else latin
    }

    private suspend fun run(r: com.google.mlkit.vision.text.TextRecognizer, img: InputImage): Text? = suspendCancellableCoroutine { c ->
        r.process(img).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
    }
}
