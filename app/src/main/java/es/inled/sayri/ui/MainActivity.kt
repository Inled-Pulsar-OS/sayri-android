package es.inled.sayri.ui

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import es.inled.sayri.R
import es.inled.sayri.SayriApp
import es.inled.sayri.api.SayriApiClient
import es.inled.sayri.data.ChatMessage
import es.inled.sayri.data.ChatRoleMessage
import es.inled.sayri.databinding.ActivityMainBinding
import es.inled.sayri.databinding.DialogSettingsBinding
import es.inled.sayri.net.NetworkDiscovery
import es.inled.sayri.service.SayriAccessibilityService
import es.inled.sayri.tools.DeviceToolManager
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val apiClient by lazy { SayriApiClient() }
    private val toolManager by lazy { DeviceToolManager(this) }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var currentSse: EventSource? = null

    private val conversationHistory = mutableListOf<ChatRoleMessage>()

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechToText()
        } else {
            Toast.makeText(this, "Permiso de micrófono necesario", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter

        tts = TextToSpeech(this, this)
        initSpeechRecognizer()

        setupListeners()
        setupSuggestions()

        refreshServerStatus()
        scanNetworkForSayri()
    }

    private fun setupListeners() {
        binding.mainOrb.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpeechToText()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnVoice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpeechToText()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etMessage.setText("")
                handleUserQuery(text)
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun setupSuggestions() {
        binding.chipScreen.setOnClickListener {
            performScreenPerception()
        }

        binding.chipIdea.setOnClickListener {
            binding.etMessage.setText("Guarda esta idea: ")
            binding.etMessage.setSelection(binding.etMessage.text.length)
            binding.etMessage.requestFocus()
        }

        binding.chipTomorrow.setOnClickListener {
            binding.etMessage.setText("Recuérdame mañana: ")
            binding.etMessage.setSelection(binding.etMessage.text.length)
            binding.etMessage.requestFocus()
        }

        binding.chipTimer.setOnClickListener {
            toolManager.setTimer(300, "Temporizador 5m")
            adapter.addMessage(ChatMessage(author = "Sayri", content = "⏱️ Temporizador de 5 minutos iniciado.", isUser = false))
        }
    }

    private fun scanNetworkForSayri() {
        lifecycleScope.launch {
            val hosts = NetworkDiscovery.discoverSayriHosts(this@MainActivity)
            if (hosts.isNotEmpty()) {
                val host = hosts.first()
                binding.layoutDiscoveryBanner.visibility = View.VISIBLE
                binding.tvDiscoveredHost.text = "Detectado: ${host.name} (${host.ip})"
                binding.btnQuickConnect.setOnClickListener {
                    SayriApp.instance.serverHost = host.ip
                    binding.layoutDiscoveryBanner.visibility = View.GONE
                    refreshServerStatus()
                    Toast.makeText(this@MainActivity, "Conectado a ${host.ip}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun refreshServerStatus() {
        lifecycleScope.launch {
            val result = apiClient.getStatus()
            result.onSuccess { status ->
                val accState = if (SayriAccessibilityService.isRunning) "Accesibilidad: ON" else "Accesibilidad: OFF"
                binding.tvStatus.text = "Pulsar OS • En línea (${status.activeAgent}) • $accState"
                binding.mainOrb.state = OrbState.IDLE
            }.onFailure {
                val accState = if (SayriAccessibilityService.isRunning) "Accesibilidad: ON" else "Accesibilidad: OFF"
                binding.tvStatus.text = "Desconectado de Sayri PC ($accState)"
                binding.mainOrb.state = OrbState.IDLE
            }
        }
    }

    private fun handleUserQuery(query: String) {
        val lower = query.lowercase().trim()

        // 1. Direct Dicio-style local tools
        if (lower.startsWith("abre ") || lower.startsWith("abrir ")) {
            val appTarget = query.substring(5).trim()
            if (toolManager.openApp(appTarget)) {
                adapter.addMessage(ChatMessage(author = "Tú", content = query, isUser = true))
                adapter.addMessage(ChatMessage(author = "Sayri", content = "Abriendo $appTarget...", isUser = false))
                return
            }
        } else if (lower.startsWith("linterna ")) {
            val on = lower.contains("enciende") || lower.contains("activa") || lower.contains("on")
            toolManager.setFlashlight(on)
            adapter.addMessage(ChatMessage(author = "Tú", content = query, isUser = true))
            adapter.addMessage(ChatMessage(author = "Sayri", content = if (on) "Linterna encendida." else "Linterna apagada.", isUser = false))
            return
        }

        // 2. Stream to Sayri Core on PC
        sendMessageToSayri(query)
    }

    private fun sendMessageToSayri(text: String) {
        adapter.addMessage(ChatMessage(author = "Tú", content = text, isUser = true))
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        conversationHistory.add(ChatRoleMessage("user", text))

        val sayriMsg = ChatMessage(author = "Sayri", content = "...", isUser = false)
        adapter.addMessage(sayriMsg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        binding.mainOrb.state = OrbState.THINKING
        val fullAnswer = StringBuilder()

        currentSse?.cancel()
        currentSse = apiClient.streamChat(
            messages = conversationHistory,
            onDelta = { delta ->
                runOnUiThread {
                    fullAnswer.append(delta)
                    adapter.updateLastMessage(fullAnswer.toString())
                    binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
                }
            },
            onDone = { finalAnswer ->
                runOnUiThread {
                    binding.mainOrb.state = OrbState.SPEAKING
                    val finalTxt = if (finalAnswer.isNotEmpty()) finalAnswer else fullAnswer.toString()
                    adapter.updateLastMessage(finalTxt)
                    conversationHistory.add(ChatRoleMessage("assistant", finalTxt))
                    speakOut(finalTxt)
                }
            },
            onError = { err ->
                runOnUiThread {
                    binding.mainOrb.state = OrbState.IDLE
                    adapter.updateLastMessage("⚠️ Error: ${err.localizedMessage}")
                }
            }
        )
    }

    private fun performScreenPerception() {
        val service = SayriAccessibilityService.instance
        if (service == null) {
            AlertDialog.Builder(this)
                .setTitle("Servicio de Accesibilidad Desactivado")
                .setMessage("Habilita el Servicio de Accesibilidad de Sayri para percibir tu pantalla.")
                .setPositiveButton("Abrir Ajustes") { dialog: DialogInterface, which: Int ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        lifecycleScope.launch {
            binding.mainOrb.state = OrbState.THINKING
            val snapshot = service.dumpScreenHierarchy(userIntent = "Analizar pantalla y guiar al usuario")
            adapter.addMessage(ChatMessage(author = "Tú", content = "📱 Percibiendo pantalla de: ${snapshot.packageName}", isUser = true))
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

            val res = apiClient.sendScreenSnapshot(snapshot)
            res.onSuccess { decision ->
                binding.mainOrb.state = OrbState.SPEAKING
                adapter.addMessage(ChatMessage(author = "Sayri", content = decision, isUser = false))
                speakOut(decision)
            }.onFailure { err ->
                binding.mainOrb.state = OrbState.IDLE
                adapter.addMessage(ChatMessage(author = "Sayri", content = "⚠️ Error analizando pantalla: ${err.message}", isUser = false))
            }
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etHost.setText(SayriApp.instance.serverHost)
        dialogBinding.etPort.setText(SayriApp.instance.serverPort.toString())
        dialogBinding.etApiKey.setText(SayriApp.instance.apiKey)

        dialogBinding.btnScanNetwork.setOnClickListener {
            dialogBinding.tvScanResult.visibility = View.VISIBLE
            dialogBinding.tvScanResult.text = "Escaneando red local..."
            lifecycleScope.launch {
                val hosts = NetworkDiscovery.discoverSayriHosts(this@MainActivity)
                if (hosts.isNotEmpty()) {
                    val host = hosts.first()
                    dialogBinding.tvScanResult.text = "✅ Encontrado ${host.name} en ${host.ip}"
                    dialogBinding.etHost.setText(host.ip)
                    dialogBinding.etPort.setText(host.port.toString())
                } else {
                    dialogBinding.tvScanResult.text = "No se encontraron otros dispositivos Sayri en la subred."
                }
            }
        }

        dialogBinding.btnDefaultAssistant.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Abre Ajustes -> Aplicaciones predeterminadas -> Asistente digital", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialogBinding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Guardar") { dialog: DialogInterface, which: Int ->
                val newHost = dialogBinding.etHost.text.toString().trim()
                val newPort = dialogBinding.etPort.text.toString().toIntOrNull() ?: SayriApp.DEFAULT_PORT
                val newKey = dialogBinding.etApiKey.text.toString().trim()

                SayriApp.instance.serverHost = if (newHost.isNotEmpty()) newHost else SayriApp.DEFAULT_HOST
                SayriApp.instance.serverPort = newPort
                SayriApp.instance.apiKey = newKey

                refreshServerStatus()
                Toast.makeText(this, "Ajustes actualizados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.mainOrb.state = OrbState.LISTENING
                    binding.tvStatus.text = "Escuchando voz... 🎙️"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.mainOrb.state = OrbState.THINKING
                    binding.tvStatus.text = "Procesando..."
                }
                override fun onError(error: Int) {
                    binding.mainOrb.state = OrbState.IDLE
                    refreshServerStatus()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        handleUserQuery(text)
                    } else {
                        binding.mainOrb.state = OrbState.IDLE
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "ES")
        }
    }

    private fun speakOut(text: String) {
        val cleanSpeech = text.replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("[*#_~]"), "")
            .trim()
        if (cleanSpeech.isNotEmpty()) {
            tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, "sayri-tts-${System.currentTimeMillis()}")
        }
    }

    override fun onDestroy() {
        currentSse?.cancel()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
