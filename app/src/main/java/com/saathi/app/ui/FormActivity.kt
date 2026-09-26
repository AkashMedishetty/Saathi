package com.saathi.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saathi.app.R
import com.saathi.app.forms.FormNodes
import com.saathi.app.forms.FormProfile
import com.saathi.app.forms.FormUi
import com.saathi.app.forms.PaperField
import com.saathi.app.forms.PaperForm
import com.saathi.app.guide.Lang
import com.saathi.app.guide.Prefs
import com.saathi.app.guide.Say
import com.saathi.app.guide.pick
import com.saathi.app.guide.say
import com.saathi.app.service.GlowView
import com.saathi.app.service.SaathiService
import com.saathi.app.service.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * "Help me fill this paper form."
 * Camera → a usable-photo check → offline OCR (Latin + Devanagari) → [PaperForm] → one box at a time: the photo is
 * zoomed to the box, the box glows, the card says what to write and shows the value in big letters to copy.
 * Saathi never writes anything and never shows a value for a secret box. Nothing leaves the phone.
 */
class FormActivity : AppCompatActivity() {
    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, FormActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        private const val MAX_SIDE = 2048 // enough detail for small print on an A4 page; ~16 MB as ARGB
    }

    private lateinit var preview: PreviewView
    private lateinit var aura: GlowView
    private lateinit var photo: PhotoGlow
    private lateinit var controls: View
    private lateinit var sheet: LinearLayout
    private lateinit var progress: TextView
    private lateinit var instruction: TextView
    private lateinit var valueBox: TextView
    private lateinit var actions: LinearLayout

    private var capture: ImageCapture? = null
    private var camera: Camera? = null
    private var torch = false
    private var speaker: Speaker? = null
    private var lang = Lang.EN
    private var profile = FormProfile()
    private var busy = false
    private var promptingProfile = false

    private var page: Bitmap? = null
    private var crop: Bitmap? = null
    private var walk: FormUi.Walk? = null

    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val deva: TextRecognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = Prefs.lang(this)
        speaker = Speaker(this)
        profile = FormProfile.load(this)
        build()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 5)
        if (FormUi.profileIsEmpty(profile)) askForProfile() else talk(FormUi.introSay())
    }

    override fun onResume() {
        super.onResume()
        SaathiService.ownUiOpen = true
        // Back from "My details": use what they just saved.
        val fresh = FormProfile.load(this)
        if (fresh != profile) {
            profile = fresh
            if (promptingProfile && !FormUi.profileIsEmpty(profile)) { promptingProfile = false; toCamera(); talk(FormUi.introSay()) }
        }
    }

    override fun onPause() { SaathiService.ownUiOpen = false; speaker?.stop(); super.onPause() }

    override fun onDestroy() {
        speaker?.shutdown()
        runCatching { latin.close() }; runCatching { deva.close() }
        crop?.recycle(); page?.recycle()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else finish()
    }

    private fun s(en: String, hi: String, te: String) = say(en, hi, te).pick(lang)
    private fun talk(x: Say) = speaker?.say(x.pick(lang), lang)

    // ───────────────────────── UI ─────────────────────────

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        photo = PhotoGlow(this).apply { visibility = View.GONE }
        root.addView(photo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, photoHeight(), Gravity.TOP).apply { topMargin = dp(112) })
        aura = GlowView(this)
        root.addView(aura, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top: close · title · torch
        val top = hbox().apply { setPadding(dp(18), dp(48), dp(18), 0) }
        top.addView(roundIcon(R.drawable.ic_close, s("Close", "बंद करें", "మూసివేయి")) { finish() }, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.add(overline(s("Fill a paper form", "काग़ज़ का फ़ॉर्म भरें", "కాగితపు ఫారం నింపండి"), C.WHITE).apply { gravity = Gravity.CENTER }, weight = 1f)
        val torchBtn = roundIcon(R.drawable.ic_flashlight_off, s("Torch", "टॉर्च", "టార్చ్")) {}
        torchBtn.setOnClickListener {
            torch = !torch; camera?.cameraControl?.enableTorch(torch)
            ((torchBtn as FrameLayout).getChildAt(0) as ImageView).setImageResource(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        }
        top.addView(torchBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom: hint + shutter
        val bottom = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), 0, dp(24), dp(40)) }
        bottom.add(body(s("Fit the whole page in the picture", "पूरा पन्ना तस्वीर में आए", "పేజీ మొత్తం ఫోటోలో రావాలి"), 17f, C.WHITE, bold = true)
            .apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 1f, 0xCC000000.toInt()) })
        val shutter = FrameLayout(this).apply {
            background = rounded(C.WHITE, dpf(46), C.MARIGOLD, dp(6))
            contentDescription = s("Take the photo", "फ़ोटो लें", "ఫోటో తీయండి")
        }
        shutter.addView(ImageView(this).apply { setImageResource(R.drawable.ic_document_scanner); imageTintList = ColorStateList.valueOf(C.PINE_DEEP) },
            FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        shutter.pressable { snap() }
        bottom.addView(shutter, LinearLayout.LayoutParams(dp(92), dp(92)).apply { topMargin = dp(18) })
        controls = bottom
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // The card: progress · instruction · the value to copy · actions
        sheet = vbox(22, 20).apply { background = rounded(C.PAPER, dpf(32)); elevation = dpf(24); visibility = View.GONE; isClickable = true }
        progress = overline("")
        instruction = display("", 24f)
        valueBox = display("", 34f, C.PINE_DEEP).apply {
            background = rounded(C.PAPER_2, dpf(20)); setPadding(dp(18), dp(14), dp(18), dp(14))
            setTextIsSelectable(true); visibility = View.GONE
        }
        actions = vbox()
        sheet.add(progress); sheet.add(instruction, 8); sheet.add(valueBox, 14); sheet.add(actions, 18)
        val sc = ScrollView(this).apply { addView(sheet); isFillViewport = false }
        root.addView(sc, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(10), dp(120), dp(10), dp(12)) })
        setContentView(root)
    }

    /** The photo takes the top half; the card sits under it. */
    private fun photoHeight() = (resources.displayMetrics.heightPixels * 0.42f).toInt()

    private fun roundIcon(icon: Int, label: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        background = rounded(0x66000000, dpf(28)); contentDescription = label
        addView(ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(C.WHITE) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    }.pressable(onClick)

    private fun secondary(label: String, icon: Int? = null, onClick: () -> Unit) = primaryButton(label, icon, bg = C.PAPER_2, fg = C.PINE_DEEP, onClick = onClick)

    private fun showCard(top: String, text: String, value: String?, buttons: List<View>) {
        progress.text = top.uppercase(); progress.visibility = if (top.isBlank()) View.GONE else View.VISIBLE
        instruction.text = text
        valueBox.text = value ?: ""; valueBox.visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
        valueBox.contentDescription = value
        actions.removeAllViews()
        buttons.forEachIndexed { i, v -> actions.add(v, if (i == 0) 0 else 10) }
        controls.visibility = View.GONE
        if (sheet.visibility != View.VISIBLE) {
            sheet.visibility = View.VISIBLE; sheet.alpha = 0f; sheet.translationY = dpf(60)
            sheet.animate().alpha(1f).translationY(0f).setInterpolator(EASE).setDuration(380).start()
        }
    }

    /** Two buttons side by side, equal width. */
    private fun pair(a: View, b: View) = hbox().apply { add(a, weight = 1f); add(b, top = 10, weight = 1f) }

    private fun toCamera() {
        photo.clear(); crop?.recycle(); crop = null; page?.recycle(); page = null; walk = null
        sheet.visibility = View.GONE; controls.visibility = View.VISIBLE; busy = false; aura.setAura(false)
    }

    private fun message(text: Say, retry: Boolean = true) {
        busy = false; aura.setAura(false)
        val buttons = mutableListOf<View>()
        if (retry) buttons += primaryButton(s("Try again", "फिर कोशिश करें", "మళ్ళీ ప్రయత్నించండి"), R.drawable.ic_photo_camera) { toCamera() }
        buttons += secondary(s("Close", "बंद करें", "మూసివేయి"), R.drawable.ic_close) { finish() }
        showCard("", text.pick(lang), null, buttons)
        talk(text)
    }

    private fun askForProfile() {
        promptingProfile = true
        val t = FormUi.profileEmptySay()
        showCard(s("My details", "मेरी जानकारी", "నా వివరాలు"), t.pick(lang), null, listOf(
            primaryButton(s("Add my details", "मेरी जानकारी भरें", "నా వివరాలు నింపండి"), R.drawable.ic_person) { ProfileActivity.start(this) },
            secondary(s("Continue without them", "इनके बिना आगे बढ़ें", "అవి లేకుండా కొనసాగండి")) { promptingProfile = false; toCamera(); talk(FormUi.introSay()) },
        ))
        talk(t)
    }

    // ───────────────────────── camera ─────────────────────────

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val pv = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
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
        showCard("", s("Reading the form…", "फ़ॉर्म पढ़ रहा हूँ…", "ఫారం చదువుతున్నాను…"), null, emptyList())
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = runCatching { upright(image) }.getOrNull()
                image.close()
                if (bmp == null) { message(PaperForm.nothingFound()); return }
                lifecycleScope.launch { understand(bmp) }
            }
            override fun onError(e: ImageCaptureException) { message(PaperForm.nothingFound()) }
        })
    }

    /** Upright (rotation applied to the pixels), so OCR boxes and the drawn glow share one coordinate space. */
    private fun upright(img: ImageProxy): Bitmap {
        var b = img.toBitmap()
        val rot = img.imageInfo.rotationDegrees
        if (rot != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val scale = MAX_SIDE.toFloat() / maxOf(b.width, b.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
    }

    // ───────────────────────── reading ─────────────────────────

    private suspend fun understand(bmp: Bitmap) {
        val frame = withContext(Dispatchers.Default) { frameOf(bmp) }
        if (frame == FormUi.Frame.DARK) { bmp.recycle(); message(FormUi.frameSay(frame)); return }

        val fields = withContext(Dispatchers.Default) {
            val img = InputImage.fromBitmap(bmp, 0)
            // Both scripts: bilingual forms are common, and the recogniser that finds more boxes wins.
            val results = listOfNotNull(recognise(latin, img), recognise(deva, img))
                .map { PaperForm.analyse(FormNodes.ocrLines(it), bmp.width, bmp.height, profile) to it.text.length }
            results.maxWithOrNull(compareBy<Pair<List<PaperField>, Int>> { it.first.size }.thenBy { it.second })?.first.orEmpty()
        }
        if (isFinishing || isDestroyed) { bmp.recycle(); return }
        if (fields.isEmpty()) {
            bmp.recycle()
            // A flat frame (a wall, a covered lens) explains an empty result better than "no boxes found".
            message(if (frame == FormUi.Frame.BLANK) FormUi.frameSay(frame) else PaperForm.nothingFound())
            return
        }
        page = bmp
        aura.setAura(false)
        busy = false
        walk = FormUi.Walk(fields)
        showField(intro = FormUi.foundSay(fields.size))
    }

    private fun frameOf(bmp: Bitmap): FormUi.Frame {
        val w = 160; val h = (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()
        return FormUi.frame(FormUi.luma(px))
    }

    private suspend fun recognise(r: TextRecognizer, img: InputImage): Text? = suspendCancellableCoroutine { c ->
        r.process(img).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
    }

    // ───────────────────────── one field at a time ─────────────────────────

    private fun showField(intro: Say? = null) {
        val w = walk ?: return
        val f = w.current ?: return
        val bmp = page ?: return
        // Zoom to the field (keeping most of the page in view), then glow its write box.
        val vw = resources.displayMetrics.widthPixels; val vh = photoHeight()
        val vp = FormUi.viewport(f.labelBox, f.writeBox, bmp.width, bmp.height, vw, vh)
        val old = crop
        crop = if (vp.l == 0 && vp.t == 0 && vp.r == bmp.width && vp.b == bmp.height) null
            else Bitmap.createBitmap(bmp, vp.l, vp.t, vp.w, vp.h)
        val shown = crop ?: bmp
        val b = f.writeBox
        photo.show(shown, listOf(Rect(b.l - vp.l, b.t - vp.t, b.r - vp.l, b.b - vp.t)))
        if (old != null && old !== crop) old.recycle()

        val next = if (w.isLast) primaryButton(s("Done", "हो गया", "అయింది"), R.drawable.ic_check) { finishWalk() }
            else primaryButton(s("Next", "अगला", "తర్వాత"), R.drawable.ic_chevron_right) { walk = w.next(); showField() }
        val back = secondary(s("Back", "पीछे", "వెనక్కి"), R.drawable.ic_arrow_back) { walk = w.back(); showField() }.apply {
            alpha = if (w.isFirst) 0.4f else 1f; isEnabled = !w.isFirst
        }
        showCard(w.progress(lang), f.say.pick(lang), w.bigValue, listOf(
            pair(back, next),
            pair(secondary(s("Read again", "फिर से सुनाओ", "మళ్ళీ చదువు"), R.drawable.ic_replay) { talk(f.say) },
                secondary(s("Close", "बंद करें", "మూసివేయి"), R.drawable.ic_close) { finish() }),
        ))
        val spoken = listOfNotNull(intro?.pick(lang), f.say.pick(lang)).joinToString(" ")
        speaker?.say(spoken, lang)
    }

    private fun finishWalk() {
        val t = FormUi.lastFieldSay()
        photo.clear()
        showCard("", t.pick(lang), null, listOf(
            primaryButton(s("Done", "हो गया", "అయింది"), R.drawable.ic_check) { finish() },
            secondary(s("Start again", "फिर से शुरू करें", "మళ్ళీ మొదలు")) { walk = walk?.copy(index = 0); showField() },
            secondary(s("New photo", "नई फ़ोटो", "కొత్త ఫోటో"), R.drawable.ic_photo_camera) { toCamera() },
        ))
        talk(t)
    }
}
