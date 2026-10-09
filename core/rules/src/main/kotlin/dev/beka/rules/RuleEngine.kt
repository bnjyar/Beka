package dev.beka.rules

import java.time.Duration
import java.time.LocalDateTime

/** Something that happened on the phone and may fire rules. */
sealed interface RuleEvent {
    val time: LocalDateTime

    data class AppOpened(val pkg: String, override val time: LocalDateTime) : RuleEvent
    data class AppClosed(val pkg: String, override val time: LocalDateTime) : RuleEvent
    data class Notification(val pkg: String, val title: String, val text: String, override val time: LocalDateTime) : RuleEvent

    /** The schedule of rule [ruleId] came due. */
    data class ScheduleDue(val ruleId: String, override val time: LocalDateTime) : RuleEvent
}

/** What the engine needs from the phone and from past runs. */
interface RuleEnv {
    /** Null = the user never set it, which keeps proactive rules off for that app. */
    fun permission(pkg: String): AppPermission?

    /** When the rule last fired, or null if never. */
    fun lastRun(ruleId: String): LocalDateTime?

    /** How often the rule fired since midnight. */
    fun runsToday(ruleId: String): Int

    /** Text of the current screen for `screen_contains`, or null if unreadable. Called only when needed. */
    suspend fun screenText(): String?

    /** Asks the model a yes/no [question] about [context]. */
    suspend fun check(question: String, context: String): Boolean
}

/** A rule whose trigger matched [event]: [level] is what it may run at, or null with the [reason] it did not fire. */
data class Decision(val rule: Rule, val event: RuleEvent, val level: Level?, val reason: String) {
    val fires: Boolean get() = level != null
}

/**
 * Matches events to rules and checks their conditions, cheapest first: the model is only asked
 * (`llm_check`) once everything else passed.
 */
class RuleEngine {
    /** One decision per enabled rule whose trigger matches [event]; rules it does not concern are left out. */
    suspend fun evaluate(event: RuleEvent, rules: List<Rule>, env: RuleEnv): List<Decision> =
        rules.filter { it.enabled && matches(it.trigger, it.id, event) }.map { decide(it, event, env) }

    private suspend fun decide(rule: Rule, event: RuleEvent, env: RuleEnv): Decision {
        fun skip(reason: String) = Decision(rule, event, null, reason)

        // Anyone can send a message: a notification may start actions, but never unasked ones.
        var level: Level = if (rule.trigger is Trigger.Notification) minOf(rule.action.level, Level.CONFIRM) else rule.action.level
        for (pkg in rule.packages) {
            val permission = env.permission(pkg) ?: return skip("no proactive permission for $pkg")
            level = permission.cap(level) ?: return skip("$pkg is blocked")
        }
        if (env.runsToday(rule.id) >= rule.budget.maxRunsPerDay) return skip("daily budget used up")

        val last = env.lastRun(rule.id)
        if (last != null && Duration.between(last, event.time).toMinutes() < gapMinutes(rule)) {
            return skip("cooldown")
        }

        if (event is RuleEvent.Notification) {
            val keywords = (rule.trigger as Trigger.Notification).keywords.filter { it.isNotBlank() }
            val text = event.title + "\n" + event.text
            if (keywords.isNotEmpty() && keywords.none { text.contains(it, ignoreCase = true) }) return skip("no keyword")
        }

        val now = event.time
        for (c in rule.conditions) {
            when (c) {
                is Condition.TimeBetween -> {
                    val from = Schedules.parseTime(c.from) ?: return skip("bad time")
                    val to = Schedules.parseTime(c.to) ?: return skip("bad time")
                    val t = now.toLocalTime()
                    val inside = if (!to.isBefore(from)) !t.isBefore(from) && !t.isAfter(to) else !t.isBefore(from) || !t.isAfter(to)
                    if (!inside) return skip("outside ${c.from}–${c.to}")
                }
                is Condition.DayOfWeek -> if (now.dayOfWeek !in Schedules.parseDays(c.days)) return skip("not today")
                else -> Unit
            }
        }

        var cached: String? = null
        suspend fun context(): String? {
            if (cached == null) cached = contextOf(event, env)
            return cached
        }
        for (c in rule.conditions.filterIsInstance<Condition.ScreenContains>()) {
            val text = context() ?: return skip("screen unreadable")
            if (!text.contains(c.text.trim(), ignoreCase = true)) return skip("screen lacks \"${c.text}\"")
        }
        for (c in rule.conditions.filterIsInstance<Condition.LlmCheck>()) {
            if (!env.check(c.question, context().orEmpty())) return skip("llm_check said no")
        }
        return Decision(rule, event, level, "fired")
    }

    private suspend fun contextOf(event: RuleEvent, env: RuleEnv): String? = when (event) {
        is RuleEvent.Notification -> event.title + "\n" + event.text
        else -> env.screenText()
    }

    private fun matches(trigger: Trigger, ruleId: String, event: RuleEvent): Boolean = when (trigger) {
        is Trigger.AppOpened -> event is RuleEvent.AppOpened && event.pkg == trigger.pkg
        is Trigger.AppClosed -> event is RuleEvent.AppClosed && event.pkg == trigger.pkg
        is Trigger.Notification -> event is RuleEvent.Notification && event.pkg == trigger.pkg
        is Trigger.Schedule -> event is RuleEvent.ScheduleDue && event.ruleId == ruleId
    }

    companion object {
        /** Minutes that must pass between two runs of [rule]: its cooldown, or the floor for its trigger. */
        fun gapMinutes(rule: Rule): Int {
            val cooldown = rule.conditions.filterIsInstance<Condition.Cooldown>().maxOfOrNull { it.minutes } ?: 0
            val floor = if (rule.trigger is Trigger.AppOpened || rule.trigger is Trigger.AppClosed) APP_MIN_GAP_MINUTES else MIN_GAP_MINUTES
            return maxOf(cooldown, floor)
        }

        /** The first moment [rule] may fire again after running at [last]. */
        fun readyAt(rule: Rule, last: LocalDateTime): LocalDateTime = last.plusMinutes(gapMinutes(rule).toLong())

        /** No rule fires twice within this many minutes, cooldown or not: one app switch can look like several. */
        const val MIN_GAP_MINUTES = 2

        /** App triggers fire on every switch (a share sheet and back counts as one); this keeps them from nagging. */
        const val APP_MIN_GAP_MINUTES = 10
    }
}
