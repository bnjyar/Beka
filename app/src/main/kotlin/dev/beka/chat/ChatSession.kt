package dev.beka.chat

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import dev.beka.accessibility.AgentDevice
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
import dev.beka.settings.WebSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val confirmSettings: ConfirmSettings,
    private val http: OkHttpClient,
    private val phone: Phone,
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

        idleLearn?.cancel()
        val runGeneration = generation
        _lines.update { it + line }
        _busy.value = true
        _status.value = null

        job = scope.launch {
            val screen = _screen.value
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
                device = device,
                confirm = ::askUser,
                memory = memory.takeIf { memorySettings.enabled.value },
                // Rule runs get no phone tools: a notification's text must not be able to set alarms, call or make rules.
                toolkits = listOfNotNull(
                    if (webSettings.enabled.value) webSettings.tools(http) else null,
                    if (ruleRun) null else PhoneTools(phone),
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
                ),
            )
            try {
                agent.run(history, text, screen?.toObservation(media.describe())).collect { event ->
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
        return try {
            answer.await()
        } finally {
            pendingConfirm = null
            _status.value = AgentStatus(step, request.description)
        }
    }

    /** The latest tool line of the running request, for the result card when no screen action was taken. */
    private var lastTool: String? = null

    private fun finish(event: AgentEvent.Finished) {
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
        const val CLOUD_BUDGET = 60_000
        const val LOCAL_BUDGET = 12_000
        val LOCAL_MODEL_PORTS = setOf(11434, 1234, 8080)
    }
}
