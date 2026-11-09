package com.taycanscreen.app.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest receiver for players' "audio session opened/closed" announcements, so an announcement
 * made before our screen is up (Spotify resuming as the car powers on) still attaches the EQ.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EqEngine.init(context)
        EqEngine.onSessionBroadcast(intent)
    }
}
