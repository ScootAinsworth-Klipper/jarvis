package com.stark.jarvis

import android.app.Application
import android.content.Context
import android.util.Log
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
    val githubToken: String = "",
    val autoSpeak: Boolean = true,
    val isThinking: Boolean = false,
    val availableUpdate: AppReleaseInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateMessage: String? = null,
    val showUpdateDialog: Boolean = false
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val geminiClient = GeminiClient()
    private val appUpdater = AppUpdater(application)
    private var previousInteractionId: String? = null
    private var messageCounter = 0L

    private val _isThinking = MutableStateFlow(false)
    private val _speechError = MutableStateFlow<String?>(null)
    private val _uiState = MutableStateFlow(
        JarvisUiState(
            apiKey = loadInitialApiKey(),
            githubToken = prefs.getString(KEY_GITHUB_TOKEN, "").orEmpty(),
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
        onSpeechResult = { spokenText ->
            _speechError.value = null
            submitPrompt(spokenText)
        },
        onSpeechError = { err ->
            _speechError.value = err.uppercase()
        }
    )

    init {
        viewModelScope.launch {
            combine(
                voiceManager.isListening,
                _isThinking,
                voiceManager.isSpeaking,
                voiceManager.audioLevel,
                voiceManager.partialTranscript,
                _speechError
            ) { values ->
                val listening = values[0] as Boolean
                val thinking = values[1] as Boolean
                val speaking = values[2] as Boolean
                val level = values[3] as Float
                val partial = values[4] as String
                val speechErr = values[5] as String?
                val mode = when {
                    listening -> ReactorMode.LISTENING
                    thinking -> ReactorMode.THINKING
                    speaking -> ReactorMode.SPEAKING
                    else -> ReactorMode.IDLE
                }
                val status = when (mode) {
                    ReactorMode.LISTENING -> "AUDIO INPUT ACTIVE // TAP OR RELEASE TO SEND"
                    ReactorMode.THINKING -> "PROCESSING QUERY // GEMINI CORE"
                    ReactorMode.SPEAKING -> "VOCAL SYNTHESIS // EN-GB"
                    ReactorMode.IDLE -> speechErr ?: "CORE ONLINE // AWAITING COMMAND"
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
        if (_uiState.value.githubToken.isNotBlank()) {
            checkForUpdates(silent = true)
        }
    }

    private fun loadInitialApiKey(): String {
        val saved = prefs.getString(KEY_API, "").orEmpty()
        return saved.ifBlank { BuildConfig.GEMINI_API_KEY }
    }

    fun updateCredentials(newApiKey: String, newGithubToken: String) {
        val trimmedKey = newApiKey.trim()
        val trimmedToken = newGithubToken.trim()
        prefs.edit()
            .putString(KEY_API, trimmedKey)
            .putString(KEY_GITHUB_TOKEN, trimmedToken)
            .apply()
        _uiState.update { it.copy(apiKey = trimmedKey, githubToken = trimmedToken) }
        if (trimmedToken.isNotEmpty()) {
            checkForUpdates(silent = true)
        }
    }

    fun openUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = true) }
        if (_uiState.value.availableUpdate == null && !_uiState.value.isCheckingUpdate) {
            checkForUpdates(silent = false)
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun checkForUpdates(silent: Boolean = false) {
        if (_uiState.value.isCheckingUpdate || _uiState.value.isDownloadingUpdate) return
        _uiState.update {
            it.copy(
                isCheckingUpdate = true,
                updateMessage = if (silent) it.updateMessage else "Scanning GitHub releases..."
            )
        }
        viewModelScope.launch {
            val result = appUpdater.checkForUpdate(_uiState.value.githubToken)
            result.onSuccess { release ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        availableUpdate = release,
                        showUpdateDialog = if (release != null) true else it.showUpdateDialog,
                        updateMessage = if (release != null) {
                            "Version v${release.versionName} is available."
                        } else if (!silent) {
                            "J.A.R.V.I.S. is up to date (v${BuildConfig.VERSION_NAME})."
                        } else {
                            null
                        }
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateMessage = if (silent) null else (err.message ?: "Update check failed.")
                    )
                }
            }
        }
    }

    fun installAvailableUpdate() {
        val release = _uiState.value.availableUpdate ?: return
        if (!appUpdater.canInstallPackages()) {
            _uiState.update {
                it.copy(
                    updateMessage = "Enable 'Allow from this source' in Android Settings, then tap Install again."
                )
            }
            appUpdater.launchInstallPermissionSettings()
            return
        }

        _uiState.update {
            it.copy(
                isDownloadingUpdate = true,
                downloadProgress = 0f,
                updateMessage = "Downloading v${release.versionName}..."
            )
        }
        viewModelScope.launch {
            val result = appUpdater.downloadUpdateApk(
                release = release,
                githubToken = _uiState.value.githubToken,
                onProgress = { progress ->
                    _uiState.update { it.copy(downloadProgress = progress) }
                }
            )
            result.onSuccess { apkFile ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateMessage = "Launching Android installer..."
                    )
                }
                appUpdater.launchApkInstaller(apkFile)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateMessage = err.message ?: "Download failed."
                    )
                }
            }
        }
    }

    fun toggleAutoSpeak() {
        val next = !_uiState.value.autoSpeak
        if (!next) {
            voiceManager.stopSpeaking()
        }
        _uiState.update { it.copy(autoSpeak = next) }
    }

    fun startPushToTalk() {
        _speechError.value = null
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
        Log.d("JARVIS", "submitPrompt: '$clean', isThinking=${_isThinking.value}, keyLen=${_uiState.value.apiKey.length}")
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
                Log.d("JARVIS", "Gemini onSuccess: '${reply.text.take(60)}'")
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
                Log.e("JARVIS", "Gemini onFailure", error)
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
        private const val KEY_GITHUB_TOKEN = "github_token"
    }
}
