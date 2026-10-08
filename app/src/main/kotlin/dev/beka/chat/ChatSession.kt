package dev.beka.chat

import android.util.Log
import dev.beka.accessibility.AgentDevice
import dev.beka.accessibility.BekaAccessibilityService
import dev.beka.accessibility.ScreenContext
import dev.beka.accessibility.toObservation
import dev.beka.agent.Agent
import dev.beka.agent.AgentConfig
import dev.beka.agent.AgentEvent
import dev.beka.agent.ConfirmRequest
import dev.beka.agent.Risk
import dev.beka.agent.StopReason
import dev.beka.debug.ActionLog
import dev.beka.debug.DebugLog
import dev.beka.llm.Message
import dev.beka.llm.Role
import dev.beka.settings.ProviderStore
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
import kotlinx.coroutines.launch

enum class LineKind { MESSAGE, ACTION }

data class ChatLine(
    val role: Role,
    val text: String,
    val error: Boolean = false,
    val kind: LineKind = LineKind.MESSAGE,
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
class ChatSession(private val providers: ProviderStore, private val actionLog: ActionLog) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val device = AgentDevice()
    private var job: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null

    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _screen = MutableStateFlow<ScreenContext?>(null)
    val screen: StateFlow<ScreenContext?> = _screen

    /** Non-null from the first action of a run until the user dismisses the result. */
    private val _status = MutableStateFlow<AgentStatus?>(null)
    val status: StateFlow<AgentStatus?> = _status

    val includeScreenshot = MutableStateFlow(false)

    /** Off = phase-1 style chat: the model gets no tools. */
    val actionsEnabled = MutableStateFlow(true)

    /** Call while the panel is hidden, so the screenshot shows the app and not Beka. */
    suspend fun captureScreen() {
        val service = BekaAccessibilityService.instance ?: run { _screen.value = null; return }
        val tree = service.captureTree()
        val shot = if (includeScreenshot.value) service.captureScreenshot() else null
        _screen.value = tree?.copy(screenshotJpeg = shot)
    }

    fun send(text: String) {
        if (text.isBlank() || _busy.value) return
        val config = providers.active()
        if (config == null) {
            _lines.update { it + ChatLine(Role.USER, text) + ChatLine(Role.ASSISTANT, "Belum ada provider aktif. Buka app Beka → Provider.", true) }
            return
        }

        val history = history(_lines.value)
        _lines.update { it + ChatLine(Role.USER, text) }
        _busy.value = true
        _status.value = null

        job = scope.launch {
            val screen = _screen.value
            val started = System.currentTimeMillis()
            val reply = StringBuilder()
            var inputTokens = 0
            var outputTokens = 0
            var steps = 0
            var risk = Risk.SAFE
            var error: String? = null
            val agent = Agent(
                provider = providers.client(config),
                model = config.model,
                device = device,
                confirm = ::askUser,
                config = AgentConfig(screenshots = includeScreenshot.value, actions = actionsEnabled.value),
            )
            try {
                agent.run(history, text, screen?.toObservation()).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            reply.append(event.text)
                            appendText(event.text)
                        }
                        is AgentEvent.Acting -> {
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
                            )
                            if (_lines.value.lastOrNull()?.text == "… ${event.description}") setLast(line) else addLine(line)
                            if (_status.value == null) _status.value = AgentStatus(event.step, line.text)
                            log(event, risk, screen?.packageName)
                            risk = Risk.SAFE
                        }
                        is AgentEvent.Usage -> {
                            inputTokens += event.inputTokens
                            outputTokens += event.outputTokens
                        }
                        is AgentEvent.Finished -> finish(event)
                    }
                }
            } catch (e: CancellationException) {
                addLine(ChatLine(Role.ASSISTANT, "[dihentikan]", error = true))
                _status.value?.let { _status.value = AgentStatus(it.step, "Dihentikan", done = true) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "agent failed", e)
                error = e.message ?: e.toString()
                addLine(ChatLine(Role.ASSISTANT, "Error: $error", true))
                _status.value?.let { _status.value = AgentStatus(it.step, "Error: $error", done = true) }
            } finally {
                pendingConfirm = null
                _busy.value = false
                DebugLog.add(
                    DebugLog.Entry(
                        time = started,
                        provider = config.name,
                        model = config.model,
                        app = screen?.packageName,
                        userText = text,
                        screenChars = screen?.tree?.length ?: 0,
                        withScreenshot = screen?.screenshotJpeg != null,
                        response = reply.toString(),
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
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
        stop()
        _lines.value = emptyList()
        _status.value = null
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
        if (_status.value != null) {
            val summary = listOfNotNull(event.text.takeIf { it.isNotEmpty() }, note).joinToString("\n").ifEmpty { "Selesai." }
            _status.value = AgentStatus(event.steps, summary, done = true)
        }
    }

    private fun log(event: AgentEvent.Acted, risk: Risk, app: String?) {
        val line = DebugLog.add(
            DebugLog.ActionEntry(
                time = System.currentTimeMillis(),
                step = event.step,
                app = BekaAccessibilityService.foregroundApp.value ?: app,
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

    /** Earlier turns as plain messages; actions are folded into the assistant reply that follows them. */
    private fun history(lines: List<ChatLine>): List<Message> {
        val out = mutableListOf<Message>()
        val actions = mutableListOf<String>()
        for (line in lines) {
            when {
                line.kind == LineKind.ACTION -> actions += line.text
                line.error -> Unit
                line.role == Role.USER -> {
                    if (actions.isNotEmpty()) out += Message.assistant("[Actions taken: ${actions.joinToString("; ")}]")
                    actions.clear()
                    out += Message.user(line.text)
                }
                else -> {
                    val prefix = if (actions.isEmpty()) "" else "[Actions taken: ${actions.joinToString("; ")}]\n"
                    actions.clear()
                    out += Message.assistant(prefix + line.text)
                }
            }
        }
        if (actions.isNotEmpty()) out += Message.assistant("[Actions taken: ${actions.joinToString("; ")}]")
        return out
    }

    private companion object {
        const val TAG = "BekaChat"
    }
}
