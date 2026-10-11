package dev.beka.web

import dev.beka.llm.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** One slice of a page: [text] covers chars [offset, offset + text.length) of [totalChars]. */
data class Page(val title: String, val url: String, val text: String, val totalChars: Int, val offset: Int = 0)

private class BlockedHost(message: String) : UnknownHostException(message)

/**
 * Keeps the agent away from the user's own network: a web page must not be able to make Beka read the local
 * LLM proxy on 127.0.0.1 or anything on the home network. [allowPrivate] is for tests only.
 */
class HostGuard(private val allowPrivate: Boolean = false) {
    /** @throws WebException when [host] is local or resolves to a private address. */
    suspend fun check(host: String) {
        try {
            withContext(Dispatchers.IO) { resolve(host) }
        } catch (e: BlockedHost) {
            throw WebException(e.message ?: tr("Address refused", "Alamat ditolak"))
        } catch (e: UnknownHostException) {
            throw WebException(tr("Domain not found: $host", "Domain tidak ditemukan: $host"))
        }
    }

    /** Applies the same check to every connection OkHttp opens. */
    fun dns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }

    private fun resolve(host: String): List<InetAddress> {
        val name = host.trim().trimEnd('.').lowercase()
        if (!allowPrivate && (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local"))) {
            throw BlockedHost(tr("Local address refused: $host", "Alamat lokal ditolak: $host"))
        }
        val all = InetAddress.getAllByName(name).toList()
        if (!allowPrivate && all.any(::isPrivate)) throw BlockedHost(tr("Local/private network address refused: $host", "Alamat jaringan lokal/privat ditolak: $host"))
        return all
    }

    companion object {
        fun isPrivate(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            // fc00::/7 (unique local) is not covered by isSiteLocalAddress, which means the deprecated fec0::/10.
            if (a is Inet6Address) return (b[0].toInt() and 0xFE) == 0xFC
            // 100.64.0.0/10 carrier-grade NAT (Tailscale and friends).
            return (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
        }
    }
}

class PageFetcher(
    client: OkHttpClient,
    /** Used for fetching when set. */
    private val tinyFishKey: String? = null,
    private val guard: HostGuard = HostGuard(),
    private val tinyFishUrl: String = "https://api.fetch.tinyfish.ai",
    private val jinaUrl: String = "https://r.jina.ai/",
) {
    /** For TinyFish and Jina: fixed, trusted hosts. */
    private val api = client
    /** For the page itself: redirects are followed by hand so each hop is checked, and DNS is filtered. */
    private val direct = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(guard.dns())
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val cache = object : LinkedHashMap<String, Loaded>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Loaded>) = size > CACHE_PAGES
    }

    private class Loaded(val title: String, val text: String)

    /** @throws IllegalArgumentException for a URL that is not http(s); [WebException] when the page cannot be read. */
    suspend fun fetch(url: String, offset: Int = 0): Page {
        val trimmed = url.trim()
        val parsed = (if ("://" in trimmed) trimmed else "https://$trimmed").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Not a valid http(s) URL: ${url.take(200)}")
        guard.check(parsed.host)
        val key = parsed.toString()
        val loaded = synchronized(cache) { cache[key] } ?: load(parsed).also { synchronized(cache) { cache[key] = it } }
        val total = loaded.text.length
        val start = offset.coerceIn(0, total)
        val end = minOf(total, start + PAGE_CHARS)
        return Page(loaded.title, key, loaded.text.substring(start, end), total, start)
    }

    private suspend fun load(url: HttpUrl): Loaded {
        if (!tinyFishKey.isNullOrBlank()) {
            try {
                return viaTinyFish(url, tinyFishKey)
            } catch (e: WebException) {
                // Fall through to fetching it ourselves.
            }
        }
        var directError: WebException? = null
        var thin: Loaded? = null
        try {
            val page = viaDirect(url)
            if (page.text.length >= MIN_CHARS) return page
            thin = page
        } catch (e: WebException) {
            directError = e
        }
        // Empty or script-only pages, and anything we could not fetch: let Jina render it.
        try {
            val page = viaJina(url)
            if (page.text.isNotBlank()) return page
        } catch (e: WebException) {
            if (directError == null && thin == null) directError = e
        }
        return thin?.takeIf { it.text.isNotBlank() } ?: throw (directError ?: WebException(tr("Empty page: $url", "Halaman kosong: $url")))
    }

    private suspend fun viaTinyFish(url: HttpUrl, key: String): Loaded {
        val body = buildJsonObject {
            put("urls", buildJsonArray { add(JsonPrimitive(url.toString())) })
            put("format", "markdown")
        }.toString().toRequestBody("application/json".toMediaType())
        val reply = api.send(
            Request.Builder().url(tinyFishUrl).post(body).header("X-API-Key", key).header("Accept", "application/json").build(),
            maxBytes = MAX_BODY,
        )
        if (reply.status !in 200..299) throw reply.failure()
        val root = runCatching { webJson.parseToJsonElement(reply.text()) as? JsonObject }.getOrNull()
            ?: throw WebException(tr("TinyFish did not answer with JSON", "Respons TinyFish bukan JSON"))
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject
        val text = (first?.get("text") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) {
            val error = ((root["errors"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("error")
            throw WebException("TinyFish: ${(error as? JsonPrimitive)?.contentOrNull ?: tr("empty page", "halaman kosong")}")
        }
        val title = (first?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Loaded(title.ifBlank { url.host }, text)
    }

    private suspend fun viaDirect(start: HttpUrl): Loaded {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            guard.check(url.host)
            val request = Request.Builder().url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.8")
                .header("Accept-Language", "id,en;q=0.8")
                .build()
            val reply = direct.send(request, maxBytes = MAX_BODY)
            if (reply.status in REDIRECTS) {
                url = reply.location?.let { url.resolve(it) } ?: throw WebException(tr("Redirect without a target", "Redirect tanpa tujuan"), reply.status)
                if (url.scheme != "http" && url.scheme != "https") throw WebException(tr("Redirect to an unsupported scheme", "Redirect ke skema tidak didukung"))
                return@repeat
            }
            if (reply.status !in 200..299) throw reply.failure()
            val type = reply.contentType?.toString()?.lowercase().orEmpty()
            return when {
                type.isEmpty() || "html" in type -> readable(reply, url)
                "text/plain" in type || "json" in type -> Loaded(url.host, reply.text().trim())
                else -> throw WebException(tr("Unsupported content type: $type", "Tipe konten tidak didukung: $type"))
            }
        }
        throw WebException(tr("Too many redirects", "Terlalu banyak redirect"))
    }

    private suspend fun viaJina(url: HttpUrl): Loaded {
        val request = Request.Builder().url(jinaUrl + url)
            .header("Accept", "text/plain")
            .header("X-Return-Format", "markdown")
            .header("User-Agent", BROWSER_UA)
            .build()
        val reply = api.send(request, maxBytes = MAX_BODY)
        if (reply.status !in 200..299) throw reply.failure()
        val raw = reply.text().trim()
        // Jina answers "Title: …\nURL Source: …\nMarkdown Content:\n<body>".
        val title = Regex("""^Title:\s*(.*)$""", RegexOption.MULTILINE).find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val content = raw.substringAfter("Markdown Content:", raw).trim()
        return Loaded(title.ifBlank { url.host }, content)
    }

    private fun readable(reply: HttpReply, url: HttpUrl): Loaded {
        val charset = reply.contentType?.charset()?.name()
        val doc = Jsoup.parse(reply.body.inputStream(), charset, url.toString())
        return Loaded(doc.title().trim().ifBlank { url.host }, Readable.text(doc))
    }

    companion object {
        const val PAGE_CHARS = 12_000
        private const val MIN_CHARS = 300
        private const val MAX_BODY = 3 * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CACHE_PAGES = 4
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

/** Turns an HTML document into plain text: headings as "# ", list items as "- ", paragraphs apart. */
internal object Readable {
    private val DROP = "script,style,nav,header,footer,aside,form,noscript,svg,iframe,template,button,select"
    private val BLOCKS = setOf("p", "div", "section", "article", "main", "ul", "ol", "table", "tr", "blockquote", "pre", "figure", "details", "dl", "dt", "dd", "br")
    private val PARAGRAPH = setOf("p", "table", "blockquote", "pre", "section", "article")
    private val LISTS = setOf("ul", "ol")

    fun text(doc: org.jsoup.nodes.Document): String {
        doc.select(DROP).remove()
        val root = listOf("article", "main")
            .firstNotNullOfOrNull { tag -> doc.selectFirst(tag)?.takeIf { it.text().length >= 500 } }
            ?: doc.body()
            ?: return ""
        val out = StringBuilder()
        walk(root, out)
        return out.toString()
            .replace(Regex("[ \t ]+"), " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun walk(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.text())
            is Element -> {
                val tag = node.tagName().lowercase()
                when {
                    tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                        val text = node.text().trim()
                        if (text.isNotEmpty()) out.append("\n\n# ").append(text).append("\n\n")
                    }
                    tag == "li" -> {
                        val own = node.childNodes()
                            .filterNot { it is Element && it.tagName().lowercase() in LISTS }
                            .joinToString(" ") { if (it is Element) it.text() else (it as? TextNode)?.text().orEmpty() }
                            .trim()
                        if (own.isNotEmpty()) out.append("\n- ").append(own)
                        node.children().filter { it.tagName().lowercase() in LISTS }.forEach { walk(it, out) }
                    }
                    tag == "br" -> out.append('\n')
                    else -> {
                        val gap = if (tag in PARAGRAPH) "\n\n" else if (tag in BLOCKS) "\n" else ""
                        out.append(gap)
                        node.childNodes().forEach { walk(it, out) }
                        out.append(gap)
                    }
                }
            }
            else -> Unit
        }
    }
}
