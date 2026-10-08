package dev.beka.agent

import dev.beka.llm.ChatEvent
import dev.beka.llm.ChatRequest
import dev.beka.llm.LlmException
import dev.beka.llm.LlmProvider
import dev.beka.llm.Message
import dev.beka.llm.ModelInfo
import dev.beka.llm.ProviderConfig
import dev.beka.llm.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.fail

/** Replays scripted turns, one per [chat] call, and records what it was asked. */
class FakeProvider(vararg turns: List<ChatEvent>) : LlmProvider {
    private val script: List<List<ChatEvent>> = turns.toList()
    private var next = 0
    private var calls = 0

    /** Call number (0-based) → error thrown on that call, optionally after streaming some text. Doesn't use up a turn. */
    val errors: MutableMap<Int, Pair<String?, LlmException>> = mutableMapOf()

    /** Snapshots of each request (the agent mutates its message list afterwards). */
    val requests: MutableList<ChatRequest> = mutableListOf()

    /** The agent's live message list from the latest call; sees results appended after that call. */
    var lastLiveMessages: List<Message> = emptyList()
        private set

    override val config: ProviderConfig =
        ProviderConfig("fake", "Fake", ProviderKind.OPENAI_COMPATIBLE, "http://localhost", "fake-model")

    override suspend fun listModels(): List<ModelInfo> = emptyList()

    override fun chat(request: ChatRequest): Flow<ChatEvent> {
        lastLiveMessages = request.messages
        requests += request.copy(messages = request.messages.toList())
        errors.remove(calls++)?.let { (text, error) ->
            return flow {
                text?.let { emit(ChatEvent.TextDelta(it)) }
                throw error
            }
        }
        if (next >= script.size) {
            fail("FakeProvider called ${next + 1} times but only ${script.size} turn(s) were scripted")
        }
        val turn = script[next++]
        val events: List<ChatEvent> = if (turn.any { it is ChatEvent.Done }) turn else turn + ChatEvent.Done(null)
        return flow { events.forEach { emit(it) } }
    }
}

class FakeDevice(private val screens: List<Observation>) : Device {
    var observeCalls = 0
        private set
    val performed: MutableList<Action> = mutableListOf()
    val labels: MutableMap<String, String> = mutableMapOf()
    val apps: MutableMap<String, String> = mutableMapOf()
    var failWith: String? = null

    override suspend fun observe(screenshot: Boolean): Observation? {
        val index = observeCalls
        observeCalls++
        return screens.getOrNull(index) ?: screens.lastOrNull()
    }

    var pageText: String? = "full page"

    override suspend fun readFullPage(): String? = pageText

    override fun label(nodeId: String): String? = labels[nodeId]

    val editable: MutableSet<String> = mutableSetOf()

    override fun isEditable(nodeId: String): Boolean = nodeId in editable

    override fun resolveApp(name: String): String? = apps[name]

    override suspend fun perform(action: Action): String {
        val reason = failWith
        if (reason != null) throw ActionFailed(reason)
        performed += action
        return "ok"
    }
}

fun obs(text: String, pkg: String = "com.example", label: String = "Example") = Observation(pkg, label, text)

fun call(id: String, name: String, args: String = "{}") = ChatEvent.ToolCall(id, name, args)
