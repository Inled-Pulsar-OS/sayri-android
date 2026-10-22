package es.inled.sayri.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import es.inled.sayri.databinding.ActivityDictationBinding
import es.inled.sayri.voice.VoiceManager

class DictationActivity : ComponentActivity() {

    private lateinit var binding: ActivityDictationBinding
    private lateinit var voiceManager: VoiceManager
    private var lastRecognizedText: String = ""

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceManager.startListening(allowIntentFallback = false)
        } else {
            Toast.makeText(this, "Microphone permission is required for dictation", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceManager = VoiceManager(
            context = this,
            onSpeechResult = { text ->
                lastRecognizedText = text
                runOnUiThread {
                    binding.tvDictationPreview.text = text
                    binding.tvDictationStatus.text = "Dictation finished"
                    binding.dictationOrb.state = OrbState.IDLE
                    // Auto-return result
                    completeWithResult(text)
                }
            },
            onAudioLevel = { rms ->
                binding.dictationOrb.setAudioLevel(rms)
            },
            onStateChange = { isListening ->
                if (isListening) {
                    binding.dictationOrb.state = OrbState.LISTENING
                    binding.tvDictationStatus.text = "Sayri is listening..."
                } else {
                    if (binding.dictationOrb.state == OrbState.LISTENING) {
                        binding.dictationOrb.state = OrbState.IDLE
                    }
                }
            }
        )

        binding.dictationBackdrop.setOnClickListener {
            cancelDictation()
        }

        binding.btnDictationCancel.setOnClickListener {
            cancelDictation()
        }

        binding.btnDictationDone.setOnClickListener {
            voiceManager.stopListening()
            if (lastRecognizedText.isNotEmpty()) {
                completeWithResult(lastRecognizedText)
            } else {
                cancelDictation()
            }
        }

        binding.dictationOrb.setOnClickListener {
            voiceManager.stopListening()
            if (lastRecognizedText.isNotEmpty()) {
                completeWithResult(lastRecognizedText)
            }
        }

        // Start listening immediately
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            binding.dictationOrb.postDelayed({
                voiceManager.startListening(allowIntentFallback = false)
            }, 200)
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun completeWithResult(text: String) {
        val intent = Intent().apply {
            putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun cancelDictation() {
        voiceManager.stopListening()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        voiceManager.destroy()
        super.onDestroy()
    }
}
