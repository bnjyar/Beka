package dev.beka.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dev.beka.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** What the update UI shows besides the found update itself. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [percent] is -1 when the server did not say how big the file is. */
    data class Downloading(val percent: Int) : UpdateState
    data object Installing : UpdateState
    data class Info(val text: String) : UpdateState
    data class Error(val text: String) : UpdateState
}

/**
 * Checks GitHub releases for a newer build, downloads its apk and hands it to the system installer.
 * No background work: it checks when the app opens (at most once a day) or when asked.
 */
class Updater(context: Context, private val http: OkHttpClient, private val settings: UpdateSettings) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    init {
        // Whatever was found last time may be installed by now.
        settings.available.value?.let { if (it.versionCode <= BuildConfig.VERSION_CODE) settings.setAvailable(null) }
    }

    private val busy: Boolean
        get() = _state.value.let { it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing }

    /** Called when the app opens: checks if auto-check is on and the last check is over a day old. */
    fun autoCheckIfDue() {
        if (!settings.autoCheck.value) return
        if (System.currentTimeMillis() - settings.lastCheckMillis < DAY_MILLIS) return
        runCheck(silent = true)
    }

    fun checkNow() = runCheck(silent = false)

    private fun runCheck(silent: Boolean) {
        if (busy) return
        if (!silent) _state.value = UpdateState.Checking
        scope.launch {
            val channel = settings.channel.value
            val result = runCatching { fetch(channel) }
            result.onFailure { Log.w(TAG, "check failed", it) }
            val outcome = result.getOrNull()
            if (outcome != null) settings.setLastCheck(System.currentTimeMillis())
            when (outcome) {
                null -> if (!silent) _state.value = UpdateState.Error(errorText(result.exceptionOrNull()))
                is Outcome.NoStable -> {
                    settings.setAvailable(null)
                    if (!silent) _state.value = UpdateState.Info("Belum ada rilis stabil. Coba saluran Nightly.")
                }
                is Outcome.Found -> {
                    val info = outcome.info
                    if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                        settings.setAvailable(info)
                        Log.i(TAG, "update available: ${info.versionName} (${info.versionCode}) on $channel")
                        if (!silent) _state.value = UpdateState.Info("Versi ${info.versionName} tersedia")
                    } else {
                        settings.setAvailable(null)
                        if (!silent) _state.value = UpdateState.Info("Sudah versi terbaru")
                    }
                }
            }
            if (silent && _state.value is UpdateState.Checking) _state.value = UpdateState.Idle
        }
    }

    private sealed interface Outcome {
        data object NoStable : Outcome
        data class Found(val info: UpdateInfo?) : Outcome
    }

    private fun fetch(channel: UpdateChannel): Outcome {
        val url = manifestUrl(channel)
        http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (response.code == 404) {
                if (channel == UpdateChannel.STABLE) return Outcome.NoStable
                throw IOException("beka.json tidak ditemukan (404)")
            }
            if (!response.isSuccessful) throw IOException("GitHub membalas ${response.code}")
            val body = response.body?.string().orEmpty()
            return Outcome.Found(parseManifest(body, apkUrl(channel)))
        }
    }

    /** Starts the install of the update found earlier: download, check the file, then the system installer. */
    fun install() {
        val info = settings.available.value ?: return
        if (busy) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Error(
                "Android belum mengizinkan Beka memasang aplikasi. Aktifkan \"Izinkan dari sumber ini\" di layar yang terbuka, lalu tap Pasang lagi.",
            )
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "cannot open unknown-sources settings", it) }
            return
        }
        _state.value = UpdateState.Downloading(0)
        scope.launch {
            val file = File(File(app.cacheDir, "update").apply { mkdirs() }, "beka.apk")
            try {
                download(info.apkUrl, file)
                verify(file, info)
                _state.value = UpdateState.Installing
                commit(file)
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                file.delete()
                _state.value = UpdateState.Error(errorText(e))
            }
        }
    }

    private fun download(url: String, target: File) {
        target.delete()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub membalas ${response.code}")
            val body = response.body ?: throw IOException("Respons kosong")
            val total = body.contentLength()
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(percent)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Unduhan terpotong")
        }
        Log.i(TAG, "downloaded ${target.length()} bytes")
    }

    private fun verify(file: File, info: UpdateInfo) {
        val archive = app.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("File unduhan bukan apk yang valid")
        if (archive.packageName != app.packageName) {
            throw IOException("File unduhan bukan Beka (${archive.packageName})")
        }
        if (archive.longVersionCode != info.versionCode.toLong()) {
            throw IOException("Versi file (${archive.longVersionCode}) beda dari yang diumumkan (${info.versionCode})")
        }
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("beka.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Mutable: the installer adds the status extras. Explicit component: required for mutable intents.
            val pending = PendingIntent.getBroadcast(
                app, sessionId, Intent(app, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "install session $sessionId committed")
    }

    /** Called by [UpdateReceiver] when the system installer gave up or the user cancelled. */
    internal fun reportInstallFailure(status: Int, message: String?) {
        File(File(app.cacheDir, "update"), "beka.apk").delete()
        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            UpdateState.Info("Pemasangan dibatalkan")
        } else {
            UpdateState.Error("Pemasangan gagal: ${message?.takeIf { it.isNotBlank() } ?: "kode $status"}")
        }
    }

    private fun errorText(e: Throwable?): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Tidak bisa terhubung ke GitHub. Cek internet kamu."
        null -> "Gagal mengecek pembaruan"
        else -> "Gagal: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        const val TAG = "BekaUpdate"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        private fun base(channel: UpdateChannel) = when (channel) {
            UpdateChannel.STABLE -> "https://github.com/${BuildConfig.REPO}/releases/latest/download"
            UpdateChannel.NIGHTLY -> "https://github.com/${BuildConfig.REPO}/releases/download/nightly"
        }

        fun manifestUrl(channel: UpdateChannel) = "${base(channel)}/beka.json"

        fun apkUrl(channel: UpdateChannel) = "${base(channel)}/beka.apk"

        /** Reads `{"versionCode": 12, "versionName": "0.1.12", "commit": "abc1234"}`; null when it is not that. */
        fun parseManifest(body: String, apkUrl: String): UpdateInfo? = runCatching {
            val obj = Json.parseToJsonElement(body).jsonObject
            val code = obj["versionCode"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
            val name = obj["versionName"]?.jsonPrimitive?.contentOrNull ?: code.toString()
            UpdateInfo(code, name, apkUrl)
        }.getOrNull()
    }
}
