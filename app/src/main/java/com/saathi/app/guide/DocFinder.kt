package com.saathi.app.guide

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * "Where is my Aadhaar card?" / "send my Aadhaar to my son": the phone's own pictures, read ON the phone (ML Kit OCR,
 * the same reader as the camera), so a photo of a document can be found by what it is. Only "picture id → kind" is
 * kept (files/docs.json); the words read are never stored. Google Photos then shows it; WhatsApp's own picker sends it.
 */
object DocFinder {
    enum class Kind(val rx: Regex, val en: String, val hi: String, val te: String) {
        AADHAAR(Regex("(?i)aadhaa?r|आधार|ఆధార్|unique identification|\\b(\\d{4}|x{4}) (\\d{4}|x{4}) \\d{4}\\b"), "Aadhaar card", "आधार कार्ड", "ఆధార్ కార్డు"),
        PAN(Regex("(?i)income tax department|permanent account number|\\b[A-Z]{5}\\d{4}[A-Z]\\b"), "PAN card", "पैन कार्ड", "పాన్ కార్డు"),
        VOTER(Regex("(?i)election commission|elector'?s? photo|voter"), "voter ID", "वोटर आईडी", "ఓటర్ ఐడీ"),
        LICENCE(Regex("(?i)driving licen[cs]e|transport department"), "driving licence", "ड्राइविंग लाइसेंस", "డ్రైవింగ్ లైసెన్స్"),
        PRESCRIPTION(Regex("(?i)\\brx\\b|prescription|\\d+\\s?mg\\b|tablet"), "doctor's prescription", "डॉक्टर की पर्ची", "డాక్టర్ చీటీ"),
        BILL(Regex("(?i)electricity|amount due|due date|bill"), "bill", "बिल", "బిల్లు");

        fun name(l: Lang) = when (l) { Lang.EN -> en; Lang.HI -> hi; Lang.TE -> te }
    }

    /** What they asked for, in their words: "aadhar", "आधार", "పాన్ కార్డు", "my prescription", "electricity bill". */
    private val ASKED = listOf(
        Kind.AADHAAR to Regex("(?i)aadhaa?r|adhar|आधार|ఆధార్"),
        Kind.PAN to Regex("(?i)\\bpan( card)?\\b|पैन|పాన్"),
        Kind.VOTER to Regex("(?i)voter|वोटर|ఓటర్"),
        Kind.LICENCE to Regex("(?i)licen[cs]e|लाइसेंस|లైసెన్స్"),
        Kind.PRESCRIPTION to Regex("(?i)prescription|doctor'?s? (paper|note|slip)|medicine (paper|slip|list)|पर्ची|దవాఖానా చీటీ|మందుల చీటీ|డాక్టర్ చీటీ"),
        Kind.BILL to Regex("(?i)electricity bill|current bill|power bill|\\bbill\\b|बिजली (का )?बिल|బిల్లు|కరెంట్ బిల్"),
    )
    private val DOC_WORDS = Regex("(?i)card|document|photo|picture|paper|bill|prescription|कार्ड|कागज़|फोटो|बिल|పత్రం|కార్డు|ఫోటో|బిల్లు")

    data class Ask(val kind: Kind, val send: Boolean)

    /** A request about one of their documents, or null. */
    fun ask(g: String): Ask? {
        val kind = ASKED.firstOrNull { it.second.containsMatchIn(g) }?.first ?: return null
        // "pan" / "bill" alone are common words: need a document word or a where/show/send verb with them.
        if ((kind == Kind.PAN || kind == Kind.BILL) && !DOC_WORDS.containsMatchIn(g)) return null
        val send = Regex("(?i)\\b(send|share|forward|whatsapp)\\b|भेज|शेयर|పంపు|పంపించు|షేర్").containsMatchIn(g)
        val show = Regex("(?i)\\b(where|show|find|open|see|look)\\b|कहाँ|कहां|दिखाओ|ढूंढो|ढूँढो|ఎక్కడ|చూపించు|వెతుకు").containsMatchIn(g)
        return if (send || show) Ask(kind, send) else null
    }

    private val lock = Any()
    private var cache: MutableMap<Long, String>? = null
    private fun file(c: Context) = File(c.filesDir, "docs.json")

    private fun load(c: Context): MutableMap<Long, String> = synchronized(lock) {
        cache ?: runCatching {
            val j = JSONObject(file(c).readText()); j.keys().asSequence().associate { it.toLong() to j.getString(it) }.toMutableMap()
        }.getOrElse { mutableMapOf() }.also { cache = it }
    }

    private fun save(c: Context) = synchronized(lock) {
        runCatching { file(c).writeText(JSONObject(cache.orEmpty().mapKeys { it.key.toString() }).toString()) }
    }

    /** Read the newest [limit] pictures not read yet. Returns how many were read. */
    suspend fun index(c: Context, limit: Int = 300): Int = withContext(Dispatchers.Default) {
        val seen = load(c)
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val ids = runCatching {
            c.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                buildList { while (cur.moveToNext() && size < limit) add(cur.getLong(0)) }
            }
        }.getOrNull().orEmpty()
        val todo = ids.filter { it !in seen }
        if (todo.isEmpty()) return@withContext 0
        val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var n = 0
        for (id in todo) {
            val uri = ContentUris.withAppendedId(col, id)
            val text = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var s = 1; while (maxOf(opts.outWidth, opts.outHeight) / s > 1600) s *= 2
                val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
                bmp?.let { b -> suspendCancellableCoroutine<String?> { k ->
                    reader.process(InputImage.fromBitmap(b, 0)).addOnSuccessListener { k.resume(it.text) }.addOnFailureListener { k.resume(null) } } }
            }.getOrNull().orEmpty()
            val kind = Kind.entries.firstOrNull { it.rx.containsMatchIn(text) }?.name ?: ""
            synchronized(lock) { seen[id] = kind }
            n++
        }
        save(c)
        com.saathi.app.llm.AiMeter.record("CPU", "ML Kit OCR", "read $n photos", android.os.SystemClock.elapsedRealtime() - t0)
        com.saathi.app.DebugLog.i("docs", "read $n pictures; documents: ${seen.values.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()}")
        n
    }

    /** The newest picture of this kind, or null. */
    fun find(c: Context, kind: Kind): Uri? {
        val seen = load(c)
        val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return runCatching {
            c.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                while (cur.moveToNext()) { val id = cur.getLong(0); if (seen[id] == kind.name) return@use ContentUris.withAppendedId(col, id) }
                null
            }
        }.getOrNull()
    }
}
