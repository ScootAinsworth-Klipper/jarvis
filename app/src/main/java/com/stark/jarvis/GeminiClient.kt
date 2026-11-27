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
        cfAccountId: String,
        cfApiToken: String,
        prompt: String,
        userName: String = "Sir",
        onStatus: (String) -> Unit = {}
    ): Result<GeminiImageReply> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedKey = apiKey.trim()
            val trimmedCfAccount = cfAccountId.trim()
            val trimmedCfToken = cfApiToken.trim()
            val addressee = userName.trim().ifBlank { "Sir" }

            onStatus("REFINING IMAGE PROMPT...")
            val visualPrompt = refineImagePrompt(trimmedKey, prompt)
            Log.d("JARVIS", "Refined image prompt: '$visualPrompt'")

            if (trimmedCfAccount.isNotEmpty() && trimmedCfToken.isNotEmpty()) {
                onStatus("GENERATING FLUX IMAGE (CLOUDFLARE)...")
                return@runCatching generateWithCloudflare(
                    accountId = trimmedCfAccount,
                    apiToken = trimmedCfToken,
                    prompt = visualPrompt,
                    addressee = addressee
                )
            }

            onStatus("GENERATING FLUX IMAGE...")
            val fluxResult = runCatching { generateWithGradioFlux(visualPrompt, addressee) }.getOrNull()
            if (fluxResult != null) return@runCatching fluxResult

            onStatus("GENERATING SD 3.5 IMAGE...")
            val sd35Result = runCatching { generateWithGradioSd35(visualPrompt, addressee) }.getOrNull()
            if (sd35Result != null) return@runCatching sd35Result

            onStatus("GENERATING SDXL IMAGE...")
            generateWithGradioSdxlLightning(visualPrompt, addressee)
        }
    }

    private fun generateWithCloudflare(
        accountId: String,
        apiToken: String,
        prompt: String,
        addressee: String
    ): GeminiImageReply {
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/@cf/black-forest-labs/flux-1-schnell"
        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("steps", 4)
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        Log.d("JARVIS", "Generating image via Cloudflare Workers AI FLUX.1-schnell")
        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = runCatching {
                    JSONObject(rawBody).optJSONArray("errors")?.optJSONObject(0)?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                throw IllegalStateException(
                    errMsg ?: "Cloudflare AI error (${response.code}). Check Account ID and API Token."
                )
            }
            val root = JSONObject(rawBody)
            val b64 = root.optJSONObject("result")?.optString("image").orEmpty()
            if (b64.isBlank()) {
                throw IllegalStateException("Cloudflare returned an empty image.")
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            return GeminiImageReply(
                caption = "Here is the generated image, $addressee.",
                imageBytes = bytes
            )
        }
    }

    private fun generateWithGradioFlux(
        prompt: String,
        addressee: String
    ): GeminiImageReply {
        val dataArr = JSONArray().apply {
            put(prompt)
            put(0)
            put(true)
            put(768)
            put(768)
            put(4)
        }
        val payload = JSONObject().apply {
            put("data", dataArr)
        }
        val postReq = Request.Builder()
            .url(GRADIO_FLUX_ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val eventId = httpClient.newCall(postReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Image queue error (${resp.code}).")
            }
            JSONObject(raw).optString("event_id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No event ID returned from image space.")
        }

        val sseReq = Request.Builder()
            .url("$GRADIO_FLUX_ENDPOINT/$eventId")
            .get()
            .build()

        val sseText = httpClient.newCall(sseReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Image stream error (${resp.code}).")
            }
            resp.body?.string().orEmpty()
        }

        val dataLine = sseText.lines().lastOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: throw IllegalStateException("Image generation failed or timed out.")

        val arr = JSONArray(dataLine)
        val imgUrl = arr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No image URL in response.")

        val dlReq = Request.Builder().url(imgUrl).get().build()
        httpClient.newCall(dlReq).execute().use { dlResp ->
            val bytes = dlResp.body?.bytes()
            if (!dlResp.isSuccessful || bytes == null || bytes.isEmpty()) {
                throw IllegalStateException("Failed to download generated image (${dlResp.code}).")
            }
            return GeminiImageReply(
                caption = "Here is the generated image, $addressee.",
                imageBytes = bytes
            )
        }
    }

    private fun generateWithGradioSd35(prompt: String, addressee: String): GeminiImageReply {
        val dataArr = JSONArray().apply {
            put(prompt)
            put("")
            put(0)
            put(true)
            put(768)
            put(768)
            put(4.5)
            put(28)
        }
        return callGradioSpace(GRADIO_SD35_ENDPOINT, dataArr, addressee)
    }

    private fun generateWithGradioSdxlLightning(prompt: String, addressee: String): GeminiImageReply {
        val dataArr = JSONArray().apply {
            put(prompt)
            put("4-Step")
        }
        return callGradioSpace(GRADIO_SDXL_LIGHTNING_ENDPOINT, dataArr, addressee)
    }

    private fun callGradioSpace(
        endpoint: String,
        dataArr: JSONArray,
        addressee: String
    ): GeminiImageReply {
        val payload = JSONObject().apply { put("data", dataArr) }
        val postReq = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val eventId = httpClient.newCall(postReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Image queue error (${resp.code}).")
            }
            JSONObject(raw).optString("event_id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No event ID returned.")
        }

        val sseReq = Request.Builder().url("$endpoint/$eventId").get().build()
        val sseText = httpClient.newCall(sseReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Image stream error (${resp.code}).")
            }
            resp.body?.string().orEmpty()
        }

        if (sseText.contains("event: error")) {
            throw IllegalStateException("Space quota reached. Please try again later.")
        }

        val dataLine = sseText.lines().lastOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: throw IllegalStateException("Image generation failed.")

        val arr = JSONArray(dataLine)
        val imgUrl = arr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No image URL in response.")

        val dlReq = Request.Builder().url(imgUrl).get().build()
        httpClient.newCall(dlReq).execute().use { dlResp ->
            val bytes = dlResp.body?.bytes()
            if (!dlResp.isSuccessful || bytes == null || bytes.isEmpty()) {
                throw IllegalStateException("Failed to download generated image (${dlResp.code}).")
            }
            return GeminiImageReply(
                caption = "Here is the generated image, $addressee.",
                imageBytes = bytes
            )
        }
    }

    private suspend fun refineImagePrompt(apiKey: String, rawPrompt: String): String {
        val instruction =
            "Rewrite the user's request into a single concise, vivid image-generation prompt (max 30 words). " +
                "Remove command words like 'draw' or 'generate an image of'. Describe the full subject clearly with realistic visual details. Output ONLY the prompt."
        val refined = runCatching {
            if (apiKey.isNotEmpty()) {
                val payload = JSONObject().apply {
                    put("model", "gemini-3.5-flash-lite")
                    put("system_instruction", instruction)
                    put("input", rawPrompt)
                }
                val req = Request.Builder()
                    .url(INTERACTIONS_ENDPOINT)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        extractReplyText(JSONObject(resp.body?.string().orEmpty()))
                    } else ""
                }
            } else {
                sendViaPollinations(instruction, rawPrompt, emptyList()).text
            }
        }.getOrDefault("")
        return refined.trim().ifBlank { rawPrompt }
    }

    private fun generateWithPollinations(prompt: String, addressee: String): GeminiImageReply {
        val encoded = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val seed = System.currentTimeMillis() % 100000
        val url = "https://image.pollinations.ai/prompt/$encoded?model=flux&width=768&height=768&nologo=true&seed=$seed"
        Log.d("JARVIS", "Generating image via Pollinations")
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "JARVIS-Android")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Pollinations HTTP ${response.code}")
            }
            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                throw IllegalStateException("Empty Pollinations image")
            }
            return GeminiImageReply(
                caption = "Here is the generated image, $addressee.",
                imageBytes = bytes
            )
        }
    }

    private fun generateWithAiHorde(
        prompt: String,
        addressee: String,
        onStatus: (String) -> Unit
    ): GeminiImageReply {
        Log.d("JARVIS", "Generating image via AI Horde")
        val submitPayload = JSONObject().apply {
            put("prompt", "$prompt, highly detailed, sharp focus, realistic###blurry, low quality, distorted, deformed, ugly, watermark, text")
            put("nsfw", false)
            put("models", JSONArray().apply {
                put("AlbedoBase XL (SDXL)")
                put("AlbedoBase XL 3.1")
                put("Juggernaut XL")
                put("SDXL 1.0")
            })
            put("params", JSONObject().apply {
                put("width", 1024)
                put("height", 1024)
                put("steps", 25)
                put("cfg_scale", 7.0)
                put("sampler_name", "k_dpmpp_2m")
                put("karras", true)
                put("n", 1)
            })
        }
        val submitReq = Request.Builder()
            .url("https://stablehorde.net/api/v2/generate/async")
            .addHeader("apikey", "0000000000")
            .addHeader("Content-Type", "application/json")
            .addHeader("Client-Agent", "JARVIS:1.25:android")
            .post(submitPayload.toString().toRequestBody(jsonMediaType))
            .build()

        val jobId = httpClient.newCall(submitReq).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Image service unavailable (HTTP ${response.code}).")
            }
            JSONObject(raw).optString("id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No job ID returned from image service.")
        }

        // Poll up to 75 seconds (25 x 3s) with live ETA in header
        for (attempt in 0 until 25) {
            Thread.sleep(3000L)
            val statusReq = Request.Builder()
                .url("https://stablehorde.net/api/v2/generate/status/$jobId")
                .addHeader("Client-Agent", "JARVIS:1.25:android")
                .get()
                .build()
            val imgUrl = httpClient.newCall(statusReq).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val eta = json.optInt("wait_time", 0)
                if (!json.optBoolean("done", false)) {
                    onStatus(if (eta > 0) "RENDERING IMAGE (ETA ~${eta}s)..." else "RENDERING IMAGE...")
                    return@use null
                }
                val gens = json.optJSONArray("generations")
                gens?.optJSONObject(0)?.optString("img")?.takeIf { it.isNotBlank() }
            }
            if (imgUrl != null) {
                val dlReq = Request.Builder().url(imgUrl).get().build()
                httpClient.newCall(dlReq).execute().use { dlResp ->
                    val bytes = dlResp.body?.bytes()
                    if (dlResp.isSuccessful && bytes != null && bytes.isNotEmpty()) {
                        return GeminiImageReply(
                            caption = "Here is the generated image, $addressee.",
                            imageBytes = bytes
                        )
                    }
                }
            }
        }
        throw IllegalStateException("Image generation timed out. Please try again.")
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

    @Volatile
    private var geminiImageFreeTierBlocked = false

    companion object {
        private const val INTERACTIONS_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val GRADIO_FLUX_ENDPOINT =
            "https://black-forest-labs-flux-1-schnell.hf.space/gradio_api/call/infer"
        private const val GRADIO_SD35_ENDPOINT =
            "https://stabilityai-stable-diffusion-3-5-large.hf.space/gradio_api/call/infer"
        private const val GRADIO_SDXL_LIGHTNING_ENDPOINT =
            "https://bytedance-sdxl-lightning.hf.space/call/generate_image"
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
