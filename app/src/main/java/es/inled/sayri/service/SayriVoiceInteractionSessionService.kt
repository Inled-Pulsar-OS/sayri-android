package es.inled.sayri.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.LayoutInflater
import android.view.View
import es.inled.sayri.R

class SayriVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return SayriVoiceInteractionSession(this)
    }
}

class SayriVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    override fun onCreateContentView(): View {
        return LayoutInflater.from(context).inflate(R.layout.layout_sayri_cajita_assist, null)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Assistant invoked via voice/home gesture
    }
}
