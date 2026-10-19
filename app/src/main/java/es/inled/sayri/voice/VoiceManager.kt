package es.inled.sayri.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onStateChange: (isListening: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isLiveMode: Boolean = false
    private var isSpeaking: Boolean = false

    init {
        tts = TextToSpeech(context, this)
        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStateChange(true)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {
                    onAudioLevel(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    onStateChange(false)
                }

                override fun onError(error: Int) {
                    onStateChange(false)
                    // If live mode is active and not speaking, rearm after brief delay
                    if (isLiveMode && !isSpeaking) {
                        mainHandler.postDelayed({ startListening() }, 1500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    onStateChange(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        onSpeechResult(text)
                    } else if (isLiveMode && !isSpeaking) {
                        mainHandler.postDelayed({ startListening() }, 1000)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun startListening() {
        if (isSpeaking) {
            tts?.stop()
            isSpeaking = false
        }
        if (speechRecognizer == null) {
            setupSpeechRecognizer()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            setupSpeechRecognizer()
            speechRecognizer?.startListening(intent)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onStateChange(false)
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val cleanSpeech = text.replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("[*#_~]"), "")
            .trim()

        if (cleanSpeech.isEmpty()) {
            onDone?.invoke()
            return
        }

        isSpeaking = true
        val utteranceId = "sayri-tts-${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    onDone?.invoke()
                    if (isLiveMode) {
                        startListening()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    onDone?.invoke()
                    if (isLiveMode) {
                        startListening()
                    }
                }
            }
        })

        tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    fun destroy() {
        isLiveMode = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
