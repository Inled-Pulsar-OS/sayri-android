package es.inled.sayri.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import es.inled.sayri.R
import es.inled.sayri.api.SayriApiClient
import es.inled.sayri.data.ChatRoleMessage
import es.inled.sayri.databinding.LayoutSayriCajitaAssistBinding
import es.inled.sayri.service.SayriAccessibilityService
import es.inled.sayri.tools.DeviceToolManager
import es.inled.sayri.voice.VoiceManager
import java.io.ByteArrayOutputStream

class AssistActivity : ComponentActivity() {

    private lateinit var binding: LayoutSayriCajitaAssistBinding
    private val apiClient by lazy { SayriApiClient() }
    private val toolManager by lazy { DeviceToolManager(this) }

    private lateinit var voiceManager: VoiceManager

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            handleCapturedPhoto(bitmap)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSayriCajitaAssistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceManager = VoiceManager(
            context = this,
            onSpeechResult = { text ->
                handleUserQuery(text)
            },
            onAudioLevel = { rms ->
                binding.assistOrb.setAudioLevel(rms)
            },
            onStateChange = { isListening ->
                if (isListening) {
                    binding.assistOrb.state = OrbState.LISTENING
                    binding.tvAssistStatus.text = "Escuchando... 🎙️"
                } else {
                    if (binding.assistOrb.state == OrbState.LISTENING) {
                        binding.assistOrb.state = OrbState.IDLE
                    }
                }
            }
        )

        setupListeners()

        // Auto start listening on assist invocation
        binding.assistOrb.postDelayed({
            voiceManager.startListening()
        }, 300)
    }

    private fun setupListeners() {
        binding.assistBackdrop.setOnClickListener {
            finish()
        }

        binding.btnAssistVoice.setOnClickListener {
            voiceManager.startListening()
        }

        binding.assistOrb.setOnClickListener {
            voiceManager.startListening()
        }

        binding.btnAssistSend.setOnClickListener {
            val text = binding.etAssistInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etAssistInput.setText("")
                handleUserQuery(text)
            }
        }

        // Live Mode toggle
        binding.btnAssistLiveMode.setOnClickListener {
            voiceManager.isLiveMode = !voiceManager.isLiveMode
            if (voiceManager.isLiveMode) {
                binding.btnAssistLiveMode.text = "Modo Live: ON 🟢"
                binding.btnAssistLiveMode.setTextColor(getColor(R.color.cajita_neon_cyan))
                voiceManager.startListening()
            } else {
                binding.btnAssistLiveMode.text = "Modo Live: OFF"
                binding.btnAssistLiveMode.setTextColor(getColor(R.color.cajita_text_secondary))
            }
        }

        // Circle to Search (Rodear para buscar)
        binding.btnAssistCircleSearch.setOnClickListener {
            startCircleToSearch()
        }

        // Camera Vision
        binding.btnAssistCamera.setOnClickListener {
            takePictureLauncher.launch(null)
        }

        // Circle Search Callback
        binding.circleSearchOverlay.onAreaSelected = { bounds ->
            binding.circleSearchOverlay.visibility = View.GONE
            binding.circleSearchOverlay.clear()

            // Dump accessibility nodes in the circled area
            val snapshot = SayriAccessibilityService.instance?.dumpScreenHierarchy("Buscar en zona rodeada")
            val targetNodes = snapshot?.elements?.filter { elem ->
                elem.bounds.size == 4 &&
                        elem.bounds[0] >= bounds.left - 50 &&
                        elem.bounds[2] <= bounds.right + 50 &&
                        elem.bounds[1] >= bounds.top - 50 &&
                        elem.bounds[3] <= bounds.bottom + 50
            } ?: emptyList()

            val textContext = targetNodes.joinToString(" ") { it.text ?: it.contentDescription ?: "" }.trim()
            val query = if (textContext.isNotEmpty()) {
                "Buscar y explicar este elemento rodeado en pantalla: \"$textContext\""
            } else {
                "Analiza lo que he rodeado en pantalla en las coordenadas [${bounds.left.toInt()}, ${bounds.top.toInt()}, ${bounds.right.toInt()}, ${bounds.bottom.toInt()}]"
            }
            handleUserQuery(query)
        }
    }

    private fun startCircleToSearch() {
        binding.circleSearchOverlay.visibility = View.VISIBLE
        Toast.makeText(this, "Rodea con tu dedo lo que deseas buscar o analizar", Toast.LENGTH_SHORT).show()
    }

    private fun handleCapturedPhoto(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val byteArray = stream.toByteArray()
        val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

        val prompt = "He tomado una fotografía de mi entorno. Analízala en detalle y dime qué ves y en qué me puedes ayudar."
        processQuery(prompt)
    }

    private fun handleUserQuery(query: String) {
        val lower = query.lowercase().trim()

        // 1. Direct Local Tools (Dicio Actions)
        if (lower.startsWith("abre ") || lower.startsWith("abrir ")) {
            val appTarget = query.substring(5).trim()
            if (toolManager.openApp(appTarget)) {
                binding.tvAssistStatus.text = "Abriendo $appTarget..."
                voiceManager.speak("Abriendo $appTarget") {
                    finish()
                }
                return
            }
        } else if (lower.startsWith("linterna ")) {
            val on = lower.contains("enciende") || lower.contains("activa") || lower.contains("on")
            toolManager.setFlashlight(on)
            val msg = if (on) "Linterna encendida." else "Linterna apagada."
            binding.tvAssistStatus.text = msg
            voiceManager.speak(msg) {
                if (!voiceManager.isLiveMode) finish()
            }
            return
        }

        // 2. Stream to Sayri Core on PC
        processQuery(query)
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

                    voiceManager.speak(res) {
                        runOnUiThread {
                            binding.assistOrb.state = OrbState.IDLE
                            if (!voiceManager.isLiveMode) {
                                binding.assistOrb.postDelayed({ finish() }, 2000)
                            }
                        }
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.assistOrb.state = OrbState.IDLE
                    binding.tvAssistTranscription.text = "⚠️ Error: ${err.message}"
                }
            }
        )
    }

    override fun onDestroy() {
        voiceManager.destroy()
        super.onDestroy()
    }
}
