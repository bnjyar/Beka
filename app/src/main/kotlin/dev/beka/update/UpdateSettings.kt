package dev.beka.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class UpdateChannel { STABLE, NIGHTLY }

/** A newer build found on GitHub. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Which builds to follow, whether to check by itself, and the newer build found last time. */
class UpdateSettings(context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    private val _channel = MutableStateFlow(
        prefs.getString(KEY_CHANNEL, null)?.let { name -> UpdateChannel.values().firstOrNull { it.name == name } }
            ?: UpdateChannel.STABLE,
    )
    val channel: StateFlow<UpdateChannel> = _channel

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck

    private val _available = MutableStateFlow(readAvailable())

    /** The newer build found by the last check, or null. */
    val available: StateFlow<UpdateInfo?> = _available

    val lastCheckMillis: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    /** Switching channel forgets the build found on the other one. */
    fun setChannel(value: UpdateChannel) {
        if (value == _channel.value) return
        prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        _channel.value = value
        setAvailable(null)
    }

    fun setAutoCheck(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()
        _autoCheck.value = value
    }

    fun setLastCheck(millis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, millis).apply()
    }

    fun setAvailable(info: UpdateInfo?) {
        val edit = prefs.edit()
        if (info == null) {
            edit.remove(KEY_CODE).remove(KEY_NAME).remove(KEY_URL)
        } else {
            edit.putInt(KEY_CODE, info.versionCode).putString(KEY_NAME, info.versionName).putString(KEY_URL, info.apkUrl)
        }
        edit.apply()
        _available.value = info
    }

    /** Re-reads the stored values, after a backup was imported. */
    fun reload() {
        val before = _channel.value
        _channel.value = prefs.getString(KEY_CHANNEL, null)?.let { name -> UpdateChannel.values().firstOrNull { it.name == name } }
            ?: UpdateChannel.STABLE
        _autoCheck.value = prefs.getBoolean(KEY_AUTO_CHECK, true)
        if (_channel.value != before) setAvailable(null)
    }

    private fun readAvailable(): UpdateInfo? {
        val code = prefs.getInt(KEY_CODE, 0)
        val name = prefs.getString(KEY_NAME, null)
        val url = prefs.getString(KEY_URL, null)
        return if (code > 0 && name != null && url != null) UpdateInfo(code, name, url) else null
    }

    companion object {
        private const val KEY_CHANNEL = "channel"
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_CODE = "available_code"
        private const val KEY_NAME = "available_name"
        private const val KEY_URL = "available_url"
    }
}
