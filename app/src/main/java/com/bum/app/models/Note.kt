package com.bum.app.models

import org.json.JSONArray
import org.json.JSONObject

// نموذج الملاحظة
data class Note(
    val id: String,
    val type: NoteType,
    val content: String,
    val createdAt: Long,
    val expiryPolicy: ExpiryPolicy,
    val expiresAt: Long,
    val destroyOnRead: Boolean,
    var hasBeenRead: Boolean = false,
    val imageNote: String = "",
    val attachments: List<Attachment> = emptyList(),
    val title: String = "",
    val isSecretCompartment: Boolean = false,
    val isGhost: Boolean = false
) {
    enum class NoteType { TEXT, IMAGE }

    enum class ExpiryPolicy(val displayLabel: String, val durationMs: Long) {
        ONE_HOUR("ساعة واحدة", 60L * 60L * 1000L),
        ONE_DAY("يوم واحد",    24L * 60L * 60L * 1000L),
        ONE_WEEK("أسبوع",      7L * 24L * 60L * 60L * 1000L),
        PERMANENT("دائم — حتى الحذف", 0L),
        ON_APP_CLOSE("يموت عند الإغلاق", -1L);

        companion object {
            fun fromName(name: String?): ExpiryPolicy =
                values().firstOrNull { it.name == name } ?: ONE_DAY
        }
    }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (expiryPolicy == ExpiryPolicy.PERMANENT) return false
        if (expiryPolicy == ExpiryPolicy.ON_APP_CLOSE) return false
        if (expiresAt <= 0L) return false
        return now >= expiresAt
    }

    fun shouldDestroyNow(now: Long = System.currentTimeMillis()): Boolean {
        if (destroyOnRead && hasBeenRead) return true
        return isExpired(now)
    }

    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        if (expiryPolicy == ExpiryPolicy.PERMANENT) return Long.MAX_VALUE
        if (expiryPolicy == ExpiryPolicy.ON_APP_CLOSE) return -1L
        return (expiresAt - now).coerceAtLeast(0L)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("content", content)
        put("createdAt", createdAt)
        put("expiryPolicy", expiryPolicy.name)
        put("expiresAt", expiresAt)
        put("destroyOnRead", destroyOnRead)
        put("hasBeenRead", hasBeenRead)
        put("imageNote", imageNote)
        put("title", title)
        put("isSecretCompartment", isSecretCompartment)
        put("isGhost", isGhost)
        val arr = JSONArray()
        attachments.forEach { arr.put(it.toJson()) }
        put("attachments", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): Note {
            val attArr = obj.optJSONArray("attachments")
            val atts = mutableListOf<Attachment>()
            if (attArr != null) {
                for (i in 0 until attArr.length()) {
                    atts += Attachment.fromJson(attArr.getJSONObject(i))
                }
            }
            return Note(
                id = obj.getString("id"),
                type = try { NoteType.valueOf(obj.getString("type")) } catch (_: Exception) { NoteType.TEXT },
                content = obj.getString("content"),
                createdAt = obj.getLong("createdAt"),
                expiryPolicy = ExpiryPolicy.fromName(obj.optString("expiryPolicy")),
                expiresAt = obj.optLong("expiresAt", 0L),
                destroyOnRead = obj.optBoolean("destroyOnRead", false),
                hasBeenRead = obj.optBoolean("hasBeenRead", false),
                imageNote = obj.optString("imageNote", ""),
                attachments = atts,
                title = obj.optString("title", ""),
                isSecretCompartment = obj.optBoolean("isSecretCompartment", false),
                isGhost = obj.optBoolean("isGhost", false)
            )
        }

        fun newId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

// نموذج المرفق المشفّر
data class Attachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val encryptedPath: String,
    val sizeBytes: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("filename", filename)
        put("mimeType", mimeType)
        put("encryptedPath", encryptedPath)
        put("sizeBytes", sizeBytes)
    }

    fun isImage(): Boolean = mimeType.startsWith("image/")
    fun isText(): Boolean = mimeType.startsWith("text/") ||
        mimeType == "application/json" || mimeType == "application/xml"

    companion object {
        fun fromJson(obj: JSONObject): Attachment = Attachment(
            id = obj.getString("id"),
            filename = obj.optString("filename", "file"),
            mimeType = obj.optString("mimeType", "application/octet-stream"),
            encryptedPath = obj.getString("encryptedPath"),
            sizeBytes = obj.optLong("sizeBytes", 0L)
        )

        fun newId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
