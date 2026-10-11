package dev.beka.rules

import dev.beka.llm.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A proactive rule: trigger → conditions → action. Stored as JSON, written by a model from the
 * user's own words and shown to them as a card before it is saved.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    val action: RuleAction,
    val budget: Budget = Budget(),
) {
    /** Packages whose permission caps this rule: the trigger's app and the apps the action works in. */
    val packages: List<String>
        get() = (listOfNotNull(trigger.pkg?.takeIf { it.isNotBlank() }) + action.apps).distinct()

    /** What is wrong with the rule, for the rule card (in [dev.beka.llm.Lang.current]). Empty when it can be saved. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add(tr("The rule has no name", "Nama rule kosong"))
        if (action.prompt.isBlank()) add(tr("The task (prompt) is empty", "Tugas (prompt) kosong"))
        when (trigger) {
            is Trigger.Schedule -> {
                if (Schedules.parseTime(trigger.time) == null) add(tr("Time \"${trigger.time}\" is not valid (use HH:mm)", "Jam \"${trigger.time}\" tidak valid (pakai HH:mm)"))
                trigger.days.filter { Schedules.parseDay(it) == null }.forEach { add(tr("Unknown day \"$it\"", "Hari \"$it\" tidak dikenal")) }
            }
            else -> if (trigger.pkg.isNullOrBlank()) add(appMissing(trigger.app.orEmpty()))
        }
        conditions.forEach { c ->
            when (c) {
                is Condition.TimeBetween ->
                    if (Schedules.parseTime(c.from) == null || Schedules.parseTime(c.to) == null) add(tr("Time range ${c.from}–${c.to} is not valid", "Rentang jam ${c.from}–${c.to} tidak valid"))
                is Condition.DayOfWeek -> c.days.filter { Schedules.parseDay(it) == null }.forEach { add(tr("Unknown day \"$it\"", "Hari \"$it\" tidak dikenal")) }
                is Condition.Cooldown -> if (c.minutes < 0) add(tr("Cooldown cannot be negative", "Cooldown tidak boleh negatif"))
                is Condition.ScreenContains -> if (c.text.isBlank()) add(tr("screen_contains text is empty", "Teks screen_contains kosong"))
                is Condition.LlmCheck -> if (c.question.isBlank()) add(tr("llm_check question is empty", "Pertanyaan llm_check kosong"))
            }
        }
        action.apps.filterNot { looksLikePackage(it) }.forEach { add(appMissing(it)) }
        // Acting rules may act only in their own apps; with none they could do nothing at all.
        if (action.level.acts && packages.isEmpty()) add(tr("A rule that acts needs the app it works in (e.g. Settings)", "Rule yang bertindak perlu app tempat ia bekerja (misalnya Settings)"))
        if (trigger is Trigger.AppClosed && conditions.any { it is Condition.ScreenContains }) {
            add(tr("screen_contains cannot be used when an app closes", "screen_contains tidak bisa dipakai saat app ditutup"))
        }
        if (budget.maxRunsPerDay < 1) add(tr("The daily budget must be at least 1", "Budget harian minimal 1"))
    }

    private fun appMissing(app: String) = tr("App \"$app\" is not on this phone", "App \"$app\" tidak ditemukan di HP ini")

    /**
     * Fills in packages from the app names a model wrote. Names [resolve] cannot place stay as they
     * are, and [problems] reports them.
     */
    fun resolveApps(resolve: (String) -> String?): Rule {
        fun pkgOf(pkg: String, app: String?) = pkg.ifBlank { app?.let(resolve).orEmpty() }
        val t = when (trigger) {
            is Trigger.AppOpened -> trigger.copy(pkg = pkgOf(trigger.pkg, trigger.app))
            is Trigger.AppClosed -> trigger.copy(pkg = pkgOf(trigger.pkg, trigger.app))
            is Trigger.Notification -> trigger.copy(pkg = pkgOf(trigger.pkg, trigger.app))
            is Trigger.Schedule -> trigger
        }
        val apps = action.apps.map { if (looksLikePackage(it)) it else resolve(it) ?: it }.distinct()
        return copy(trigger = t, action = action.copy(apps = apps - listOfNotNull(t.pkg)))
    }

    fun toJson(): String = RuleJson.pretty.encodeToString(serializer(), this)

    companion object {
        private val PACKAGE = Regex("""[a-zA-Z][\w]*(\.[a-zA-Z_][\w]*)+""")

        fun looksLikePackage(name: String): Boolean = PACKAGE.matches(name)

        /** @throws IllegalArgumentException with a readable message when the JSON is not a rule. */
        fun fromJson(text: String): Rule = try {
            RuleJson.lenient.decodeFromString(serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException(tr("Invalid rule JSON: ", "JSON rule tidak valid: ") + e.message?.lineSequence()?.firstOrNull(), e)
        }
    }
}

@Serializable
sealed interface Trigger {
    /** Package the trigger watches; null for [Schedule]. */
    val pkg: String?

    /** App name as the user said it, kept for display and to resolve [pkg] on this phone. */
    val app: String?

    @Serializable
    @SerialName("app_opened")
    data class AppOpened(@SerialName("package") override val pkg: String = "", override val app: String? = null) : Trigger

    @Serializable
    @SerialName("app_closed")
    data class AppClosed(@SerialName("package") override val pkg: String = "", override val app: String? = null) : Trigger

    /** Any of [keywords] must appear in the notification's title or text; empty = every notification. */
    @Serializable
    @SerialName("notification")
    data class Notification(
        @SerialName("package") override val pkg: String = "",
        override val app: String? = null,
        val keywords: List<String> = emptyList(),
    ) : Trigger

    /** [time] is HH:mm; [days] like "mon" or "senin", empty = every day. */
    @Serializable
    @SerialName("schedule")
    data class Schedule(val time: String, val days: List<String> = emptyList()) : Trigger {
        override val pkg: String? get() = null
        override val app: String? get() = null
    }
}

@Serializable
sealed interface Condition {
    /** Case-insensitive. Checks the screen for app triggers, the notification for notification triggers. */
    @Serializable
    @SerialName("screen_contains")
    data class ScreenContains(val text: String) : Condition

    /** HH:mm, inclusive; wraps past midnight when [to] is before [from]. */
    @Serializable
    @SerialName("time_between")
    data class TimeBetween(val from: String, val to: String) : Condition

    @Serializable
    @SerialName("day_of_week")
    data class DayOfWeek(val days: List<String>) : Condition

    /** Minutes since this rule last fired. */
    @Serializable
    @SerialName("cooldown")
    data class Cooldown(val minutes: Int) : Condition

    /** A yes/no question to the model about the screen or notification. Checked last: it costs a model call. */
    @Serializable
    @SerialName("llm_check")
    data class LlmCheck(val question: String) : Condition
}

@Serializable
data class RuleAction(
    /** The task, in the user's words. */
    val prompt: String,
    val level: Level = Level.SUGGEST,
    /** Other apps (packages) the task works in, e.g. the music app a morning rule opens. */
    val apps: List<String> = emptyList(),
)

@Serializable
data class Budget(@SerialName("max_runs_per_day") val maxRunsPerDay: Int = 10)

/** How far a rule may go, from showing a note to acting on its own. */
@Serializable
enum class Level {
    /** Only a note, as a notification. */
    @SerialName("notify") NOTIFY,

    /** A note with a "Lakukan" button that hands the task to the agent. */
    @SerialName("suggest") SUGGEST,

    /** The agent acts after the user allows it once (sending, paying and deleting still ask each time). */
    @SerialName("confirm") CONFIRM,

    /** The agent acts; only high-risk actions (send, pay, delete...) ask first. */
    @SerialName("auto") AUTO;

    val acts: Boolean get() = this >= CONFIRM
}

/** What the user allows Beka to do in an app on its own. No entry = proactive off for that app. */
@Serializable
enum class AppPermission {
    /** Never read, never trigger. */
    @SerialName("blocked") BLOCKED,
    @SerialName("read") READ,
    @SerialName("suggest") SUGGEST,
    @SerialName("act") ACT;

    /** The highest level a rule gets under this permission, or null if it may not run. */
    fun cap(level: Level): Level? = when (this) {
        BLOCKED -> null
        READ -> Level.NOTIFY
        SUGGEST -> minOf(level, Level.SUGGEST)
        ACT -> level
    }

    companion object {
        /** The permission a rule at [level] needs in its trigger app. */
        fun needed(level: Level): AppPermission = when (level) {
            Level.NOTIFY -> READ
            Level.SUGGEST -> SUGGEST
            Level.CONFIRM, Level.AUTO -> ACT
        }
    }
}

object RuleJson {
    val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    val pretty = Json { prettyPrint = true; encodeDefaults = true }
}

object Schedules {
    private val DAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY, "senin" to DayOfWeek.MONDAY, "sen" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY, "selasa" to DayOfWeek.TUESDAY, "sel" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "rabu" to DayOfWeek.WEDNESDAY, "rab" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY, "kamis" to DayOfWeek.THURSDAY, "kam" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY, "jumat" to DayOfWeek.FRIDAY, "jum'at" to DayOfWeek.FRIDAY, "jum" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY, "sabtu" to DayOfWeek.SATURDAY, "sab" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY, "minggu" to DayOfWeek.SUNDAY, "ahad" to DayOfWeek.SUNDAY, "min" to DayOfWeek.SUNDAY,
    )
    private val TIME = Regex("""(\d{1,2})[:.](\d{2})""")

    fun parseTime(text: String): LocalTime? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    fun parseDay(text: String): DayOfWeek? = DAYS[text.trim().lowercase()]

    /** Empty = every day. Unknown names are ignored (the rule card reports them). */
    fun parseDays(days: List<String>): Set<DayOfWeek> =
        days.mapNotNull(::parseDay).toSet().ifEmpty { DayOfWeek.entries.toSet() }

    /** The first time the schedule is due strictly after [after], or null if its time is invalid. */
    fun next(schedule: Trigger.Schedule, after: java.time.LocalDateTime): java.time.LocalDateTime? {
        val time = parseTime(schedule.time) ?: return null
        val days = parseDays(schedule.days)
        var date = after.toLocalDate()
        repeat(8) {
            val at = date.atTime(time)
            if (at.isAfter(after) && date.dayOfWeek in days) return at
            date = date.plusDays(1)
        }
        return null
    }
}
