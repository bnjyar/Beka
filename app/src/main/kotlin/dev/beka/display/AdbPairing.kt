package dev.beka.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.beka.BekaApp
import dev.beka.R
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One-time pairing with Wireless debugging (fase 9), the way Shizuku does it: Beka watches for the
 * pairing service while the "Pair device with pairing code" dialog is open, and the user types the
 * code shown there into a notification's reply field, so the dialog can stay open.
 */
class AdbPairing(private val app: BekaApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<String?>(null)
    /** Progress for the settings screen, null when idle. */
    val state: StateFlow<String?> = _state

    /** Pairing port seen over mDNS while the dialog is open. */
    @Volatile
    private var pairingPort: Int? = null

    fun start() {
        job?.cancel()
        pairingPort = null
        _state.value = app.getString(R.string.pair_waiting)
        post(app.getString(R.string.pair_notif_open), input = false)
        job = scope.launch {
            val port = app.adbLink.findPort(AdbMdns.SERVICE_TYPE_TLS_PAIRING, WAIT_MS)
            if (port == null) {
                _state.value = app.getString(R.string.pair_not_found)
                cancelNotification()
                return@launch
            }
            pairingPort = port
            _state.value = app.getString(R.string.pair_found)
            post(app.getString(R.string.pair_notif_type), input = true)
        }
    }

    fun cancel() {
        job?.cancel()
        pairingPort = null
        _state.value = null
        cancelNotification()
    }

    /** The code typed into the notification. */
    fun onCode(code: String) {
        val port = pairingPort ?: return post(app.getString(R.string.pair_no_dialog), input = false)
        val digits = code.filter(Char::isDigit)
        if (digits.length != 6) return post(app.getString(R.string.pair_six_digits), input = true)
        post(app.getString(R.string.pair_pairing), input = false)
        scope.launch {
            if (!app.adbLink.pairWith(port, digits)) {
                _state.value = app.getString(R.string.pair_failed)
                post(app.getString(R.string.pair_notif_failed), input = true)
                return@launch
            }
            pairingPort = null
            _state.value = app.getString(R.string.pair_connecting)
            post(app.getString(R.string.pair_notif_done), input = false, done = true)
            // A host started through Shizuku is replaced by one on Beka's own link.
            launch(Dispatchers.Main) { app.virtualScreen.reconnect() }
        }
    }

    private fun post(text: String, input: Boolean, done: Boolean = false) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH))
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(app.getString(R.string.pair_notif_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
        if (done) builder.setTimeoutAfter(DONE_SHOWN_MS)
        if (input) {
            val remote = RemoteInput.Builder(KEY_CODE).setLabel(app.getString(R.string.pair_code_label)).build()
            // RemoteInput needs a mutable intent: the system fills in the typed text.
            val reply = PendingIntent.getBroadcast(
                app, 0, Intent(app, Receiver::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, app.getString(R.string.pair_enter_code), reply).addRemoteInput(remote).build())
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() =
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    /** Not exported: only the notification's reply reaches it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CODE) return
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString() ?: return
            Log.i(DisplayHostService.TAG, "adb: pairing code entered")
            (context.applicationContext as BekaApp).adbPairing.onCode(code)
        }
    }

    private companion object {
        const val CHANNEL = "adb_pairing"
        const val NOTIFICATION_ID = 7201
        const val ACTION_CODE = "dev.beka.ADB_PAIRING_CODE"
        const val KEY_CODE = "code"
        /** How long to wait for the user to open the pairing dialog. */
        const val WAIT_MS = 5L * 60 * 1000
        const val DONE_SHOWN_MS = 5_000L
    }
}
