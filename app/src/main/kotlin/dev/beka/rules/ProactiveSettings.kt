package dev.beka.rules

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

class ProactiveSettings(context: Context) {
    private val prefs = context.getSharedPreferences("proactive", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** The kill switch: off stops every rule at once. */
    val enabled: StateFlow<Boolean> = _enabled

    /** Model calls proactive rules may make per day, llm_check included. Manual chat is not counted. */
    val dailyCalls: Int get() = prefs.getInt(KEY_DAILY_CALLS, DEFAULT_DAILY_CALLS)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    /** Re-reads the stored values, after a backup was imported. */
    fun reload() {
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
    }

    /** Model calls made by rules today. */
    @Synchronized
    fun callsToday(): Int = if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_CALLS, 0) else 0

    /** Counts one model call; false (and nothing counted) once today's budget is used up. */
    @Synchronized
    fun spendCall(): Boolean {
        val used = callsToday()
        if (used >= dailyCalls) return false
        prefs.edit().putString(KEY_DAY, today()).putInt(KEY_CALLS, used + 1).apply()
        return true
    }

    /** Whether the "budget used up" notification was already shown today. */
    @Synchronized
    fun warnOncePerDay(): Boolean {
        if (prefs.getString(KEY_WARNED, null) == today()) return false
        prefs.edit().putString(KEY_WARNED, today()).apply()
        return true
    }

    /** Last time schedules were checked, so an alarm that fires late still catches what came due. */
    var scheduleCursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) = prefs.edit().putLong(KEY_CURSOR, value).apply()

    /** Whether the "allow exact alarms" hint was already shown (it is shown once). */
    var exactAlarmHintShown: Boolean
        get() = prefs.getBoolean(KEY_EXACT_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_EXACT_HINT, value).apply()

    private fun today() = LocalDate.now().toString()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_DAILY_CALLS = "daily_calls"
        const val KEY_DAY = "calls_day"
        const val KEY_CALLS = "calls"
        const val KEY_WARNED = "warned_day"
        const val KEY_EXACT_HINT = "exact_alarm_hint"
        const val KEY_CURSOR = "schedule_cursor"
        const val DEFAULT_DAILY_CALLS = 40
    }
}
