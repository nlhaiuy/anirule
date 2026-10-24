package rulegen

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverTest {
    private val homeGet = """<html><body><form action="/search.html" method="get">
        <input type="hidden" name="type" value="1"><input type="text" name="wd"></form></body></html>"""
    private val homePost = """<html><body><form action="/s" method="post"><input type="text" name="q"></form></body></html>"""
    private val homePlain = "<html><body>首页</body></html>"
    private val empty = "<html><body><div>没有找到相关内容</div></body></html>"

    private fun results(kw: String) = """<html><body><ul class="results">
        <li class="row"><a class="title" href="/v/1">$kw</a></li>
        <li class="row"><a class="title" href="/v/2">$kw 第二季</a></li></ul></body></html>"""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fetcher(
        pages: Map<String, String> = emptyMap(),
        resultsFor: List<String> = listOf("葬送的芙莉莲"),
        resultsPrefix: String? = null,
    ) = Fetcher { url ->
        pages[url]
            ?: resultsPrefix?.takeIf { url.startsWith(it) }?.let {
                resultsFor.firstOrNull { kw -> enc(kw) in url }?.let(::results) ?: empty
            }
            ?: throw java.io.IOException("404")
    }

    @Test
    fun formTemplateWithHidden() {
        val tpls = Discover.templatesFromHome(homeGet, "https://x.com/", ArrayList())
        assertEquals(listOf("https://x.com/search.html?type=1&wd={keyword}"), tpls)
    }

    @Test
    fun postFormNoted() {
        val notes = ArrayList<String>()
        assertTrue(Discover.templatesFromHome(homePost, "https://x.com/", notes).isEmpty())
        assertTrue(notes.any { "POST" in it })
    }

    @Test
    fun discoverFromHomeForm() {
        val f = fetcher(mapOf("https://x.com/" to homeGet), resultsPrefix = "https://x.com/search.html?type=1&wd=")
        val r = Discover.discover("https://x.com/", f)
        assertTrue(r.ok, r.notes.toString())
        assertEquals("https://x.com/search.html?type=1&wd={keyword}", r.searchUrl)
        assertEquals("葬送的芙莉莲", r.keyword)
    }

    @Test
    fun triesNextKeyword() {
        val f = fetcher(mapOf("https://x.com/" to homeGet), listOf("鬼灭之刃"), "https://x.com/search.html?type=1&wd=")
        val r = Discover.discover("https://x.com/", f)
        assertTrue(r.ok, r.notes.toString())
        assertEquals("鬼灭之刃", r.keyword)
    }

    @Test
    fun fallbackCommonPath() {
        val f = fetcher(mapOf("https://x.com/" to homePlain), resultsPrefix = "https://x.com/search.html?wd=")
        val r = Discover.discover("https://x.com/", f)
        assertTrue(r.ok, r.notes.toString())
        assertEquals("https://x.com/search.html?wd={keyword}", r.searchUrl)
    }

    @Test
    fun userGivesSearchTemplate() {
        val r = Discover.discover("https://x.com/q?k={keyword}", fetcher(resultsPrefix = "https://x.com/q?k="))
        assertTrue(r.ok, r.notes.toString())
    }

    @Test
    fun jsonResponseReported() {
        val f = Fetcher { u -> if (u == "https://x.com/") homePlain else """{"list": []}""" }
        val r = Discover.discover("https://x.com/", f)
        assertFalse(r.ok)
        assertTrue(r.notes.any { "json-api" in it })
    }

    @Test
    fun unreachable() {
        val r = Discover.discover("https://x.com/", Fetcher { throw java.io.IOException("boom") })
        assertFalse(r.ok)
        assertTrue(r.notes.any { "无法访问" in it })
    }

    @Test
    fun noResultsAnywhere() {
        assertFalse(Discover.discover("https://x.com/", Fetcher { empty }).ok)
    }
}
