package dev.beka.agent

import dev.beka.llm.ToolSpec

/** A group of tools the agent can call that live outside the phone (web, MCP, ...). Offered in chat mode too. */
interface ToolKit {
    val specs: List<ToolSpec>

    /** Risk of a call; SAFE runs without asking, HIGH (or MEDIUM with confirmAll) goes through the confirm callback. */
    fun risk(name: String, argsJson: String): Risk = Risk.SAFE

    /** Short Indonesian line for the chat, e.g. "Cari web: harga iPhone 17". */
    fun describe(name: String, argsJson: String): String

    /** Result text for the model. Throw IllegalArgumentException(msg for model) for bad args, ToolFailed(msg) for failures. */
    suspend fun run(name: String, argsJson: String): String
}

/** A toolkit call that failed for a reason the model should hear about (network, HTTP error, ...). */
class ToolFailed(message: String) : Exception(message)
