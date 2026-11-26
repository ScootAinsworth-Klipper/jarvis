package com.stark.jarvis

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

class JarvisVoiceManager(
    context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onSpeechError: (String) -> Unit,
    private val onTtsReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ttsReady = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _voiceLabel = MutableStateFlow("EN-GB INITIALIZING")
    val voiceLabel: StateFlow<String> = _voiceLabel.asStateFlow()

    private var lastSubmittedText: String = ""

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            _voiceLabel.value = "TTS UNAVAILABLE"
            return
        }
        val engine = tts ?: return
        val langResult = engine.setLanguage(Locale.UK)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            _voiceLabel.value = "EN-GB MISSING"
        } else {
            val selected = selectBritishMaleVoice(engine)
            if (selected != null) {
                engine.voice = selected
                _voiceLabel.value = "EN-GB MALE (${selected.name.takeLast(8).uppercase()})"
            } else {
                _voiceLabel.value = "EN-GB DEFAULT"
            }
        }

        engine.setPitch(0.92f)
        engine.setSpeechRate(1.0f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
            }
        })
        ttsReady = true
        onTtsReady()
    }

    private fun selectBritishMaleVoice(engine: TextToSpeech): Voice? {
        val voices = engine.voices ?: return null
        val ukVoices = voices.filter { voice ->
            voice.locale.language.equals("en", ignoreCase = true) &&
                voice.locale.country.equals("GB", ignoreCase = true)
        }
        if (ukVoices.isEmpty()) return null

        val maleHints = listOf("gbd", "rjs", "male", "daniel", "arthur", "oliver", "george", "ryan")
        val preferred = ukVoices.firstOrNull { voice ->
            val lower = voice.name.lowercase(Locale.US)
            maleHints.any { hint -> lower.contains(hint) }
        }
        return preferred ?: ukVoices.firstOrNull { !it.isNetworkConnectionRequired } ?: ukVoices.first()
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onSpeechError("Speech recognition service is unavailable on this device, Sir.")
            return
        }
        stopSpeaking()
        lastSubmittedText = ""
        _partialTranscript.value = ""

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        _isListening.value = true
        _audioLevel.value = 0.15f
        Log.d("JARVIS", "startListening() called")
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        Log.d("JARVIS", "stopListening() called, isListening=${_isListening.value}")
        if (_isListening.value) {
            speechRecognizer?.stopListening()
        }
    }

    fun speak(text: String) {
        Log.d("JARVIS", "speak() called, ttsReady=$ttsReady, len=${text.length}")
        if (!ttsReady) return
        val cleanText = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()
        if (cleanText.isEmpty()) return

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        if (ttsReady) {
            tts?.stop()
        }
        _isSpeaking.value = false
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
        }

        override fun onBeginningOfSpeech() {
            _audioLevel.value = 0.35f
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _audioLevel.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            _isListening.value = false
            _audioLevel.value = 0f
        }

        override fun onError(error: Int) {
            Log.e("JARVIS", "SpeechRecognizer.onError($error), partial='${_partialTranscript.value}'")
            _isListening.value = false
            _audioLevel.value = 0f
            val partial = _partialTranscript.value.trim()
            if (error == SpeechRecognizer.ERROR_NO_MATCH && partial.isNotEmpty() && partial != lastSubmittedText) {
                lastSubmittedText = partial
                onSpeechResult(partial)
                return
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                onSpeechError("Didn't catch that, Sir. Please speak clearly.")
            } else if (error != SpeechRecognizer.ERROR_CLIENT) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech network unavailable."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected, Sir."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy, try again."
                    else -> "Voice recognition error ($error)."
                }
                onSpeechError(message)
            }
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _audioLevel.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            Log.d("JARVIS", "SpeechRecognizer.onResults: '$text'")
            if (text.isNotEmpty() && text != lastSubmittedText) {
                lastSubmittedText = text
                _partialTranscript.value = ""
                onSpeechResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                _partialTranscript.value = text
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
