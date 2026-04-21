package com.bum.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// تشفير البيانات الحساسة في الذاكرة
class SecureBytes private constructor(
    private val cipherBytes: ByteArray,   // IV(12) + ciphertext + tag
    private val key: SecretKey
) {

    @Volatile
    private var wiped: Boolean = false

    // الوصول المؤقت للبيانات المفكوكة
    @Synchronized
    fun <T> use(block: (ByteArray) -> T): T? {
        if (wiped) return null
        var plain: ByteArray? = null
        try {
            plain = decryptInternal()
            return block(plain)
        } finally {
            plain?.let { scrub(it) }
        }
    }


    @Synchronized
    fun borrow(): ByteArray? = if (wiped) null else decryptInternal()

    @Synchronized
    fun wipe() {
        if (wiped) return
        scrub(cipherBytes)
        // المفتاح نفسه: لا يمكننا فعلياً إزالته من SecretKey لكنه سيموت مع GC
        wiped = true
    }

    fun size(): Int = (cipherBytes.size - IV_LEN - TAG_BYTES).coerceAtLeast(0)

    private fun decryptInternal(): ByteArray {
        val iv = cipherBytes.copyOfRange(0, IV_LEN)
        val ct = cipherBytes.copyOfRange(IV_LEN, cipherBytes.size)
        val cipher = Cipher.getInstance(ALGO).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ct)
    }

    companion object {
        private const val ALGO = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LEN = 12
        private const val TAG_BYTES = GCM_TAG_BITS / 8


        fun wrap(plaintext: ByteArray): SecureBytes {
            val keyGen = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }
            val sk = keyGen.generateKey()
            val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(ALGO).apply {
                init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val ct = cipher.doFinal(plaintext)
            val combined = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ct, 0, combined, iv.size, ct.size)
            scrub(plaintext)
            return SecureBytes(combined, sk)
        }

        fun scrub(bytes: ByteArray) {
            try {
                val rnd = SecureRandom()
                rnd.nextBytes(bytes)
                // تصفير ثاني
                for (i in bytes.indices) bytes[i] = 0
            } catch (_: Throwable) {
                for (i in bytes.indices) bytes[i] = 0
            }
        }
    }
}
