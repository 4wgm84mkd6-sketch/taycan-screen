package com.taycanscreen.app.audio

import android.app.Activity
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import com.taycanscreen.app.MainActivity
import com.taycanscreen.app.ui.UiEvents

/**
 * Opened by a player's Equalizer menu (Spotify: Settings → Playback → Equalizer) with its audio
 * session. We attach the EQ to that session and show our Audio screen.
 */
class EqPanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "?"
        EqEngine.init(this)
        EqEngine.attachFromPanel(session, pkg)
        UiEvents.openPanel.value = "audio"
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
