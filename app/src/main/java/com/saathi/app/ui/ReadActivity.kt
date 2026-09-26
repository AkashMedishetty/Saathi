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
class ReadActivity : AppCompatActivity() {
    companion object { const val EXTRA_MODE = "mode"; const val MODE_READ = "read"; const val MODE_MEDICINE = "medicine" }

    private lateinit var preview: PreviewView
    private lateinit var aura: GlowView
    private lateinit var sheet: LinearLayout
    private lateinit var sheetText: TextView
    private lateinit var sheetSub: TextView
    private lateinit var sheetActions: LinearLayout
    private lateinit var controls: View
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
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 4)
        val hello = if (mode == MODE_MEDICINE) s("Hold the medicine strip flat, name side up, and tap the big button.",
            "दवा का पत्ता सीधा पकड़िए, नाम ऊपर, और बड़ा बटन दबाइए।", "మందుల స్ట్రిప్‌ను పేరు పైకి ఉండేలా పట్టుకుని పెద్ద బటన్ నొక్కండి.")
        else s("Point at the paper and tap the big button. I'll read it to you.",
            "काग़ज़ की ओर फ़ोन कीजिए और बड़ा बटन दबाइए। मैं पढ़कर सुनाऊँगा।", "కాగితం వైపు ఫోన్ పెట్టి పెద్ద బటన్ నొక్కండి. నేను చదివి వినిపిస్తాను.")
        speaker?.say(hello, lang)
    }

    override fun onResume() { super.onResume(); SaathiService.ownUiOpen = true }
    override fun onPause() { SaathiService.ownUiOpen = false; super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }

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
        aura = GlowView(this)
        root.addView(aura, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top: close · title · torch
        val top = hbox().apply { setPadding(dp(18), dp(52), dp(18), 0) }
        top.addView(roundIcon(R.drawable.ic_close, s("Close", "बंद करें", "మూసివేయి")) { finish() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.add(overline(if (mode == MODE_MEDICINE) s("Medicine strip", "दवा का पत्ता", "మందుల స్ట్రిప్") else s("Read this for me", "मेरे लिए पढ़ो", "నా కోసం చదువు"), C.WHITE).apply {
            gravity = Gravity.CENTER }, weight = 1f)
        val torchBtn = roundIcon(R.drawable.ic_flashlight_off, s("Torch", "टॉर्च", "టార్చ్")) {}
        torchBtn.setOnClickListener {
            torch = !torch; camera?.cameraControl?.enableTorch(torch)
            ((torchBtn as FrameLayout).getChildAt(0) as ImageView).setImageResource(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        }
        top.addView(torchBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

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
                lifecycleScope.launch { understand(bmp) }
            }
            override fun onError(e: ImageCaptureException) { reset() }
        })
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

        if (mode == MODE_MEDICINE) { medicine(lines.map { it.text to (it.boundingBox?.height() ?: 0) }); return }

        // 1) FastVLM on the NPU looks at the photo. 2) else Gemma explains the OCR text. 3) else read it out.
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val vlm = VisionBrain.describe(applicationContext, jpeg,
            "You help an elderly person. In at most 2 short, simple sentences, say what this paper is and what matters in it. " +
                "If it is a bill, say the amount and the due date. If it is a medicine, say its name. " +
                "If it is a letter, say who it is from and what they want. If it looks like a scam, say so clearly.")
        val llm = if (vlm == null && plain.length > 12) {
            if (!LlmManager.isReady) LlmManager.load(applicationContext)
            LlmManager.generate("You explain papers to elderly people in very simple English. 2 or 3 short sentences. " +
                "Bills: amount and due date. Letters: who from and what they want. Scams: say so.", "The paper says:\n${plain.take(1500)}")
        } else null
        val explained = (vlm ?: llm)?.let { firstSentences(it, 2) }
        aura.setAura(false)
        busy = false
        val engine = when { vlm != null -> "FastVLM · Snapdragon NPU · ${VisionBrain.lastMs} ms"; llm != null -> LlmManager.label ?: ""; else -> "" }

        if (explained == null && plain.isBlank()) {
            val t = s("I couldn't see any words. Hold it closer, with more light, and try again.",
                "कोई अक्षर नहीं दिखे। पास लाकर, रोशनी में फिर कोशिश कीजिए।", "అక్షరాలు కనిపించలేదు. దగ్గరగా, వెలుతురులో మళ్ళీ ప్రయత్నించండి.")
            showSheet(t, "", listOf(again(), close())); speaker?.say(t, lang); return
        }
        // Speech: English explanation when EN; for HI/TE we read the words themselves (small models write broken Indic, trap #5).
        val shown = if (lang == Lang.EN) explained ?: plain.lines().take(6).joinToString("\n")
            else s("", "इस पर लिखा है:", "దీని మీద రాసి ఉంది:") + "\n" + plain.lines().take(6).joinToString("\n")
        val preview = plain.lines().filter { it.isNotBlank() }.take(4).joinToString(" · ")
        val sub = if (lang == Lang.EN) (if (explained != null) preview else "") else (explained ?: "")
        showSheet(shown, listOf(sub, engine).filter { it.isNotBlank() }.joinToString("\n\n"),
            listOf(primaryButton(s("Read it again", "फिर से पढ़ो", "మళ్ళీ చదువు"), R.drawable.ic_volume_up) { speaker?.say(shown, lang) }, again(), close()))
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
    }.pressable { finish() }

    /** Small models ramble: keep the first [n] sentences. */
    private fun firstSentences(t: String, n: Int): String {
        val parts = Regex("(?<=[.!?।])\\s+").split(t.trim())
        return parts.take(n).joinToString(" ").take(260)
    }

    private suspend fun ocr(bmp: Bitmap): Text? = withContext(Dispatchers.Default) {
        val img = InputImage.fromBitmap(bmp, 0)
        val latin = run(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), img)
        if (lang == Lang.HI || (latin?.text?.length ?: 0) < 8) run(TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()), img)?.takeIf { (it.text.length) > (latin?.text?.length ?: 0) } ?: latin
        else latin
    }

    private suspend fun run(r: com.google.mlkit.vision.text.TextRecognizer, img: InputImage): Text? = suspendCancellableCoroutine { c ->
        r.process(img).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
    }
}
