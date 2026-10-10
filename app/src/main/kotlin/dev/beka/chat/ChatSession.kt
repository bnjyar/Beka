package dev.beka.chat

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import dev.beka.accessibility.AgentDevice
import dev.beka.display.LockedNotices
import dev.beka.rules.ProactivePrompt
import dev.beka.display.VirtualScreen
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.accessibility.ScreenContext
import dev.beka.accessibility.toObservation
import dev.beka.agent.Agent
import dev.beka.agent.AgentConfig
import dev.beka.agent.AgentEvent
import dev.beka.agent.Blocklist
import dev.beka.agent.ConfirmRequest
import dev.beka.agent.Phone
import dev.beka.agent.PhoneTools
import dev.beka.agent.Risk
import dev.beka.agent.StopReason
import dev.beka.debug.ActionLog
import dev.beka.debug.DebugLog
import dev.beka.llm.Message
import dev.beka.media.MediaState
import dev.beka.llm.Role
import dev.beka.memory.Extractor
import dev.beka.memory.Memory
import dev.beka.memory.MemorySettings
import dev.beka.memory.MemoryStore
import dev.beka.memory.SessionLine
import dev.beka.settings.ProviderStore
import dev.beka.settings.ConfirmSettings
import dev.beka.settings.McpSettings
import dev.beka.settings.WebSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class LineKind { MESSAGE, ACTION, MEMORY, TOOL }

data class ChatLine(
    val role: Role,
    val text: String,
    val error: Boolean = false,
    val kind: LineKind = LineKind.MESSAGE,
    /** For actions: the app they happened in, so extraction can learn per-app procedures. */
    val app: String? = null,
    /** A task started by a proactive rule, not typed by the user. */
    val proactive: Boolean = false,
)

/**
 * What the status strip shows once the agent starts acting: the current step, a pending
 * confirmation, or (when [done]) the final reply.
 */
data class AgentStatus(
    val step: Int,
    val text: String,
    val confirm: ConfirmRequest? = null,
    val done: Boolean = false,
)

/** One running conversation shown in the overlay panel. */
class ChatSession(
    private val providers: ProviderStore,
    private val actionLog: ActionLog,
    private val media: MediaState,
    memoryStore: MemoryStore,
    private val memorySettings: MemorySettings,
    private val webSettings: WebSettings,
    private val mcpSettings: McpSettings,
    private val confirmSettings: ConfirmSettings,
    private val http: OkHttpClient,
    private val phone: Phone,
    private val virtualScreen: VirtualScreen,
    private val notices: LockedNotices,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Extraction outlives the conversation it reads: it runs after "Baru" clears the panel. */
    private val learnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val device = AgentDevice(media)
    private val memory = Memory(memoryStore)
    private var job: Job? = null
    private var idleLearn: Job? = null
    /** Lines before this index were already given to extraction. */
    private var learnedUpTo = 0
    /** Bumped by [clear], so a run cancelled by it does not arm the idle timer for the next conversation. */
    private var generation = 0
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    /** The current run was started by a rule, so the kill switch may stop it. */
    private var ruleRun = false

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _screen = MutableStateFlow<ScreenContext?>(null)
    val screen: StateFlow<ScreenContext?> = _screen

    /** Non-null from the first action of a run until the user dismisses the result. */
    private val _status = MutableStateFlow<AgentStatus?>(null)
    val status: StateFlow<AgentStatus?> = _status

    /** On by default: unlabeled UIs are only usable through the picture. Skipped for models that reject images. */
    val includeScreenshot = MutableStateFlow(true)

    /** Off = phase-1 style chat: the model gets no tools. */
    val actionsEnabled = MutableStateFlow(true)

    /** On = typed tasks run on the virtual display, so the user keeps their screen (fase 9). */
    val background = MutableStateFlow(false)

    /** The run [status] describes works on the virtual display. */
    private val _backgroundRun = MutableStateFlow(false)
    val backgroundRun: StateFlow<Boolean> = _backgroundRun

    /** "⚡ rule" or the typed request, for notifications about a background run. */
    private var runTitle = ""

    /** Closes the virtual display a while after the last background run. */
    private var closeIdle: Job? = null

    /** Rules always run in the background when the display host is up; typed tasks when the user asks. */
    private fun useBackground(): Boolean =
        actionsEnabled.value && (ruleRun || background.value) && virtualScreen.status.value == VirtualScreen.Status.READY

    /** Stops the background run and hands its app to the user's screen. */
    fun showBackgroundApp() {
        val pkg = virtualScreen.packages().firstOrNull()
        stop()
        if (pkg != null) scope.launch {
            // Let the run's own clean-up (giving borrowed apps back) finish first.
            job?.join()
            Log.i(TAG, "show $pkg on the main screen: ${virtualScreen.moveToMain(pkg)}")
        }
    }

    /** Call while the panel is hidden, so the screenshot shows the app and not Beka. */
    suspend fun captureScreen() {
        val service = BekaAccessibilityService.instance ?: run { _screen.value = null; return }
        val tree = service.captureTree()
        val shot = if (includeScreenshot.value && !Blocklist.isBlocked(tree?.packageName)) service.captureScreenshot() else null
        _screen.value = tree?.copy(screenshotJpeg = shot)
    }

    /** [onFinished] gets the final reply, "Selesai (N langkah)" for a silent task, or "Error: …". */
    fun send(text: String, onFinished: ((String) -> Unit)? = null) {
        if (text.isBlank() || _busy.value) return
        ruleRun = false
        start(text, ChatLine(Role.USER, text), history(_lines.value), confirmAll = false, isBlocked = Blocklist::isBlocked, memoryWrites = true, onFinished = onFinished)
    }

    /**
     * Runs a proactive rule's [task] like a typed request, on a fresh read of the screen, shown in the
     * chat as "⚡ [title]". [isBlocked] limits the apps it may act in; [onFinished] gets the outcome.
     * Memory is read-only: the task may carry notification text. Returns false if a run is going on.
     */
    fun runRule(
        title: String,
        task: String,
        confirmAll: Boolean,
        isBlocked: (String?) -> Boolean,
        onFinished: ((String) -> Unit)? = null,
    ): Boolean {
        if (_busy.value) return false
        // Claimed before the screen read suspends, so nothing else starts in between.
        _busy.value = true
        ruleRun = true
        job = scope.launch {
            if (useBackground()) {
                // The rule works on its own screen; what the user has open is none of its business.
                _screen.value = null
                _busy.value = false
                start(task, ChatLine(Role.USER, "⚡ $title", proactive = true), emptyList(), confirmAll, isBlocked, memoryWrites = false, onFinished = onFinished)
                return@launch
            }
            try {
                // Started from a notification button, the shade may still cover the app.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BekaAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    delay(400)
                }
                captureScreen()
                // The shade takes a moment to close; do not start from a picture of it.
                var tries = 0
                while (_screen.value?.packageName == "com.android.systemui" && tries++ < 5) {
                    delay(300)
                    captureScreen()
                }
                // A rule never sends a banking or wallet screen anywhere, not even its text.
                if (Blocklist.isBlocked(_screen.value?.packageName)) _screen.value = null
            } catch (e: CancellationException) {
                _busy.value = false
                onFinished?.invoke("Error: dihentikan")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "screen read before a rule failed", e)
                _screen.value = null
            }
            _busy.value = false
            start(task, ChatLine(Role.USER, "⚡ $title", proactive = true), emptyList(), confirmAll, isBlocked, memoryWrites = false, onFinished = onFinished)
        }
        return true
    }

    /** The kill switch: stops the current run only if a rule started it. */
    fun stopRule() {
        if (ruleRun && _busy.value) stop()
    }

    private fun start(
        text: String,
        line: ChatLine,
        history: List<Message>,
        confirmAll: Boolean,
        isBlocked: (String?) -> Boolean,
        memoryWrites: Boolean,
        onFinished: ((String) -> Unit)?,
    ) {
        val config = providers.active()
        if (config == null) {
            _lines.update { it + line + ChatLine(Role.ASSISTANT, "Belum ada provider aktif. Buka app Beka → Provider.", true) }
            onFinished?.invoke("Belum ada provider aktif")
            return
        }

        // Asked for the background but the display host is down: never fall back to the user's screen.
        if (!ruleRun && background.value && actionsEnabled.value && virtualScreen.status.value != VirtualScreen.Status.READY) {
            virtualScreen.connect()
            val note = "Layar belakang belum tersambung, jadi belum Beka kerjakan. Coba lagi sebentar lagi, atau matikan \"Di belakang\"."
            _lines.update { it + line + ChatLine(Role.ASSISTANT, note, true) }
            onFinished?.invoke("Error: layar belakang belum tersambung")
            return
        }

        idleLearn?.cancel()
        val runGeneration = generation
        _lines.update { it + line }
        _busy.value = true
        _status.value = null

        val inBackground = useBackground()
        _backgroundRun.value = inBackground
        runTitle = line.text
        val runDevice = if (inBackground) AgentDevice(media, virtualScreen) else device
        job = scope.launch {
            if (inBackground) {
                closeIdle?.cancel()
                withContext(Dispatchers.IO) {
                    virtualScreen.open()
                    // Keeps the display awake for the whole task, also with the phone locked.
                    virtualScreen.holdAwake(true)
                }
            }
            // A background run starts from its own (usually empty) screen, not the user's.
            val screen = if (inBackground) null else _screen.value
            val firstScreen = if (inBackground) runDevice.observe(screenshot = false) else screen?.toObservation(media.describe())
            val started = System.currentTimeMillis()
            val reply = StringBuilder()
            var inputTokens = 0
            var outputTokens = 0
            var cacheRead = 0
            var cacheWrite = 0
            var steps = 0
            lastTool = null
            var risk = Risk.SAFE
            var screenshots = includeScreenshot.value && !providers.rejectsImages(config)
            var visionRejected = false
            var error: String? = null
            var finalText = ""
            val agent = Agent(
                provider = providers.client(config),
                model = config.model,
                device = runDevice,
                confirm = ::askUser,
                memory = memory.takeIf { memorySettings.enabled.value },
                // Rule runs get no phone tools: a notification's text must not be able to set alarms, call or make rules.
                toolkits = listOfNotNull(
                    if (webSettings.enabled.value) webSettings.tools(http) else null,
                    if (ruleRun) null else PhoneTools(phone),
                    // External services too: their output could carry injected text, so rule runs never see them.
                    if (ruleRun) null else mcpSettings.toolkit(),
                ),
                config = AgentConfig(
                    screenshots = screenshots,
                    actions = actionsEnabled.value,
                    confirmAll = confirmAll,
                    isBlocked = isBlocked,
                    // Rule runs always ask: their input is notification or screen text that could carry injected instructions.
                    skipConfirm = if (ruleRun) ({ _: String? -> false }) else confirmSettings::shouldSkip,
                    memoryWrites = memoryWrites,
                    contextBudgetTokens = if (isLocal(config.baseUrl)) LOCAL_BUDGET else CLOUD_BUDGET,
                    background = inBackground,
                ),
            )
            try {
                agent.run(history, text, firstScreen).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            reply.append(event.text)
                            appendText(event.text)
                        }
                        is AgentEvent.Acting -> {
                            if (reply.isNotEmpty() && !reply.endsWith("\n")) reply.append('\n')
                            val first = _status.value == null
                            steps = event.step
                            risk = event.risk
                            _status.value = AgentStatus(event.step, event.description)
                            Log.i(TAG, "step ${event.step}: ${event.description}")
                            addLine(ChatLine(Role.ASSISTANT, "… ${event.description}", kind = LineKind.ACTION))
                            // Give the overlay a moment to hide the panel before the first touch lands.
                            if (first) delay(250)
                        }
                        is AgentEvent.Acted -> {
                            steps = event.step
                            val line = ChatLine(
                                Role.ASSISTANT,
                                (if (event.ok) "✓ " else "✗ ") + event.description + if (event.ok) "" else " — ${event.outcome}",
                                error = !event.ok,
                                kind = LineKind.ACTION,
                                app = BekaAccessibilityService.instance?.lastPackage ?: screen?.packageName,
                            )
                            if (_lines.value.lastOrNull()?.text == "… ${event.description}") setLast(line) else addLine(line)
                            if (_status.value == null) _status.value = AgentStatus(event.step, line.text)
                            log(event, risk, screen?.packageName)
                            risk = Risk.SAFE
                        }
                        is AgentEvent.Usage -> {
                            inputTokens += event.inputTokens
                            outputTokens += event.outputTokens
                            cacheRead += event.cacheReadTokens
                            cacheWrite += event.cacheWriteTokens
                        }
                        is AgentEvent.ToolUsed -> {
                            addLine(ChatLine(Role.ASSISTANT, event.text, error = !event.ok, kind = LineKind.TOOL))
                            lastTool = event.text
                            // Another app came to the front: hide the panel and show the strip, as screen actions do.
                            if (event.opensApp && _status.value == null) _status.value = AgentStatus(steps, event.text)
                            DebugLog.add(
                                DebugLog.ActionEntry(
                                    type = "tool", time = System.currentTimeMillis(), step = steps, app = null,
                                    action = "tool", description = event.text, risk = "", outcome = event.text,
                                    ok = event.ok, durationMs = 0,
                                )
                            )
                        }
                        is AgentEvent.MemoryUsed -> addLine(ChatLine(Role.ASSISTANT, event.text, error = !event.ok, kind = LineKind.MEMORY))
                        is AgentEvent.Finished -> {
                            finalText = event.text
                            finish(event)
                        }
                        AgentEvent.VisionUnsupported -> {
                            providers.markRejectsImages(config)
                            screenshots = false
                            visionRejected = true
                            addLine(ChatLine(Role.ASSISTANT, "Model ini tidak bisa membaca gambar; lanjut tanpa screenshot.", kind = LineKind.ACTION))
                        }
                    }
                }
            } catch (e: CancellationException) {
                error = "dihentikan"
                addLine(ChatLine(Role.ASSISTANT, "[dihentikan]", error = true))
                _status.value?.let { _status.value = AgentStatus(it.step, "Dihentikan", done = true) }
                throw e
            } catch (e: Exception) {
                // Stopping mid-request surfaces from OkHttp as IOException("Canceled"), not a cancellation.
                if (!isActive) {
                    error = "dihentikan"
                    addLine(ChatLine(Role.ASSISTANT, "[dihentikan]", error = true))
                    _status.value?.let { _status.value = AgentStatus(it.step, "Dihentikan", done = true) }
                    return@launch
                }
                Log.e(TAG, "agent failed", e)
                error = e.message ?: e.toString()
                addLine(ChatLine(Role.ASSISTANT, "Error: $error", true))
                _status.value?.let { _status.value = AgentStatus(it.step, "Error: $error", done = true) }
            } finally {
                if (inBackground) {
                    // Nobody sees the result card on a locked or dark phone.
                    if (!notices.phoneInUse() && error != "dihentikan") {
                        notices.postResult("Beka · $runTitle", error?.let { "Gagal: $it" } ?: ProactivePrompt.plain(finalText).ifEmpty { "Selesai ($steps langkah)" })
                    }
                    (runDevice as AgentDevice).returnApps()
                    virtualScreen.holdAwake(false)
                    closeIdle = scope.launch {
                        delay(BACKGROUND_IDLE_MS)
                        if (!_busy.value) virtualScreen.close()
                    }
                }
                pendingConfirm = null
                _busy.value = false
                onFinished?.invoke(error?.let { "Error: $it" } ?: finalText.ifEmpty { "Selesai ($steps langkah)" })
                if (runGeneration == generation) {
                    idleLearn = scope.launch {
                        delay(IDLE_LEARN_MS)
                        learn()
                    }
                }
                DebugLog.add(
                    DebugLog.Entry(
                        time = started,
                        provider = config.name,
                        model = config.model,
                        app = screen?.packageName,
                        userText = text,
                        screenChars = screen?.tree?.length ?: 0,
                        withScreenshot = screenshots,
                        visionRejected = visionRejected,
                        response = reply.toString(),
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cacheReadTokens = cacheRead,
                        cacheWriteTokens = cacheWrite,
                        durationMs = System.currentTimeMillis() - started,
                        error = error,
                        steps = steps,
                    )
                )
            }
        }
    }

    /** Answer to the confirmation card in the status strip. */
    fun answerConfirm(allow: Boolean) {
        pendingConfirm?.complete(allow)
    }

    fun dismissStatus() {
        if (_status.value?.done == true) _status.value = null
    }

    fun stop() {
        job?.cancel()
    }

    fun clear() {
        generation++
        stop()
        idleLearn?.cancel()
        learn()
        _lines.value = emptyList()
        learnedUpTo = 0
        _status.value = null
    }

    /**
     * End-of-session extraction over the lines not read yet. Runs when the user starts a new chat,
     * or after [IDLE_LEARN_MS] without a message, whichever comes first.
     */
    private fun learn() {
        val lines = _lines.value
        val fresh = lines.drop(learnedUpTo)
        learnedUpTo = lines.size
        if (!memorySettings.enabled.value || !memorySettings.autoExtract.value) return
        // A rule's task is not something the user said in this conversation.
        if (fresh.none { it.role == Role.USER && it.kind == LineKind.MESSAGE && !it.proactive }) return
        val config = providers.active() ?: return
        val session = fresh.filter { !it.error && it.text.isNotBlank() && it.kind != LineKind.MEMORY && it.kind != LineKind.TOOL && !it.proactive }.map {
            when {
                it.kind == LineKind.ACTION -> SessionLine(SessionLine.Kind.ACTION, it.text, it.app)
                it.role == Role.USER -> SessionLine(SessionLine.Kind.USER, it.text)
                else -> SessionLine(SessionLine.Kind.ASSISTANT, it.text)
            }
        }
        learnScope.launch {
            runCatching { memory.learn(session, Extractor(providers.client(config), config.model)) }
                .onSuccess { Log.i(TAG, "learned: ${it.added} added, ${it.pending} for review") }
                .onFailure { Log.w(TAG, "extraction failed", it) }
        }
    }

    private suspend fun askUser(request: ConfirmRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingConfirm = answer
        val step = _status.value?.step ?: 0
        _status.value = AgentStatus(step, request.description, confirm = request)
        // A background run on a locked or dark phone asks in the notification shade as well;
        // whichever answers first wins. Unanswered, it counts as no.
        val locked = _backgroundRun.value && !notices.phoneInUse()
        if (locked) {
            notices.postConfirm("Beka minta izin · $runTitle", "${request.description} di ${request.appLabel}")
            // The display may sleep while waiting; the next step wakes it again.
            virtualScreen.holdAwake(false)
        }
        return try {
            if (locked) withTimeoutOrNull(LOCKED_CONFIRM_MS) { answer.await() } ?: false else answer.await()
        } finally {
            if (locked) {
                notices.cancelConfirm()
                virtualScreen.holdAwake(true)
            }
            pendingConfirm = null
            _status.value = AgentStatus(step, request.description)
        }
    }

    /** The latest tool line of the running request, for the result card when no screen action was taken. */
    private var lastTool: String? = null

    private fun finish(event: AgentEvent.Finished) {
        Log.i(TAG, "run finished: ${event.reason} after ${event.steps} step(s), strip=${_status.value != null}")
        val note = when (event.reason) {
            StopReason.DONE -> null
            StopReason.MAX_STEPS -> "Berhenti: batas ${event.steps} langkah tercapai."
            StopReason.NO_PROGRESS -> "Berhenti: layar tidak berubah setelah beberapa aksi."
        }
        note?.let { addLine(ChatLine(Role.ASSISTANT, it)) }
        if (event.text.isEmpty() && note == null && event.steps == 0) {
            addLine(ChatLine(Role.ASSISTANT, "(respons kosong)", true))
        }
        // The result card stays short; the full answer is in the chat.
        if (_status.value != null) {
            val done = if (event.steps == 0 && lastTool != null) "Selesai · $lastTool" else "Selesai · ${event.steps} langkah"
            _status.value = AgentStatus(event.steps, note ?: done, done = true)
        }
    }

    private fun log(event: AgentEvent.Acted, risk: Risk, app: String?) {
        val line = DebugLog.add(
            DebugLog.ActionEntry(
                time = System.currentTimeMillis(),
                step = event.step,
                app = BekaAccessibilityService.instance?.lastPackage ?: app,
                action = event.action.toString(),
                description = event.description,
                risk = risk.name,
                outcome = event.outcome,
                ok = event.ok,
                durationMs = event.durationMs,
            )
        )
        scope.launch(Dispatchers.IO) { runCatching { actionLog.append(System.currentTimeMillis(), line) } }
    }

    /** Streams model text into the last assistant message, starting a new one after an action. */
    private fun appendText(delta: String) {
        val last = _lines.value.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.kind == LineKind.MESSAGE && !last.error) {
            setLast(last.copy(text = last.text + delta))
        } else {
            addLine(ChatLine(Role.ASSISTANT, delta.trimStart()))
        }
    }

    private fun addLine(line: ChatLine) {
        _lines.update { it + line }
    }

    private fun setLast(line: ChatLine) {
        _lines.update { it.dropLast(1) + line }
    }

    /**
     * Earlier turns as plain messages. Action lines are left out: when they were folded into the
     * replies, weak models started writing fake actions as text instead of calling tools.
     */
    private fun history(lines: List<ChatLine>): List<Message> = lines
        .filter { it.kind == LineKind.MESSAGE && !it.error && it.text.isNotBlank() }
        .map { if (it.role == Role.USER) Message.user(it.text) else Message.assistant(it.text) }

    /**
     * Local model servers (Ollama 11434, LM Studio 1234, llama.cpp 8080) usually run 8–32K contexts.
     * Judged by port, not address: a local proxy such as CLIProxyAPI fronts a cloud model.
     */
    private fun isLocal(baseUrl: String): Boolean {
        val port = runCatching { java.net.URI(baseUrl).port }.getOrDefault(-1)
        return port in LOCAL_MODEL_PORTS
    }

    private companion object {
        const val TAG = "BekaChat"
        const val IDLE_LEARN_MS = 10L * 60 * 1000
        /** Follow-ups within this time reuse the open virtual display and the app on it. */
        const val BACKGROUND_IDLE_MS = 2L * 60 * 1000
        /** How long a confirmation asked on a locked phone waits before it counts as no. */
        const val LOCKED_CONFIRM_MS = 10L * 60 * 1000
        const val CLOUD_BUDGET = 60_000
        const val LOCAL_BUDGET = 12_000
        val LOCAL_MODEL_PORTS = setOf(11434, 1234, 8080)
    }
}
