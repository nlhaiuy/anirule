package rulegen

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 搜索框不可用 / 搜不到结果时的兜底与诊断。用基于 Jsoup 的假浏览器，不依赖任何具体站点。 */
class FlowFallbackTest {
    private val base = "https://site.example"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val home = """<html><head><title>MX动漫-最新最全动漫免费在线观看</title>
        <link rel="shortcut icon" href="/fav.ico"></head><body><div>首页</div></body></html>"""
    private fun results(kw: String) = """<html><body><ul class="results">
        <li class="row"><a class="title" href="/detail/1.html">$kw</a></li>
        <li class="row"><a class="title" href="/detail/2.html">$kw 第二季</a></li></ul></body></html>"""
    private val detail = "<html><body><div>详情</div></body></html>"
    private val empty = "<html><head><title>搜索结果</title></head><body><div>没有找到相关内容</div></body></html>"

    private class Fake(val pages: Map<String, String>, val uiSearch: Boolean = false, val rawOk: Boolean = true) : Browser {
        var url = ""
        var dom = ""
        override fun load(url: String) { this.url = url; dom = pages[url] ?: "" }
        override fun call(fn: String, vararg args: String): String = when (fn) {
            "dom" -> dom
            "snippet" -> Jsoup.parse(dom).let { "${it.title()} ${it.text()}" }.take(200)
            "fillSearch" -> if (uiSearch) {
                load("https://site.example/search.html?wd=" + URLEncoder.encode(args[0], "UTF-8").replace("+", "%20"))
                "ok:enter"
            } else "no-input"
            else -> "none"
        }
        override fun currentUrl() = url
        override fun waitIdle() {}
        override fun records(): List<CaptureRecord> = emptyList()
        override fun fetchRaw(url: String): String? = if (rawOk) pages[url] else null
    }

    private val kws = listOf("葬送的芙莉莲", "孤独摇滚")
    private fun run(b: Browser) = Flow.run("$base/", b, keywords = kws, pauseMs = 0)

    @Test
    fun noSearchBoxFallsBackToDirectUrl() {
        val pages = mapOf(
            "$base/" to home,
            "$base/search.html?wd=${enc("葬送的芙莉莲")}" to results("葬送的芙莉莲"),
            "$base/detail/1.html" to detail,
        )
        val r = run(Fake(pages))
        assertTrue(r.ok, r.notes.toString())
        assertEquals("html", r.mode)
        val args = r.rule!!["arguments"]!!.jsonObject
        assertEquals("$base/search.html?wd={keyword}", args["searchConfig"]!!.jsonObject["searchUrl"]!!.jsonPrimitive.content)
        assertTrue(r.notes.any { "直接搜索链接" in it }, r.notes.toString())
        // 名称与图标来自首页，不是写死的默认值
        assertEquals("MX动漫", args["name"]!!.jsonPrimitive.content)
        assertEquals("$base/fav.ico", args["iconUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun noSearchBoxAndNoDirectUrlStopsEarlyWithNote() {
        val r = run(Fake(mapOf("$base/" to home)))
        assertFalse(r.ok)
        assertTrue(r.notes.any { "搜索框" in it }, r.notes.toString())
    }

    @Test
    fun failureNotesContainDiagnostics() {
        val pages = mapOf("$base/" to home) + kws.associate { "$base/search.html?wd=${enc(it)}" to empty }
        val r = run(Fake(pages, uiSearch = true))
        assertFalse(r.ok)
        val joined = r.notes.joinToString("\n")
        assertTrue("搜索后停在" in joined, joined)
        assertTrue("没有找到相关内容" in joined, joined)
    }
}
