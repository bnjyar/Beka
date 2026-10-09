package dev.beka.accessibility

import android.content.Intent
import android.content.pm.PackageManager

/** Maps the app name a user or model wrote to an installed launcher app's package. */
object AppResolver {
    class App(val pkg: String, val label: String)

    fun launcherApps(pm: PackageManager): List<App> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }

    /**
     * Exact label or package, then label prefix/substring, then every word of the name found in the
     * label or package ("YouTube Music" → YT Music's com.google.android.apps.youtube.music).
     */
    fun resolve(apps: List<App>, name: String): String? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        val words = wanted.split(Regex("\\W+")).filter { it.isNotEmpty() }
        fun App.l() = label.lowercase()
        return (apps.firstOrNull { it.l() == wanted || it.pkg == wanted }
            ?: apps.firstOrNull { it.l().startsWith(wanted) }
            ?: apps.firstOrNull { wanted in it.l() }
            ?: apps.firstOrNull { app -> words.isNotEmpty() && words.all { it in app.l() || it in app.pkg } })?.pkg
    }
}
