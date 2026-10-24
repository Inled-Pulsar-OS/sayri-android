package es.inled.sayri.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import es.inled.sayri.R
import es.inled.sayri.SayriApp
import es.inled.sayri.api.SayriApiClient
import es.inled.sayri.data.ChatRoleMessage
import es.inled.sayri.databinding.LayoutSayriCajitaAssistBinding
import es.inled.sayri.service.SayriAccessibilityService
import es.inled.sayri.tools.DeviceToolManager
import es.inled.sayri.voice.VoiceManager
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream

class AssistActivity : ComponentActivity() {

    private lateinit var binding: LayoutSayriCajitaAssistBinding
    private val apiClient by lazy { SayriApiClient() }
    private val toolManager by lazy { DeviceToolManager(this) }

    private lateinit var voiceManager: VoiceManager
    private val conversationHistory = mutableListOf<ChatRoleMessage>()
    private val assistSessionId = "mobile-assist-${java.util.UUID.randomUUID()}"
    private var lastInputWasVoice: Boolean = false
    private var isProcessingOrSpeaking: Boolean = false
    private var activeStreamSource: EventSource? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceCapture()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

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
                runOnUiThread {
                    if (SayriApp.instance.confirmVoiceBeforeSending) {
                        binding.etAssistInput.setText(text)
                        binding.etAssistInput.setSelection(text.length)
                        binding.etAssistInput.requestFocus()
                        binding.tvAssistStatus.text = "Review and send"
                        binding.cajitaCard.mode = "idle"
                    } else {
                        handleUserQuery(text, isVoice = true)
                    }
                }
            },
            onAudioLevel = { _ -> },
            onStateChange = { isListening ->
                if (isListening) {
                    binding.cajitaCard.mode = "active"
                    binding.tvAssistStatus.text = "Sayri is listening..."
                    binding.btnAssistVoice.text = "Stop Mic"
                } else {
                    binding.btnAssistVoice.text = "Voice"
                    binding.cajitaCard.mode = "idle"
                }
            }
        ).apply {
            onPartialSpeechResult = { partial ->
                runOnUiThread {
                    binding.tvAssistStatus.text = partial
                    binding.etAssistInput.setText(partial)
                    binding.etAssistInput.setSelection(partial.length)
                }
            }
            onSpeakingStateChange = { isSpeaking ->
                runOnUiThread {
                    binding.btnAssistStopTts.visibility = if (isSpeaking) View.VISIBLE else View.GONE
                    if (isSpeaking) {
                        binding.cajitaCard.mode = "speaking"
                    } else if (binding.cajitaCard.mode == "speaking") {
                        binding.cajitaCard.mode = "idle"
                    }
                }
            }
        }

        setupListeners()
        setupCardDimensions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopAllImmediately()
                finish()
            }
        })

        // Auto start listening on assist invocation
        binding.root.postDelayed({
            startVoiceCapture()
        }, 200)
    }

    private fun setupCardDimensions() {
        val screenHeight = resources.displayMetrics.heightPixels
        val halfScreen = screenHeight / 2
        binding.cajitaCard.maxCardHeight = halfScreen

        // Calculate max allowed height for scrollAssistResponse:
        // Non-scrollable controls take ~210dp:
        val density = resources.displayMetrics.density
        val chromeHeightPx = (210 * density).toInt()
        val maxScrollHeight = (halfScreen - chromeHeightPx).coerceAtLeast((130 * density).toInt())
        binding.scrollAssistResponse.maxHeightPx = maxScrollHeight
    }

    private fun startVoiceCapture() {
        if (isProcessingOrSpeaking || voiceManager.isSpeakingOrPlaying()) {
            android.util.Log.d("AssistActivity", "startVoiceCapture rejected: assistant is still processing or speaking.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            lastInputWasVoice = true
            voiceManager.startListening(allowIntentFallback = false)
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopAllImmediately() {
        isProcessingOrSpeaking = false

        try {
            activeStreamSource?.cancel()
        } catch (_: Throwable) {}
        activeStreamSource = null

        if (::voiceManager.isInitialized) {
            voiceManager.stopAll()
        }

        binding.cajitaCard.mode = "idle"
        binding.btnAssistVoice.text = "Voice"
        binding.btnAssistStopTts.visibility = View.GONE
    }

    private fun stopAnyPlaybackOrCapture() {
        stopAllImmediately()
    }

    private fun triggerCamera() {
        stopAnyPlaybackOrCapture()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePictureLauncher.launch(null)
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupListeners() {
        binding.tvAssistTranscription.setOnLongClickListener {
            val text = binding.tvAssistTranscription.text?.toString() ?: ""
            if (text.isNotBlank() && text != "Thinking...") {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Sayri Assistant", text)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        binding.assistBackdrop.setOnClickListener {
            stopAnyPlaybackOrCapture()
            finish()
        }

        binding.btnAssistClose.setOnClickListener {
            stopAnyPlaybackOrCapture()
            finish()
        }

        binding.btnAssistStopTts.setOnClickListener {
            voiceManager.stopSpeaking()
            binding.btnAssistStopTts.visibility = View.GONE
            binding.cajitaCard.mode = "idle"
            isProcessingOrSpeaking = false
        }

        binding.btnAssistVoice.setOnClickListener {
            if (voiceManager.isSpeakingOrPlaying()) {
                voiceManager.stopSpeaking()
                binding.btnAssistStopTts.visibility = View.GONE
                binding.cajitaCard.mode = "idle"
                isProcessingOrSpeaking = false
                startVoiceCapture()
            } else if (voiceManager.isCurrentlyListening) {
                voiceManager.stopListening()
            } else {
                isProcessingOrSpeaking = false
                startVoiceCapture()
            }
        }

        binding.btnAssistSend.setOnClickListener {
            val text = binding.etAssistInput.text.toString().trim()
            if (text.isNotEmpty()) {
                binding.etAssistInput.setText("")
                handleUserQuery(text, isVoice = false)
            }
        }

        binding.etAssistInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val text = binding.etAssistInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    binding.etAssistInput.setText("")
                    handleUserQuery(text, isVoice = false)
                }
                true
            } else {
                false
            }
        }

        // Live Mode toggle
        binding.btnAssistLiveMode.setOnClickListener {
            voiceManager.isLiveMode = !voiceManager.isLiveMode
            updateLiveModeUi()
            if (voiceManager.isLiveMode) {
                startVoiceCapture()
            } else {
                stopAnyPlaybackOrCapture()
            }
        }

        // Circle to Search
        binding.btnAssistCircleSearch.setOnClickListener {
            stopAnyPlaybackOrCapture()
            startCircleToSearch()
        }

        // Camera Vision
        binding.btnAssistCamera.setOnClickListener {
            triggerCamera()
        }

        // Circle Search Callback with real Screenshot Vision
        binding.circleSearchOverlay.onAreaSelected = { bounds ->
            binding.circleSearchOverlay.visibility = View.GONE
            binding.circleSearchOverlay.clear()

            val accService = SayriAccessibilityService.instance
            if (accService != null) {
                accService.captureScreenshot { screenBitmap ->
                    runOnUiThread {
                        var base64Img: String? = null
                        if (screenBitmap != null) {
                            try {
                                val left = bounds.left.toInt().coerceIn(0, screenBitmap.width - 1)
                                val top = bounds.top.toInt().coerceIn(0, screenBitmap.height - 1)
                                val right = bounds.right.toInt().coerceIn(left + 1, screenBitmap.width)
                                val bottom = bounds.bottom.toInt().coerceIn(top + 1, screenBitmap.height)
                                val width = right - left
                                val height = bottom - top
                                if (width > 10 && height > 10) {
                                    val cropped = Bitmap.createBitmap(screenBitmap, left, top, width, height)
                                    val baos = ByteArrayOutputStream()
                                    cropped.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                                    base64Img = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("AssistActivity", "Crop screenshot error", e)
                            }
                        }

                        val snapshot = accService.dumpScreenHierarchy("Search in circled region")
                        val targetNodes = snapshot.elements.filter { elem ->
                            if (elem.bounds.size < 4) return@filter false
                            val eLeft = elem.bounds[0]; val eTop = elem.bounds[1]; val eRight = elem.bounds[2]; val eBottom = elem.bounds[3]
                            val hasText = !elem.text.isNullOrBlank() || !elem.contentDescription.isNullOrBlank()
                            if (!hasText) return@filter false
                            val intersects = (eLeft < bounds.right && eRight > bounds.left && eTop < bounds.bottom && eBottom > bounds.top)
                            val cX = (eLeft + eRight) / 2; val cY = (eTop + eBottom) / 2
                            intersects || bounds.contains(cX.toFloat(), cY.toFloat())
                        }
                        val textElements = targetNodes.mapNotNull { it.text?.trim() ?: it.contentDescription?.trim() }.distinct()
                        val textContext = textElements.joinToString(" ").trim()

                        val query = if (textContext.isNotEmpty()) {
                            val cX = ((bounds.left + bounds.right) / 2).toInt()
                            val cY = ((bounds.top + bounds.bottom) / 2).toInt()
                            "[Mobile Circle to Search & OCR]\nThe user circled this text on their phone screen: \"$textContext\".\nRegion: center=($cX, $cY), bounds=[${bounds.left.toInt()}, ${bounds.top.toInt()} to ${bounds.right.toInt()}, ${bounds.bottom.toInt()}].\nExplain what this is, or interact with it using [ACTION: CLICK_TEXT \"$textContext\"] or [ACTION: CLICK_COORDS $cX $cY]."
                        } else {
                            "[Mobile Screen Perception] The user circled an area on their mobile screen. (Note: Image perception is currently disabled; no text was found in the area)."
                        }
                        handleUserQuery(query, isVoice = lastInputWasVoice)
                    }
                }
            } else {
                Toast.makeText(this, "Accessibility Service is needed for screen capture", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCircleToSearch() {
        binding.circleSearchOverlay.visibility = View.VISIBLE
        binding.circleSearchOverlay.bringToFront()
        Toast.makeText(this, "Circle the area on screen you want to search", Toast.LENGTH_SHORT).show()
    }

    private fun handleCapturedPhoto(bitmap: Bitmap) {
        val prompt = "[Photo attached by user]\nI took a photo with my Android phone camera. (Note: If vision processing is currently unavailable on this model, please let me know and ask how you can help.)"
        handleUserQuery(prompt, isVoice = lastInputWasVoice)
    }

    private fun handleUserQuery(query: String, isVoice: Boolean) {
        lastInputWasVoice = isVoice
        voiceManager.stopListening()
        isProcessingOrSpeaking = true
        val lower = query.lowercase().trim()

        // 1. Direct local execution for instant mobile tools
        if (lower.startsWith("open ") || lower.startsWith("abre ") || lower.startsWith("abrir ") || lower.startsWith("lanza ") || lower.startsWith("inicia ")) {
            if (toolManager.openApp(query)) {
                val reply = "Opening app..."
                binding.tvAssistStatus.text = query
                binding.tvAssistTranscription.visibility = View.VISIBLE
                binding.tvAssistTranscription.text = reply
                conversationHistory.add(ChatRoleMessage("user", query))
                conversationHistory.add(ChatRoleMessage("assistant", reply))
                if (isVoice) {
                    binding.btnAssistStopTts.visibility = View.VISIBLE
                    voiceManager.speak(reply) {
                        runOnUiThread {
                            isProcessingOrSpeaking = false
                            binding.btnAssistStopTts.visibility = View.GONE
                        }
                    }
                } else {
                    isProcessingOrSpeaking = false
                }
                return
            }
        } else if (lower.startsWith("flashlight ") || lower.startsWith("linterna ")) {
            val on = lower.contains("on") || lower.contains("enciende") || lower.contains("activa")
            toolManager.setFlashlight(on)
            val msg = if (on) "Flashlight turned on." else "Flashlight turned off."
            binding.tvAssistStatus.text = query
            binding.tvAssistTranscription.visibility = View.VISIBLE
            binding.tvAssistTranscription.text = msg
            conversationHistory.add(ChatRoleMessage("user", query))
            conversationHistory.add(ChatRoleMessage("assistant", msg))
            if (isVoice) {
                binding.btnAssistStopTts.visibility = View.VISIBLE
                voiceManager.speak(msg) {
                    runOnUiThread {
                        isProcessingOrSpeaking = false
                        binding.btnAssistStopTts.visibility = View.GONE
                    }
                }
            } else {
                isProcessingOrSpeaking = false
            }
            return
        }

        // 2. Stream to Sayri Core with persistent assistSessionId for multi-turn dialogue
        processQuery(query, isVoice)
    }

    private fun processQuery(query: String, isVoice: Boolean) {
        isProcessingOrSpeaking = true
        binding.cajitaCard.mode = "thinking"
        val displayQuery = if (query.contains("data:image/")) {
            val note = query.substringAfterLast("\n\n").take(120)
            if (note.isNotBlank()) "[Image selection] $note" else "[Image selection]"
        } else {
            query
        }
        binding.tvAssistStatus.text = displayQuery
        binding.tvAssistTranscription.visibility = View.VISIBLE
        binding.tvAssistTranscription.text = "Thinking..."

        conversationHistory.add(ChatRoleMessage("user", query))
        val fullResponse = StringBuilder()

        activeStreamSource?.cancel()
        activeStreamSource = apiClient.streamChat(
            messages = conversationHistory,
            sessionId = assistSessionId,
            onDelta = { delta ->
                if (isFinishing || isDestroyed || activeStreamSource == null) return@streamChat
                runOnUiThread {
                    if (isFinishing || isDestroyed || activeStreamSource == null) return@runOnUiThread
                    fullResponse.append(delta)
                    binding.tvAssistTranscription.text = MarkdownRenderer.render(fullResponse.toString())
                }
            },
            onDone = { finalTxt ->
                if (isFinishing || isDestroyed || activeStreamSource == null) return@streamChat
                runOnUiThread {
                    if (isFinishing || isDestroyed || activeStreamSource == null) return@runOnUiThread
                    activeStreamSource = null
                    val raw = if (finalTxt.isNotEmpty()) finalTxt else fullResponse.toString()
                    val (cleanRes, _) = toolManager.parseAndExecuteAction(raw)

                    conversationHistory.add(ChatRoleMessage("assistant", cleanRes))
                    binding.tvAssistTranscription.text = MarkdownRenderer.render(cleanRes)

                    if (isVoice) {
                        isProcessingOrSpeaking = true
                        binding.cajitaCard.mode = "speaking"
                        binding.btnAssistStopTts.visibility = View.VISIBLE
                        voiceManager.speak(cleanRes) {
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                isProcessingOrSpeaking = false
                                binding.cajitaCard.mode = "idle"
                                binding.btnAssistStopTts.visibility = View.GONE
                                // Reopen mic ONLY after dictation has fully completed!
                                if (!isFinishing && !isDestroyed) {
                                    startVoiceCapture()
                                }
                            }
                        }
                    } else {
                        isProcessingOrSpeaking = false
                        binding.cajitaCard.mode = "idle"
                    }
                }
            },
            onError = { err ->
                if (isFinishing || isDestroyed) return@streamChat
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    activeStreamSource = null
                    isProcessingOrSpeaking = false
                    binding.cajitaCard.mode = "idle"
                    binding.btnAssistStopTts.visibility = View.GONE
                    val msg = if (err.message?.contains("401") == true) {
                        "Device not paired. Open Sayri settings to enter PC pairing PIN."
                    } else {
                        "Error: ${err.message}"
                    }
                    binding.tvAssistTranscription.text = msg
                }
            }
        )
    }

    private fun updateLiveModeUi() {
        if (voiceManager.isLiveMode) {
            binding.btnAssistLiveMode.text = "Live: ON"
            binding.btnAssistLiveMode.setBackgroundResource(R.drawable.bg_cajita_btn_primary)
            binding.btnAssistLiveMode.setTextColor(getColor(R.color.cajita_text_primary))
        } else {
            binding.btnAssistLiveMode.text = "Live: OFF"
            binding.btnAssistLiveMode.setBackgroundResource(R.drawable.bg_cajita_btn)
            binding.btnAssistLiveMode.setTextColor(getColor(R.color.cajita_text_secondary))
        }
    }

    override fun onPause() {
        stopAllImmediately()
        super.onPause()
    }

    override fun onStop() {
        stopAllImmediately()
        super.onStop()
    }

    override fun onDestroy() {
        stopAllImmediately()
        if (::voiceManager.isInitialized) {
            voiceManager.destroy()
        }
        super.onDestroy()
    }
}
