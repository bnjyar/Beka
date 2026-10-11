package dev.beka.rules

import android.app.AlarmManager
import android.app.KeyguardManager
import dev.beka.R
import dev.beka.display.VirtualScreen
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
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
import dev.beka.chat.errorSummary
import dev.beka.chat.friendlyError
import dev.beka.debug.AppLog
import dev.beka.debug.DebugLog
import dev.beka.memory.Memory
import dev.beka.overlay.OverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _callout = MutableStateFlow<Callout?>(null)

    /** A rule's note shown next to the bubble, or null. The overlay draws it. */
    val callout: StateFlow<Callout?> = _callout
    private var calloutTimer: Job? = null

    /** The app in front once switching settled. */
    @Volatile
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
        // The kill switch stops a rule's agent run that is already going, not only future ones.
        scope.launch {
            settings.enabled.collect { on ->
                if (!on) {
                    app.chat.stopRule()
                    calloutTimer?.cancel()
                    _callout.value = null
                }
            }
        }
        scope.launch(Dispatchers.IO) { runCatching { store.prune() } }
    }

    // ---- Event sources ----

    /** Every window change from the accessibility service (Beka and System UI already left out). */
    fun onForeground(pkg: String) {
        if (pkg in imePackages || pkg in TRANSIENT) return
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
        // Redacted together: the hint ("Kode verifikasi") is often in the title and the code in the text.
        val masked = redact("$title\n$text")
        submit(RuleEvent.Notification(pkg, masked.substringBefore('\n'), masked.substringAfter('\n'), LocalDateTime.now()))
    }

    /**
     * The schedule alarm went off, or the phone booted: fire every schedule rule that came due since
     * the last check. [done] is called once the events are queued (the receiver's goAsync).
     */
    fun onAlarm(done: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                checkSchedules()
            } finally {
                done()
            }
        }
    }

    private fun checkSchedules() {
        val nowMillis = System.currentTimeMillis()
        val now = toLocal(nowMillis)
        val cursor = toLocal(settings.scheduleCursor.takeIf { it > 0 } ?: (nowMillis - 60_000))
        settings.scheduleCursor = nowMillis
        note("alarm: checking schedules since $cursor")
        // Arm the next alarm before running anything: if the process dies below, schedules still go on.
        reschedule()
        if (!settings.enabled.value) return
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
                note("${rule.name}: schedule $latest missed, skipped")
            }
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
        note("next schedule at $next (exact=$exact)")
    }

    // ---- Notification buttons ----

    fun markWrong(runId: Long) {
        scope.launch { store.markWrong(runId, true) }
    }

    /** "Do it" on a suggestion: the user asked for it, so it runs like a request typed into the chat. */
    fun accept(ruleId: String, runId: Long, note: String) {
        // A button on an old notification must not outlive the kill switch or the rule.
        val rule = store.rules.value.firstOrNull { it.id == ruleId && it.enabled }
        if (!settings.enabled.value || rule == null) {
            toast(app.getString(R.string.pro_rule_off))
            return
        }
        val task = "The user accepted your suggestion from the proactive rule \"${rule.name}\".\n" +
            "Task: ${rule.action.prompt.trim()}\n<your_note>\n${ProactivePrompt.fenced(note)}\n</your_note>\nDo it now."
        // Only the rule's own apps: the note may carry a notification's text. A schedule rule that
        // names no app has no outside text in it, so it may go anywhere the blocklist allows.
        val allowed = rule.packages.toSet()
        val isBlocked = { pkg: String? -> Blocklist.isBlocked(pkg) || (allowed.isNotEmpty() && pkg != null && pkg !in allowed) }
        val started = app.chat.runRule(rule.name, task, confirmAll = false, isBlocked = isBlocked) { result ->
            // The history shows what was done, not the suggestion that led to it.
            scope.launch { store.updateRun(runId, "accepted", result) }
        }
        if (!started) {
            toast(app.getString(R.string.pro_busy_other))
            return
        }
        scope.launch { store.updateRun(runId, "accepted", "…") }
    }

    // ---- Callout next to the bubble ----

    /** "Lakukan" in the callout. */
    fun acceptCallout(callout: Callout) {
        closeCallout(callout)
        accept(callout.ruleId, callout.runId, callout.text)
    }

    fun wrongCallout(callout: Callout) {
        closeCallout(callout)
        markWrong(callout.runId)
    }

    fun closeCallout(callout: Callout) {
        if (_callout.value?.runId != callout.runId) return
        calloutTimer?.cancel()
        _callout.value = null
    }

    // ---- Running rules ----

    /** Logged and kept in the debug report, so a rule that did not run can be explained later. */
    private fun note(msg: String, short: String = msg) {
        // Logcat gets [short] (no notification titles) unless the user records logs; the report gets [msg].
        Log.i(TAG, if (AppLog.enabled.value) msg else short)
        DebugLog.add(
            DebugLog.ActionEntry(
                type = "rule-log", time = System.currentTimeMillis(), step = 0, app = null, action = "rule",
                description = msg, risk = "", outcome = msg, ok = true, durationMs = 0,
            )
        )
    }

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
                    Log.e(TAG, "rule handling failed for ${event::class.simpleName}", e)
                }
            }
        }
    }

    private suspend fun handle(event: RuleEvent) {
        if (!settings.enabled.value) return
        // Never start a rule over a running task, the user's or another rule's.
        if (app.chat.busy.value) {
            note("skipped $event: agent busy")
            return
        }
        // Events wait behind earlier ones; an app switch the user already moved on from is stale.
        val stale = when (event) {
            is RuleEvent.AppOpened -> current != event.pkg || age(event) > STALE_APP_EVENT_MS
            is RuleEvent.AppClosed -> age(event) > STALE_APP_EVENT_MS
            else -> false
        }
        if (stale) {
            note("skipped ${describe(event)}: stale", "skipped ${describe(event, titles = false)}: stale")
            return
        }
        val env = EventEnv(event)
        for (decision in engine.evaluate(event, store.rules.value, env)) {
            note(
                "${decision.rule.name} on ${describe(event)}: ${decision.reason}",
                "${decision.rule.name} on ${describe(event, titles = false)}: ${decision.reason}",
            )
            if (decision.fires) fire(decision, env)
            if (env.outOfBudget) break
        }
    }

    private suspend fun fire(decision: Decision, env: EventEnv) {
        val rule = decision.rule
        val level = decision.level ?: return
        val trigger = describe(decision.event)
        // Checking conditions can take seconds; the kill switch may have been hit meanwhile.
        if (!settings.enabled.value) return
        if (!settings.spendCall()) {
            env.outOfBudget = true
            note("${rule.name}: daily budget used up")
            budgetNotice()
            return
        }
        // Acting needs the overlay (status strip, Stop, confirmations), actions switched on and a phone
        // in use, or the virtual display, which works on a locked phone too (confirmations then go to
        // the notification shade); otherwise the task is offered instead.
        val background = app.virtualScreen.status.value == VirtualScreen.Status.READY
        val canAct = OverlayService.running.value && app.chat.actionsEnabled.value && (inUse() || background) && !app.chat.busy.value
        val effective = if (level.acts && !canAct) Level.SUGGEST else level
        if (level.acts) note("${rule.name}: overlay=${OverlayService.running.value} actions=${app.chat.actionsEnabled.value} inUse=${inUse()} background=$background busy=${app.chat.busy.value} → $effective")
        val task = ProactivePrompt.task(decision.copy(level = effective), ::label)

        if (effective.acts) {
            val runId = store.addRun(RuleRun(ruleId = rule.id, ruleName = rule.name, time = now(), trigger = trigger, level = effective, outcome = "acting"))
            val allowed = rule.packages.toSet()
            val started = withContext(Dispatchers.Main) {
                app.chat.runRule(rule.name, task, confirmAll = effective == Level.CONFIRM, isBlocked = { blocksActing(it, allowed) }) { summary ->
                    scope.launch { store.updateRun(runId, "acted", summary) }
                }
            }
            if (!started) store.updateRun(runId, "skipped", app.getString(R.string.pro_busy), counted = false)
            return
        }

        val runId = store.addRun(RuleRun(ruleId = rule.id, ruleName = rule.name, time = now(), trigger = trigger, level = effective, outcome = "running"))
        val screen = if (decision.event is RuleEvent.AppOpened) env.screen() else null
        val reply = try {
            noteFor(task, screen?.toObservation(app.media.describe()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rule run failed: ${errorSummary(e)}")
            AppLog.step("rule run failed: ${e.message ?: e}")
            store.updateRun(runId, "error", friendlyError(e))
            return
        }
        if (!settings.enabled.value) {
            store.updateRun(runId, "skipped", app.getString(R.string.pro_proactive_off), counted = false)
            return
        }
        val nothing = ProactivePrompt.nothingReason(reply)
        if (nothing != null) {
            store.updateRun(runId, "nothing", nothing)
            return
        }
        val note = ProactivePrompt.plain(reply)
        notify(rule, runId, effective, note)
        store.updateRun(runId, if (effective == Level.SUGGEST) "suggested" else "notified", note)
    }

    /** A read-only agent run: the model sees the task (and the screen) and writes a short note. */
    private suspend fun noteFor(task: String, screen: Observation?): String {
        val config = app.providers.active() ?: throw IllegalStateException(app.getString(R.string.no_model_short))
        val agent = Agent(
            provider = app.providers.client(config),
            model = config.model,
            device = NoDevice,
            confirm = { false },
            config = AgentConfig(actions = false, screenshots = false, maxTokens = 600, memoryWrites = false),
            memory = Memory(app.memoryStore).takeIf { app.memorySettings.enabled.value },
        )
        val started = System.currentTimeMillis()
        var text = ""
        var input = 0
        var output = 0
        agent.run(emptyList(), task, screen).collect {
            when (it) {
                is AgentEvent.Finished -> text = it.text
                is AgentEvent.Usage -> { input += it.inputTokens; output += it.outputTokens }
                else -> Unit
            }
        }
        // In the debug bundle, so a rule that stayed quiet can be explained.
        DebugLog.add(
            DebugLog.Entry(
                type = "rule",
                time = started,
                provider = config.name,
                model = config.model,
                app = screen?.packageName,
                userText = task,
                screenChars = screen?.text?.length ?: 0,
                withScreenshot = false,
                response = text,
                inputTokens = input,
                outputTokens = output,
                durationMs = System.currentTimeMillis() - started,
            ),
        )
        return text
    }

    /** What a rule's agent run may act in: only the rule's own apps, and only with the "act" permission. */
    private fun blocksActing(pkg: String?, allowed: Set<String>): Boolean =
        Blocklist.isBlocked(pkg) || (pkg != null && (pkg !in allowed || store.permissions.value[pkg] != AppPermission.ACT))

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
            // Conditions of schedule rules read whatever is in front: never a blocked or sensitive app.
            if (permission(tree.packageName) == AppPermission.BLOCKED) return null
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

    /**
     * Shows the note next to the bubble while the overlay is up and the phone in use; otherwise, or
     * when it is left unanswered, as a notification so it is not lost.
     */
    private fun notify(rule: Rule, runId: Long, level: Level, text: String) {
        if (!OverlayService.running.value || !inUse()) {
            postNotification(rule.id, rule.name, runId, level, text)
            return
        }
        val callout = Callout(rule.id, rule.name, runId, level, text)
        scope.launch {
            // A newer note takes the bubble; the older one moves to the notification shade.
            _callout.value?.let { postNotification(it.ruleId, it.ruleName, it.runId, it.level, it.text) }
            calloutTimer?.cancel()
            _callout.value = callout
            calloutTimer = scope.launch {
                delay(if (level == Level.SUGGEST) SUGGEST_CALLOUT_MS else NOTE_CALLOUT_MS)
                if (_callout.value?.runId == runId) {
                    _callout.value = null
                    postNotification(rule.id, rule.name, runId, level, text)
                }
            }
        }
    }

    private fun postNotification(ruleId: String, ruleName: String, runId: Long, level: Level, text: String) {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.pro_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val id = NOTIFICATION_BASE + (runId % 10_000).toInt()
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⚡ $ruleName")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        // "Do it" lets Beka act, so from the lock screen it needs the screen lock first.
        val accept = if (level == Level.SUGGEST) {
            Notification.Action.Builder(null, app.getString(R.string.do_it), OverlayService.acceptIntent(app, ruleId, runId, id, text))
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true) }
                .build()
        } else null
        val wrong = Notification.Action.Builder(null, app.getString(R.string.pro_wrong_trigger), ProactiveReceiver.wrongIntent(app, runId, id)).build()
        accept?.let(builder::addAction)
        builder.addAction(wrong)
        // With "hide sensitive content" on, the lock screen shows this instead: the rule's name and
        // its buttons, without the note itself.
        builder.setPublicVersion(
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("⚡ $ruleName")
                .setContentText(if (accept != null) app.getString(R.string.pro_public_suggest) else app.getString(R.string.pro_public_note))
                .apply { accept?.let(::addAction) }
                .build()
        )
        manager.notify(id, builder.build())
    }

    private fun budgetNotice() {
        if (!settings.warnOncePerDay()) return
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.pro_channel), NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(
            NOTIFICATION_BASE - 1,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.pro_budget_title))
                .setContentText(app.getString(R.string.pro_budget_text, settings.dailyCalls))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** [titles] false leaves out a notification's title (often a person's name). */
    private fun describe(event: RuleEvent, titles: Boolean = true): String = when (event) {
        is RuleEvent.AppOpened -> app.getString(R.string.pro_ev_opened, label(event.pkg))
        is RuleEvent.AppClosed -> app.getString(R.string.pro_ev_closed, label(event.pkg))
        is RuleEvent.Notification ->
            if (titles) app.getString(R.string.pro_ev_notif_title, label(event.pkg), event.title).trimEnd(':', ' ') else app.getString(R.string.pro_ev_notif, label(event.pkg))
        is RuleEvent.ScheduleDue -> app.getString(R.string.pro_ev_schedule, event.time.format(HHMM))
    }

    fun label(pkg: String): String = runCatching {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Screen on and unlocked: nobody wants an agent tapping around on a dark or locked phone. */
    private fun inUse(): Boolean =
        app.getSystemService(PowerManager::class.java).isInteractive &&
            !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun age(event: RuleEvent): Long = Duration.between(event.time, LocalDateTime.now()).toMillis()

    private fun toast(text: String) {
        scope.launch { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }

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
        private const val STALE_APP_EVENT_MS = 15_000L
        private const val NOTE_CALLOUT_MS = 20_000L
        private const val SUGGEST_CALLOUT_MS = 45_000L

        /** Windows that pop over an app without the user leaving it: share sheets, permission and sign-in dialogs. */
        private val TRANSIENT = setOf(
            "android",
            "com.android.intentresolver",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.gms",
        )
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

/** A rule's note waiting next to the bubble. */
data class Callout(val ruleId: String, val ruleName: String, val runId: Long, val level: Level, val text: String)
