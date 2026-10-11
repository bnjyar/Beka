package dev.beka.web

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebTest {
    private val client = OkHttpClient()

    private fun server(vararg responses: MockResponse): MockWebServer =
        MockWebServer().apply { responses.forEach { enqueue(it) }; start() }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun html(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    @Test
    fun `brave parses web results and sends the token`() = runTest {
        val s = server(json("""{"web":{"results":[{"title":"A","url":"https://a.com","description":"da"},{"title":"B","url":"https://b.com","description":"db"}]}}"""))
        val results = BraveSearch(client, "k", s.url("/search").toString()).search("kopi", 5)
        assertEquals(listOf(SearchResult("A", "https://a.com", "da"), SearchResult("B", "https://b.com", "db")), results)
        val req = s.takeRequest()
        assertEquals("k", req.getHeader("X-Subscription-Token"))
        assertEquals("kopi", req.requestUrl!!.queryParameter("q"))
        s.shutdown()
    }

    @Test
    fun `brave retries once on 429`() = runTest {
        val s = server(
            json("{}", 429),
            json("""{"web":{"results":[{"title":"A","url":"https://a.com","description":"d"}]}}"""),
        )
        val results = BraveSearch(client, "k", s.url("/").toString(), retryDelayMs = 1).search("x", 3)
        assertEquals(1, results.size)
        assertEquals(2, s.requestCount)
        s.shutdown()
    }

    @Test
    fun `tavily posts json with bearer key`() = runTest {
        val s = server(json("""{"results":[{"title":"T","url":"https://t.com","content":"c"}]}"""))
        val results = TavilySearch(client, "tk", s.url("/search").toString()).search("q", 4)
        assertEquals(listOf(SearchResult("T", "https://t.com", "c")), results)
        val req = s.takeRequest()
        assertEquals("Bearer tk", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"max_results\":4"))
        s.shutdown()
    }

    @Test
    fun `tinyfish parses results and sends the key`() = runTest {
        val s = server(json("""{"query":"q","results":[{"position":1,"site_name":"x.com","title":"X","snippet":"sn","url":"https://x.com"}],"total_results":1,"page":0}"""))
        val results = TinyFishSearch(client, "fk", s.url("/").toString()).search("harga emas", 5)
        assertEquals(listOf(SearchResult("X", "https://x.com", "sn")), results)
        val req = s.takeRequest()
        assertEquals("fk", req.getHeader("X-API-Key"))
        assertEquals("harga emas", req.requestUrl!!.queryParameter("query"))
        s.shutdown()
    }

    @Test
    fun `http errors become WebException with the status`() = runTest {
        val s = server(json("""{"error":"bad key"}""", 401))
        val e = assertFailsWith<WebException> { TinyFishSearch(client, "bad", s.url("/").toString()).search("q", 5) }
        assertEquals(401, e.status)
        s.shutdown()
    }

    private val ddgHtml = """
        <html><body>
        <div class="result result--ad"><a class="result__a" href="https://ad.example/x">Ad</a><a class="result__snippet">buy</a></div>
        <div class="result results_links"><h2><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.example.com%2Fa%3Fx%3D1&rut=abc">Example A</a></h2>
          <a class="result__snippet">Snippet A</a></div>
        <div class="result"><a class="result__a" href="https://direct.example/b">Direct B</a><a class="result__snippet">Snippet B</a></div>
        </body></html>
    """.trimIndent()

    @Test
    fun `duckduckgo parsing decodes uddg and skips ads`() {
        val results = DuckDuckGoSearch.parse(ddgHtml, 10)
        assertEquals(
            listOf(
                SearchResult("Example A", "https://www.example.com/a?x=1", "Snippet A"),
                SearchResult("Direct B", "https://direct.example/b", "Snippet B"),
            ),
            results,
        )
    }

    @Test
    fun `duckduckgo 202 means blocked`() = runTest {
        val s = server(html("<html></html>", 202))
        val e = assertFailsWith<WebException> { DuckDuckGoSearch(client, s.url("/html/").toString()).search("q", 5) }
        assertTrue(e.message!!.contains("DuckDuckGo"))
        s.shutdown()
    }

    @Test
    fun `failed primary falls back to duckduckgo with a note`() = runTest {
        val primary = MockWebServer().apply { enqueue(json("{}", 500)); start() }
        val ddg = server(html(ddgHtml))
        val web = WebSearch(TinyFishSearch(client, "k", primary.url("/").toString()), DuckDuckGoSearch(client, ddg.url("/html/").toString()))
        val outcome = web.search("q", 5)
        assertEquals(2, outcome.results.size)
        assertTrue(outcome.note!!.contains("tinyfish failed"))
        assertTrue(outcome.note!!.contains("duckduckgo"))
        primary.shutdown()
        ddg.shutdown()
    }

    @Test
    fun `no primary goes straight to the fallback`() = runTest {
        val ddg = server(html(ddgHtml))
        val outcome = WebSearch(null, DuckDuckGoSearch(client, ddg.url("/html/").toString())).search("q", 1)
        assertEquals(1, outcome.results.size)
        assertEquals(null, outcome.note)
        ddg.shutdown()
    }

    private val filler = "Kalimat isi artikel yang cukup panjang. ".repeat(20)

    @Test
    fun `readable text prefers article and keeps structure`() = runTest {
        val s = server(
            html(
                """<html><head><title>Judul Halaman</title></head><body>
                <nav>Menu Navigasi</nav><div>Sidebar noise</div>
                <article><h1>Judul Artikel</h1><p>$filler</p><ul><li>satu</li><li>dua</li></ul><p>Paragraf <a href="/x">tautan</a> akhir.</p></article>
                <footer>Hak cipta</footer></body></html>"""
            )
        )
        val page = PageFetcher(client, guard = HostGuard(allowPrivate = true)).fetch(s.url("/a").toString())
        assertEquals("Judul Halaman", page.title)
        assertTrue(page.text.startsWith("# Judul Artikel"), page.text.take(80))
        assertTrue(page.text.contains("- satu\n- dua"), page.text)
        assertTrue(page.text.contains("Paragraf tautan akhir."))
        assertTrue(!page.text.contains("Menu Navigasi"))
        assertTrue(!page.text.contains("Sidebar noise"))
        assertTrue(!page.text.contains("Hak cipta"))
        s.shutdown()
    }

    @Test
    fun `thin pages fall back to jina`() = runTest {
        val page = server(html("<html><head><title>T</title></head><body><div id=app></div></body></html>"))
        val jina = server(MockResponse().setBody("Title: Dari Jina\nURL Source: x\nMarkdown Content:\n# Isi\n\n${"Teks. ".repeat(80)}"))
        val fetcher = PageFetcher(client, guard = HostGuard(allowPrivate = true), jinaUrl = jina.url("/").toString())
        val result = fetcher.fetch(page.url("/spa").toString())
        assertEquals("Dari Jina", result.title)
        assertTrue(result.text.startsWith("# Isi"))
        assertTrue(jina.takeRequest().path!!.startsWith("/http://"))
        page.shutdown()
        jina.shutdown()
    }

    @Test
    fun `tinyfish fetch is used first when a key is set`() = runTest {
        val tf = server(json("""{"results":[{"url":"u","title":"TF","text":"# markdown body"}],"errors":[]}"""))
        val fetcher = PageFetcher(client, tinyFishKey = "fk", guard = HostGuard(allowPrivate = true), tinyFishUrl = tf.url("/").toString())
        val page = fetcher.fetch("http://localhost:1/never-fetched")
        assertEquals("TF", page.title)
        assertEquals("# markdown body", page.text)
        val req = tf.takeRequest()
        assertEquals("fk", req.getHeader("X-API-Key"))
        assertTrue(req.body.readUtf8().contains("localhost:1/never-fetched"))
        tf.shutdown()
    }

    @Test
    fun `long pages are paged by offset`() = runTest {
        val long = "<html><body><p>" + "abcdefghij".repeat(2_500) + "</p></body></html>"
        val s = server(html(long), html(long))
        val fetcher = PageFetcher(client, guard = HostGuard(allowPrivate = true))
        val url = s.url("/long").toString()
        val first = fetcher.fetch(url)
        assertEquals(25_000, first.totalChars)
        assertEquals(PageFetcher.PAGE_CHARS, first.text.length)
        val second = fetcher.fetch(url, offset = PageFetcher.PAGE_CHARS)
        assertEquals(PageFetcher.PAGE_CHARS, second.offset)
        assertEquals(PageFetcher.PAGE_CHARS, second.text.length)

        val tools = WebTools(WebSearch(null, DuckDuckGoSearch(client)), fetcher)
        val text = tools.run("fetch_page", """{"url":"$url"}""")
        assertTrue(text.contains("Showing chars 0–12000 of 25000; call fetch_page again with offset=12000 for more."), text.takeLast(150))
        s.shutdown()
    }

    @Test
    fun `private and local addresses are refused without network`() = runTest {
        val fetcher = PageFetcher(client)
        for (url in listOf("http://127.0.0.1:8080/v1", "http://192.168.1.1/", "http://localhost/x", "http://[::1]/", "http://10.0.0.5/", "http://169.254.169.254/latest", "http://printer.local/")) {
            val e = assertFailsWith<WebException>(url) { fetcher.fetch(url) }
            assertTrue(e.message!!.contains("refused"), "$url: ${e.message}")
        }
        assertFailsWith<IllegalArgumentException> { fetcher.fetch("ftp://example.com/x") }
    }

    @Test
    fun `address classification`() {
        fun priv(ip: String) = HostGuard.isPrivate(java.net.InetAddress.getByName(ip))
        assertTrue(priv("172.16.0.1") && priv("172.31.255.1") && priv("fc00::1") && priv("fe80::1") && priv("100.100.1.1"))
        assertTrue(!priv("8.8.8.8") && !priv("172.32.0.1") && !priv("2606:4700:4700::1111"))
    }

    @Test
    fun `tools format search results and report bad arguments`() = runTest {
        val ddg = server(html(ddgHtml))
        val tools = WebTools(WebSearch(null, DuckDuckGoSearch(client, ddg.url("/html/").toString())), PageFetcher(client))
        val text = tools.run("web_search", """{"query":"kopi","count":1}""")
        assertTrue(text.startsWith("1. Example A\n   https://www.example.com/a?x=1\n   Snippet A"), text)
        assertEquals("Search the web: kopi", tools.describe("web_search", """{"query":"kopi"}"""))
        assertEquals("Read page: www.example.com", tools.describe("fetch_page", """{"url":"https://www.example.com/a"}"""))
        dev.beka.llm.Lang.current = dev.beka.llm.Lang.ID
        try {
            assertEquals("Cari web: kopi", tools.describe("web_search", """{"query":"kopi"}"""))
        } finally {
            dev.beka.llm.Lang.current = dev.beka.llm.Lang.EN
        }
        assertFailsWith<IllegalArgumentException> { tools.run("web_search", "{}") }
        assertFailsWith<dev.beka.agent.ToolFailed> { tools.run("fetch_page", """{"url":"http://127.0.0.1/"}""") }
        ddg.shutdown()
    }
}
