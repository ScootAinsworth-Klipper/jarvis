package com.stark.jarvis

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReactorMode {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

data class HudMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String
)

data class JarvisUiState(
    val messages: List<HudMessage> = emptyList(),
    val reactorMode: ReactorMode = ReactorMode.IDLE,
    val audioLevel: Float = 0f,
    val partialTranscript: String = "",
    val voiceLabel: String = "EN-GB",
    val statusText: String = "CORE ONLINE // AWAITING COMMAND",
    val apiKey: String = "",
    val autoSpeak: Boolean = true,
    val isThinking: Boolean = false
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val geminiClient = GeminiClient()
    private var previousInteractionId: String? = null
    private var messageCounter = 0L

    private val _isThinking = MutableStateFlow(false)
    private val _uiState = MutableStateFlow(
        JarvisUiState(
            apiKey = loadInitialApiKey(),
            messages = listOf(
                HudMessage(
                    id = ++messageCounter,
                    isUser = false,
                    text = "Good evening, Sir. All systems are online and at your disposal."
                )
            )
        )
    )
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    val voiceManager = JarvisVoiceManager(
        context = application,
        onSpeechResult = { spokenText -> submitPrompt(spokenText) },
        onSpeechError = { err ->
            _uiState.update { it.copy(statusText = err.uppercase()) }
        }
    )

    init {
        viewModelScope.launch {
            combine(
                voiceManager.isListening,
                _isThinking,
                voiceManager.isSpeaking,
                voiceManager.audioLevel,
                voiceManager.partialTranscript
            ) { listening, thinking, speaking, level, partial ->
                val mode = when {
                    listening -> ReactorMode.LISTENING
                    thinking -> ReactorMode.THINKING
                    speaking -> ReactorMode.SPEAKING
                    else -> ReactorMode.IDLE
                }
                val status = when (mode) {
                    ReactorMode.LISTENING -> "AUDIO INPUT ACTIVE // RELEASE TO TRANSMIT"
                    ReactorMode.THINKING -> "PROCESSING QUERY // GEMINI CORE"
                    ReactorMode.SPEAKING -> "VOCAL SYNTHESIS // EN-GB"
                    ReactorMode.IDLE -> "CORE ONLINE // AWAITING COMMAND"
                }
                Triple(mode, level, Pair(partial, status))
            }.collect { (mode, level, textPair) ->
                _uiState.update { state ->
                    state.copy(
                        reactorMode = mode,
                        audioLevel = level,
                        partialTranscript = textPair.first,
                        statusText = textPair.second,
                        isThinking = _isThinking.value,
                        voiceLabel = voiceManager.voiceLabel.value
                    )
                }
            }
        }
    }

    private fun loadInitialApiKey(): String {
        val saved = prefs.getString(KEY_API, "").orEmpty()
        return saved.ifBlank { BuildConfig.GEMINI_API_KEY }
    }

    fun updateApiKey(newKey: String) {
        val trimmed = newKey.trim()
        prefs.edit().putString(KEY_API, trimmed).apply()
        _uiState.update { it.copy(apiKey = trimmed) }
    }

    fun toggleAutoSpeak() {
        val next = !_uiState.value.autoSpeak
        if (!next) {
            voiceManager.stopSpeaking()
        }
        _uiState.update { it.copy(autoSpeak = next) }
    }

    fun startPushToTalk() {
        voiceManager.startListening()
    }

    fun stopPushToTalk() {
        voiceManager.stopListening()
    }

    fun stopSpeaking() {
        voiceManager.stopSpeaking()
    }

    fun clearSession() {
        previousInteractionId = null
        voiceManager.stopSpeaking()
        _uiState.update {
            it.copy(
                messages = listOf(
                    HudMessage(
                        id = ++messageCounter,
                        isUser = false,
                        text = "Memory buffers cleared, Sir. How may I assist you?"
                    )
                ),
                statusText = "SESSION RESET // CORE ONLINE"
            )
        }
    }

    fun submitPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty() || _isThinking.value) return

        voiceManager.stopSpeaking()
        _uiState.update {
            it.copy(
                messages = it.messages + HudMessage(
                    id = ++messageCounter,
                    isUser = true,
                    text = clean
                ),
                partialTranscript = ""
            )
        }

        _isThinking.value = true
        viewModelScope.launch {
            val result = geminiClient.sendMessage(
                apiKey = _uiState.value.apiKey,
                userMessage = clean,
                previousInteractionId = previousInteractionId
            )
            _isThinking.value = false

            result.onSuccess { reply ->
                if (!reply.interactionId.isNullOrBlank()) {
                    previousInteractionId = reply.interactionId
                }
                _uiState.update {
                    it.copy(
                        messages = it.messages + HudMessage(
                            id = ++messageCounter,
                            isUser = false,
                            text = reply.text
                        )
                    )
                }
                if (_uiState.value.autoSpeak) {
                    voiceManager.speak(reply.text)
                }
            }.onFailure { error ->
                val errorText = error.message ?: "Unable to reach Gemini core, Sir."
                _uiState.update {
                    it.copy(
                        messages = it.messages + HudMessage(
                            id = ++messageCounter,
                            isUser = false,
                            text = "Apologies, Sir. $errorText"
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_hud_prefs"
        private const val KEY_API = "gemini_api_key"
    }
}
