package com.saathi.app.guide

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * The iQOO 15 has an IR blaster: Saathi can be a big-button TV remote you can also talk to.
 * Samsung and LG TVs (the most common in Indian homes) with their standard 32-bit codes.
 */
object IrRemote {
    enum class Key { POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN }
    enum class Brand(val label: String) { SAMSUNG("Samsung"), LG("LG") }

    private val codes = mapOf(
        Brand.SAMSUNG to mapOf(Key.POWER to 0xE0E040BFL, Key.VOL_UP to 0xE0E0E01FL, Key.VOL_DOWN to 0xE0E0D02FL,
            Key.MUTE to 0xE0E0F00FL, Key.CH_UP to 0xE0E048B7L, Key.CH_DOWN to 0xE0E008F7L),
        Brand.LG to mapOf(Key.POWER to 0x20DF10EFL, Key.VOL_UP to 0x20DF40BFL, Key.VOL_DOWN to 0x20DFC03FL,
            Key.MUTE to 0x20DF906FL, Key.CH_UP to 0x20DF00FFL, Key.CH_DOWN to 0x20DF807FL),
    )

    fun available(ctx: Context) = ctx.getSystemService(ConsumerIrManager::class.java)?.hasIrEmitter() == true

    fun brand(ctx: Context): Brand = runCatching {
        Brand.valueOf(ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE).getString("tv_brand", "SAMSUNG")!!)
    }.getOrDefault(Brand.SAMSUNG)

    fun setBrand(ctx: Context, b: Brand) = ctx.getSharedPreferences("saathi", Context.MODE_PRIVATE).edit().putString("tv_brand", b.name).apply()

    fun send(ctx: Context, key: Key, brand: Brand = brand(ctx)): Boolean {
        val ir = ctx.getSystemService(ConsumerIrManager::class.java) ?: return false
        if (!ir.hasIrEmitter()) return false
        val code = codes[brand]?.get(key) ?: return false
        return runCatching { ir.transmit(38_000, pattern(code, samsung = brand == Brand.SAMSUNG)) }.isSuccess
    }

    /** Pure: NEC / Samsung32 pulse pattern in µs (MSB first, like the common IRremote notation). */
    fun pattern(code: Long, samsung: Boolean): IntArray {
        val p = ArrayList<Int>(68)
        if (samsung) { p += 4500; p += 4500 } else { p += 9000; p += 4500 }
        for (i in 31 downTo 0) {
            p += 560
            p += if ((code shr i) and 1L == 1L) 1690 else 560
        }
        p += 560
        return p.toIntArray()
    }

    /** "TV volume up", "टीवी बंद करो", "టీవీ సౌండ్ తగ్గించు" → key, or null. */
    fun keyFor(goal: String): Key? {
        val g = goal.lowercase()
        return when {
            Regex("mute|आवाज़ बंद|चुप|మ్యూట్").containsMatchIn(g) -> Key.MUTE
            Regex("volume up|louder|sound up|आवाज़ बढ़ा|आवाज बढ़ा|సౌండ్ పెంచు|పెంచు").containsMatchIn(g) -> Key.VOL_UP
            Regex("volume down|softer|quieter|sound down|आवाज़ कम|आवाज कम|సౌండ్ తగ్గించు|తగ్గించు").containsMatchIn(g) -> Key.VOL_DOWN
            Regex("channel up|next channel|अगला चैनल|చానెల్ మార్చు|తర్వాతి ఛానెల్").containsMatchIn(g) -> Key.CH_UP
            Regex("channel down|previous channel|पिछला चैनल|ముందరి ఛానెల్").containsMatchIn(g) -> Key.CH_DOWN
            Regex("\\b(on|off)\\b|power|चालू|बंद|ఆన్|ఆఫ్").containsMatchIn(g) -> Key.POWER
            else -> null
        }
    }
}
