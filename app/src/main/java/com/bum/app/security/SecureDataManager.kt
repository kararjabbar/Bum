package com.bum.app.security

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bum.app.BuildConfig
import com.bum.app.models.Attachment
import com.bum.app.models.Note
import com.bum.app.models.VaultKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// إدارة التشفير والبيانات
class SecureDataManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecureDataManager"

        private const val PREF_FILE      = "bum_secure_prefs_v2"
        private const val KEY_ALIAS      = "bum_master_key_v2"

        private const val K_NOTES_JSON   = "notes_json"
        private const val K_VAULT_JSON   = "vault_json"
        private const val K_WIPE_COUNT   = "wipe_count"

        private const val K_PERSISTENT_AES = "persistent_aes_key"

        private const val AES_ALGO       = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS   = 128
        private const val IV_LEN         = 12

        @Volatile private var INSTANCE: SecureDataManager? = null
        fun getInstance(ctx: Context): SecureDataManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureDataManager(ctx.applicationContext).also { INSTANCE = it }
            }
    }



    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context, PREF_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }


    private val persistentKey: SecretKey by lazy { loadOrCreatePersistentKey() }

    private fun loadOrCreatePersistentKey(): SecretKey {
        val existing = prefs.getString(K_PERSISTENT_AES, null)
        if (existing != null) {
            val raw = Base64.decode(existing, Base64.NO_WRAP)
            return SecretKeySpec(raw, "AES")
        }
        val gen = KeyGenerator.getInstance("AES").apply { init(256, SecureRandom()) }
        val key = gen.generateKey()
        prefs.edit()
            .putString(K_PERSISTENT_AES, Base64.encodeToString(key.encoded, Base64.NO_WRAP))
            .apply()
        return key
    }



    fun encryptText(plain: String): String {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_ALGO).apply {
            init(Cipher.ENCRYPT_MODE, persistentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decryptText(encoded: String): String? = try {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = data.sliceArray(0 until IV_LEN)
        val ct = data.sliceArray(IV_LEN until data.size)
        val cipher = Cipher.getInstance(AES_ALGO).apply {
            init(Cipher.DECRYPT_MODE, persistentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "decryptText failed", e)
        null
    }


    fun encryptBytesToFile(plain: ByteArray, destFile: File) {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_ALGO).apply {
            init(Cipher.ENCRYPT_MODE, persistentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plain)
        destFile.parentFile?.mkdirs()
        destFile.outputStream().use { it.write(iv); it.write(ct) }
    }


    fun decryptFileToBytes(file: File): ByteArray? = try {
        val bytes = file.readBytes()
        val iv = bytes.sliceArray(0 until IV_LEN)
        val ct = bytes.sliceArray(IV_LEN until bytes.size)
        val cipher = Cipher.getInstance(AES_ALGO).apply {
            init(Cipher.DECRYPT_MODE, persistentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        cipher.doFinal(ct)
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "decryptFileToBytes failed", e)
        null
    }



    @Synchronized
    fun getAllNotes(): MutableList<Note> {
        val enc = prefs.getString(K_NOTES_JSON, null) ?: return mutableListOf()
        val dec = decryptText(enc) ?: return mutableListOf()
        return try {
            val arr = JSONArray(dec)
            val list = mutableListOf<Note>()
            for (i in 0 until arr.length()) list += Note.fromJson(arr.getJSONObject(i))
            list
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized
    private fun saveAllNotes(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_NOTES_JSON, encryptText(arr.toString())).apply()
    }


    fun addTextNote(
        text: String,
        policy: Note.ExpiryPolicy,
        destroyOnRead: Boolean,
        attachments: List<Attachment> = emptyList(),
        title: String = "",
        isSecretCompartment: Boolean = false,
        isGhost: Boolean = false
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = Note.newId(),
            type = Note.NoteType.TEXT,
            content = encryptText(text),  // مشفّر داخل الـ JSON
            createdAt = now,
            expiryPolicy = policy,
            expiresAt = if (policy.durationMs > 0) now + policy.durationMs else 0L,
            destroyOnRead = destroyOnRead,
            attachments = attachments,
            title = if (title.isNotBlank()) encryptText(title) else "",
            isSecretCompartment = isSecretCompartment,
            isGhost = isGhost
        )
        val notes = getAllNotes().also { it.add(0, note) }
        saveAllNotes(notes)
        return note
    }


    fun decryptNoteTitle(note: Note): String {
        if (note.title.isBlank()) return ""
        return decryptText(note.title) ?: ""
    }


    fun getRegularNotes(): MutableList<Note> =
        getAllNotes()
            .filter { !it.isSecretCompartment && !it.isGhost }
            .toMutableList()


    fun getSecretCompartmentNotes(): MutableList<Note> =
        getAllNotes()
            .filter { it.isSecretCompartment && !it.isGhost }
            .toMutableList()


    fun revealGhostNotesIfCodeMatches(
        typedCode: String,
        expectedHash: String
    ): List<Note> {
        if (typedCode.isBlank() || expectedHash.isBlank()) return emptyList()
        val actualHash = sha256Hex(typedCode)
        if (actualHash != expectedHash) return emptyList()
        return getAllNotes().filter { it.isGhost }
    }


    fun searchRegularNotes(query: String): List<Note> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return getRegularNotes()
        return getRegularNotes().filter { n ->
            val plain = decryptNoteText(n) ?: ""
            val imgNote = n.imageNote
            val title = decryptNoteTitle(n)
            plain.lowercase().contains(q) ||
                imgNote.lowercase().contains(q) ||
                title.lowercase().contains(q)
        }
    }

    private fun sha256Hex(plain: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(plain.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }




    fun encryptAttachmentBytes(
        plain: ByteArray,
        filename: String,
        mimeType: String
    ): Attachment {
        val encDir = File(context.filesDir, "enc_attachments").apply { mkdirs() }
        val attId = Attachment.newId()
        val encFile = File(encDir, "$attId.enc")
        encryptBytesToFile(plain, encFile)
        return Attachment(
            id = attId,
            filename = filename,
            mimeType = mimeType,
            encryptedPath = encFile.absolutePath,
            sizeBytes = plain.size.toLong()
        )
    }


    fun decryptAttachmentToCache(att: Attachment): File? {
        val bytes = decryptFileToBytes(File(att.encryptedPath)) ?: return null
        val outDir = File(context.cacheDir, "decrypted").apply { mkdirs() }
        val safeName = att.filename.replace('/', '_').replace('\\', '_')
        val outFile = File(outDir, "${att.id}_$safeName")
        outFile.writeBytes(bytes)
        return outFile
    }


    fun wipeDecryptedCache() {
        try {
            val dir = File(context.cacheDir, "decrypted")
            if (dir.exists()) dir.listFiles()?.forEach { secureDeleteFile(it) }
        } catch (_: Exception) { }
    }


    fun addImageNote(
        tempPlainFile: File,
        policy: Note.ExpiryPolicy,
        destroyOnRead: Boolean,
        imageNote: String = "",
        title: String = "",
        isSecretCompartment: Boolean = false,
        isGhost: Boolean = false
    ): Note {
        val now = System.currentTimeMillis()
        val encDir = File(context.filesDir, "enc_images").apply { mkdirs() }
        val encFile = File(encDir, "${Note.newId()}.enc")
        encryptBytesToFile(tempPlainFile.readBytes(), encFile)
        // تدمير الملف الخام
        secureDeleteFile(tempPlainFile)

        val note = Note(
            id = Note.newId(),
            type = Note.NoteType.IMAGE,
            content = encFile.absolutePath,
            createdAt = now,
            expiryPolicy = policy,
            expiresAt = if (policy.durationMs > 0) now + policy.durationMs else 0L,
            destroyOnRead = destroyOnRead,
            imageNote = imageNote,
            title = if (title.isNotBlank()) encryptText(title) else "",
            isSecretCompartment = isSecretCompartment,
            isGhost = isGhost
        )
        val notes = getAllNotes().also { it.add(0, note) }
        saveAllNotes(notes)
        return note
    }




    fun decryptAttachmentToBytes(att: Attachment): ByteArray? =
        decryptFileToBytes(File(att.encryptedPath))


    fun exportAttachmentBytes(att: Attachment): ByteArray? =
        decryptAttachmentToBytes(att)


    fun decryptNoteText(note: Note): String? =
        if (note.type == Note.NoteType.TEXT) decryptText(note.content) else null


    fun decryptNoteImage(note: Note): ByteArray? =
        if (note.type == Note.NoteType.IMAGE) decryptFileToBytes(File(note.content)) else null


    fun markAsRead(noteId: String) {
        val list = getAllNotes()
        val idx = list.indexOfFirst { it.id == noteId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(hasBeenRead = true)
            saveAllNotes(list)
        }
    }


    fun deleteNote(noteId: String) {
        val list = getAllNotes()
        val note = list.firstOrNull { it.id == noteId } ?: return
        if (note.type == Note.NoteType.IMAGE) {
            secureDeleteFile(File(note.content))
        }
        // مسح المرفقات المشفّرة المرتبطة
        note.attachments.forEach { att ->
            secureDeleteFile(File(att.encryptedPath))
        }
        list.removeAll { it.id == noteId }
        saveAllNotes(list)
    }


    fun purgeExpiredNotes(): Int {
        val now = System.currentTimeMillis()
        val all = getAllNotes()
        val keep = mutableListOf<Note>()
        var removed = 0
        for (n in all) {
            if (n.shouldDestroyNow(now)) {
                if (n.type == Note.NoteType.IMAGE) secureDeleteFile(File(n.content))
                n.attachments.forEach { secureDeleteFile(File(it.encryptedPath)) }
                removed++
            } else {
                keep += n
            }
        }
        if (removed > 0) {
            saveAllNotes(keep)
            val c = prefs.getInt(K_WIPE_COUNT, 0)
            prefs.edit().putInt(K_WIPE_COUNT, c + removed).apply()
        }
        return removed
    }


    fun purgeOnAppCloseNotes(): Int {
        val all = getAllNotes()
        val keep = mutableListOf<Note>()
        var removed = 0
        for (n in all) {
            if (n.expiryPolicy == Note.ExpiryPolicy.ON_APP_CLOSE) {
                if (n.type == Note.NoteType.IMAGE) secureDeleteFile(File(n.content))
                n.attachments.forEach { secureDeleteFile(File(it.encryptedPath)) }
                removed++
            } else keep += n
        }
        if (removed > 0) {
            saveAllNotes(keep)
            val c = prefs.getInt(K_WIPE_COUNT, 0)
            prefs.edit().putInt(K_WIPE_COUNT, c + removed).apply()
        }
        return removed
    }

    fun getNotesCount(): Int = getAllNotes().size
    fun getWipeCount(): Int = prefs.getInt(K_WIPE_COUNT, 0)



    @Synchronized
    fun getAllVaultKeys(): MutableList<VaultKey> {
        val enc = prefs.getString(K_VAULT_JSON, null) ?: return mutableListOf()
        val dec = decryptText(enc) ?: return mutableListOf()
        return try {
            val arr = JSONArray(dec)
            val list = mutableListOf<VaultKey>()
            for (i in 0 until arr.length()) list += VaultKey.fromJson(arr.getJSONObject(i))
            list
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized
    private fun saveAllVaultKeys(list: List<VaultKey>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(K_VAULT_JSON, encryptText(arr.toString())).apply()
    }

    fun upsertVaultKey(
        id: String?,
        label: String,
        username: String,
        categoryName: String,
        plainSecret: String,
        note: String
    ): VaultKey {
        val list = getAllVaultKeys()
        val now = System.currentTimeMillis()
        val encSecret = encryptText(plainSecret)
        val existingIdx = id?.let { key -> list.indexOfFirst { it.id == key } } ?: -1
        val result: VaultKey
        if (existingIdx >= 0) {
            val old = list[existingIdx]
            val newHistory = (old.history + VaultKey.HistoryEntry("edited", now)).takeLast(10)
            result = old.copy(
                label = label,
                username = username,
                categoryName = categoryName,
                secret = encSecret,
                note = note,
                updatedAt = now,
                history = newHistory
            )
            list[existingIdx] = result
        } else {
            result = VaultKey(
                id = VaultKey.newId(),
                label = label,
                username = username,
                categoryName = categoryName,
                secret = encSecret,
                note = note,
                createdAt = now,
                updatedAt = now,
                lastAccessedAt = 0L,
                history = listOf(VaultKey.HistoryEntry("created", now))
            )
            list.add(0, result)
        }
        saveAllVaultKeys(list)
        return result
    }

    fun deleteVaultKey(id: String) {
        val list = getAllVaultKeys()
        if (list.removeAll { it.id == id }) saveAllVaultKeys(list)
    }

    fun revealVaultSecret(id: String): String? {
        val list = getAllVaultKeys()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val key = list[idx]
        val now = System.currentTimeMillis()
        val newHistory = (key.history + VaultKey.HistoryEntry("viewed", now)).takeLast(10)
        list[idx] = key.copy(lastAccessedAt = now, history = newHistory)
        saveAllVaultKeys(list)
        return decryptText(key.secret)
    }


    fun peekVaultSecret(id: String): String? {
        val key = getAllVaultKeys().firstOrNull { it.id == id } ?: return null
        return decryptText(key.secret)
    }




    fun wipeEverything() {
        File(context.filesDir, "enc_images").listFiles()?.forEach { secureDeleteFile(it) }
        File(context.filesDir, "enc_attachments").listFiles()?.forEach { secureDeleteFile(it) }
        prefs.edit()
            .remove(K_NOTES_JSON)
            .remove(K_VAULT_JSON)
            .apply()
        wipeTempFiles()
        wipeDecryptedCache()
    }


    fun performScheduledWipe(wipeAllOnClose: Boolean): Int {
        var removed = purgeExpiredNotes()
        if (wipeAllOnClose) removed += purgeOnAppCloseNotes()
        wipeTempFiles()
        wipeDecryptedCache()
        return removed
    }


    private fun wipeTempFiles() {
        try {
            val dirs = listOfNotNull(
                context.cacheDir,
                context.externalCacheDir,
                File(context.filesDir, "temp"),
                File(context.filesDir, "captures"),
                File(context.cacheDir, "decrypted")
            )
            dirs.forEach { dir ->
                if (dir.exists()) dir.listFiles()?.forEach { secureDeleteFile(it) }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "wipeTempFiles", e)
        }
    }

    private fun secureDeleteFile(file: File) {
        try {
            if (file.exists() && file.isFile) {
                val len = file.length()
                if (len > 0) {
                    val randoms = ByteArray(minOf(len.toInt(), 1024 * 1024))
                    SecureRandom().nextBytes(randoms)
                    file.writeBytes(randoms)
                }
                file.delete()
            } else if (file.isDirectory) {
                file.listFiles()?.forEach { secureDeleteFile(it) }
                file.delete()
            }
        } catch (_: Exception) { file.delete() }
    }

    fun clearSessionCache() {
        // لا شيء حرج في الذاكرة؛ نُجبر GC فقط
        System.gc()
    }
}
