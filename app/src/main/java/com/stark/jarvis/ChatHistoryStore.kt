package com.stark.jarvis

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SavedChatSession(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val interactionId: String?,
    val messages: List<HudMessage>
)

class ChatHistoryStore(private val prefs: SharedPreferences) {

    fun loadSessions(): List<SavedChatSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val msgArr = obj.optJSONArray("messages") ?: JSONArray()
                    val msgs = buildList {
                        for (j in 0 until msgArr.length()) {
                            val m = msgArr.optJSONObject(j) ?: continue
                            add(
                                HudMessage(
                                    id = m.optLong("id", j.toLong()),
                                    isUser = m.optBoolean("isUser", false),
                                    text = m.optString("text", ""),
                                    imagePath = m.optString("imagePath").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    add(
                        SavedChatSession(
                            id = obj.optString("id"),
                            title = obj.optString("title", "Conversation"),
                            updatedAtMs = obj.optLong("updatedAtMs", 0L),
                            interactionId = obj.optString("interactionId").takeIf { it.isNotBlank() },
                            messages = msgs
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsertSession(session: SavedChatSession): List<SavedChatSession> {
        val current = loadSessions().filterNot { it.id == session.id }
        val updated = (listOf(session) + current).take(MAX_SESSIONS)
        saveAll(updated)
        return updated
    }

    private fun saveAll(sessions: List<SavedChatSession>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val msgArr = JSONArray()
            s.messages.forEach { m ->
                msgArr.put(
                    JSONObject().apply {
                        put("id", m.id)
                        put("isUser", m.isUser)
                        put("text", m.text)
                        put("imagePath", m.imagePath.orEmpty())
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("updatedAtMs", s.updatedAtMs)
                    put("interactionId", s.interactionId.orEmpty())
                    put("messages", msgArr)
                }
            )
        }
        prefs.edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_SESSIONS = "saved_chat_sessions_v1"
        private const val MAX_SESSIONS = 5
    }
}
