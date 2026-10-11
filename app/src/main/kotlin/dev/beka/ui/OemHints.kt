package dev.beka.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Phone brands whose battery managers stop background apps beyond what stock Android does. */
enum class Oem(val hint: String) {
    XIAOMI("Nyalakan Mulai otomatis dan Tampilkan jendela pop-up saat berjalan di latar belakang."),
    OPPO_VIVO("Nyalakan Mulai otomatis / Izinkan aktivitas latar belakang."),
    SAMSUNG("Baterai → Tidak dibatasi, dan jangan masukkan Beka ke Aplikasi tidur.");

    companion object {
        fun current(): Oem? = when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> XIAOMI
            "oppo", "realme", "oneplus", "vivo", "iqoo" -> OPPO_VIVO
            "samsung" -> SAMSUNG
            else -> null
        }
    }
}

object OemHints {
    /** Whether "Buka setelan HP" is worth showing: every brand but Samsung, whose hint is text only. */
    fun showsSettingsButton(oem: Oem?) = oem == Oem.XIAOMI || oem == Oem.OPPO_VIVO

    /** The screens to try, best first; the last one (app details) exists on every phone. */
    fun intents(context: Context, oem: Oem?): List<Intent> = buildList {
        if (oem == Oem.XIAOMI) {
            add(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", context.packageName),
            )
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}
