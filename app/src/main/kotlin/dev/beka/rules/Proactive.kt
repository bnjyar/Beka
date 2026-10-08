package dev.beka.rules

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.inputmethod.InputMethodManager
import dev.beka.BekaApp
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.accessibility.ScreenContext
import dev.beka.accessibility.toObservation
import dev.beka.agent.Action
import dev.beka.agent.ActionFailed
import dev.beka.agent.Agent
import dev.beka.agent.AgentConfig
import dev.beka.agent.AgentEvent
import dev.beka.agent.Blocklist
import dev.beka.agent.Device
import dev.beka.agent.Observation
import dev.beka.memory.Memory
import dev.beka.overlay.OverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Runs proactive rules: turns app switches, notifications and alarms into [RuleEvent]s, asks the
 * [RuleEngine] which rules fire, and carries them out — a note as a notification, or an agent run
 * through the chat (with its status strip and Stop button) for rules allowed to act.
 */
class Proactive(private val app: BekaApp) {
    private val store get() = app.rulesStore
    private val settings get() = app.proactiveSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Events are handled one at a time, so two rules never run over each other. */
    private val mutex = Mutex()
    private val engine = RuleEngine()

    /** The app in front once switching settled. */
    private var current: String? = null
    private var settling: Job? = null

    /** Until this uptime, app switches are Beka's own doing (an agent run) and fire nothing. */
    @Volatile
    private var quietUntil = 0L

    /** Notification signatures seen recently: apps re-post the same notification on every update. */
    private val seen = LinkedHashMap<String, Long>()

    private val imePackages: Set<String> by lazy {
        app.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
    }

    fun start() {
        scope.launch {
            app.chat.busy.collect { busy -> quietUntil = if (busy) Long.MAX_VALUE else SystemClock.uptimeMillis() + QUIET_AFTER_RUN_MS }
        }
        scope.launch { combine(store.rules, settings.enabled) { _, _ -> }.collect { reschedule() } }
        scope.launch(Dispatchers.IO) { runCatching { store.prune() } }
    }

    // ---- Event sources ----

    /** Every window change from the accessibility service (Beka and System UI already left out). */
    fun onForeground(pkg: String) {
        if (pkg in imePackages) return
        settling?.cancel()
        settling = scope.launch {
            // A dialog or a quick back-and-forth is not an app switch: only count where things settle.
            delay(SETTLE_MS)
            val previous = current
            if (previous == pkg) return@launch
            current = pkg
            if (SystemClock.uptimeMillis() < quietUntil) return@launch
            val now = LocalDateTime.now()
            previous?.let { submit(RuleEvent.AppClosed(it, now)) }
            submit(RuleEvent.AppOpened(pkg, now))
        }
    }

    fun onNotification(sbn: StatusBarNotification) {
        if (!settings.enabled.value) return
        val pkg = sbn.packageName
        if (pkg == app.packageName || Blocklist.isBlocked(pkg)) return
        // Only apps the user made a notification rule for are read at all.
        if (store.rules.value.none { it.enabled && it.trigger is Trigger.Notification && it.trigger.pkg == pkg }) return
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(seen) {
            seen.entries.removeAll { now - it.value > DEDUPE_MS }
            val signature = "${sbn.key}|$title|$text"
            if (signature in seen) return
            seen[signature] = now
        }
        submit(RuleEvent.Notification(pkg, redact(title), redact(text), LocalDateTime.now()))
    }

    /** The schedule alarm went off: fire every schedule rule that came due since the last check. */
    fun onAlarm() {
        scope.launch(Dispatchers.IO) {
            val nowMillis = System.currentTimeMillis()
            val now = toLocal(nowMillis)
            val cursor = toLocal(settings.scheduleCursor.takeIf { it > 0 } ?: (nowMillis - 60_000))
            settings.scheduleCursor = nowMillis
            if (settings.enabled.value) {
                for (rule in store.rules.value.filter { it.enabled }) {
                    val schedule = rule.trigger as? Trigger.Schedule ?: continue
                    var due = Schedules.next(schedule, cursor)
                    var latest: LocalDateTime? = null
                    while (due != null && !due.isAfter(now)) {
                        latest = due
                        due = Schedules.next(schedule, due)
                    }
                    // A phone that was off at the time does not get a pile of stale reminders.
                    if (latest != null && Duration.between(latest, now).toMinutes() <= STALE_SCHEDULE_MINUTES) {
                        submit(RuleEvent.ScheduleDue(rule.id, now))
                    } else if (latest != null) {
                        Log.i(TAG, "${rule.name}: schedule $latest missed, skipped")
                    }
                }
            }
            reschedule()
        }
    }

    /** Sets the alarm for the next schedule rule; called whenever rules or the kill switch change. */
    fun reschedule() {
        val alarms = app.getSystemService(AlarmManager::class.java)
        val pending = ProactiveReceiver.alarmIntent(app)
        alarms.cancel(pending)
        if (!settings.enabled.value) return
        val now = LocalDateTime.now()
        val next = store.rules.value
            .filter { it.enabled }
            .mapNotNull { rule -> (rule.trigger as? Trigger.Schedule)?.let { Schedules.next(it, now) } }
            .minOrNull() ?: return
        if (settings.scheduleCursor == 0L) settings.scheduleCursor = System.currentTimeMillis()
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Log.i(TAG, "next schedule at $next (exact=$exact)")
    }

    // ---- Notification buttons ----

    fun markWrong(runId: Long) {
        scope.launch { store.markWrong(runId, true) }
    }

    /** "Lakukan" on a suggestion: the user asked for it, so it runs like a request typed into the chat. */
    fun accept(ruleId: String, runId: Long, note: String) {
        val rule = store.rules.value.firstOrNull { it.id == ruleId } ?: return
        if (app.chat.busy.value) return
        scope.launch { store.updateRun(runId, "accepted", note) }
        val task = "The user tapped \"Lakukan\" on your suggestion from the proactive rule \"${rule.name}\".\n" +
            "Task: ${rule.action.prompt.trim()}\nYour note: $note\nDo it now."
        app.chat.runRule(rule.name, task, confirmAll = false, isBlocked = Blocklist::isBlocked)
    }

    // ---- Running rules ----

    private fun submit(event: RuleEvent) {
        if (!settings.enabled.value) return
        val pkg = when (event) {
            is RuleEvent.AppOpened -> event.pkg
            is RuleEvent.AppClosed -> event.pkg
            is RuleEvent.Notification -> event.pkg
            is RuleEvent.ScheduleDue -> null
        }
        if (pkg != null && store.rules.value.none { it.enabled && it.trigger.pkg == pkg }) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "rule handling failed for $event", e)
                }
            }
        }
    }

    private suspend fun handle(event: RuleEvent) {
        if (!settings.enabled.value) return
        // Never start a rule over a running task, the user's or another rule's.
        if (app.chat.busy.value) {
            Log.i(TAG, "skipped $event: agent busy")
            return
        }
        val env = EventEnv(event)
        for (decision in engine.evaluate(event, store.rules.value, env)) {
            Log.i(TAG, "${decision.rule.name} on ${describe(event)}: ${decision.reason}")
            if (decision.fires) fire(decision, env)
            if (env.outOfBudget) break
        }
    }

    private suspend fun fire(decision: Decision, env: EventEnv) {
        val rule = decision.rule
        val level = decision.level ?: return
        val trigger = describe(decision.event)
        if (!settings.spendCall()) {
            env.outOfBudget = true
            store.addRun(RuleRun(ruleId = rule.id, ruleName = rule.name, time = now(), trigger = trigger, level = level, outcome = "skipped", text = "budget harian habis", counted = false))
            budgetNotice()
            return
        }
        // Acting needs the overlay (status strip, Stop, confirmations) and an unlocked phone; otherwise offer it instead.
        val canAct = OverlayService.running.value && !locked() && !app.chat.busy.value
        val effective = if (level.acts && !canAct) Level.SUGGEST else level
        val task = ProactivePrompt.task(decision.copy(level = effective), ::label)

        if (effective.acts) {
            val runId = store.addRun(RuleRun(ruleId = rule.id, ruleName = rule.name, time = now(), trigger = trigger, level = effective, outcome = "acting"))
            withContext(Dispatchers.Main) {
                app.chat.runRule(rule.name, task, confirmAll = effective == Level.CONFIRM, isBlocked = ::blocksActing) { summary ->
                    scope.launch { store.updateRun(runId, "acted", summary) }
                }
            }
            return
        }

        val runId = store.addRun(RuleRun(ruleId = rule.id, ruleName = rule.name, time = now(), trigger = trigger, level = effective, outcome = "running"))
        val screen = if (decision.event is RuleEvent.AppOpened) env.screen() else null
        val reply = try {
            noteFor(task, screen?.toObservation(app.media.describe()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${rule.name} failed", e)
            store.updateRun(runId, "error", e.message ?: e.toString())
            return
        }
        if (ProactivePrompt.isNothing(reply)) {
            store.updateRun(runId, "nothing", "")
            return
        }
        notify(rule, runId, effective, reply.trim())
        store.updateRun(runId, if (effective == Level.SUGGEST) "suggested" else "notified", reply.trim())
    }

    /** A read-only agent run: the model sees the task (and the screen) and writes a short note. */
    private suspend fun noteFor(task: String, screen: Observation?): String {
        val config = app.providers.active() ?: throw IllegalStateException("Belum ada provider aktif")
        val agent = Agent(
            provider = app.providers.client(config),
            model = config.model,
            device = NoDevice,
            confirm = { false },
            config = AgentConfig(actions = false, screenshots = false, maxTokens = 600),
            memory = Memory(app.memoryStore).takeIf { app.memorySettings.enabled.value },
        )
        var text = ""
        agent.run(emptyList(), task, screen).collect { if (it is AgentEvent.Finished) text = it.text }
        return text
    }

    /** What a proactive agent run may act in: only apps the user gave the "act" permission. */
    private fun blocksActing(pkg: String?): Boolean =
        Blocklist.isBlocked(pkg) || (pkg != null && store.permissions.value[pkg] != AppPermission.ACT)

    private inner class EventEnv(private val event: RuleEvent) : RuleEnv {
        var outOfBudget = false
        private var captured: ScreenContext? = null
        private var read = false

        override fun permission(pkg: String): AppPermission? =
            if (Blocklist.isBlocked(pkg)) AppPermission.BLOCKED else store.permissions.value[pkg]

        override fun lastRun(ruleId: String) = store.lastRun(ruleId)

        override fun runsToday(ruleId: String) = store.runsToday(ruleId)

        override suspend fun screenText(): String? = screen()?.tree

        /** The screen once it settled; for an app that was just opened, null if the user already left it. */
        suspend fun screen(): ScreenContext? {
            if (read) return captured
            read = true
            if (event is RuleEvent.AppClosed) return null
            val service = BekaAccessibilityService.instance ?: return null
            service.awaitIdle(maxMs = 2000)
            val tree = withContext(Dispatchers.Main) { service.captureTree() } ?: return null
            if (event is RuleEvent.AppOpened && tree.packageName != event.pkg) return null
            captured = tree
            return tree
        }

        override suspend fun check(question: String, context: String): Boolean {
            if (outOfBudget || !settings.spendCall()) {
                outOfBudget = true
                budgetNotice()
                return false
            }
            val config = app.providers.active() ?: return false
            return try {
                RuleChecker(app.providers.client(config), config.model).check(question, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "llm_check failed", e)
                false
            }
        }
    }

    /** The agent needs a device, but a note-only run never touches it. */
    private object NoDevice : Device {
        override suspend fun observe(screenshot: Boolean): Observation? = null
        override suspend fun readFullPage(): String? = null
        override fun label(nodeId: String): String? = null
        override fun resolveApp(name: String): String? = null
        override suspend fun perform(action: Action): String = throw ActionFailed("Actions are off for this run")
    }

    // ---- Helpers ----

    private fun notify(rule: Rule, runId: Long, level: Level, text: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rule proaktif", NotificationManager.IMPORTANCE_DEFAULT))
        val id = NOTIFICATION_BASE + (runId % 10_000).toInt()
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚡ ${rule.name}")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (level == Level.SUGGEST) {
            builder.addAction(Notification.Action.Builder(null, "Lakukan", OverlayService.acceptIntent(app, rule.id, runId, id, text)).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Salah trigger", ProactiveReceiver.wrongIntent(app, runId, id)).build())
        manager.notify(id, builder.build())
    }

    private fun budgetNotice() {
        if (!settings.warnOncePerDay()) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Rule proaktif", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            NOTIFICATION_BASE - 1,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Budget proaktif hari ini habis")
                .setContentText("Rule berhenti sampai besok (${settings.dailyCalls} panggilan model/hari). Chat biasa tetap jalan.")
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun describe(event: RuleEvent): String = when (event) {
        is RuleEvent.AppOpened -> "${label(event.pkg)} dibuka"
        is RuleEvent.AppClosed -> "${label(event.pkg)} ditutup"
        is RuleEvent.Notification -> "Notifikasi ${label(event.pkg)}: ${event.title}".trimEnd(':', ' ')
        is RuleEvent.ScheduleDue -> "Jadwal ${event.time.format(HHMM)}"
    }

    fun label(pkg: String): String = runCatching {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun locked(): Boolean = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun now() = System.currentTimeMillis()

    private fun toLocal(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    companion object {
        private const val TAG = "BekaRules"
        private const val CHANNEL = "proactive"
        private const val NOTIFICATION_BASE = 1000
        private const val SETTLE_MS = 1500L
        private const val QUIET_AFTER_RUN_MS = 5000L
        private const val DEDUPE_MS = 30L * 60 * 1000
        private const val STALE_SCHEDULE_MINUTES = 30L
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        private val SECRET_HINT = Regex("""otp|kode|code|verifikasi|verification|\bpin\b|sandi|password""", RegexOption.IGNORE_CASE)
        private val CODE = Regex("""\b\d{4,8}\b""")
        private val CARD = Regex("""\b(?:\d[ -]?){12,18}\d\b""")

        /** Notification text leaves the phone only after codes and card numbers are masked. */
        fun redact(text: String): String {
            val masked = CARD.replace(text, "[REDACTED]")
            return if (SECRET_HINT.containsMatchIn(masked)) CODE.replace(masked, "[REDACTED]") else masked
        }
    }
}
