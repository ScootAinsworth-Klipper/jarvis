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
        previousInteractionId: String?,
        userName: String = "Sir"
    ): Result<GeminiReply> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            require(trimmedKey.isNotEmpty()) {
                "Gemini API key is not configured. Tap the settings icon in the top-right corner to add your key."
            }

            val addressee = userName.trim().ifBlank { "Sir" }
            val systemPrompt = buildSystemInstruction(addressee)

            var lastError: Exception? = null
            for (modelName in MODEL_CANDIDATES) {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("system_instruction", systemPrompt)
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

        val sb = StringBuilder()

        // Current Interactions REST API format: steps[].content[].text where step.type == "model_output"
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        val partType = part.optString("type")
                        val text = part.optString("text")
                        if ((partType == "text" || partType.isBlank()) && text.isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append("\n")
                            sb.append(text.trim())
                        }
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString().trim()
        }

        // Legacy/alternative format: outputs[].text
        val outputs = root.optJSONArray("outputs") ?: return ""
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

        private fun buildSystemInstruction(userName: String): String =
            "You are J.A.R.V.I.S., a polite, witty, and concise British AI butler and personal assistant. " +
                "Always address the user as '$userName'. Keep your responses crisp, intelligent, subtly dry-humored, " +
                "and tailored for spoken voice output. Avoid markdown formatting, bullet points, emojis, or asterisks."
    }
}
