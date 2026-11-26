package com.stark.jarvis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class GeminiReply(
    val text: String,
    val interactionId: String?
)

class GeminiClient {
    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val ipv4 = all.filterIsInstance<Inet4Address>()
            return ipv4.ifEmpty { all }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendMessage(
        apiKey: String,
        userMessage: String,
        previousInteractionId: String?
    ): Result<GeminiReply> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            require(trimmedKey.isNotEmpty()) {
                "Gemini API key is not configured. Tap the settings icon in the top-right corner to add your key."
            }

            var lastError: Exception? = null
            for (modelName in MODEL_CANDIDATES) {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("system_instruction", SYSTEM_INSTRUCTION)
                    put("input", userMessage)
                    if (!previousInteractionId.isNullOrBlank()) {
                        put("previous_interaction_id", previousInteractionId)
                    }
                }

                val request = Request.Builder()
                    .url(INTERACTIONS_ENDPOINT)
                    .addHeader("x-goog-api-key", trimmedKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                Log.d("JARVIS", "Sending request to $INTERACTIONS_ENDPOINT ($modelName)")
                httpClient.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string().orEmpty()
                    Log.d("JARVIS", "Gemini HTTP ${response.code} ($modelName), bodyLen=${rawBody.length}")
                    if (response.code == 503 || response.code == 429) {
                        val apiMsg = runCatching {
                            JSONObject(rawBody).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        lastError = IllegalStateException(
                            apiMsg?.takeIf { it.isNotBlank() }
                                ?: "Gemini API error (${response.code})"
                        )
                        return@use
                    }
                    if (!response.isSuccessful) {
                        val apiMsg = runCatching {
                            JSONObject(rawBody).optJSONObject("error")?.optString("message")
                        }.getOrNull()
                        throw IllegalStateException(
                            apiMsg?.takeIf { it.isNotBlank() }
                                ?: "Gemini API error (${response.code}): ${rawBody.take(180)}"
                        )
                    }

                    val root = JSONObject(rawBody)
                    val interactionId = root.optString("id").takeIf { it.isNotBlank() }
                    val replyText = extractReplyText(root)
                    if (replyText.isBlank()) {
                        throw IllegalStateException("Received an empty response from Gemini.")
                    }
                    return@runCatching GeminiReply(text = replyText, interactionId = interactionId)
                }
            }
            throw (lastError ?: IllegalStateException("All Gemini models are currently unavailable."))
        }
    }

    private fun extractReplyText(root: JSONObject): String {
        val direct = root.optString("output_text")
        if (direct.isNotBlank()) return direct.trim()

        val outputs = root.optJSONArray("outputs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until outputs.length()) {
            val item = outputs.optJSONObject(i) ?: continue
            val type = item.optString("type")
            val text = item.optString("text")
            if ((type == "text" || type.isBlank()) && text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(text.trim())
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private val MODEL_CANDIDATES = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash")

        private const val SYSTEM_INSTRUCTION =
            "You are J.A.R.V.I.S., a polite, witty, and concise British AI butler and personal assistant. " +
                "Always address the user as 'Sir'. Keep your responses crisp, intelligent, subtly dry-humored, " +
                "and tailored for spoken voice output. Avoid markdown formatting, bullet points, emojis, or asterisks."
    }
}
