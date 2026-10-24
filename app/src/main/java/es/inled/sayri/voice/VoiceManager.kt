package es.inled.sayri.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import es.inled.sayri.SayriApp
import es.inled.sayri.api.SayriApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onStateChange: (isListening: Boolean) -> Unit,
    var onRequestIntentRecognition: ((Intent) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private val tag = "SayriVoiceManager"
    private val apiClient by lazy { SayriApiClient() }
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    var onPartialSpeechResult: ((String) -> Unit)? = null
    var onSpeakingStateChange: ((Boolean) -> Unit)? = null
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

    var isLiveMode: Boolean = false
    var isSpeaking: Boolean = false
        private set(value) {
            field = value
            mainHandler.post { onSpeakingStateChange?.invoke(value) }
        }
    var isCurrentlyListening: Boolean = false
        private set

    @Volatile private var isTtsInitialized: Boolean = false

    fun isSpeakingOrPlaying(): Boolean {
        return isSpeaking || (mediaPlayer?.isPlaying == true)
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording: Boolean = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    init {
        tts = TextToSpeech(context, this)
    }

    fun startListening(allowIntentFallback: Boolean = true) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO permission not granted.")
            return
        }

        // Under NO circumstance reactivate mic while TTS voice dictation is playing!
        if (isSpeakingOrPlaying()) {
            Log.d(tag, "startListening ignored: speech playback is currently active.")
            return
        }

        if (isCurrentlyListening) {
            return
        }

        // 1. Try SpeechRecognizer first for instant, word-by-word streaming recognition
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            startSpeechRecognizer()
        } else {
            startAudioRecordListening(allowIntentFallback)
        }
    }

    private fun startSpeechRecognizer() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    val serviceIntent = Intent("android.speech.RecognitionService")
                    val resolveInfos = context.packageManager.queryIntentServices(serviceIntent, 0)
                    val componentName = resolveInfos.firstOrNull { it.serviceInfo.packageName != context.packageName }?.serviceInfo?.let {
                        android.content.ComponentName(it.packageName, it.name)
                    }
                    val recognizer = if (componentName != null) {
                        Log.i(tag, "Binding SpeechRecognizer to Component: $componentName")
                        android.speech.SpeechRecognizer.createSpeechRecognizer(context, componentName)
                    } else {
                        android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                    }
                    speechRecognizer = recognizer.apply {
                        setRecognitionListener(object : android.speech.RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                isCurrentlyListening = true
                                mainHandler.post { onStateChange(true) }
                            }
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {
                                mainHandler.post { onAudioLevel(rmsdB) }
                            }
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}
                            override fun onError(error: Int) {
                                Log.w(tag, "SpeechRecognizer error code: $error")
                                isCurrentlyListening = false
                                mainHandler.post { onStateChange(false) }
                                if (isSpeakingOrPlaying()) {
                                    return
                                }
                                if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || 
                                    error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                    return
                                }
                                try { speechRecognizer?.destroy() } catch (_: Throwable) {}
                                speechRecognizer = null
                                startAudioRecordListening(allowIntentFallback = false)
                            }
                            override fun onResults(results: Bundle?) {
                                isCurrentlyListening = false
                                mainHandler.post { onStateChange(false) }
                                val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim() ?: ""
                                if (text.isNotEmpty()) {
                                    mainHandler.post { onSpeechResult(text) }
                                }
                            }
                            override fun onPartialResults(partialResults: Bundle?) {
                                val matches = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                                val text = matches?.firstOrNull()?.trim() ?: ""
                                if (text.isNotEmpty()) {
                                    mainHandler.post { onPartialSpeechResult?.invoke(text) }
                                }
                            }
                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }
                isCurrentlyListening = true
                mainHandler.post { onStateChange(true) }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start SpeechRecognizer", e)
                try { speechRecognizer?.destroy() } catch (_: Throwable) {}
                speechRecognizer = null
                startAudioRecordListening(allowIntentFallback = false)
            }
        }
    }

    private fun startAudioRecordListening(allowIntentFallback: Boolean = false) {
        if (isSpeakingOrPlaying()) {
            Log.d(tag, "startAudioRecordListening ignored: TTS playback is active.")
            return
        }
        if (isRecording) return

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = max(minBufSize, 4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                if (allowIntentFallback) {
                    launchIntentRecognizer()
                }
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            isCurrentlyListening = true
            mainHandler.post { onStateChange(true) }

            recordingThread = Thread {
                recordLoop(allowIntentFallback)
            }.apply { start() }

        } catch (e: Throwable) {
            Log.e(tag, "Error starting AudioRecord", e)
            audioRecord?.release()
            audioRecord = null
            isRecording = false
            isCurrentlyListening = false
            mainHandler.post { onStateChange(false) }
            if (allowIntentFallback) {
                launchIntentRecognizer()
            }
        }
    }

    private fun recordLoop(allowIntentFallback: Boolean) {
        val shortBuffer = ShortArray(1024)
        val byteStream = ByteArrayOutputStream()
        var speechDetected = false
        var speechStartTime = 0L
        var lastSpeechTime = 0L
        val startTime = System.currentTimeMillis()

        try {
            while (isRecording) {
                val readShorts = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                if (readShorts > 0) {
                    val bytes = ByteArray(readShorts * 2)
                    var sum = 0.0
                    for (i in 0 until readShorts) {
                        val s = shortBuffer[i]
                        sum += s * s
                        bytes[i * 2] = (s.toInt() and 0xff).toByte()
                        bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
                    }
                    byteStream.write(bytes)

                    val rms = sqrt(sum / readShorts).toFloat()
                    val now = System.currentTimeMillis()

                    val db = if (rms > 1f) (20 * log10(rms.toDouble())).toFloat() else 0f
                    mainHandler.post { onAudioLevel(db) }

                    if (rms > 600f) {
                        if (!speechDetected) {
                            speechDetected = true
                            speechStartTime = now
                        }
                        lastSpeechTime = now
                    }

                    if (speechDetected && (now - lastSpeechTime > 1200L) && (now - speechStartTime > 600L)) {
                        Log.d(tag, "Silence timeout detected, ending utterance")
                        break
                    }

                    if (now - startTime > 15000L) {
                        Log.d(tag, "Max recording duration reached")
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Error in recording loop", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Throwable) {}
            audioRecord = null
            isRecording = false
            isCurrentlyListening = false
            mainHandler.post { onStateChange(false) }

            val pcmData = byteStream.toByteArray()
            if (pcmData.isNotEmpty() && speechDetected) {
                val wavBytes = pcmToWav(pcmData, sampleRate, 1, 16)
                sendForTranscription(wavBytes, allowIntentFallback)
            } else {
                Log.d(tag, "No speech detected in audio stream")
                if (isLiveMode) {
                    mainHandler.postDelayed({
                        if (isLiveMode && !isSpeaking && !isCurrentlyListening) {
                            startListening(allowIntentFallback = false)
                        }
                    }, 500)
                }
            }
        }
    }

    private fun sendForTranscription(wavBytes: ByteArray, allowIntentFallback: Boolean) {
        scope.launch {
            val result = apiClient.transcribeAudio(wavBytes)
            result.onSuccess { text ->
                if (text.isNotBlank()) {
                    mainHandler.post { onSpeechResult(text) }
                } else {
                    Log.d(tag, "Transcription returned empty string")
                }
            }.onFailure { err ->
                Log.w(tag, "Remote Whisper transcription failed: ${err.message}")
                if (allowIntentFallback) {
                    mainHandler.post { launchIntentRecognizer() }
                }
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Throwable) {}
        }
        isRecording = false
        isCurrentlyListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
        mainHandler.post { onStateChange(false) }
    }

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = ((totalDataLen shr 8) and 0xffL).toByte()
        header[6] = ((totalDataLen shr 16) and 0xffL).toByte()
        header[7] = ((totalDataLen shr 24) and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate.toLong() and 0xffL).toByte()
        header[25] = ((sampleRate.toLong() shr 8) and 0xffL).toByte()
        header[26] = ((sampleRate.toLong() shr 16) and 0xffL).toByte()
        header[27] = ((sampleRate.toLong() shr 24) and 0xffL).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = ((byteRate shr 8) and 0xffL).toByte()
        header[30] = ((byteRate shr 16) and 0xffL).toByte()
        header[31] = ((byteRate shr 24) and 0xffL).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xffL).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xffL).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xffL).toByte()

        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        return wav
    }

    fun launchIntentRecognizer() {
        stopSpeaking()
        isCurrentlyListening = true
        onStateChange(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Sayri...")
        }
        try {
            onRequestIntentRecognition?.invoke(intent)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to invoke intent recognizer", e)
            isCurrentlyListening = false
            onStateChange(false)
        }
    }

    fun onIntentRecognizerFinished() {
        isCurrentlyListening = false
        onStateChange(false)
    }

    fun handleIntentResult(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            onSpeechResult(clean)
        }
    }

    fun stopAll() {
        stopListening()
        stopSpeaking()
        try {
            scope.coroutineContext.cancelChildren()
        } catch (_: Throwable) {}
    }

    fun stopSpeaking() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Throwable) {}
        try {
            tts?.stop()
        } catch (_: Throwable) {}
        isSpeaking = false
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

        stopListening()
        isSpeaking = true

        val host = SayriApp.instance.serverHost
        val token = SayriApp.instance.apiKey
        val encodedText = URLEncoder.encode(cleanSpeech, "UTF-8")
        val audioUrl = "http://$host:8765/v1/audio/speech?text=$encodedText"

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build()
                )
                val headers = if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap()
                setDataSource(context, Uri.parse(audioUrl), headers)
                setOnPreparedListener { mp ->
                    isSpeaking = true
                    mp.start()
                }
                setOnCompletionListener { mp ->
                    isSpeaking = false
                    try { mp.release() } catch (_: Throwable) {}
                    mediaPlayer = null
                    mainHandler.post {
                        onDone?.invoke()
                        if (isLiveMode) {
                            mainHandler.postDelayed({
                                if (isLiveMode && !isSpeaking && !isCurrentlyListening) {
                                    startListening(allowIntentFallback = false)
                                }
                            }, 500)
                        }
                    }
                }
                setOnErrorListener { mp, _, _ ->
                    try { mp.release() } catch (_: Throwable) {}
                    mediaPlayer = null
                    speakLocalTts(cleanSpeech, onDone)
                    true
                }
                prepareAsync()
            }
        } catch (e: Throwable) {
            Log.w(tag, "Remote TTS unavailable, falling back to local TTS", e)
            speakLocalTts(cleanSpeech, onDone)
        }
    }

    private fun speakLocalTts(cleanSpeech: String, onDone: (() -> Unit)? = null) {
        if (!isTtsInitialized) {
            mainHandler.postDelayed({
                if (isTtsInitialized) {
                    speakLocalTts(cleanSpeech, onDone)
                } else {
                    isSpeaking = false
                    onDone?.invoke()
                }
            }, 300)
            return
        }

        isSpeaking = true
        val utteranceId = "sayri-tts-${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    onDone?.invoke()
                    if (isLiveMode) {
                        mainHandler.postDelayed({
                            if (isLiveMode && !isSpeakingOrPlaying() && !isCurrentlyListening) {
                                startListening(allowIntentFallback = false)
                            }
                        }, 500)
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post {
                    onDone?.invoke()
                }
            }
        })
        val res = tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            isSpeaking = false
            onDone?.invoke()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isTtsInitialized = true
        }
    }

    fun destroy() {
        stopListening()
        stopSpeaking()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Throwable) {}
        }
        try {
            tts?.shutdown()
        } catch (_: Throwable) {}
    }
}
