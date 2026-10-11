package dev.beka.web

import dev.beka.llm.tr
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

data class SearchResult(val title: String, val url: String, val snippet: String)

interface SearchProvider {
    val id: String

    /** @throws WebException on HTTP or network errors. */
    suspend fun search(query: String, count: Int): List<SearchResult>
}

internal val webJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseJson(reply: HttpReply): JsonObject =
    runCatching { webJson.parseToJsonElement(reply.text()) as? JsonObject }.getOrNull()
        ?: throw WebException(tr("The search did not answer with JSON", "Respons pencarian bukan JSON"), reply.status)

private fun JsonElement?.str(vararg keys: String): String {
    val obj = this as? JsonObject ?: return ""
    for (key in keys) {
        val value = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (!value.isNullOrBlank()) return value.trim()
    }
    return ""
}

private fun JsonElement?.items(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun toResults(items: List<JsonObject>, count: Int): List<SearchResult> =
    items.mapNotNull {
        val url = it.str("url")
        if (url.isEmpty()) null else SearchResult(it.str("title").ifEmpty { url }, url, it.str("snippet", "description", "content"))
    }.take(count)

/** GET <base>?query=<q> with X-API-Key. Shape from docs.tinyfish.ai/search-api: results[] {title, url, snippet, site_name}. */
class TinyFishSearch(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.search.tinyfish.ai",
) : SearchProvider {
    override val id = "tinyfish"

    override suspend fun search(query: String, count: Int): List<SearchResult> {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("query", query).build()
        val reply = client.send(Request.Builder().url(url).header("X-API-Key", apiKey).header("Accept", "application/json").build())
        if (reply.status !in 200..299) throw reply.failure()
        // Parsed defensively: look for a results array whatever the exact field names.
        return toResults(parseJson(reply)["results"].items(), count)
    }
}

class BraveSearch(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.search.brave.com/res/v1/web/search",
    private val retryDelayMs: Long = 1_100,
) : SearchProvider {
    override val id = "brave"

    override suspend fun search(query: String, count: Int): List<SearchResult> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", count.toString())
            .build()
        val request = Request.Builder().url(url)
            .header("X-Subscription-Token", apiKey)
            .header("Accept", "application/json")
            .build()
        var reply = client.send(request)
        if (reply.status == 429) {
            // Free plans allow one request per second.
            delay(retryDelayMs)
            reply = client.send(request)
        }
        if (reply.status !in 200..299) throw reply.failure()
        val web = parseJson(reply)["web"] as? JsonObject
        return toResults(web?.get("results").items(), count)
    }
}

class TavilySearch(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com/search",
) : SearchProvider {
    override val id = "tavily"

    override suspend fun search(query: String, count: Int): List<SearchResult> {
        val body = buildJsonObject {
            put("query", query)
            put("max_results", count)
        }.toString().toRequestBody("application/json".toMediaType())
        val reply = client.send(
            Request.Builder().url(baseUrl).post(body).header("Authorization", "Bearer $apiKey").header("Accept", "application/json").build()
        )
        if (reply.status !in 200..299) throw reply.failure()
        return toResults(parseJson(reply)["results"].items(), count)
    }
}

/** No key needed, but DuckDuckGo throttles scrapers; used as the fallback. */
class DuckDuckGoSearch(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://html.duckduckgo.com/html/",
) : SearchProvider {
    override val id = "duckduckgo"

    override suspend fun search(query: String, count: Int): List<SearchResult> {
        val request = Request.Builder().url(baseUrl)
            .post(FormBody.Builder().add("q", query).build())
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "id,en;q=0.8")
            .build()
        val reply = client.send(request)
        if (reply.status == 202 || reply.status == 403 || reply.status == 429) throw blocked()
        if (reply.status !in 200..299) throw reply.failure()
        val html = reply.text()
        val results = parse(html, count)
        if (results.isEmpty() && Regex("anomaly|captcha|challenge", RegexOption.IGNORE_CASE).containsMatchIn(html)) throw blocked()
        return results
    }

    companion object {
        private fun blocked() = WebException(tr("DuckDuckGo refused or limited the search", "DuckDuckGo menolak/membatasi pencarian"))

        fun parse(html: String, count: Int): List<SearchResult> =
            Jsoup.parse(html).select("div.result").asSequence()
                .filter { !it.hasClass("result--ad") }
                .mapNotNull { row ->
                    val link = row.selectFirst("a.result__a") ?: return@mapNotNull null
                    val url = target(link.attr("href")) ?: return@mapNotNull null
                    SearchResult(link.text().trim().ifEmpty { url }, url, row.selectFirst(".result__snippet")?.text()?.trim().orEmpty())
                }
                .take(count)
                .toList()

        /** DuckDuckGo wraps results as //duckduckgo.com/l/?uddg=<encoded target>. */
        internal fun target(href: String): String? {
            if (href.isBlank()) return null
            val absolute = if (href.startsWith("//")) "https:$href" else href
            val parsed: HttpUrl = absolute.toHttpUrlOrNull() ?: return null
            if (parsed.host.endsWith("duckduckgo.com") && parsed.encodedPath == "/l/") {
                return parsed.queryParameter("uddg")?.takeIf { it.startsWith("http") }
            }
            if (parsed.host.endsWith("duckduckgo.com")) return null
            return absolute
        }
    }
}

/** What a search returned, plus a note for the model when the primary provider failed. */
data class SearchOutcome(val results: List<SearchResult>, val note: String?)

class WebSearch(
    private val primary: SearchProvider?,
    private val fallback: SearchProvider,
) {
    suspend fun search(query: String, count: Int): SearchOutcome {
        var note: String? = null
        if (primary != null) {
            try {
                return SearchOutcome(primary.search(query, count), null)
            } catch (e: WebException) {
                note = "(${primary.id} failed: ${e.message}; results from ${fallback.id})"
            }
        }
        return SearchOutcome(fallback.search(query, count), note)
    }
}

internal const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
