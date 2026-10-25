package rulegen

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailGenTest {
    private val channels = """
        <html><body>
        <div class="anthology-tab"><a class="swiper-slide" href="#">线路一</a><a class="swiper-slide swiper-slide-active" href="#">线路二</a></div>
        <div class="anthology-list-box"><ul class="anthology-list-play">
          <li><a href="/play/1-1-1.html">第1集</a></li><li><a href="/play/1-1-2.html">第2集</a></li><li><a href="/play/1-1-3.html">第3集</a></li></ul></div>
        <div class="anthology-list-box"><ul class="anthology-list-play">
          <li><a href="/play/1-2-1.html">第1集</a></li><li><a href="/play/1-2-2.html">第2集</a></li><li><a href="/play/1-2-3.html">第3集</a></li></ul></div>
        <ul class="years"><li><a href="/y/2024">2024</a></li><li><a href="/y/2023">2023</a></li><li><a href="/y/2022">2022</a></li></ul>
        <div class="side"><a href="/v/9">最新更新：另一部动漫的第12集</a></div>
        </body></html>
    """.trimIndent()

    private val plain = """
        <html><body><div class="nav"><a href="/">首页</a></div>
        <div class="eps"><a href="/p/1">01</a><a href="/p/2">02</a><a href="/p/3">03</a><a href="/p/4">04</a></div></body></html>
    """.trimIndent()

    private val twoListsNoTabs = """
        <html><body>
        <div class="a"><ul class="pl"><li><a href="/1">第1集</a></li><li><a href="/2">第2集</a></li></ul></div>
        <div class="a"><ul class="pl"><li><a href="/3">第1集</a></li><li><a href="/4">第2集</a></li></ul></div>
        </body></html>
    """.trimIndent()

    private val onlyYears = """<html><body><ul><li><a href="/y/2024">2024</a></li><li><a href="/y/2023">2023</a></li></ul></body></html>"""

    private fun cfg(r: DetailGen.Result) = JsonObject(r.config)
    private fun JsonObject.s(vararg path: String): String {
        var cur: JsonObject = this
        for (k in path.dropLast(1)) cur = cur[k]!!.jsonObject
        return cur[path.last()]!!.jsonPrimitive.content
    }

    @Test
    fun channelsAndEpisodes() {
        val r = assertNotNull(DetailGen.generate(channels))
        val c = cfg(r)
        assertEquals("index-grouped", c.s("channelFormatId"))
        val f = c["selectorChannelFormatFlattened"]!!.jsonObject
        assertEquals("a", f.s("selectEpisodesFromList"))
        assertEquals("^(?<ch>.+?)(\\d+)?$", f.s("matchChannelName"))
        assertNull(r.note)
        val rep = DetailGen.test(c, channels)
        assertTrue(rep.ok, rep.message)
        assertEquals("线路 2 条，剧集共 6 个", rep.message)
    }

    @Test
    fun selectorsDoNotUseNth() {
        assertTrue("nth" !in assertNotNull(DetailGen.generate(channels)).config.toString())
    }

    @Test
    fun plainNumbersWithoutChannels() {
        val r = assertNotNull(DetailGen.generate(plain))
        val c = cfg(r)
        assertEquals("no-channel", c.s("channelFormatId"))
        assertEquals("^(?<ep>\\d+(?:\\.\\d+)?)$", c.s("selectorChannelFormatNoChannel", "matchEpisodeSortFromName"))
        val rep = DetailGen.test(c, plain)
        assertTrue(rep.ok, rep.message)
        assertEquals("剧集 4 集（无线路）", rep.message)
    }

    @Test
    fun twoListsWithoutTabsFallBackToNoChannel() {
        val r = assertNotNull(DetailGen.generate(twoListsNoTabs))
        assertEquals("no-channel", cfg(r).s("channelFormatId"))
        assertNotNull(r.note)
        assertTrue(DetailGen.test(cfg(r), twoListsNoTabs).ok)
    }

    @Test
    fun yearListIsNotEpisodes() {
        assertNull(DetailGen.generate(onlyYears))
    }

    @Test
    fun noEpisodes() {
        assertNull(DetailGen.generate("<html><body><a href='/x'>首页</a></body></html>"))
    }

    @Test
    fun testDetectsBrokenSelector() {
        val r = assertNotNull(DetailGen.generate(plain))
        assertTrue(!DetailGen.test(cfg(r), channels).ok) // 换一个页面，选择器选不到
    }
}
