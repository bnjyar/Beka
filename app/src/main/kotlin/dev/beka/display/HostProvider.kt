package dev.beka.display

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import dev.beka.BekaApp

/**
 * Receives the display host's binder from [HostMain]. Exported so the shell process can call it,
 * but only callers running as shell or root are accepted.
 */
class HostProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) {
            Log.w(DisplayHostService.TAG, "provider: rejected uid $uid")
            return null
        }
        if (method != METHOD_HOST) return null
        val binder = extras?.getBinder(EXTRA_BINDER) ?: return null
        val token = (context?.applicationContext as? BekaApp)?.virtualScreen?.onHost(binder) ?: return null
        return Bundle().apply { putBinder(EXTRA_BINDER, token) }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "dev.beka.displayhost"
        const val METHOD_HOST = "host"
        const val EXTRA_BINDER = "binder"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0
    }
}
