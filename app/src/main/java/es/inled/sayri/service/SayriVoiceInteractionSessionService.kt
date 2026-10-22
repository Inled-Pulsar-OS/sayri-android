package es.inled.sayri.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import es.inled.sayri.R
import es.inled.sayri.ui.AssistActivity

class SayriVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return SayriVoiceInteractionSession(this)
    }
}

class SayriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val tag = "SayriVoiceSession"

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_sayri_cajita_assist, null)
        view.findViewById<View>(R.id.assistBackdrop)?.setOnClickListener {
            hide()
        }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(tag, "SayriVoiceInteractionSession onShow -> Launching AssistActivity")
        try {
            val intent = Intent(context, AssistActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startAssistantActivity(intent)
            hide()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to startAssistantActivity, displaying session window", e)
        }
    }
}
