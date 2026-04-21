package com.bum.app.models

import org.json.JSONArray
import org.json.JSONObject

// نموذج مفتاح مخزن الأسرار
data class VaultKey(
    val id: String,
    val label: String,
    val username: String,
    val categoryName: String,
    val secret: String,                // مشفّر
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long = 0L,
    val history: List<HistoryEntry> = emptyList()
) {
    data class HistoryEntry(
        val event: String,
        val timestamp: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("event", event); put("timestamp", timestamp)
        }
        companion object {
            fun fromJson(obj: JSONObject): HistoryEntry = HistoryEntry(
                event = obj.optString("event", "edited"),
                timestamp = obj.optLong("timestamp", 0L)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("username", username)
        put("categoryName", categoryName)
        put("secret", secret)
        put("note", note)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastAccessedAt", lastAccessedAt)
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        put("history", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject): VaultKey {
            val histArr = obj.optJSONArray("history")
            val hist = mutableListOf<HistoryEntry>()
            if (histArr != null) {
                for (i in 0 until histArr.length()) {
                    hist += HistoryEntry.fromJson(histArr.getJSONObject(i))
                }
            }
            return VaultKey(
                id = obj.getString("id"),
                label = obj.getString("label"),
                username = obj.optString("username", ""),
                categoryName = obj.optString("categoryName", "")
                    .ifEmpty { obj.optString("category", "عام") },
                secret = obj.getString("secret"),
                note = obj.optString("note", ""),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.optLong("updatedAt", obj.getLong("createdAt")),
                lastAccessedAt = obj.optLong("lastAccessedAt", 0L),
                history = hist
            )
        }

        fun newId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
