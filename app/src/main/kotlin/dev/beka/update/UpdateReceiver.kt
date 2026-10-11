package dev.beka.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dev.beka.BekaApp
import dev.beka.R

/** Gets the system installer's progress: asks for the confirmation screen, or reports why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(Updater.TAG, "installer status $status: $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    (context.applicationContext as BekaApp).updater.reportInstallFailure(status, context.applicationContext.getString(R.string.update_no_confirm_screen))
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure {
                        Log.w(Updater.TAG, "cannot open the install confirmation", it)
                        (context.applicationContext as BekaApp).updater.reportInstallFailure(status, context.applicationContext.getString(R.string.update_cannot_open_confirm))
                    }
            }
            // The new version replaces this process, so there is nobody left to tell.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> (context.applicationContext as BekaApp).updater.reportInstallFailure(status, message)
        }
    }

    companion object {
        const val ACTION = "dev.beka.UPDATE_INSTALL_RESULT"
    }
}
