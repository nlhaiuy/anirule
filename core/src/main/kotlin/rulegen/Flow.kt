package rulegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
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

    /** 切换桌面版/手机版页面（User-Agent 与视口）。默认什么也不做。 */
    fun setDesktopMode(desktop: Boolean) {}

    /**
     * 用页面自己的 fetch 取原始 HTML（不执行页面 JS），也就是 Animeko 的 HTML 数据源实际会拿到的内容。
     * 失败返回 null。
     */
    fun fetchRaw(url: String): String? {
        val mark = records().size
        call("rawFetch", url)
        repeat(50) {
            records().drop(mark).firstOrNull {
                it.type == "fetch" && it.status != null && it.url.trimEnd('/') == url.trimEnd('/')
            }?.let { r -> return if (r.status in 200..299) r.response else null }
            Thread.sleep(200)
        }
        return null
    }
}

data class FlowResult(
    val ok: Boolean,
    val mode: String = "",       // "json-api" | "html" | ""
    val partial: Boolean = false, // 没拿到完整的三个阶段（json-api）或没拿到剧集（html）
    val rule: JsonObject? = null,
    val report: TestReport? = null,
    val keyword: String = "",
    val notes: List<String> = emptyList(),
)

/** 全自动流程：自动搜索 → 点进详情 → 点第一集 → 从抓到的接口/页面生成并测试规则。对站点不做任何特殊处理。 */
object Flow {
    val KEYWORDS = listOf("葬送的芙莉莲", "孤独摇滚", "药屋少女的呢喃", "咒术回战", "鬼灭之刃")
    private val SKIP_HEADERS = setOf(
        "accept", "accept-language", "accept-encoding", "content-length", "user-agent", "referer", "cookie", "host", "connection",
    )
    private const val PAGE_KEYS = "page|page_number|pageNum|pageNo|pn|current"
    private const val SIZE_KEYS = "pageSize|page_size|items_per_page|limit|per_page|perPage|size"
    private val RATE_LIMIT = Regex("频繁|太快|过于快|稍后再|请.{0,6}(勿|不要).{0,6}(搜索|操作)|搜索.{0,8}间隔|\\d+\\s*秒后")

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
        listOf(kw, enc(kw, false), enc(kw, true)).distinct().fold(text) { acc, f -> acc.replace(f, "{keyword}", ignoreCase = true) }

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

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim().replace(" ", "%20")).toString()
    } catch (_: Exception) { ref }

    // ---------- 规则 JSON 的小改动 ----------

    private fun JsonObject.editArgs(edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
        val args = this["arguments"]!!.jsonObject.toMutableMap()
        edit(args)
        return JsonObject(toMutableMap().also { it["arguments"] = JsonObject(args) })
    }

    private fun JsonObject.editConfig(edit: (MutableMap<String, JsonElement>) -> Unit): JsonObject = editArgs { args ->
        val cfg = args["searchConfig"]!!.jsonObject.toMutableMap()
        edit(cfg)
        args["searchConfig"] = JsonObject(cfg)
    }

    private fun JsonObject.withMeta(name: String, icon: String): JsonObject = editArgs {
        it["name"] = JsonPrimitive(name)
        it["iconUrl"] = JsonPrimitive(icon)
    }

    private fun FlowResult.withMeta(name: String, icon: String) = copy(rule = rule?.withMeta(name, icon))

    // ---------- 入口 ----------

    /**
     * 先用桌面版页面（搜索框、链接结构通常更完整），失败再换手机版。
     * name 为 null 时从首页自动取站点名，图标同理。
     * pauseMs：两次搜索之间的间隔，避免触发站点的搜索频率限制。
     */
    fun run(
        url: String, browser: Browser, keywords: List<String> = KEYWORDS, name: String? = null, pauseMs: Long = 4000,
    ): FlowResult {
        val all = ArrayList<String>()
        for (desktop in listOf(true, false)) {
            browser.setDesktopMode(desktop)
            val label = if (desktop) "[桌面版] " else "[手机版] "
            val r = runOnce(url, browser, if (desktop) keywords else keywords.take(2), name, pauseMs)
            if (r.ok) return r.copy(notes = all + r.notes.map { label + it })
            all += r.notes.map { label + it }
        }
        return FlowResult(false, notes = all + "桌面版和手机版都没有生成可用规则")
    }

    private fun sleep(ms: Long) { if (ms > 0) Thread.sleep(ms) }

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

    private fun runOnce(url: String, browser: Browser, keywords: List<String>, nameArg: String?, pauseMs: Long): FlowResult {
        val notes = ArrayList<String>()
        browser.load(url)
        browser.waitIdle()
        val homeDom = browser.call("dom")
        val homeUrl = browser.currentUrl().ifEmpty { url }
        val meta = if (homeDom.isNotBlank()) SiteMeta.extract(homeDom, homeUrl)
        else SiteMeta.Meta(SiteMeta.extractName("", "", "", url), "")
        val name = nameArg ?: meta.name
        val icon = meta.iconUrl

        var noBox = 0
        kwLoop@ for (kw in keywords) {
            var retried = false
            while (true) {
                val mark = browser.records().size
                if (!doSearch(browser, kw, notes)) {
                    // 搜索框不可用（找不到、被验证码遮挡等）：绕开它，直接加载候选搜索链接
                    directSearch(browser, url, homeDom, name, kw, notes)?.let { return it.withMeta(name, icon) }
                    noBox++
                    break
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
                    if (res.ok) return res.withMeta(name, icon)
                    notes += "关键词「$kw」的 json-api 流程未通过: ${res.report?.message ?: ""}"
                    break
                }
                val h = htmlFlow(browser, name, kw, notes)
                if (h != null && h.ok) return h.withMeta(name, icon)
                if (h == null && !retried && RATE_LIMIT.containsMatchIn(Jsoup.parse(browser.call("dom")).text())) {
                    retried = true
                    notes += "站点限制了搜索频率，等待后重试「$kw」"
                    sleep(pauseMs * 2)
                    browser.load(url); browser.waitIdle()
                    continue
                }
                if (h == null) {
                    val snip = try { browser.call("snippet") } catch (_: Exception) { "" }
                    notes += "搜索后停在 ${browser.currentUrl()}，页面：${snip.take(100)}"
                }
                break
            }
            if (noBox >= 2) { notes += "页面没有可用的搜索框，换关键词也没有意义，已停止"; break@kwLoop }
            browser.load(url); browser.waitIdle()
            sleep(pauseMs)
        }
        notes += "所有关键词都没有生成可用规则"
        return FlowResult(false, notes = notes)
    }

    private const val MAX_DIRECT = 8

    /** 不经过搜索框：依次加载首页表单推导出的、以及常见的搜索链接，第一个能识别出结果的就用。 */
    private fun directSearch(
        b: Browser, homeUrl: String, homeDom: String, name: String, kw: String, notes: MutableList<String>,
    ): FlowResult? {
        val tpls = Discover.candidates(homeUrl, homeDom, ArrayList()).take(MAX_DIRECT)
        for (tpl in tpls) {
            b.load(tpl.replace("{keyword}", enc(kw, false)))
            b.waitIdle()
            val own = ArrayList<String>()
            val r = htmlFlow(b, name, kw, own) ?: continue
            if (!r.ok) continue
            notes += "搜索框方式没成功，改用直接搜索链接：$tpl"
            return r.copy(notes = notes + r.notes)
        }
        return null
    }

    // ---------- HTML 站点 ----------

    private fun htmlFlow(b: Browser, name: String, kw: String, notes: MutableList<String>): FlowResult? {
        val searchPageUrl = b.currentUrl()
        val searchUrl = subKeyword(searchPageUrl, kw)
        if ("{keyword}" !in searchUrl) {
            notes += "搜索后网址里没有关键词（站点可能用 POST 搜索；Animeko 的 HTML 数据源只支持带关键词的 GET 链接）"
            return null
        }
        val rendered = b.call("dom")
        val raw = b.fetchRaw(searchPageUrl)

        fun tryGen(html: String): Pair<JsonObject, TestReport>? {
            val rule = try { RuleGen.generateRule(html, kw, searchUrl, name) } catch (_: GenerateException) { return null }
            return rule to RuleGen.testRule(rule, html, kw)
        }

        // 以 Animeko 实际会拿到的原始 HTML 为准；原始 HTML 里没有结果、只有渲染后才有，说明依赖 JS
        val base: String
        if (raw != null) {
            val r = tryGen(raw)
            if (r == null || !r.second.ok) {
                val renderedOk = tryGen(rendered)?.second?.ok == true
                notes += if (renderedOk) "搜索结果是由 JS 渲染出来的，Animeko 的 HTML 数据源读不到原始页面里的结果"
                else "搜索结果页里没有识别出结果列表: ${r?.second?.message ?: "找不到包含关键词的元素"}"
                return null
            }
            base = raw
        } else {
            notes += "无法获取原始 HTML，改用渲染后的页面生成规则（可能与 Animeko 实际抓到的不同）"
            base = rendered
        }
        val (rule0, searchRep) = tryGen(base) ?: run { notes += "HTML 解析失败"; return null }
        if (!searchRep.ok) { notes += "搜索规则测试未通过: ${searchRep.message}"; return null }

        var rule = rule0.editConfig { it["requestInterval"] = JsonPrimitive(3000) }

        // ---- 步骤 2：详情页的线路与剧集 ----
        val idx = searchRep.names.indexOfFirst { RuleGen.norm(kw) in RuleGen.norm(it) }.coerceAtLeast(0)
        val detailUrl = resolve(searchPageUrl, searchRep.links[idx])
        b.load(detailUrl)
        b.waitIdle()
        val detailRendered = b.call("dom")
        val detailRaw = b.fetchRaw(detailUrl)
        val detailBase = detailRaw ?: detailRendered
        val gen = DetailGen.generate(detailBase)
        if (gen == null) {
            notes += if (detailRaw != null && DetailGen.generate(detailRendered) != null)
                "剧集列表是由 JS 渲染出来的，Animeko 读不到原始页面里的剧集"
            else "详情页上没找到剧集列表（可能要点进播放页，或页面需要验证）"
            return FlowResult(true, "html", true, rule, searchRep, kw, notes)
        }
        gen.note?.let { notes += it }
        rule = rule.editConfig { cfg -> gen.config.forEach { (k, v) -> cfg[k] = v } }
        val cfg = rule["arguments"]!!.jsonObject["searchConfig"]!!.jsonObject
        val detailRep = DetailGen.test(cfg, detailBase)
        val report = TestReport(searchRep.ok && detailRep.ok, "${searchRep.message}；${detailRep.message}", searchRep.names, searchRep.links)
        if (!detailRep.ok) notes += "剧集规则测试未通过: ${detailRep.message}"
        return FlowResult(true, "html", !detailRep.ok, rule, report, kw, notes)
    }

    // ---------- JSON 接口站点 ----------

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
