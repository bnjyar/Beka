package dev.beka.display

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beka's own ADB client to this phone over Wireless debugging (fase 9), so the display host can be
 * started without Shizuku. Pairing stores this key with adbd once; afterwards connecting only needs
 * Wireless debugging on, which Beka can switch on itself once it holds WRITE_SECURE_SETTINGS.
 */
class AdbLink(private val context: Context) : AbsAdbConnectionManager() {

    private val dir = File(context.filesDir, "adb").apply { mkdirs() }
    private val keyFile = File(dir, "private.key")
    private val certFile = File(dir, "cert.der")
    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    private val key: PrivateKey
    private val cert: Certificate

    /** The shell stream the host runs in; closing it ends the host. */
    @Volatile
    private var hostStream: AdbStream? = null

    init {
        setApi(Build.VERSION.SDK_INT)
        if (keyFile.exists() && certFile.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Beka")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - DAY), Date(now + 30 * 365 * DAY), name, pair.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            key = pair.private
            cert = JcaX509CertificateConverter().getCertificate(holder)
            keyFile.writeBytes(key.encoded)
            certFile.writeBytes(cert.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Beka"

    /** adbd accepted this key at least once. */
    val paired: Boolean get() = prefs.getBoolean(PAIRED, false)

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Pairs with the "Pair device with pairing code" dialog. Blocking. */
    fun pairWith(port: Int, code: String): Boolean {
        val ok = runCatching { pair(LOCALHOST, port, code) }
            .onFailure { log("pair failed: $it") }
            .getOrDefault(false)
        if (ok) prefs.edit().putBoolean(PAIRED, true).apply()
        log("pair on port $port → $ok")
        return ok
    }

    /**
     * Connects (switching Wireless debugging on first when allowed) and starts [command] in a shell
     * stream that stays open. Output lines go to [onLine]. Blocking; throws when it cannot connect.
     */
    @Synchronized
    fun startShell(command: String, onLine: (String) -> Unit) {
        // A connection from before Wireless debugging was switched off still reads as connected.
        val stream = runCatching { ensureConnected(); openStream("shell:$command") }.getOrElse {
            log("stale connection (${it.message ?: it}), reconnecting")
            runCatching { disconnect() }
            ensureConnected()
            openStream("shell:$command")
        }
        hostStream?.let { runCatching { it.close() } }
        hostStream = stream
        Thread {
            runCatching { stream.openInputStream().bufferedReader().forEachLine(onLine) }
            log("host shell stream ended")
        }.apply { isDaemon = true }.start()
    }

    private fun ensureConnected() {
        if (isConnected) return
        if (canWriteSecureSettings() && Settings.Global.getInt(context.contentResolver, ADB_WIFI, 0) != 1) {
            Settings.Global.putInt(context.contentResolver, ADB_WIFI, 1)
            log("switched Wireless debugging on")
        }
        val port = findPort(AdbMdns.SERVICE_TYPE_TLS_CONNECT, DISCOVERY_MS)
            ?: error("Wireless debugging tidak ditemukan. Nyalakan Wireless debugging (perlu Wi-Fi).")
        check(connect(LOCALHOST, port)) { "ADB menolak koneksi" }
        log("connected on port $port")
        if (!canWriteSecureSettings()) grantSelf()
    }

    /**
     * Lets Beka switch Wireless debugging on by itself after a reboot, and keeps adbd from forgetting
     * the pairing after a week without use.
     */
    private fun grantSelf() {
        runCatching {
            val out = exec("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; settings put global adb_allowed_connection_time 0; echo done")
            log("grant: ${out.trim()}")
        }.onFailure { log("grant failed: $it") }
    }

    /** Runs a short command and returns its output. */
    private fun exec(command: String): String {
        val stream = openStream("shell:$command")
        return stream.use { it.openInputStream().bufferedReader().readText() }
    }

    /** Port of an adbd service of [type] on this phone (mDNS also sees other phones on the Wi-Fi). */
    fun findPort(type: String, timeoutMs: Long): Int? {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)
        val local = localAddresses()
        val mdns = AdbMdns(context, type) { host, p ->
            if (host != null && host.hostAddress in local && p > 0) {
                port.set(p)
                found.countDown()
            }
        }
        mdns.start()
        try {
            found.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        return port.get().takeIf { it > 0 }
    }

    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
    }.getOrDefault(emptySet())

    private fun log(msg: String) {
        Log.i(DisplayHostService.TAG, "adb: $msg")
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val PAIRED = "paired"
        private const val ADB_WIFI = "adb_wifi_enabled"
        private const val DISCOVERY_MS = 8_000L
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
