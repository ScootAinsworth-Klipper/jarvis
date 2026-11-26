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
import java.util.Calendar
import java.util.UUID

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
    val userName: String = "Sir",
    val showNamePrompt: Boolean = false,
    val savedChats: List<SavedChatSession> = emptyList(),
    val showHistoryDialog: Boolean = false,
    val apiKey: String = "",
    val githubToken: String = "",
    val useOnDeviceNano: Boolean = false,
    val autoSpeak: Boolean = true,
    val speakGreetingOnLaunch: Boolean = true,
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
    private val historyStore = ChatHistoryStore(prefs)
    private val geminiClient = GeminiClient()
    private val nanoClient = NanoClient()
    private val appUpdater = AppUpdater(application)
    private var previousInteractionId: String? = null
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var messageCounter = 0L

    private val _isThinking = MutableStateFlow(false)
    private val _nanoStatus = MutableStateFlow<String?>(null)
    private val _speechError = MutableStateFlow<String?>(null)
    private val initialApiKey = loadInitialApiKey()
    private val hasConfiguredName = prefs.contains(KEY_USER_NAME)
    private val initialUserName = prefs.getString(KEY_USER_NAME, "Sir").orEmpty().ifBlank { "Sir" }
    private val openingTemplate = GREETING_TEMPLATES.random()
    private var greetingSpokenThisLaunch = false

    private val _uiState = MutableStateFlow(
        JarvisUiState(
            apiKey = initialApiKey,
            githubToken = prefs.getString(KEY_GITHUB_TOKEN, "").orEmpty(),
            userName = initialUserName,
            showNamePrompt = !hasConfiguredName,
            savedChats = historyStore.loadSessions(),
            speakGreetingOnLaunch = prefs.getBoolean(KEY_SPEAK_GREETING, true),
            useOnDeviceNano = if (prefs.contains(KEY_USE_NANO)) {
                prefs.getBoolean(KEY_USE_NANO, false)
            } else {
                initialApiKey.isBlank()
            },
            messages = listOf(
                HudMessage(
                    id = ++messageCounter,
                    isUser = false,
                    text = formatGreeting(openingTemplate, initialUserName)
                )
            )
        )
    )
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    val voiceManager: JarvisVoiceManager = JarvisVoiceManager(
        context = application,
        onSpeechResult = { spokenText ->
            _speechError.value = null
            submitPrompt(spokenText)
        },
        onSpeechError = { err ->
            _speechError.value = err.uppercase()
        },
        onTtsReady = {
            val state = _uiState.value
            if (!greetingSpokenThisLaunch &&
                !state.showNamePrompt &&
                state.speakGreetingOnLaunch &&
                state.autoSpeak
            ) {
                greetingSpokenThisLaunch = true
                state.messages.firstOrNull()?.text?.let { greeting ->
                    voiceManager.speak(greeting)
                }
            }
        }
    )

    init {
        viewModelScope.launch {
            combine<Any?, Triple<ReactorMode, Float, Pair<String, String>>>(
                voiceManager.isListening,
                _isThinking,
                voiceManager.isSpeaking,
                voiceManager.audioLevel,
                voiceManager.partialTranscript,
                _speechError,
                _nanoStatus
            ) { values ->
                val listening = values[0] as Boolean
                val thinking = values[1] as Boolean
                val speaking = values[2] as Boolean
                val level = values[3] as Float
                val partial = values[4] as String
                val speechErr = values[5] as String?
                val nanoStat = values[6] as String?
                val mode = when {
                    listening -> ReactorMode.LISTENING
                    thinking -> ReactorMode.THINKING
                    speaking -> ReactorMode.SPEAKING
                    else -> ReactorMode.IDLE
                }
                val status = when (mode) {
                    ReactorMode.LISTENING -> "AUDIO INPUT ACTIVE // TAP OR RELEASE TO SEND"
                    ReactorMode.THINKING -> nanoStat ?: "PROCESSING QUERY // GEMINI CORE"
                    ReactorMode.SPEAKING -> "VOCAL SYNTHESIS // EN-GB"
                    ReactorMode.IDLE -> speechErr ?: nanoStat ?: "CORE ONLINE // AWAITING COMMAND"
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
        if (_uiState.value.useOnDeviceNano) {
            prepareNanoOnLaunch()
        }
    }

    private fun prepareNanoOnLaunch() {
        viewModelScope.launch {
            _nanoStatus.value = "CHECKING ON-DEVICE GEMINI NANO..."
            val result = nanoClient.ensureReady { status ->
                _nanoStatus.value = status
            }
            result.onSuccess {
                _nanoStatus.value = "NANO READY // AWAITING COMMAND"
            }.onFailure { err ->
                _nanoStatus.value = "NANO: ${(err.message ?: "UNAVAILABLE").uppercase()}"
            }
        }
    }

    private fun loadInitialApiKey(): String {
        val saved = prefs.getString(KEY_API, "").orEmpty()
        return saved.ifBlank { BuildConfig.GEMINI_API_KEY }
    }

    fun saveUserName(newName: String) {
        val clean = newName.trim().ifBlank { "Sir" }
        prefs.edit().putString(KEY_USER_NAME, clean).apply()
        val greeting = formatGreeting(openingTemplate, clean)
        _uiState.update { state ->
            val updatedMessages = if (state.messages.size == 1 && !state.messages.first().isUser) {
                listOf(
                    HudMessage(
                        id = state.messages.first().id,
                        isUser = false,
                        text = greeting
                    )
                )
            } else {
                state.messages
            }
            state.copy(
                userName = clean,
                showNamePrompt = false,
                messages = updatedMessages
            )
        }
        if (!greetingSpokenThisLaunch && _uiState.value.speakGreetingOnLaunch && _uiState.value.autoSpeak) {
            greetingSpokenThisLaunch = true
            voiceManager.speak(greeting)
        }
    }

    fun dismissNamePrompt() {
        if (!prefs.contains(KEY_USER_NAME)) {
            prefs.edit().putString(KEY_USER_NAME, "Sir").apply()
        }
        _uiState.update { it.copy(showNamePrompt = false) }
        if (!greetingSpokenThisLaunch && _uiState.value.speakGreetingOnLaunch && _uiState.value.autoSpeak) {
            greetingSpokenThisLaunch = true
            _uiState.value.messages.firstOrNull()?.text?.let { voiceManager.speak(it) }
        }
    }

    fun updateCredentials(
        newUserName: String,
        newApiKey: String,
        newGithubToken: String,
        newSpeakGreeting: Boolean
    ) {
        val cleanName = newUserName.trim().ifBlank { "Sir" }
        val trimmedKey = newApiKey.trim()
        val trimmedToken = newGithubToken.trim()
        prefs.edit()
            .putString(KEY_USER_NAME, cleanName)
            .putString(KEY_API, trimmedKey)
            .putString(KEY_GITHUB_TOKEN, trimmedToken)
            .putBoolean(KEY_SPEAK_GREETING, newSpeakGreeting)
            .apply()
        _uiState.update {
            it.copy(
                userName = cleanName,
                apiKey = trimmedKey,
                githubToken = trimmedToken,
                speakGreetingOnLaunch = newSpeakGreeting
            )
        }
        if (trimmedToken.isNotEmpty()) {
            checkForUpdates(silent = true)
        }
    }

    fun openHistoryDialog() {
        _uiState.update { it.copy(showHistoryDialog = true, savedChats = historyStore.loadSessions()) }
    }

    fun dismissHistoryDialog() {
        _uiState.update { it.copy(showHistoryDialog = false) }
    }

    fun restoreChatSession(session: SavedChatSession) {
        voiceManager.stopSpeaking()
        currentSessionId = session.id
        previousInteractionId = session.interactionId
        messageCounter = (session.messages.maxOfOrNull { it.id } ?: 0L) + 1L
        _uiState.update {
            it.copy(
                messages = session.messages,
                showHistoryDialog = false
            )
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

    fun toggleInferenceEngine() {
        val next = !_uiState.value.useOnDeviceNano
        prefs.edit().putBoolean(KEY_USE_NANO, next).apply()
        _uiState.update { it.copy(useOnDeviceNano = next) }
        if (next) {
            prepareNanoOnLaunch()
        } else {
            _nanoStatus.value = null
        }
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
        persistCurrentChatIfNeeded()
        previousInteractionId = null
        currentSessionId = UUID.randomUUID().toString()
        voiceManager.stopSpeaking()
        val name = _uiState.value.userName
        _uiState.update {
            it.copy(
                messages = listOf(
                    HudMessage(
                        id = ++messageCounter,
                        isUser = false,
                        text = "Memory buffers cleared, $name. How may I assist you?"
                    )
                ),
                statusText = "SESSION RESET // CORE ONLINE"
            )
        }
    }

    private fun persistCurrentChatIfNeeded() {
        val msgs = _uiState.value.messages
        val firstUserPrompt = msgs.firstOrNull { it.isUser }?.text ?: return
        val title = firstUserPrompt.take(42).let { if (firstUserPrompt.length > 42) "$it..." else it }
        val session = SavedChatSession(
            id = currentSessionId,
            title = title,
            updatedAtMs = System.currentTimeMillis(),
            interactionId = previousInteractionId,
            messages = msgs
        )
        val updated = historyStore.upsertSession(session)
        _uiState.update { it.copy(savedChats = updated) }
    }

    fun submitPrompt(prompt: String) {
        val clean = prompt.trim()
        val name = _uiState.value.userName
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
        persistCurrentChatIfNeeded()

        _isThinking.value = true
        viewModelScope.launch {
            val useNano = _uiState.value.useOnDeviceNano
            if (useNano) {
                val nanoResult = nanoClient.sendMessage(
                    userMessage = clean,
                    recentHistory = _uiState.value.messages,
                    userName = name,
                    onStatus = { status ->
                        _nanoStatus.value = status
                    }
                )
                _nanoStatus.value = "NANO READY // AWAITING COMMAND"
                _isThinking.value = false
                nanoResult.onSuccess { replyText ->
                    Log.d("JARVIS", "Nano onSuccess: '${replyText.take(60)}'")
                    _uiState.update {
                        it.copy(
                            messages = it.messages + HudMessage(
                                id = ++messageCounter,
                                isUser = false,
                                text = replyText
                            )
                        )
                    }
                    persistCurrentChatIfNeeded()
                    if (_uiState.value.autoSpeak) {
                        voiceManager.speak(replyText)
                    }
                }.onFailure { error ->
                    Log.e("JARVIS", "Nano onFailure", error)
                    val errorText = error.message ?: "On-device Gemini Nano failed, $name."
                    _uiState.update {
                        it.copy(
                            messages = it.messages + HudMessage(
                                id = ++messageCounter,
                                isUser = false,
                                text = "Apologies, $name. $errorText"
                            )
                        )
                    }
                    persistCurrentChatIfNeeded()
                }
                return@launch
            }

            val result = geminiClient.sendMessage(
                apiKey = _uiState.value.apiKey,
                userMessage = clean,
                previousInteractionId = previousInteractionId,
                userName = name
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
                persistCurrentChatIfNeeded()
                if (_uiState.value.autoSpeak) {
                    voiceManager.speak(reply.text)
                }
            }.onFailure { error ->
                Log.e("JARVIS", "Gemini onFailure", error)
                val errorText = error.message ?: "Unable to reach Gemini core, $name."
                _uiState.update {
                    it.copy(
                        messages = it.messages + HudMessage(
                            id = ++messageCounter,
                            isUser = false,
                            text = "Apologies, $name. $errorText"
                        )
                    )
                }
                persistCurrentChatIfNeeded()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
        nanoClient.close()
    }

    private fun formatGreeting(template: String, userName: String): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        return template
            .replace("{TIME}", timeGreeting)
            .replace("{NAME}", userName)
    }

    companion object {
        private const val PREFS_NAME = "jarvis_hud_prefs"
        private const val KEY_API = "gemini_api_key"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_USE_NANO = "use_on_device_nano"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_SPEAK_GREETING = "speak_greeting_on_launch"

        private val GREETING_TEMPLATES = listOf(
            "{TIME}, {NAME}. All systems are online and at your disposal.",
            "{TIME}, {NAME}. Neural cores nominal. What shall we tackle today?",
            "{TIME}, {NAME}. Diagnostic sweep complete, and everything appears remarkably intact.",
            "{TIME}, {NAME}. I've taken the liberty of warming up the arc reactor. How may I assist?",
            "{TIME}, {NAME}. Power levels optimal, sensors clear. Ready when you are.",
            "{TIME}, {NAME}. Always a pleasure. What's on the agenda?",
            "{TIME}, {NAME}. Interface calibrated and awaiting your instructions.",
            "{TIME}, {NAME}. Shall we build something brilliant, or merely put out a few fires?"
        )
    }
}
