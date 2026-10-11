package dev.beka.agent

import dev.beka.llm.ToolSpec

/** A group of tools the agent can call that live outside the phone (web, MCP, ...). Offered in chat mode too. */
interface ToolKit {
    val specs: List<ToolSpec>

    /** Extra system-prompt text about these tools (e.g. an MCP server's own usage notes); null = none. */
    val prompt: String? get() = null

    /** Risk of a call; SAFE runs without asking, HIGH (or MEDIUM with confirmAll) goes through the confirm callback. */
    fun risk(name: String, argsJson: String): Risk = Risk.SAFE

    /** The app a call acts through (a call goes through the phone app), for per-app confirm settings; null = none. */
    fun appPackage(name: String, argsJson: String): String? = null

    /** True = confirm this call even when the user turned confirmations off (an explicit per-server "ask"). */
    fun alwaysAsk(name: String): Boolean = false

    /** Whether a call brings another app to the front (a call, a share sheet), so the chat panel should get out of the way. */
    fun opensApp(name: String): Boolean = false

    /** Short line for the chat in [dev.beka.llm.Lang.current], e.g. "Search the web: iPhone 17 price". */
    fun describe(name: String, argsJson: String): String

    /** Result text for the model. Throw IllegalArgumentException(msg for model) for bad args, ToolFailed(msg) for failures. */
    suspend fun run(name: String, argsJson: String): String
}

/** A toolkit call that failed for a reason the model should hear about (network, HTTP error, ...). */
class ToolFailed(message: String) : Exception(message)
