package rulegen

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowTest {
    private val headers = mapOf("apikey" to "k123", "Authorization" to "Bearer k123", "Content-Type" to "application/json")
    private val detail = """{"id":101,"sources":[{"name":"线路A"},{"name":"线路B"}],
        "episodes":[{"id":9001,"title":"冒险的结束","episode_number":1,"kind":"main"},
                    {"id":9002,"title":"下一站","episode_number":2,"kind":"main"}]}"""
    private val playJson = """{"candidates":[{"source_name":"线路A","url":"https://cdn.example.com/a.m3u8"}]}"""

    /** 假站点：通过 hook 模拟页面对 capture.js 各函数的响应。 */
    private class FakeBrowser(val site: Site) : Browser {
        val recs = ArrayList<CaptureRecord>()
        var url = ""
        var dom = "<html><body></body></html>"
        init { site.b = this }
        override fun load(url: String) { this.url = url; site.reset() }
        override fun call(fn: String, vararg args: String) = site.handle(fn, args.toList())
        override fun currentUrl() = url
        override fun waitIdle() {}
        override fun records(): List<CaptureRecord> = recs
        fun emit(url: String, body: String, response: String, headers: Map<String, String>) {
            recs += CaptureRecord("fetch", url, "POST", headers, body, 200, response)
        }
    }

    private abstract class Site {
        lateinit var b: FakeBrowser
        open fun reset() {}
        open fun handle(fn: String, args: List<String>): String = when (fn) { "dom" -> ""; "fillSearch" -> "no-input"; else -> "none" }
    }

    private inner class JsonSite(
        val resultsFor: Set<String> = setOf("葬送的芙莉莲"),
        val withDetail: Boolean = true,
        val withPlay: Boolean = true,
        val hiddenSearch: Boolean = false,
    ) : Site() {
        var opened = false
        override fun reset() { opened = false }
        override fun handle(fn: String, args: List<String>): String = when (fn) {
            "openSearch" -> if (hiddenSearch) { opened = true; "ok" } else "none"
            "fillSearch" -> if (hiddenSearch && !opened) "no-input" else {
                val kw = args[0]
                val data = if (kw in resultsFor)
                    """[{"id":101,"title":"$kw"},{"id":102,"title":"$kw 第二季"},{"id":103,"title":"别的番"}]""" else "[]"
                b.emit("https://api.site.example/rpc/search",
                    """{"search_term":"$kw","page_number":1,"items_per_page":50}""", data, headers)
                "ok:button"
            }
            "clickResult" -> {
                b.url = "https://site.example/anime/101"
                if (withDetail) b.emit("https://api.site.example/rpc/detail", """{"p_id":101}""", detail, headers)
                "ok:a"
            }
            "clickEpisode" -> {
                b.url = "https://site.example/anime/101/play/9001"
                if (withPlay) b.emit("https://api.site.example/fn/issue", """{"action":"fallback","episode_id":9001}""", playJson, headers)
                "ok"
            }
            else -> "none"
        }
    }

    private inner class HtmlSite : Site() {
        override fun handle(fn: String, args: List<String>): String = when (fn) {
            "fillSearch" -> {
                val kw = args[0]
                b.url = "https://x.com/search.html?wd=" + URLEncoder.encode(kw, "UTF-8")
                b.dom = """<html><body><ul class="results">
                    <li class="row"><a class="title" href="/v/1">$kw</a></li>
                    <li class="row"><a class="title" href="/v/2">$kw 第二季</a></li></ul></body></html>"""
                "ok:enter"
            }
            "dom" -> b.dom
            else -> "none"
        }
    }

    private object NoSearchSite : Site() {}

    private val kws = listOf("葬送的芙莉莲", "孤独摇滚")
    private fun go(site: Site) = Flow.run("https://site.example/", FakeBrowser(site), kws)

    @Test
    fun jsonSiteFullAuto() {
        val r = go(JsonSite())
        assertTrue(r.ok, r.notes.toString())
        assertEquals("json-api", r.mode)
        assertFalse(r.partial)
        val cfg = r.rule!!["arguments"]!!.jsonObject["searchConfig"]!!.jsonObject
        val s = cfg["search"]!!.jsonObject
        assertEquals(
            """{"search_term":"{keyword}","page_number":{page},"items_per_page":{pageSize}}""",
            s["request"]!!.jsonObject["body"]!!.jsonPrimitive.content,
        )
        assertEquals("50", s["pageSize"]!!.jsonPrimitive.content)
        assertEquals("https://site.example/anime/{id}", s["subjectUrlTemplate"]!!.jsonPrimitive.content)
        assertEquals("""{"p_id":{id}}""", cfg["subject"]!!.jsonObject["request"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        assertEquals(
            "https://site.example/anime/{subjectId}/play/{id}",
            cfg["subject"]!!.jsonObject["episodes"]!!.jsonObject["episodeUrlTemplate"]!!.jsonPrimitive.content,
        )
        assertEquals(
            """{"action":"fallback","episode_id":{id}}""",
            cfg["play"]!!.jsonObject["request"]!!.jsonObject["body"]!!.jsonPrimitive.content,
        )
        assertEquals("https://api.site.example", cfg["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("apikey: k123\nAuthorization: Bearer k123", cfg["headers"]!!.jsonPrimitive.content)
        assertTrue("线路 2 条" in r.report!!.message)
    }

    @Test
    fun secondKeywordWhenFirstHasNoResults() {
        val r = go(JsonSite(resultsFor = setOf("孤独摇滚")))
        assertTrue(r.ok, r.notes.toString())
        assertEquals("孤独摇滚", r.keyword)
    }

    @Test
    fun hiddenSearchBoxIsOpened() {
        assertTrue(go(JsonSite(hiddenSearch = true)).ok)
    }

    @Test
    fun noDetailGivesPartialRule() {
        val r = go(JsonSite(withDetail = false))
        assertTrue(r.ok, r.notes.toString())
        assertTrue(r.partial)
        assertTrue(r.notes.any { "详情" in it })
    }

    @Test
    fun noPlayIsPartial() {
        val r = go(JsonSite(withPlay = false))
        assertTrue(r.ok, r.notes.toString())
        assertTrue(r.partial)
        assertTrue(r.notes.any { "播放" in it })
    }

    @Test
    fun htmlSite() {
        val r = go(HtmlSite())
        assertTrue(r.ok, r.notes.toString())
        assertEquals("html", r.mode)
        assertEquals(
            "https://x.com/search.html?wd={keyword}",
            r.rule!!["arguments"]!!.jsonObject["searchConfig"]!!.jsonObject["searchUrl"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun noSearchBox() {
        val r = go(NoSearchSite)
        assertFalse(r.ok)
        assertTrue(r.notes.any { "搜索框" in it })
    }

    @Test
    fun nothingFoundAnywhere() {
        assertFalse(go(JsonSite(resultsFor = emptySet())).ok)
    }
}
