package com.saathi.app.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * FastVLM on the NPU, looking at a photo the person took on purpose. Runs in the ":brain" process ([LocalVision]).
 */
object VisionBrain {
    private fun info() = Brain.sync("") { it.visionInfo() }.split("\u001f").takeIf { it.size == 2 }
    val label: String? get() = info()?.get(0)
    val lastMs: Long get() = info()?.get(1)?.toLongOrNull() ?: 0L

    /** Plain-language explanation of the photo, or null if the vision model isn't available. */
    suspend fun describe(ctx: Context, jpeg: ByteArray, prompt: String): String? {
        Brain.connect(ctx)
        val small = shrink(jpeg)
        return Brain.call(null) { it.vision(small, prompt) }
    }

    fun unload() = Brain.sync(Unit) { it.unloadVision() }

    /** Binder calls carry at most ~1 MB: big photos are scaled down (the model sees ~1 k pixels anyway). */
    private fun shrink(jpeg: ByteArray): ByteArray {
        if (jpeg.size <= 600_000) return jpeg
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
            val b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = s })
            ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it); b.recycle() }.toByteArray()
        }.getOrDefault(jpeg)
    }
}
