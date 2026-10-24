package rulegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonApiTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private val search = j("""[{"id":101,"title":"葬送的芙莉莲","score":9.1},
        {"id":102,"title":"葬送的芙莉莲 第二季","score":8.9},{"id":103,"title":"别的番","score":7.0}]""")
    private val detail = j("""{"id":101,"sources":[{"name":"线路A"},{"name":"线路B"}],
        "episodes":[{"id":9001,"title":"冒险的结束","episode_number":1,"kind":"main"},
                    {"id":9002,"title":"下一站","episode_number":2,"kind":"main"}]}""")
    private val play = j("""{"candidates":[{"source_name":"线路A","url":"https://cdn.example.com/a.m3u8"},
        {"source_name":"线路B","url":"https://cdn.example.com/b.m3u8"}]}""")
    private val nested = j("""{"code":0,"data":{"total":2,"list":[
        {"vod_id":"7","vod_name":"孤独摇滚！","vod_sub":"Bocchi the Rock!"},
        {"vod_id":"8","vod_name":"孤独摇滚！ 剧场版","vod_sub":""}]}}""")

    private fun build() = JsonApi.generate(
        "示例 JSON 源",
        JsonApi.Request("https://api.x.com/search", "POST", body = """{"search_term":"葬送的芙莉莲","page":1}"""),
        search, "葬送的芙莉莲",
        JsonApi.Request("https://api.x.com/detail", "POST", body = """{"p_id":{id}}"""), detail,
        JsonApi.Request("https://api.x.com/play", "POST", body = """{"episode_id":{id}}"""), play,
    )

    private fun JsonObject.cfg() = this["arguments"]!!.jsonObject["searchConfig"]!!.jsonObject

    @Test
    fun jsonPathBasics() {
        val doc = j("""{"a":{"b":[{"c":1},{"c":2}]}}""")
        assertEquals(listOf("1", "2"), JsonApi.select(doc, "$.a.b[*].c").map { it.jsonPrimitive.content })
        assertTrue(JsonApi.select(doc, "$.a.nope").isEmpty())
        assertTrue(JsonApi.select(doc, "").isEmpty())
    }

    @Test
    fun defaultArgsMatchBlankTemplate() {
        // 在 core 目录下运行时，fixture 位于 ../proto/fixtures
        val f = listOf("../proto/fixtures/blank_json_api.json", "proto/fixtures/blank_json_api.json")
            .map(::File).firstOrNull { it.exists() } ?: return
        val blank = Json.parseToJsonElement(f.readText()).jsonObject["mediaSources"]!!.jsonArray[0]
            .jsonObject["arguments"]!!
        assertEquals(blank, JsonApi.toJson(JsonApi.defaultArgs("JSON API")))
    }

    @Test
    fun searchTopLevelArray() {
        val r = JsonApi.inferSearch(search, "葬送的芙莉莲")
        assertEquals(listOf("$[*]", "$.title", "$.id"), listOf(r["selectItems"], r["selectName"], r["selectId"]))
    }

    @Test
    fun searchNestedWithAlias() {
        val r = JsonApi.inferSearch(nested, "孤独摇滚")
        assertEquals("$.data.list[*]", r["selectItems"])
        assertEquals("$.vod_name", r["selectName"])
        assertEquals("$.vod_id", r["selectId"])
        assertEquals("$.vod_sub", r["selectAliases"])
    }

    @Test
    fun searchKeywordMissing() {
        assertFailsWith<IllegalArgumentException> { JsonApi.inferSearch(search, "不存在的番") }
    }

    @Test
    fun searchDuplicateIdsRejected() {
        val dup = j("""[{"id":1,"title":"葬送的芙莉莲"},{"id":1,"title":"葬送的芙莉莲2"}]""")
        assertFailsWith<IllegalArgumentException> { JsonApi.inferSearch(dup, "葬送") }
    }

    @Test
    fun subjectGrouped() {
        val r = JsonApi.inferSubject(detail)
        assertEquals("$.episodes[*]", r.episodes["selectEpisodes"])
        assertEquals("$.episode_number", r.episodes["selectSort"])
        assertEquals("$.kind", r.episodes["selectType"])
        assertEquals("grouped", r.channelFormatId)
        assertEquals("$.sources[*]", r.grouped!!["selectChannels"])
    }

    @Test
    fun subjectNoChannel() {
        assertEquals("no-channel", JsonApi.inferSubject(j("""{"episodes":[{"id":1,"name":"a","ep":1}]}""")).channelFormatId)
    }

    @Test
    fun subjectWithoutEpisodesFails() {
        assertFailsWith<IllegalArgumentException> { JsonApi.inferSubject(j("""{"foo":[{"a":1}]}""")) }
    }

    @Test
    fun playCandidates() {
        val r = JsonApi.inferPlay(play)
        assertEquals("$.candidates[*]", r["selectCandidates"])
        assertEquals("$.source_name", r["selectCandidateChannel"])
        assertEquals("$.url", r["selectVideoUrl"])
        assertEquals(false, r["fallbackToFirstCandidate"])
    }

    @Test
    fun playSingleObject() {
        val r = JsonApi.inferPlay(j("""{"url":"https://x.com/v.mp4"}"""))
        assertEquals("", r["selectCandidates"])
        assertEquals("$.url", r["selectVideoUrl"])
    }

    @Test
    fun playNoUrlFails() {
        assertFailsWith<IllegalArgumentException> { JsonApi.inferPlay(j("""{"candidates":[{"url":"relative/path"}]}""")) }
    }

    @Test
    fun keywordLiteralBecomesPlaceholder() {
        val body = build().cfg()["search"]!!.jsonObject["request"]!!.jsonObject["body"]!!.jsonPrimitive.content
        assertTrue("{keyword}" in body)
        assertFalse("葬送" in body)
    }

    @Test
    fun fullPipelinePasses() {
        val rep = JsonApi.test(build(), "葬送的芙莉莲", search, detail, play)
        assertTrue(rep.ok, rep.message)
        assertTrue("线路 2 条" in rep.message)
    }

    @Test
    fun exportWrapper() {
        val w = JsonApi.wrapExport(build())
        assertEquals(2, w["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("json-api", w["mediaSources"]!!.jsonArray[0].jsonObject["factoryId"]!!.jsonPrimitive.content)
    }

    @Test
    fun detectsBrokenSelector() {
        val rule = build()
        val broken = JsonApi.toJson(rule.toMutableMap().also { m ->
            val args = rule["arguments"]!!.jsonObject.toMutableMap()
            val cfg = rule.cfg().toMutableMap()
            val s = cfg["search"]!!.jsonObject.toMutableMap()
            s["selectItems"] = JsonPrimitive("$.nothing[*]")
            cfg["search"] = JsonObject(s)
            args["searchConfig"] = JsonObject(cfg)
            m["arguments"] = JsonObject(args)
        }) as JsonObject
        assertFalse(JsonApi.test(broken, "葬送的芙莉莲", search).ok)
    }
}
