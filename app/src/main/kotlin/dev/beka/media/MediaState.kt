package dev.beka.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import dev.beka.agent.ActionFailed
import dev.beka.agent.MediaCommand

/**
 * What is playing, from Android rather than from the app's UI: many players leave their play
 * button unlabeled, so the screen alone cannot tell whether a tap started playback.
 */
class MediaState(private val context: Context) {

    fun hasAccess(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** One line for the model, or null when nothing is playing or paused. */
    fun describe(): String? {
        val controllers = controllers()
        if (controllers == null) {
            val audio = context.getSystemService(AudioManager::class.java)
            return if (audio.isMusicActive) "audio is playing (grant notification access for details)" else null
        }
        return controllers
            .mapNotNull { c ->
                val state = when (c.playbackState?.state) {
                    PlaybackState.STATE_PLAYING -> "playing"
                    PlaybackState.STATE_PAUSED -> "paused"
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "loading"
                    else -> return@mapNotNull null
                }
                val meta = c.metadata?.description
                val title = meta?.title?.toString()?.takeIf { it.isNotBlank() }
                val artist = meta?.subtitle?.toString()?.takeIf { it.isNotBlank() }
                buildString {
                    append(appLabel(c.packageName)).append(": ")
                    append(title?.let { "\"$it\"" } ?: "(untitled)")
                    artist?.let { append(" by ").append(it) }
                    append(" (").append(state).append(')')
                }
            }
            .take(2)
            .joinToString("; ")
            .ifEmpty { null }
    }

    fun control(command: MediaCommand): String {
        val all = controllers() ?: throw ActionFailed(
            "Media control needs notification access for Beka. Ask the user to enable it in the Beka app, or use the app's buttons."
        )
        // Prefer what is playing, then what is paused; sessions come most recently active first.
        val target = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: all.firstOrNull()
            ?: throw ActionFailed("Nothing is playing or paused")
        val controls = target.transportControls
        when (command) {
            MediaCommand.PLAY -> controls.play()
            MediaCommand.PAUSE -> controls.pause()
            MediaCommand.NEXT -> controls.skipToNext()
            MediaCommand.PREVIOUS -> controls.skipToPrevious()
        }
        return "Sent ${command.name.lowercase()} to ${appLabel(target.packageName)}"
    }

    /** Null without notification access. */
    private fun controllers(): List<MediaController>? {
        if (!hasAccess()) return null
        val manager = context.getSystemService(MediaSessionManager::class.java)
        return runCatching {
            manager.getActiveSessions(ComponentName(context, BekaNotificationListener::class.java))
        }.getOrNull()
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
