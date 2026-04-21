package com.bum.app.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// إعدادات التطبيق المحمية
class AppSettings private constructor(context: Context) {

    companion object {
        private const val PREFS = "bum_settings"
        private const val K_GRACE_ENABLED   = "grace_enabled"
        private const val K_GRACE_SECONDS   = "grace_seconds"
        private const val K_DEFAULT_EXPIRY  = "default_expiry"
        private const val K_WIPE_ON_CLOSE   = "wipe_on_close"
        private const val K_DEFAULT_DESTROY_ON_READ = "default_destroy_on_read"
        private const val K_PASSWORD_HASH   = "password_hash"
        private const val K_PASSWORD_ENABLED = "password_enabled"
        private const val K_VAULT_CATEGORIES = "vault_categories"
        private const val K_THEME_KEY        = "theme_key"
        private const val K_GHOST_CODE_HASH  = "ghost_code_hash"

        // الكلمة السرية الافتراضية
        const val DEFAULT_GHOST_CODE = "0000"

        @Volatile private var INSTANCE: AppSettings? = null
        fun getInstance(ctx: Context): AppSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(ctx.applicationContext).also { INSTANCE = it }
            }

        const val DEFAULT_GRACE_SECONDS = 600
        const val MIN_GRACE_SECONDS     = 15
        const val MAX_GRACE_SECONDS     = 1800
    }

    private val masterKey = MasterKey.Builder(context, "bum_settings_mk")
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, PREFS, masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // فترة السماح قبل القفل
    var isGraceEnabled: Boolean
        get() = prefs.getBoolean(K_GRACE_ENABLED, true)
        set(v) { prefs.edit().putBoolean(K_GRACE_ENABLED, v).apply() }

    var gracePeriodSeconds: Int
        get() = prefs.getInt(K_GRACE_SECONDS, DEFAULT_GRACE_SECONDS)
            .coerceIn(MIN_GRACE_SECONDS, MAX_GRACE_SECONDS)
        set(v) {
            prefs.edit()
                .putInt(K_GRACE_SECONDS, v.coerceIn(MIN_GRACE_SECONDS, MAX_GRACE_SECONDS))
                .apply()
        }

    // سياسة الحفظ
    var defaultExpiryPolicyName: String
        get() = prefs.getString(K_DEFAULT_EXPIRY, "ONE_DAY") ?: "ONE_DAY"
        set(v) { prefs.edit().putString(K_DEFAULT_EXPIRY, v).apply() }

    var defaultDestroyOnRead: Boolean
        get() = prefs.getBoolean(K_DEFAULT_DESTROY_ON_READ, false)
        set(v) { prefs.edit().putBoolean(K_DEFAULT_DESTROY_ON_READ, v).apply() }

    // مسح عند الإغلاق
    var wipeAllOnClose: Boolean
        get() = prefs.getBoolean(K_WIPE_ON_CLOSE, false)
        set(v) { prefs.edit().putBoolean(K_WIPE_ON_CLOSE, v).apply() }

    // كلمة المرور الاحتياطية
    var isPasswordEnabled: Boolean
        get() = prefs.getBoolean(K_PASSWORD_ENABLED, false)
        set(v) { prefs.edit().putBoolean(K_PASSWORD_ENABLED, v).apply() }

    var passwordHash: String
        get() = prefs.getString(K_PASSWORD_HASH, "") ?: ""
        set(v) { prefs.edit().putString(K_PASSWORD_HASH, v).apply() }

    fun setPassword(plain: String) {
        val hash = hashPassword(plain)
        passwordHash = hash
        isPasswordEnabled = true
    }

    fun verifyPassword(plain: String): Boolean {
        if (!isPasswordEnabled) return false
        return hashPassword(plain) == passwordHash
    }

    fun removePassword() {
        isPasswordEnabled = false
        passwordHash = ""
    }

    private fun hashPassword(plain: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(plain.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // فئات مخزن المفاتيح
    fun getCustomVaultCategories(): MutableList<String> {
        val raw = prefs.getString(K_VAULT_CATEGORIES, null) ?: return mutableListOf()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    fun saveCustomVaultCategories(list: List<String>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(K_VAULT_CATEGORIES, arr.toString()).apply()
    }

    fun addCustomVaultCategory(name: String) {
        val list = getCustomVaultCategories()
        if (!list.contains(name)) {
            list.add(name)
            saveCustomVaultCategories(list)
        }
    }

    // الثيم
    var selectedThemeKey: String
        get() = prefs.getString(K_THEME_KEY, "default") ?: "default"
        set(v) { prefs.edit().putString(K_THEME_KEY, v).apply() }

    // هاش الكلمة السرية للملاحظات الشبحية
    val ghostCodeHash: String
        get() {
            val stored = prefs.getString(K_GHOST_CODE_HASH, "") ?: ""
            return if (stored.isNotEmpty()) stored else hashPassword(DEFAULT_GHOST_CODE)
        }

    // تعيين كلمة سرية جديدة
    fun setGhostCode(plain: String) {
        if (plain.isBlank()) return
        prefs.edit().putString(K_GHOST_CODE_HASH, hashPassword(plain)).apply()
    }

    // التحقق من الكلمة السرية
    fun verifyGhostCode(plain: String): Boolean {
        if (plain.isBlank()) return false
        return hashPassword(plain) == ghostCodeHash
    }
}
