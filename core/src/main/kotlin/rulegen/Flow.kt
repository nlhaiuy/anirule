package rulegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder

/** 注入脚本 capture.js 抓到的一条网络记录。 */
data class CaptureRecord(
    val type: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val status: Int? = null,
    val response: String = "",
)

/** 由调用方实现（Android 上是 WebView 适配层）。call 对应 capture.js 里的 __rg.<fn>(...)。 */
interface Browser {
    fun load(url: String)
    fun call(fn: String, vararg args: String): String
    fun currentUrl(): String
    fun waitIdle()
    fun records(): List<CaptureRecord>
}

data class FlowResult(
    val ok: Boolean,
    val mode: String = "",       // "json-api" | "html" | ""
    val partial: Boolean = false, // json-api 没拿到完整三个阶段
    val rule: JsonObject? = null,
    val report: TestReport? = null,
    val keyword: String = "",
    val notes: List<String> = emptyList(),
)

/** 全自动流程：自动搜索 → 点进详情 → 点第一集 → 从抓到的接口生成并测试规则。 */
object Flow {
    val KEYWORDS = listOf("葬送的芙莉莲", "孤独摇滚", "药屋少女的呢喃", "咒术回战", "鬼灭之刃")
    private val SKIP_HEADERS = setOf(
        "accept", "accept-language", "accept-encoding", "content-length", "user-agent", "referer", "cookie", "host", "connection",
    )
    private const val PAGE_KEYS = "page|page_number|pageNum|pageNo|pn|current"
    private const val SIZE_KEYS = "pageSize|page_size|items_per_page|limit|per_page|perPage|size"

    private fun parse(text: String): JsonElement? {
        val t = text.trimStart()
        if (t.firstOrNull() != '{' && t.firstOrNull() != '[') return null
        return try { Json.parseToJsonElement(t) } catch (_: Exception) { null }
    }

    private fun apiRecords(recs: List<CaptureRecord>): List<Pair<CaptureRecord, JsonElement>> =
        recs.filter { it.type in setOf("fetch", "xhr") && it.status == 200 }
            .mapNotNull { r -> parse(r.response)?.let { r to it } }

    private fun subId(text: String, value: Any, placeholder: String): String =
        Regex("(?<![\\w.%])" + Regex.escape(value.toString()) + "(?![\\w.%])").replace(text) { placeholder }

    private fun enc(s: String, plus: Boolean) =
        URLEncoder.encode(s, "UTF-8").let { if (plus) it else it.replace("+", "%20") }

    private fun subKeyword(text: String, kw: String): String =
        listOf(kw, enc(kw, false), enc(kw, true)).fold(text) { acc, f -> acc.replace(f, "{keyword}") }

    private data class Paged(val url: String, val body: String, val size: Int?)

    /** 把页码/每页条数换成 {page}/{pageSize}。 */
    private fun paginate(url: String, body: String): Paged {
        var size: Int? = null
        val grab = { m: MatchResult -> size = m.groupValues[2].toInt(); m.groupValues[1] + "{pageSize}" }
        var b = Regex("(\"(?:$PAGE_KEYS)\"\\s*:\\s*)(\\d+)").replace(body) { it.groupValues[1] + "{page}" }
        b = Regex("(\"(?:$SIZE_KEYS)\"\\s*:\\s*)(\\d+)").replace(b, grab)
        var u = Regex("([?&](?:$PAGE_KEYS)=)\\d+").replace(url) { it.groupValues[1] + "{page}" }
        u = Regex("([?&](?:$SIZE_KEYS)=)(\\d+)").replace(u, grab)
        return Paged(u, b, size)
    }

    private fun headersText(recs: List<CaptureRecord>): String {
        val seen = HashSet<String>()
        val lines = ArrayList<String>()
        for (r in recs) for ((k, v) in r.headers) {
            val lk = k.lowercase()
            if (lk in SKIP_HEADERS || lk.startsWith("sec-")) continue
            if (lk == "content-type" && "json" in v.lowercase()) continue
            if (seen.add(lk)) lines += "$k: $v"
        }
        return lines.joinToString("\n")
    }

    private fun origin(url: String): String = URI(url).let { "${it.scheme}://${it.authority}" }

    private fun doSearch(b: Browser, kw: String, notes: MutableList<String>): Boolean {
        var res = b.call("fillSearch", kw)
        if (res == "no-input" && b.call("openSearch") == "ok") {
            b.waitIdle()
            res = b.call("fillSearch", kw)
        }
        if (res == "no-input") { notes += "页面上找不到搜索框"; return false }
        b.waitIdle()
        return true
    }

    fun run(url: String, browser: Browser, keywords: List<String> = KEYWORDS, name: String = "示例动漫"): FlowResult {
        val notes = ArrayList<String>()
        browser.load(url)
        browser.waitIdle()
        for (kw in keywords) {
            val mark = browser.records().size
            if (!doSearch(browser, kw, notes)) {
                browser.load(url); browser.waitIdle(); continue
            }
            val fresh = browser.records().drop(mark)

            // JSON 接口：挑条目数最多的搜索响应
            var best: Triple<CaptureRecord, JsonElement, Map<String, Any?>>? = null
            var bestN = -1
            for ((rec, data) in apiRecords(fresh)) {
                val sel = try { JsonApi.inferSearch(data, kw) } catch (_: IllegalArgumentException) { continue }
                val n = JsonApi.select(data, sel["selectItems"] as String).size
                if (n > bestN) { best = Triple(rec, data, sel); bestN = n }
            }
            if (best != null) {
                val res = jsonFlow(browser, name, kw, best.first, best.second, best.third, notes)
                if (res.ok) return res
                notes += "关键词「$kw」的 json-api 流程未通过: ${res.report?.message ?: ""}"
            } else {
                htmlFlow(browser, name, kw, notes)?.let { if (it.ok) return it }
            }
            browser.load(url); browser.waitIdle()
        }
        notes += "所有关键词都没有生成可用规则"
        return FlowResult(false, notes = notes)
    }

    private fun htmlFlow(b: Browser, name: String, kw: String, notes: MutableList<String>): FlowResult? {
        val searchUrl = subKeyword(b.currentUrl(), kw)
        if ("{keyword}" !in searchUrl) { notes += "搜索后网址里没有关键词，无法生成 HTML 规则"; return null }
        val dom = b.call("dom")
        val rule = try { RuleGen.generateRule(dom, kw, searchUrl, name) } catch (e: GenerateException) {
            notes += "HTML 解析失败: ${e.message}"; return null
        }
        val rep = RuleGen.testRule(rule, dom, kw)
        return FlowResult(rep.ok, "html", false, rule, rep, kw, notes)
    }

    private fun jsonFlow(
        b: Browser, name: String, kw: String, srec: CaptureRecord, sdata: JsonElement,
        ssel: Map<String, Any?>, notes: MutableList<String>,
    ): FlowResult {
        val items = JsonApi.select(sdata, ssel["selectItems"] as String)
        val itemId = JsonApi.select(items.first(), ssel["selectId"] as String).first().jsonPrimitive.content
        val used = arrayListOf(srec)
        val paged = paginate(srec.url, srec.body)
        val searchRequest = JsonApi.Request(subKeyword(paged.url, kw), srec.method, body = subKeyword(paged.body, kw))
        val origin = origin(srec.url)

        // 详情：点进第一个结果
        var mark = b.records().size
        b.call("clickResult", kw)
        b.waitIdle()
        val detailUrl = b.currentUrl()
        var drec: CaptureRecord? = null
        var ddata: JsonElement? = null
        var dsel: JsonApi.SubjectInference? = null
        for ((rec, data) in apiRecords(b.records().drop(mark))) {
            if (itemId !in rec.url + rec.body) continue
            dsel = try { JsonApi.inferSubject(data) } catch (_: IllegalArgumentException) { continue }
            drec = rec; ddata = data
            break
        }
        val subjectTpl = if (itemId in detailUrl) subId(detailUrl, itemId, "{id}") else ""

        if (drec == null || ddata == null || dsel == null) {
            notes += "没有抓到详情接口（可能是服务端渲染），只生成了搜索阶段"
            val rule = JsonApi.generate(
                name, searchRequest, sdata, kw, headers = headersText(used), baseUrl = origin,
                pageSize = paged.size, subjectUrlTemplate = subjectTpl,
            )
            val rep = JsonApi.test(rule, kw, sdata)
            return FlowResult(rep.ok, "json-api", true, rule, rep, kw, notes)
        }
        used += drec
        val subjectRequest = JsonApi.Request(
            subId(drec.url, itemId, "{id}"), drec.method, body = subId(drec.body, itemId, "{id}"),
        )
        val epSelId = dsel.episodes["selectId"] as String
        val eps = JsonApi.select(ddata, dsel.episodes["selectEpisodes"] as String)
        val epId: String? = if (eps.isNotEmpty() && epSelId.isNotEmpty()) {
            JsonApi.select(eps.first(), epSelId).firstOrNull()?.jsonPrimitive?.content
        } else null

        // 播放：点第一集
        mark = b.records().size
        if (b.call("clickEpisode") == "none") notes += "详情页上找不到集数入口"
        b.waitIdle()
        var playPage = b.currentUrl()
        fun findPlay(): Pair<CaptureRecord, JsonElement>? {
            for ((rec, data) in apiRecords(b.records().drop(mark))) {
                if (epId != null && epId !in rec.url + rec.body) continue
                try { JsonApi.inferPlay(data) } catch (_: IllegalArgumentException) { continue }
                return rec to data
            }
            return null
        }
        var play = findPlay()
        if (play == null && b.call("clickPlay") == "ok") {
            b.waitIdle()
            playPage = b.currentUrl()
            play = findPlay()
        }

        var episodeTpl = ""
        if (itemId in playPage) {
            episodeTpl = subId(playPage, itemId, "{subjectId}")
            if (epId != null) episodeTpl = subId(episodeTpl, epId, "{id}")
        }
        var playRequest: JsonApi.Request? = null
        if (play != null) {
            val (prec, _) = play
            playRequest = JsonApi.Request(
                if (epId != null) subId(prec.url, epId, "{id}") else prec.url, prec.method,
                body = if (epId != null) subId(prec.body, epId, "{id}") else prec.body,
            )
            used += prec
        } else notes += "没有抓到播放接口，规则里不含播放阶段"

        val rule = JsonApi.generate(
            name, searchRequest, sdata, kw, subjectRequest, ddata, playRequest, play?.second,
            headers = headersText(used), baseUrl = origin, pageSize = paged.size,
            subjectUrlTemplate = subjectTpl, episodeUrlTemplate = episodeTpl,
        )
        val rep = JsonApi.test(rule, kw, sdata, ddata, play?.second)
        return FlowResult(rep.ok, "json-api", play == null, rule, rep, kw, notes)
    }
}
