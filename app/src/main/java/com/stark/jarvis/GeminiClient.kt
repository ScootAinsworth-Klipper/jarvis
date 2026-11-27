package com.stark.jarvis

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GeminiReply(
    val text: String,
    val interactionId: String?
)

data class GeminiImageReply(
    val caption: String,
    val imageBytes: ByteArray
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
        userName: String = "Sir",
        recentHistory: List<HudMessage> = emptyList()
    ): Result<GeminiReply> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            val addressee = userName.trim().ifBlank { "Sir" }
            val systemPrompt = buildSystemInstruction(addressee)

            if (trimmedKey.isNotEmpty()) {
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
                    val reply = runCatching {
                        httpClient.newCall(request).execute().use { response ->
                            val rawBody = response.body?.string().orEmpty()
                            Log.d("JARVIS", "Gemini HTTP ${response.code} ($modelName), bodyLen=${rawBody.length}")
                            if (!response.isSuccessful) {
                                return@use null
                            }
                            val root = JSONObject(rawBody)
                            val interactionId = root.optString("id").takeIf { it.isNotBlank() }
                            val replyText = extractReplyText(root)
                            if (replyText.isBlank()) return@use null
                            GeminiReply(text = replyText, interactionId = interactionId)
                        }
                    }.getOrNull()
                    if (reply != null) {
                        return@runCatching reply
                    }
                }
            }

            // Automatic free fallback if Gemini returns 402/503/429 or no key is set
            sendViaPollinations(systemPrompt, userMessage, recentHistory)
        }
    }

    private fun sendViaPollinations(
        systemPrompt: String,
        userMessage: String,
        recentHistory: List<HudMessage>
    ): GeminiReply {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            recentHistory.takeLast(6).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.isUser) "user" else "assistant")
                    put("content", msg.text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
        val payload = JSONObject().apply {
            put("messages", messages)
            put("model", "openai")
        }
        val request = Request.Builder()
            .url("https://text.pollinations.ai/")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "JARVIS-Android")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        Log.d("JARVIS", "Sending request via free text fallback")
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful || body.isBlank()) {
                throw IllegalStateException("AI service unavailable (${response.code}).")
            }
            return GeminiReply(text = body, interactionId = null)
        }
    }

    suspend fun generateImage(
        apiKey: String,
        prompt: String,
        userName: String = "Sir"
    ): Result<GeminiImageReply> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            val addressee = userName.trim().ifBlank { "Sir" }

            if (trimmedKey.isNotEmpty()) {
                for (modelName in IMAGE_MODEL_CANDIDATES) {
                    val payload = JSONObject().apply {
                        put("model", modelName)
                        put("input", prompt)
                    }

                    val request = Request.Builder()
                        .url(INTERACTIONS_ENDPOINT)
                        .addHeader("x-goog-api-key", trimmedKey)
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()

                    Log.d("JARVIS", "Generating image with $modelName")
                    val geminiResult = runCatching {
                        httpClient.newCall(request).execute().use { response ->
                            val rawBody = response.body?.string().orEmpty()
                            Log.d("JARVIS", "Gemini Image HTTP ${response.code} ($modelName), bodyLen=${rawBody.length}")
                            if (!response.isSuccessful) {
                                return@use null
                            }
                            val root = JSONObject(rawBody)
                            val base64Data = extractImageBase64(root)
                            if (base64Data.isBlank()) return@use null
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            val caption = extractReplyText(root).ifBlank {
                                "Here is the generated image, $addressee."
                            }
                            GeminiImageReply(caption = caption, imageBytes = bytes)
                        }
                    }.getOrNull()
                    if (geminiResult != null) {
                        return@runCatching geminiResult
                    }
                }
            }

            // Free fallback (works on Free Tier Gemini keys and NANO mode with no key)
            generateWithPollinations(prompt, addressee)
        }
    }

    private fun generateWithPollinations(prompt: String, addressee: String): GeminiImageReply {
        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val seed = System.currentTimeMillis() % 100000
        val url = "https://image.pollinations.ai/prompt/$encoded?model=flux&width=768&height=768&nologo=true&seed=$seed"
        Log.d("JARVIS", "Generating image via free Pollinations endpoint")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "JARVIS-Android")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Image service returned HTTP ${response.code}.")
            }
            val bytes = response.body?.bytes()
                ?: throw IllegalStateException("Empty image response.")
            if (bytes.isEmpty()) {
                throw IllegalStateException("Received 0 image bytes.")
            }
            return GeminiImageReply(
                caption = "Here is the generated image, $addressee.",
                imageBytes = bytes
            )
        }
    }

    private fun extractImageBase64(root: JSONObject): String {
        val outputImg = root.optJSONObject("output_image")?.optString("data").orEmpty()
        if (outputImg.isNotBlank()) return outputImg

        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val content = step.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "image") {
                        val data = part.optString("data")
                        if (data.isNotBlank()) return data
                    }
                }
            }
        }

        val outputs = root.optJSONArray("outputs")
        if (outputs != null) {
            for (i in 0 until outputs.length()) {
                val item = outputs.optJSONObject(i) ?: continue
                if (item.optString("type") == "image") {
                    val data = item.optString("data")
                    if (data.isNotBlank()) return data
                }
            }
        }
        return ""
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
        private val IMAGE_MODEL_CANDIDATES = listOf(
            "gemini-nano-banana-2.1",
            "gemini-3.1-flash-image",
            "gemini-2.5-flash-image"
        )

        private fun buildSystemInstruction(userName: String): String =
            "You are J.A.R.V.I.S., a polite, witty, and concise British AI butler and personal assistant. " +
                "Always address the user as '$userName'. Keep your responses crisp, intelligent, subtly dry-humored, " +
                "and tailored for spoken voice output. Avoid markdown formatting, bullet points, emojis, or asterisks."
    }
}
