package es.inled.sayri.ui

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import es.inled.sayri.R
import es.inled.sayri.SayriApp
import es.inled.sayri.api.SayriApiClient
import es.inled.sayri.data.ChatMessage
import es.inled.sayri.data.ChatRoleMessage
import es.inled.sayri.databinding.ActivityMainBinding
import es.inled.sayri.databinding.DialogHistoryBinding
import es.inled.sayri.databinding.DialogSettingsBinding
import es.inled.sayri.net.NetworkDiscovery
import es.inled.sayri.service.SayriAccessibilityService
import es.inled.sayri.tools.DeviceToolManager
import es.inled.sayri.voice.VoiceManager
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val apiClient by lazy { SayriApiClient() }
    private val toolManager by lazy { DeviceToolManager(this) }

    private lateinit var voiceManager: VoiceManager
    private var currentSse: EventSource? = null

    private val conversationHistory = mutableListOf<ChatRoleMessage>()
    private var currentSessionId: String = "mobile-${UUID.randomUUID()}"

    private var lastInputWasVoice = false

    private var attachedPhotoBitmap: Bitmap? = null
    private var attachedPhotoBase64: String? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            triggerVoice()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            setAttachedPhoto(bitmap)
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        setAttachedPhoto(bitmap)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load selected image", Toast.LENGTH_SHORT).show()
            }
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
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
        })

        voiceManager = VoiceManager(
            context = this,
            onSpeechResult = { text ->
                runOnUiThread {
                    if (SayriApp.instance.confirmVoiceBeforeSending) {
                        binding.etMessage.setText(text)
                        binding.etMessage.setSelection(text.length)
                        binding.etMessage.requestFocus()
                        Toast.makeText(this@MainActivity, "Voice recognized. Review and send.", Toast.LENGTH_SHORT).show()
                    } else {
                        handleUserQuery(text, isVoice = true)
                    }
                }
            },
            onAudioLevel = { _ -> },
            onStateChange = { isListening ->
                runOnUiThread {
                    if (isListening) {
                        binding.tvStatus.text = "Sayri is listening..."
                        binding.btnVoice.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    } else {
                        binding.btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now)
                        refreshServerStatus()
                    }
                }
            }
        ).apply {
            onPartialSpeechResult = { partial ->
                runOnUiThread {
                    binding.etMessage.setText(partial)
                    binding.etMessage.setSelection(partial.length)
                }
            }
            onSpeakingStateChange = { isSpeaking ->
                runOnUiThread {
                    binding.layoutStopTts.visibility = if (isSpeaking) View.VISIBLE else View.GONE
                }
            }
        }

        setupListeners()
        setupSuggestions()

        refreshServerStatus()
        scanNetworkForSayri(autoConnect = true)
    }

    private fun setupListeners() {
        binding.btnStopTts.setOnClickListener {
            voiceManager.stopSpeaking()
            binding.layoutStopTts.visibility = View.GONE
        }

        binding.btnVoice.setOnClickListener {
            if (voiceManager.isCurrentlyListening) {
                voiceManager.stopListening()
            } else if (voiceManager.isSpeaking) {
                voiceManager.stopSpeaking()
            } else {
                triggerVoice()
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() || attachedPhotoBase64 != null) {
                binding.etMessage.setText("")
                val query = if (text.isNotEmpty()) text else "Analyze this attached image and explain what you see."
                handleUserQuery(query, isVoice = false)
            }
        }

        binding.btnAttachPhoto.setOnClickListener {
            showAttachPhotoOptions()
        }

        binding.btnRemovePhoto.setOnClickListener {
            clearAttachedPhoto()
        }

        binding.btnNewChat.setOnClickListener {
            startNewConversation()
        }

        binding.btnHistory.setOnClickListener {
            showHistoryDialog()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun showAttachPhotoOptions() {
        val options = arrayOf("Take Photo with Camera", "Choose Image from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Attach Image")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            takePictureLauncher.launch(null)
                        } else {
                            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
                        }
                    }
                    1 -> {
                        pickImageLauncher.launch("image/*")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setAttachedPhoto(bitmap: Bitmap) {
        val maxDim = 1280
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val w = if (ratio > 1) maxDim else (maxDim * ratio).toInt()
            val h = if (ratio > 1) (maxDim / ratio).toInt() else maxDim
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val bytes = baos.toByteArray()
        attachedPhotoBitmap = scaled
        attachedPhotoBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        binding.ivAttachedPhoto.setImageBitmap(scaled)
        binding.layoutAttachedPhoto.visibility = View.VISIBLE
    }

    private fun clearAttachedPhoto() {
        attachedPhotoBitmap = null
        attachedPhotoBase64 = null
        binding.ivAttachedPhoto.setImageBitmap(null)
        binding.layoutAttachedPhoto.visibility = View.GONE
    }

    private fun startNewConversation() {
        currentSse?.cancel()
        voiceManager.stopSpeaking()
        currentSessionId = "mobile-${UUID.randomUUID()}"
        conversationHistory.clear()
        adapter.clear()
        clearAttachedPhoto()
        Toast.makeText(this, "Started new conversation", Toast.LENGTH_SHORT).show()
    }

    private fun showHistoryDialog() {
        val dialogBinding = DialogHistoryBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        val historyAdapter = SessionHistoryAdapter(
            onSessionSelected = { session ->
                loadPastSession(session.id, dialog)
            },
            onSessionDelete = { session ->
                confirmDeleteSession(session.id) {
                    loadSessionsList(dialogBinding, null)
                }
            }
        )

        dialogBinding.rvHistorySessions.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvHistorySessions.adapter = historyAdapter

        dialogBinding.btnCloseHistory.setOnClickListener {
            dialog.dismiss()
        }

        loadSessionsList(dialogBinding, historyAdapter)
        dialog.show()
    }

    private fun loadSessionsList(binding: DialogHistoryBinding, adapterInstance: SessionHistoryAdapter?) {
        binding.pbLoading.visibility = View.VISIBLE
        binding.rvHistorySessions.visibility = View.GONE
        binding.tvEmptyHistory.visibility = View.GONE

        lifecycleScope.launch {
            val res = apiClient.getSessions()
            binding.pbLoading.visibility = View.GONE
            res.onSuccess { list ->
                val activeList = list.sortedByDescending { it.updatedAt ?: it.createdAt ?: 0.0 }
                if (activeList.isEmpty()) {
                    binding.tvEmptyHistory.visibility = View.VISIBLE
                } else {
                    binding.rvHistorySessions.visibility = View.VISIBLE
                    adapterInstance?.setSessions(activeList)
                }
            }.onFailure { err ->
                binding.tvEmptyHistory.text = "Error loading history: ${err.message}"
                binding.tvEmptyHistory.visibility = View.VISIBLE
            }
        }
    }

    private fun loadPastSession(sessionId: String, dialog: AlertDialog) {
        lifecycleScope.launch {
            val res = apiClient.getSessionById(sessionId)
            res.onSuccess { detail ->
                dialog.dismiss()
                currentSse?.cancel()
                voiceManager.stopSpeaking()

                currentSessionId = detail.id
                conversationHistory.clear()
                adapter.clear()
                clearAttachedPhoto()

                detail.messages.forEach { msg ->
                    val isUser = msg.role == "user"
                    val author = if (isUser) "You" else "Sayri"
                    conversationHistory.add(ChatRoleMessage(msg.role, msg.content))
                    adapter.addMessage(ChatMessage(author = author, content = msg.content, isUser = isUser))
                }

                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                Toast.makeText(this@MainActivity, "Conversation resumed", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Failed to load session: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteSession(sessionId: String, onDeleted: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete this conversation?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val res = apiClient.deleteSession(sessionId)
                    res.onSuccess {
                        Toast.makeText(this@MainActivity, "Conversation deleted", Toast.LENGTH_SHORT).show()
                        if (currentSessionId == sessionId) {
                            startNewConversation()
                        }
                        onDeleted()
                    }.onFailure { err ->
                        Toast.makeText(this@MainActivity, "Failed to delete: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerVoice() {
        lastInputWasVoice = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceManager.startListening(allowIntentFallback = false)
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupSuggestions() {
        binding.chipScreen.setOnClickListener {
            performScreenPerception()
        }

        binding.chipIdea.setOnClickListener {
            binding.etMessage.setText("Save idea: ")
            binding.etMessage.setSelection(binding.etMessage.text.length)
            binding.etMessage.requestFocus()
        }

        binding.chipTomorrow.setOnClickListener {
            binding.etMessage.setText("Remind tomorrow: ")
            binding.etMessage.setSelection(binding.etMessage.text.length)
            binding.etMessage.requestFocus()
        }

        binding.chipTimer.setOnClickListener {
            toolManager.setTimer(300, "Timer 5 min")
            adapter.addMessage(ChatMessage(author = "Sayri", content = "Timer for 5 minutes started.", isUser = false))
        }
    }

    private fun scanNetworkForSayri(autoConnect: Boolean = true) {
        lifecycleScope.launch {
            val hosts = NetworkDiscovery.discoverSayriHosts(this@MainActivity)
            if (hosts.isNotEmpty()) {
                val host = hosts.first()
                if (autoConnect) {
                    SayriApp.instance.serverHost = host.ip
                    binding.layoutDiscoveryBanner.visibility = View.GONE
                    refreshServerStatus()
                } else {
                    binding.layoutDiscoveryBanner.visibility = View.VISIBLE
                    binding.tvDiscoveredHost.text = "Discovered: ${host.name} (${host.ip})"
                    binding.btnQuickConnect.setOnClickListener {
                        SayriApp.instance.serverHost = host.ip
                        binding.layoutDiscoveryBanner.visibility = View.GONE
                        refreshServerStatus()
                        if (SayriApp.instance.apiKey.isEmpty()) {
                            showPairPinDialog()
                        } else {
                            Toast.makeText(this@MainActivity, "Connected to ${host.ip}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun showPairPinDialog(onSuccess: (() -> Unit)? = null) {
        val input = android.widget.EditText(this).apply {
            hint = "PIN (e.g. 812234)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            setPadding(40, 30, 40, 30)
            setTextColor(getColor(R.color.cajita_text_primary))
            setBackgroundResource(R.drawable.bg_cajita_pill)
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(50, 20, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Pair with Sayri PC")
            .setMessage("Enter the 6-digit PIN displayed on your Sayri Desktop (Mobile tab):")
            .setView(container)
            .setPositiveButton("Pair") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length == 6) {
                    lifecycleScope.launch {
                        val res = apiClient.pairWithPin(pin, android.os.Build.MODEL)
                        res.onSuccess {
                            Toast.makeText(this@MainActivity, "Device paired successfully", Toast.LENGTH_SHORT).show()
                            refreshServerStatus()
                            onSuccess?.invoke()
                        }.onFailure { err ->
                            Toast.makeText(this@MainActivity, "PIN Error: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "PIN must be 6 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshServerStatus() {
        lifecycleScope.launch {
            val result = apiClient.getStatus()
            result.onSuccess { status ->
                val accState = if (SayriAccessibilityService.isRunning) "Accessibility: ON" else "Accessibility: OFF"
                binding.tvStatus.text = "Pulsar OS • Online (${status.activeAgent}) • $accState"
                binding.layoutDiscoveryBanner.visibility = View.GONE
            }.onFailure {
                val accState = if (SayriAccessibilityService.isRunning) "Accessibility: ON" else "Accessibility: OFF"
                binding.tvStatus.text = "Disconnected from Sayri PC ($accState)"
                scanNetworkForSayri(autoConnect = true)
            }
        }
    }

    private fun handleUserQuery(query: String, isVoice: Boolean = false) {
        lastInputWasVoice = isVoice
        val lower = query.lowercase().trim()

        if (lower.startsWith("open ") || lower.startsWith("abre ") || lower.startsWith("abrir ") || lower.startsWith("lanza ") || lower.startsWith("inicia ")) {
            if (toolManager.openApp(query)) {
                adapter.addMessage(ChatMessage(author = "You", content = query, isUser = true))
                val reply = "Opening app..."
                adapter.addMessage(ChatMessage(author = "Sayri", content = reply, isUser = false))
                if (isVoice) {
                    voiceManager.speak(reply) {
                        runOnUiThread {
                            triggerVoice()
                        }
                    }
                }
                return
            }
        } else if (lower.startsWith("flashlight ") || lower.startsWith("linterna ")) {
            val on = lower.contains("on") || lower.contains("enciende") || lower.contains("activa")
            toolManager.setFlashlight(on)
            val msg = if (on) "Flashlight turned on." else "Flashlight turned off."
            adapter.addMessage(ChatMessage(author = "You", content = query, isUser = true))
            adapter.addMessage(ChatMessage(author = "Sayri", content = msg, isUser = false))
            if (isVoice) {
                voiceManager.speak(msg) {
                    runOnUiThread {
                        triggerVoice()
                    }
                }
            }
            return
        }

        sendMessageToSayri(query, isVoice)
    }

    private fun sendMessageToSayri(text: String, isVoice: Boolean) {
        val hasPhoto = attachedPhotoBase64 != null
        val promptForModel = if (hasPhoto) {
            val queryText = if (text.isNotEmpty()) text else "Analyze the attached image."
            "[Photo attached by user]\n$queryText\n(Note: If vision processing is currently unavailable on this model, please notify the user that only text and device actions are supported, and ask how you can help.)"
        } else {
            text
        }

        val displayContent = if (hasPhoto) "[Photo attached] $text".trim() else text
        clearAttachedPhoto()

        adapter.addMessage(ChatMessage(author = "You", content = displayContent, isUser = true))
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        conversationHistory.add(ChatRoleMessage("user", promptForModel))

        val sayriMsg = ChatMessage(author = "Sayri", content = "...", isUser = false)
        adapter.addMessage(sayriMsg)
        binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)

        val fullAnswer = StringBuilder()

        currentSse?.cancel()
        currentSse = apiClient.streamChat(
            messages = conversationHistory,
            sessionId = currentSessionId,
            onDelta = { delta ->
                runOnUiThread {
                    fullAnswer.append(delta)
                    adapter.updateLastMessage(fullAnswer.toString())
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            },
            onDone = { finalAnswer ->
                runOnUiThread {
                    val raw = if (finalAnswer.isNotEmpty()) finalAnswer else fullAnswer.toString()
                    val (cleanRes, _) = toolManager.parseAndExecuteAction(raw)
                    adapter.updateLastMessage(cleanRes)
                    conversationHistory.add(ChatRoleMessage("assistant", cleanRes))

                    if (isVoice) {
                        voiceManager.speak(cleanRes) {
                            runOnUiThread {
                                triggerVoice()
                            }
                        }
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    val msg = if (err.message?.contains("401") == true) {
                        "Device not paired. Open Settings to pair PIN."
                    } else {
                        "Error: ${err.localizedMessage}"
                    }
                    adapter.updateLastMessage(msg)
                }
            }
        )
    }

    private fun performScreenPerception() {
        val service = SayriAccessibilityService.instance
        if (service == null) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Service Disabled")
                .setMessage("Enable Sayri Accessibility Service to perceive your mobile screen.")
                .setPositiveButton("Open Settings") { _: DialogInterface, _: Int ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        service.captureScreenshot { screenBitmap ->
            runOnUiThread {
                val snapshot = service.dumpScreenHierarchy(userIntent = "Analyze screen and guide user")
                val elementsSummary = snapshot.elements.take(20).mapNotNull { el ->
                    val text = el.text ?: el.contentDescription
                    if (!text.isNullOrBlank()) {
                        val coords = if (el.bounds.size == 4) "center: (${(el.bounds[0]+el.bounds[2])/2}, ${(el.bounds[1]+el.bounds[3])/2})" else ""
                        "- \"${text.trim()}\" $coords ${if (el.clickable) "[Clickable]" else ""}"
                    } else null
                }.joinToString("\n")

                val prompt = if (elementsSummary.isNotEmpty()) {
                    "[Mobile Screen Perception & OCR Map]\nActive app: ${snapshot.packageName}\nScreen elements with coordinates:\n$elementsSummary\n\nGuide me or interact with any element using [ACTION: CLICK_TEXT <text>] or [ACTION: CLICK_COORDS <x> <y>]."
                } else {
                    "[Mobile Screen Perception] Active app: ${snapshot.packageName}. Guide me or assist based on this screen."
                }

                handleUserQuery(prompt, isVoice = lastInputWasVoice)
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etHost.setText(SayriApp.instance.serverHost)
        dialogBinding.etPort.setText(SayriApp.instance.serverPort.toString())
        dialogBinding.switchConfirmVoice.isChecked = SayriApp.instance.confirmVoiceBeforeSending
        dialogBinding.switchConfirmVoice.setOnCheckedChangeListener { _, isChecked ->
            SayriApp.instance.confirmVoiceBeforeSending = isChecked
        }

        dialogBinding.btnScanNetwork.setOnClickListener {
            dialogBinding.tvScanResult.visibility = View.VISIBLE
            dialogBinding.tvScanResult.text = "Scanning local network..."
            lifecycleScope.launch {
                val hosts = NetworkDiscovery.discoverSayriHosts(this@MainActivity)
                if (hosts.isNotEmpty()) {
                    val host = hosts.first()
                    dialogBinding.tvScanResult.text = "Found ${host.name} at ${host.ip}"
                    dialogBinding.etHost.setText(host.ip)
                    dialogBinding.etPort.setText(host.port.toString())
                } else {
                    dialogBinding.tvScanResult.text = "No other Sayri devices found on subnet."
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
                    Toast.makeText(this, "Open Settings -> Default Apps -> Digital Assistant", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialogBinding.btnAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val isAlreadyPaired = SayriApp.instance.apiKey.isNotEmpty()
        dialogBinding.tvPairStatus.text = if (isAlreadyPaired) "Status: Paired with token" else "Status: Not paired"
        if (isAlreadyPaired) {
            dialogBinding.tvPairStatus.setTextColor(getColor(R.color.cajita_accent_cyan))
        }

        dialogBinding.btnPairPin.setOnClickListener {
            val pin = dialogBinding.etPin.text.toString().trim()
            if (pin.isEmpty()) {
                Toast.makeText(this, "Enter the 6-digit PIN shown on Desktop", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialogBinding.tvPairStatus.text = "Pairing with PIN $pin..."
            lifecycleScope.launch {
                val res = apiClient.pairWithPin(pin, android.os.Build.MODEL)
                res.onSuccess {
                    dialogBinding.tvPairStatus.text = "Paired successfully!"
                    dialogBinding.tvPairStatus.setTextColor(getColor(R.color.cajita_accent_cyan))
                    Toast.makeText(this@MainActivity, "Device paired successfully", Toast.LENGTH_SHORT).show()
                    refreshServerStatus()
                }.onFailure { err ->
                    dialogBinding.tvPairStatus.text = "Error: ${err.message}"
                    dialogBinding.tvPairStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    Toast.makeText(this@MainActivity, "PIN Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _: DialogInterface, _: Int ->
                val newHost = dialogBinding.etHost.text.toString().trim()
                val newPort = dialogBinding.etPort.text.toString().toIntOrNull() ?: SayriApp.DEFAULT_PORT

                SayriApp.instance.serverHost = if (newHost.isNotEmpty()) newHost else SayriApp.DEFAULT_HOST
                SayriApp.instance.serverPort = newPort
                SayriApp.instance.confirmVoiceBeforeSending = dialogBinding.switchConfirmVoice.isChecked

                refreshServerStatus()
                Toast.makeText(this, "Settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        currentSse?.cancel()
        voiceManager.destroy()
        super.onDestroy()
    }
}
