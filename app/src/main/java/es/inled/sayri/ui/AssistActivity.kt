package es.inled.sayri.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import es.inled.sayri.api.SayriApiClient
import es.inled.sayri.data.ChatRoleMessage
import es.inled.sayri.databinding.LayoutSayriCajitaAssistBinding
import es.inled.sayri.tools.DeviceToolManager
import java.util.Locale

class AssistActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var binding: LayoutSayriCajitaAssistBinding
    private val apiClient by lazy { SayriApiClient() }
    private val toolManager by lazy { DeviceToolManager(this) }
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSayriCajitaAssistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        binding.assistBackdrop.setOnClickListener {
            finish()
        }

        binding.btnAssistSend.setOnClickListener {
            val text = binding.etAssistInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etAssistInput.setText("")
                processQuery(text)
            }
        }

        binding.assistOrb.setOnClickListener {
            startListening()
        }

        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        binding.assistOrb.state = OrbState.LISTENING
        binding.tvAssistStatus.text = "Escuchando a Sayri... 🎙️"

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.assistOrb.state = OrbState.THINKING
                    binding.tvAssistStatus.text = "Pensando..."
                }
                override fun onError(error: Int) {
                    binding.assistOrb.state = OrbState.IDLE
                    binding.tvAssistStatus.text = "¿En qué puedo ayudarte?"
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        processQuery(text)
                    } else {
                        binding.assistOrb.state = OrbState.IDLE
                        binding.tvAssistStatus.text = "¿En qué puedo ayudarte?"
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            startListening(intent)
        }
    }

    private fun processQuery(query: String) {
        binding.assistOrb.state = OrbState.THINKING
        binding.tvAssistStatus.text = query
        binding.tvAssistTranscription.visibility = View.VISIBLE
        binding.tvAssistTranscription.text = "..."

        val fullResponse = StringBuilder()

        apiClient.streamChat(
            messages = listOf(ChatRoleMessage("user", query)),
            onDelta = { delta ->
                runOnUiThread {
                    fullResponse.append(delta)
                    binding.tvAssistTranscription.text = fullResponse.toString()
                }
            },
            onDone = { finalTxt ->
                runOnUiThread {
                    binding.assistOrb.state = OrbState.SPEAKING
                    val res = if (finalTxt.isNotEmpty()) finalTxt else fullResponse.toString()
                    binding.tvAssistTranscription.text = res
                    speakAndClose(res)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.assistOrb.state = OrbState.IDLE
                    binding.tvAssistTranscription.text = "Error: ${err.message}"
                }
            }
        )
    }

    private fun speakAndClose(text: String) {
        val cleanSpeech = text.replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("[*#_~]"), "")
            .trim()
        if (cleanSpeech.isNotEmpty()) {
            tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, "sayri-assist")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
