package es.inled.sayri.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
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
import es.inled.sayri.service.SayriAccessibilityService
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val apiClient by lazy { SayriApiClient() }

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
            Toast.makeText(this, "Permiso de micrófono necesario para hablar", Toast.LENGTH_SHORT).show()
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
        refreshServerStatus()
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etMessage.setText("")
                sendMessageToSayri(text)
            }
        }

        binding.btnVoice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpeechToText()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnIdea.setOnClickListener {
            val currentText = binding.etMessage.text.toString().trim()
            if (currentText.isNotEmpty()) {
                binding.etMessage.setText("")
                saveIdea(currentText)
            } else {
                promptSingleInput("💡 Guardar Idea", "Escribe la idea a almacenar en la memoria de Sayri:") {
                    saveIdea(it)
                }
            }
        }

        binding.btnTomorrow.setOnClickListener {
            val currentText = binding.etMessage.text.toString().trim()
            if (currentText.isNotEmpty()) {
                binding.etMessage.setText("")
                saveTomorrowTask(currentText)
            } else {
                promptSingleInput("📅 Recordar Mañana", "¿Qué quieres que Sayri te recuerde mañana?") {
                    saveTomorrowTask(it)
                }
            }
        }

        binding.btnScreenPerception.setOnClickListener {
            performScreenPerception()
        }
    }

    private fun refreshServerStatus() {
        lifecycleScope.launch {
            binding.tvStatus.text = "Conectando a Sayri (${SayriApp.instance.serverHost})..."
            val result = apiClient.getStatus()
            result.onSuccess { status ->
                val accState = if (SayriAccessibilityService.isRunning) "Accesibilidad: ON ✅" else "Accesibilidad: OFF ⚠️"
                binding.tvStatus.text = "Conectado (${status.activeAgent}) • $accState"
            }.onFailure { err ->
                val accState = if (SayriAccessibilityService.isRunning) "Accesibilidad: ON" else "Accesibilidad: OFF"
                binding.tvStatus.text = "Sin conexión con Sayri PC ($accState)"
            }
        }
    }

    private fun sendMessageToSayri(text: String) {
        // Add User message to UI
        adapter.addMessage(ChatMessage(author = "Tú", content = text, isUser = true))
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        conversationHistory.add(ChatRoleMessage("user", text))

        // Placeholder for Sayri's streaming answer
        val sayriMsg = ChatMessage(author = "Sayri", content = "...", isUser = false)
        adapter.addMessage(sayriMsg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

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
                    val finalTxt = if (finalAnswer.isNotEmpty()) finalAnswer else fullAnswer.toString()
                    adapter.updateLastMessage(finalTxt)
                    conversationHistory.add(ChatRoleMessage("assistant", finalTxt))
                    speakOut(finalTxt)
                }
            },
            onError = { err ->
                runOnUiThread {
                    val errTxt = "⚠️ Error de conexión: ${err.localizedMessage}"
                    adapter.updateLastMessage(errTxt)
                }
            }
        )
    }

    private fun saveIdea(ideaText: String) {
        lifecycleScope.launch {
            adapter.addMessage(ChatMessage(author = "Tú", content = "💡 Guardar Idea: $ideaText", isUser = true))
            val res = apiClient.saveIdea(ideaText)
            res.onSuccess {
                adapter.addMessage(ChatMessage(author = "Sayri", content = "✅ Idea guardada en tu memoria persistente.", isUser = false))
                speakOut("Idea guardada en tu memoria.")
            }.onFailure {
                adapter.addMessage(ChatMessage(author = "Sayri", content = "❌ Error guardando idea: ${it.message}", isUser = false))
            }
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun saveTomorrowTask(taskText: String) {
        lifecycleScope.launch {
            adapter.addMessage(ChatMessage(author = "Tú", content = "📅 Para Mañana: $taskText", isUser = true))
            val res = apiClient.saveTomorrowTask(taskText)
            res.onSuccess {
                adapter.addMessage(ChatMessage(author = "Sayri", content = "✅ Tarea añadida a la agenda de mañana.", isUser = false))
                speakOut("Tarea anotada para mañana.")
            }.onFailure {
                adapter.addMessage(ChatMessage(author = "Sayri", content = "❌ Error agendando tarea: ${it.message}", isUser = false))
            }
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun performScreenPerception() {
        val service = SayriAccessibilityService.instance
        if (service == null) {
            AlertDialog.Builder(this)
                .setTitle("Servicio de Accesibilidad Desactivado")
                .setMessage("Para percibir y ayudarte en pantalla, habilita el Servicio de Accesibilidad de Sayri en los Ajustes del sistema.")
                .setPositiveButton("Abrir Ajustes") { dialog: android.content.DialogInterface, which: Int ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        lifecycleScope.launch {
            val snapshot = service.dumpScreenHierarchy(userIntent = "Analizar pantalla y guiar al usuario")
            adapter.addMessage(ChatMessage(author = "Tú", content = "📱 Percibiendo pantalla de: ${snapshot.packageName}", isUser = true))
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

            val res = apiClient.sendScreenSnapshot(snapshot)
            res.onSuccess { decision ->
                adapter.addMessage(ChatMessage(author = "Sayri", content = decision, isUser = false))
                speakOut(decision)
            }.onFailure { err ->
                adapter.addMessage(ChatMessage(author = "Sayri", content = "⚠️ Error analizando pantalla: ${err.message}", isUser = false))
            }
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun promptSingleInput(title: String, message: String, onText: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "Escribe aquí..."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Guardar") { dialog: android.content.DialogInterface, which: Int ->
                val txt = input.text.toString().trim()
                if (txt.isNotEmpty()) onText(txt)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etHost.setText(SayriApp.instance.serverHost)
        dialogBinding.etPort.setText(SayriApp.instance.serverPort.toString())
        dialogBinding.etApiKey.setText(SayriApp.instance.apiKey)

        dialogBinding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Guardar") { dialog: android.content.DialogInterface, which: Int ->
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

    // ------------------------------------------------------------- Speech & Audio
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.tvStatus.text = "Escuchando voz... 🎙️"
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    refreshServerStatus()
                }
                override fun onError(error: Int) {
                    refreshServerStatus()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        sendMessageToSayri(text)
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
        // Strip markdown code blocks before speaking
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
