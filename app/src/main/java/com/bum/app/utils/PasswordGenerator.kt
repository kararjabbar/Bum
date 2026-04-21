package com.bum.app.utils

import java.security.SecureRandom

// مولّد كلمات مرور يعتمد على SecureRandom
object PasswordGenerator {

    private const val UPPER  = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER  = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.<>?/~"
    // أحرف قد تُسبّب لبساً بصرياً
    private const val AMBIGUOUS = "0Oo1lI"

    data class Options(
        val length: Int = 20,
        val useUpper: Boolean = true,
        val useLower: Boolean = true,
        val useDigits: Boolean = true,
        val useSymbols: Boolean = true,
        val avoidAmbiguous: Boolean = false
    )

    fun generate(options: Options): String {
        val rng = SecureRandom()
        val pools = mutableListOf<String>()
        if (options.useUpper)   pools += filter(UPPER,   options.avoidAmbiguous)
        if (options.useLower)   pools += filter(LOWER,   options.avoidAmbiguous)
        if (options.useDigits)  pools += filter(DIGITS,  options.avoidAmbiguous)
        if (options.useSymbols) pools += filter(SYMBOLS, options.avoidAmbiguous)

        if (pools.isEmpty()) {
            // إذا لم تُختر أي فئة، نستخدم الأحرف الصغيرة كحد أدنى
            pools += LOWER
        }
        val all = pools.joinToString("")
        val len = options.length.coerceIn(8, 128)

        // حرف واحد على الأقل من كل فئة مختارة
        val result = StringBuilder()
        for (pool in pools) result.append(pool[rng.nextInt(pool.length)])
        // باقي الطول من الفئات المجمّعة
        while (result.length < len) result.append(all[rng.nextInt(all.length)])

        // خلط عشوائي
        val chars = result.toString().toCharArray()
        for (i in chars.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }

    private fun filter(src: String, avoidAmbiguous: Boolean): String =
        if (!avoidAmbiguous) src else src.filter { it !in AMBIGUOUS }

    // قوة كلمة المرور بالبتات
    fun entropyBits(password: String): Double {
        if (password.isEmpty()) return 0.0
        var poolSize = 0
        if (password.any { it.isUpperCase() }) poolSize += UPPER.length
        if (password.any { it.isLowerCase() }) poolSize += LOWER.length
        if (password.any { it.isDigit() })     poolSize += DIGITS.length
        if (password.any { it in SYMBOLS })    poolSize += SYMBOLS.length
        if (poolSize == 0) poolSize = 26
        return password.length * (Math.log(poolSize.toDouble()) / Math.log(2.0))
    }

    fun strengthLabel(bits: Double): String = when {
        bits < 28  -> "ضعيفة جداً"
        bits < 40  -> "ضعيفة"
        bits < 60  -> "معقولة"
        bits < 80  -> "قوية"
        bits < 110 -> "قوية جداً"
        else       -> "ممتازة"
    }
}
