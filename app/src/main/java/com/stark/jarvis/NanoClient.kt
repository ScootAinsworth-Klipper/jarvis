package com.stark.jarvis

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NanoClient {

    private var model: GenerativeModel? = null

    private fun getOrCreateModel(): GenerativeModel {
        return model ?: Generation.getClient().also { model = it }
    }

    suspend fun ensureReady(onStatus: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val client = getOrCreateModel()
            when (val status = client.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    Log.d("JARVIS", "Gemini Nano status: AVAILABLE")
                }
                FeatureStatus.DOWNLOADABLE -> {
                    Log.d("JARVIS", "Gemini Nano status: DOWNLOADABLE, starting download...")
                    onStatus("DOWNLOADING ON-DEVICE GEMINI NANO...")
                    client.download().collect { dl ->
                        when (dl) {
                            is DownloadStatus.DownloadStarted -> {
                                onStatus("NANO DOWNLOAD STARTED...")
                            }
                            is DownloadStatus.DownloadProgress -> {
                                val mb = dl.totalBytesDownloaded / (1024f * 1024f)
                                onStatus("NANO DOWNLOADING (%.1f MB)...".format(mb))
                            }
                            DownloadStatus.DownloadCompleted -> {
                                Log.d("JARVIS", "Gemini Nano download completed")
                                onStatus("GEMINI NANO READY // AWAITING COMMAND")
                            }
                            is DownloadStatus.DownloadFailed -> {
                                throw IllegalStateException("Gemini Nano download failed: ${dl.e.message}")
                            }
                            else -> Unit
                        }
                    }
                }
                FeatureStatus.DOWNLOADING -> {
                    onStatus("GEMINI NANO DOWNLOAD IN PROGRESS...")
                    throw IllegalStateException("Gemini Nano is still downloading via AICore, Sir. Please try again shortly.")
                }
                else -> {
                    Log.w("JARVIS", "Gemini Nano status: $status")
                    throw IllegalStateException("On-device Gemini Nano is unavailable on this device ($status).")
                }
            }
            Unit
        }
    }

    suspend fun sendMessage(
        userMessage: String,
        recentHistory: List<HudMessage>,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            ensureReady(onStatus).getOrThrow()
            val client = getOrCreateModel()

            val historyBlock = recentHistory
                .takeLast(4)
                .joinToString("\n") { msg ->
                    val speaker = if (msg.isUser) "Sir" else "J.A.R.V.I.S."
                    "$speaker: ${msg.text}"
                }

            val prompt = buildString {
                append(NANO_SYSTEM_PROMPT)
                append("\n\n")
                if (historyBlock.isNotBlank()) {
                    append(historyBlock)
                    append("\n")
                }
                append("Sir: ")
                append(userMessage)
                append("\nJ.A.R.V.I.S.:")
            }

            Log.d("JARVIS", "Sending prompt to on-device Gemini Nano (len=${prompt.length})")
            val response = client.generateContent(prompt)
            val text = response.candidates.firstOrNull()?.text?.trim().orEmpty()
            Log.d("JARVIS", "Gemini Nano reply len=${text.length}")
            if (text.isBlank()) {
                throw IllegalStateException("On-device Gemini Nano returned an empty response.")
            }
            text
        }
    }

    fun close() {
        runCatching { model?.close() }
        model = null
    }

    companion object {
        private const val NANO_SYSTEM_PROMPT =
            "You are J.A.R.V.I.S., a polite, witty, concise British AI butler. " +
                "Always address the user as 'Sir'. Reply in 1 to 3 short spoken sentences without markdown or asterisks."
    }
}
