package rulegen

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleGenTest {
    private val indexed = """
        <html><head><title>搜索 葬送的芙莉莲</title></head><body>
        <div class="box-width"><div class="search-box">
          <input value="葬送的芙莉莲">
          <div class="item"><div class="thumb-menu"><a href="/bangumi/1.html"><img src="1.jpg"></a></div>
            <div class="thumb-content"><div class="thumb-txt cor4">葬送的芙莉莲</div></div></div>
          <div class="item"><div class="thumb-menu"><a href="/bangumi/2.html"><img src="2.jpg"></a></div>
            <div class="thumb-content"><div class="thumb-txt cor4">葬送的芙莉莲 第二季</div></div></div>
          <div class="item"><div class="thumb-menu"><a href="/bangumi/3.html"><img src="3.jpg"></a></div>
            <div class="thumb-content"><div class="thumb-txt cor4">另一部无关的番</div></div></div>
        </div></div></body></html>
    """.trimIndent()

    private val anchor = """
        <html><body><ul class="results">
          <li class="row"><a class="title" href="/v/10">孤独摇滚！</a></li>
          <li class="row"><a class="title" href="/v/11">孤独摇滚！ 剧场版</a></li>
          <li class="row"><a class="title" href="/v/12">别的番</a></li>
        </ul><div class="nav"><a href="/">首页</a></div></body></html>
    """.trimIndent()

    private val echo = """
        <html><body>
        <h2 class="hint"><a href="/search">搜索：药屋少女的呢喃</a></h2>
        <div class="list">
          <div class="card"><a class="name" href="/d/1">药屋少女的呢喃</a></div>
          <div class="card"><a class="name" href="/d/2">药屋少女的呢喃 第二季</a></div>
        </div></body></html>
    """.trimIndent()

    private val noResult = "<html><body><div class='empty'>没有找到相关内容</div></body></html>"
    private val jsPage = "<html><body><div id='app'></div><script>load('药屋少女的呢喃')</script></body></html>"

    private fun JsonObject.cfg() = this["arguments"]!!.jsonObject["searchConfig"]!!.jsonObject

    @Test
    fun indexedFormat() {
        val (rule, rep) = RuleGen.generateAndTest(indexed, "葬送的芙莉莲", "https://x.com/s?wd={keyword}")
        assertEquals("indexed", rule.cfg()["subjectFormatId"]!!.jsonPrimitive.content)
        assertTrue(rep.ok, rep.message)
        assertEquals(3, rep.names.size) // 含不带关键词的第三项，说明规则已泛化
        assertEquals(listOf("/bangumi/1.html", "/bangumi/2.html", "/bangumi/3.html"), rep.links)
    }

    @Test
    fun anchorFormat() {
        val (rule, rep) = RuleGen.generateAndTest(anchor, "孤独摇滚", "https://x.com/s?q={keyword}")
        assertEquals("a", rule.cfg()["subjectFormatId"]!!.jsonPrimitive.content)
        assertTrue(rep.ok, rep.message)
        assertEquals(3, rep.names.size)
    }

    @Test
    fun prefersResultListOverEcho() {
        val (_, rep) = RuleGen.generateAndTest(echo, "药屋少女的呢喃", "https://x.com/s?q={keyword}")
        assertTrue(rep.ok, rep.message)
        assertEquals(listOf("/d/1", "/d/2"), rep.links)
    }

    @Test
    fun noResultThrows() {
        assertFailsWith<GenerateException> { RuleGen.generateRule(noResult, "药屋少女的呢喃", "u") }
    }

    @Test
    fun jsPageThrows() {
        assertFailsWith<GenerateException> { RuleGen.generateRule(jsPage, "药屋少女的呢喃", "u") }
    }

    @Test
    fun emptyKeywordThrows() {
        assertFailsWith<GenerateException> { RuleGen.generateRule(anchor, "  ", "u") }
    }

    @Test
    fun detectsBadSelector() {
        val bad = buildJsonObject {
            put("arguments", buildJsonObject {
                put("searchConfig", buildJsonObject {
                    put("subjectFormatId", JsonPrimitive("a"))
                    put("selectorSubjectFormatA", buildJsonObject { put("selectLists", JsonPrimitive(".nothing")) })
                })
            })
        }
        assertFalse(RuleGen.testRule(bad, anchor, "孤独摇滚").ok)
    }

    @Test
    fun noNthInSelectors() {
        val rule = RuleGen.generateRule(indexed, "葬送的芙莉莲", "u")
        assertFalse(rule.toString().contains("nth"))
    }
}
