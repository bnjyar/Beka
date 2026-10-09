package dev.beka.phone

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.app.Notification
import dev.beka.BekaApp
import dev.beka.accessibility.AppResolver
import dev.beka.agent.CalendarEvent
import dev.beka.agent.DeviceInfo
import dev.beka.agent.Phone
import dev.beka.agent.PhoneContact
import dev.beka.agent.PhoneNotification
import dev.beka.agent.ToolFailed
import dev.beka.media.BekaNotificationListener
import dev.beka.rules.saveWithGrants
import dev.beka.rules.RuleDrafter
import dev.beka.rules.RuleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.UUID

/** [Phone] over Android intents and system services. Permissions that are missing become [ToolFailed] the model can relay. */
class AndroidPhone(private val app: BekaApp) : Phone {
    override suspend fun setAlarm(hour: Int, minute: Int, label: String?, days: List<String>): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { DAY_CONSTANTS.getValue(it) }))
        start(intent, "No clock app can set alarms on this phone.")
        val time = "%02d:%02d".format(hour, minute)
        return "Alarm set for $time" + if (days.isEmpty()) "." else " on ${days.joinToString(", ")}."
    }

    override suspend fun setTimer(seconds: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(intent, "No clock app can set timers on this phone.")
        return "Timer started for $seconds seconds."
    }

    override suspend fun addCalendarEvent(event: CalendarEvent): String {
        val zone = ZoneId.systemDefault()
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        if (event.allDay) {
            // All-day events are stored as UTC midnight.
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            event.end?.let {
                intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            }
        } else {
            val end = event.end ?: event.start.plusHours(1)
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start.atZone(zone).toInstant().toEpochMilli())
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
        }
        event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        event.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        start(intent, "No calendar app is installed.")
        return "The calendar app is showing the prefilled event; tap its Save button to save it."
    }

    override suspend fun findContacts(name: String, limit: Int): List<PhoneContact> {
        requirePermission(Manifest.permission.READ_CONTACTS, "Contacts")
        return withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val found = LinkedHashMap<Pair<String, String>, PhoneContact>()
            val cursor = try {
                app.contentResolver.query(uri, projection, null, null, null)
            } catch (e: SecurityException) {
                throw ToolFailed(permissionMessage("Contacts"))
            }
            cursor?.use { c ->
                while (c.moveToNext() && found.size < limit) {
                    val display = c.getString(0).orEmpty()
                    val number = normalize(c.getString(1).orEmpty())
                    if (number.isEmpty()) continue
                    val type = when (c.getInt(2)) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> c.getString(3).orEmpty()
                        else -> ""
                    }
                    found.getOrPut(display to number) { PhoneContact(display, number, type) }
                }
            }
            found.values.toList()
        }
    }

    override suspend fun call(number: String): String {
        val uri = Uri.fromParts("tel", number, null)
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            start(Intent(Intent.ACTION_CALL, uri), "No phone app can place calls on this phone.")
            return "Calling $number."
        }
        start(Intent(Intent.ACTION_DIAL, uri), "No phone app can place calls on this phone.")
        return "Opened the dialer with the number; the user must press call."
    }

    override suspend fun copyToClipboard(text: String): String = withContext(Dispatchers.Main) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Beka", text))
        "Copied to the clipboard."
    }

    override suspend fun readClipboard(): String = withContext(Dispatchers.Main) {
        val manager = app.getSystemService(ClipboardManager::class.java)
        val text = runCatching { manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString() }.getOrNull()
        if (text.isNullOrEmpty()) {
            throw ToolFailed("Android only lets Beka read the clipboard while its panel has focus; ask the user to paste it into the chat.")
        }
        text
    }

    override suspend fun shareText(text: String, app: String?): String {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (app == null) {
            start(Intent.createChooser(send, null), "No app can receive shared text.")
            return "The share sheet is open; the user picks where to send it."
        }
        val pkg = AppResolver.resolve(AppResolver.launcherApps(this.app.packageManager), app)
            ?: throw ToolFailed("No installed app matches \"$app\".")
        start(send.setPackage(pkg), "$app cannot receive shared text.")
        return "Opened $app with the text ready to send; the user finishes sending it there."
    }

    override suspend fun notifications(): List<PhoneNotification> {
        val listener = BekaNotificationListener.instance
            ?: throw ToolFailed("Notification access is off; ask the user to allow it in Beka → Setelan → Izin (Akses notifikasi).")
        return withContext(Dispatchers.IO) {
            val active = try {
                listener.activeNotifications.orEmpty()
            } catch (e: SecurityException) {
                throw ToolFailed("Notification access is off; ask the user to allow it in Beka → Setelan → Izin (Akses notifikasi).")
            }
            active.filter { sbn ->
                val flags = sbn.notification.flags
                sbn.packageName != app.packageName &&
                    !sbn.isOngoing &&
                    (flags and Notification.FLAG_FOREGROUND_SERVICE) == 0 &&
                    (flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }.map { sbn ->
                val extras = sbn.notification.extras
                PhoneNotification(
                    packageName = sbn.packageName,
                    appLabel = app.proactive.label(sbn.packageName),
                    timeMillis = sbn.postTime,
                    title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                )
            }
        }
    }

    override suspend fun deviceInfo(): DeviceInfo {
        val battery = app.getSystemService(BatteryManager::class.java)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
        val dnd = when (app.getSystemService(NotificationManager::class.java).currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> false
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null
            else -> true
        }
        val ringer = when (app.getSystemService(AudioManager::class.java).ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return DeviceInfo(percent, battery.isCharging, network, dnd, ringer)
    }

    override suspend fun createRule(request: String): String {
        val config = app.providers.active() ?: throw ToolFailed("No AI provider is active in Beka, so a rule cannot be drafted.")
        val apps = AppResolver.launcherApps(app.packageManager)
        val rule = try {
            RuleDrafter(app.providers.client(config), config.model)
                .draft(request, LocalDateTime.now(), UUID.randomUUID().toString())
                .resolveApps { AppResolver.resolve(apps, it) }
        } catch (e: IllegalArgumentException) {
            throw ToolFailed("Could not turn that into a rule: ${e.message}")
        }
        val problems = rule.problems()
        if (problems.isNotEmpty()) throw ToolFailed("The drafted rule is not valid: ${problems.joinToString("; ")}. Ask the user to rephrase, or to create it in the Proaktif tab.")
        app.rulesStore.saveWithGrants(rule)
        val off = if (app.proactiveSettings.enabled.value) "" else " Proactive rules are switched off in Beka, so it will not run until the user turns them on."
        return "Rule saved: ${rule.name} (${RuleText.summary(rule, app.proactive::label)}).$off"
    }

    private fun start(intent: Intent, notFound: String) {
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            throw ToolFailed(notFound)
        } catch (e: SecurityException) {
            throw ToolFailed("Android refused to open that: ${e.message}")
        }
    }

    private fun requirePermission(permission: String, name: String) {
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw ToolFailed(permissionMessage(name))
    }

    private fun permissionMessage(name: String) = "$name permission is not granted; ask the user to allow it in Beka → Setelan → Izin."

    /** Keeps a leading + and the digits. */
    private fun normalize(raw: String): String = (if (raw.trim().startsWith("+")) "+" else "") + raw.filter { it.isDigit() }

    private companion object {
        val DAY_CONSTANTS = mapOf(
            "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
        )
    }
}
