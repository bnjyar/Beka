package dev.beka.web

import dev.beka.agent.ToolFailed
import dev.beka.agent.ToolKit
import dev.beka.llm.ToolSpec
import dev.beka.llm.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** web_search and fetch_page, offered to the model whenever web access is on. Both are read-only. */
class WebTools(private val search: WebSearch, private val fetcher: PageFetcher) : ToolKit {
    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "web_search",
            "Search the web. Returns titles, URLs and snippets. Use for current facts, news, prices, schedules. " +
                "Follow up with fetch_page when a snippet is not enough.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") { put("type", "string"); put("description", "Search query") }
                    putJsonObject("count") { put("type", "integer"); put("description", "Number of results, 1-10 (default 5)") }
                }
                putJsonArray("required") { add(JsonPrimitive("query")) }
            },
        ),
        ToolSpec(
            "fetch_page",
            "Read a web page as text. Long pages are returned in parts; pass offset to continue.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") { put("type", "string"); put("description", "http(s) URL") }
                    putJsonObject("offset") { put("type", "integer"); put("description", "Character to start from (default 0)") }
                }
                putJsonArray("required") { add(JsonPrimitive("url")) }
            },
        ),
    )

    override fun describe(name: String, argsJson: String): String {
        val args = parse(argsJson)
        return when (name) {
            "web_search" -> tr("Search the web: ", "Cari web: ") + (args.string("query") ?: "?").take(80)
            "fetch_page" -> {
                val url = args.string("url").orEmpty()
                tr("Read page: ", "Baca halaman: ") + (url.toHttpUrlOrNull() ?: "https://$url".toHttpUrlOrNull())?.host.orEmpty().ifEmpty { url.take(60) }
            }
            else -> name
        }
    }

    override suspend fun run(name: String, argsJson: String): String {
        val args = parse(argsJson)
        return try {
            when (name) {
                "web_search" -> {
                    val query = args.string("query") ?: throw IllegalArgumentException("Missing argument: query")
                    val count = (args.int("count") ?: 5).coerceIn(1, 10)
                    formatSearch(search.search(query, count))
                }
                "fetch_page" -> {
                    val url = args.string("url") ?: throw IllegalArgumentException("Missing argument: url")
                    formatPage(fetcher.fetch(url, (args.int("offset") ?: 0).coerceAtLeast(0)))
                }
                else -> throw IllegalArgumentException("Unknown tool: $name")
            }
        } catch (e: WebException) {
            throw ToolFailed(e.message ?: "Web request failed")
        }
    }

    private fun formatSearch(outcome: SearchOutcome): String {
        val body = if (outcome.results.isEmpty()) "No results." else outcome.results.withIndex().joinToString("\n") { (i, r) ->
            "${i + 1}. ${r.title}\n   ${r.url}" + if (r.snippet.isNotBlank()) "\n   ${r.snippet}" else ""
        }
        return (outcome.note?.let { "$it\n" }.orEmpty()) + body
    }

    private fun formatPage(page: Page): String {
        val end = page.offset + page.text.length
        val more = if (end < page.totalChars) {
            "\n\n[Showing chars ${page.offset}–$end of ${page.totalChars}; call fetch_page again with offset=$end for more.]"
        } else ""
        return "Title: ${page.title}\nURL: ${page.url}\n\n${page.text}$more"
    }

    private fun parse(argsJson: String): JsonObject =
        runCatching { webJson.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrElse { throw IllegalArgumentException("Arguments are not a JSON object: ${argsJson.take(200)}") }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
}
