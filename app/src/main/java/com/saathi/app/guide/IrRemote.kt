package com.saathi.app.guide

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * The iQOO 15 has an IR blaster: Saathi can be a big-button TV remote you can also talk to.
 * Samsung and LG TVs (the most common in Indian homes) with their standard 32-bit codes.
 */
object IrRemote {
    enum class Key { POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, SOURCE, MENU,
        N0, N1, N2, N3, N4, N5, N6, N7, N8, N9 }
    enum class Brand(val label: String) { SAMSUNG("Samsung"), LG("LG") }

    private val codes = mapOf(
        Brand.SAMSUNG to mapOf(Key.POWER to 0xE0E040BFL, Key.VOL_UP to 0xE0E0E01FL, Key.VOL_DOWN to 0xE0E0D02FL,
            Key.MUTE to 0xE0E0F00FL, Key.CH_UP to 0xE0E048B7L, Key.CH_DOWN to 0xE0E008F7L,
            Key.UP to 0xE0E006F9L, Key.DOWN to 0xE0E08679L, Key.LEFT to 0xE0E0A659L, Key.RIGHT to 0xE0E046B9L, Key.OK to 0xE0E016E9L,
            Key.BACK to 0xE0E01AE5L, Key.HOME to 0xE0E09E61L, Key.SOURCE to 0xE0E0807FL, Key.MENU to 0xE0E058A7L,
            Key.N1 to 0xE0E020DFL, Key.N2 to 0xE0E0A05FL, Key.N3 to 0xE0E0609FL, Key.N4 to 0xE0E010EFL, Key.N5 to 0xE0E0906FL,
            Key.N6 to 0xE0E050AFL, Key.N7 to 0xE0E030CFL, Key.N8 to 0xE0E0B04FL, Key.N9 to 0xE0E0708FL, Key.N0 to 0xE0E08877L),
        Brand.LG to mapOf(Key.POWER to 0x20DF10EFL, Key.VOL_UP to 0x20DF40BFL, Key.VOL_DOWN to 0x20DFC03FL,
            Key.MUTE to 0x20DF906FL, Key.CH_UP to 0x20DF00FFL, Key.CH_DOWN to 0x20DF807FL,
            Key.UP to 0x20DF02FDL, Key.DOWN to 0x20DF827DL, Key.LEFT to 0x20DFE01FL, Key.RIGHT to 0x20DF609FL, Key.OK to 0x20DF22DDL,
            Key.BACK to 0x20DF14EBL, Key.HOME to 0x20DF3EC1L, Key.SOURCE to 0x20DFD02FL, Key.MENU to 0x20DFC23DL,
            Key.N1 to 0x20DF8877L, Key.N2 to 0x20DF48B7L, Key.N3 to 0x20DFC837L, Key.N4 to 0x20DF28D7L, Key.N5 to 0x20DFA857L,
            Key.N6 to 0x20DF6897L, Key.N7 to 0x20DFE817L, Key.N8 to 0x20DF18E7L, Key.N9 to 0x20DF9867L, Key.N0 to 0x20DF08F7L),
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

    /** "channel 25" → the digit keys to press, or null. */
    fun digitsFor(goal: String): List<Key>? {
        val m = Regex("(?i)(channel|चैनल|ఛానెల్)\\s*(number\\s*)?(\\d{1,4})").find(goal) ?: return null
        return m.groupValues[3].map { Key.valueOf("N$it") }
    }

    fun sendDigits(ctx: Context, keys: List<Key>): Boolean {
        var ok = true
        keys.forEach { ok = send(ctx, it) && ok; Thread.sleep(350) }
        return ok
    }

    /** "TV volume up", "टीवी बंद करो", "టీవీ సౌండ్ తగ్గించు", "TV go down", "TV ok" → key, or null. */
    fun keyFor(goal: String): Key? {
        val g = goal.lowercase()
        return when {
            Regex("mute|आवाज़ बंद|चुप|మ్యూట్").containsMatchIn(g) -> Key.MUTE
            Regex("volume up|louder|sound up|आवाज़ बढ़ा|आवाज बढ़ा|సౌండ్ పెంచు|పెంచు").containsMatchIn(g) -> Key.VOL_UP
            Regex("volume down|softer|quieter|sound down|आवाज़ कम|आवाज कम|సౌండ్ తగ్గించు|తగ్గించు").containsMatchIn(g) -> Key.VOL_DOWN
            Regex("channel up|next channel|अगला चैनल|తర్వాతి ఛానెల్").containsMatchIn(g) -> Key.CH_UP
            Regex("channel down|previous channel|पिछला चैनल|ముందరి ఛానెల్").containsMatchIn(g) -> Key.CH_DOWN
            Regex("\\b(go )?up\\b|ऊपर|పైకి").containsMatchIn(g) -> Key.UP
            Regex("\\b(go )?down\\b|नीचे|కిందకి").containsMatchIn(g) -> Key.DOWN
            Regex("\\bleft\\b|बाएँ|बाएं|ఎడమ").containsMatchIn(g) -> Key.LEFT
            Regex("\\bright\\b|दाएँ|दाएं|కుడి").containsMatchIn(g) -> Key.RIGHT
            Regex("\\b(ok|okay|select|enter)\\b|ओके|ఓకే").containsMatchIn(g) -> Key.OK
            Regex("\\bback\\b|वापस|వెనక్కి").containsMatchIn(g) -> Key.BACK
            Regex("\\bhome\\b|होम|హోమ్").containsMatchIn(g) -> Key.HOME
            Regex("source|input|hdmi|set ?top|सेट टॉप|సెట్ టాప్").containsMatchIn(g) -> Key.SOURCE
            Regex("\\bmenu\\b|मेनू|మెనూ").containsMatchIn(g) -> Key.MENU
            Regex("\\b(on|off)\\b|power|चालू|बंद|ఆన్|ఆఫ్").containsMatchIn(g) -> Key.POWER
            else -> null
        }
    }
}
