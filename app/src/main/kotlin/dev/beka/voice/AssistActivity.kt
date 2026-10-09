package dev.beka.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import dev.beka.MainActivity
import dev.beka.overlay.OverlayService

/**
 * What the system opens when Beka is the digital assistant (holding the power button, a headset's
 * button). It shows nothing: it hands over to the overlay, which opens the panel already listening.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("BekaVoice", "assist requested (${intent?.action})")
        if (Settings.canDrawOverlays(this)) {
            OverlayService.assist(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
