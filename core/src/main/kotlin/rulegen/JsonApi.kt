package rulegen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Animeko json-api 数据源规则生成。只用到 JSONPath 子集：$  .field  [*]  ['field'] */
object JsonApi {
    private val NAME_KEYS = listOf("title", "name", "vod_name", "title_cn", "anime_name", "subject_name")
    private val ALIAS_KEYS = listOf(
        "aliases", "alias", "other_names", "other_name", "vod_sub", "original_title",
        "title_original", "title_en", "title_jp", "name_en",
    )
    private val SORT_KEYS = listOf("episode_number", "episode", "sort", "ep", "number", "index", "order", "num")
    private val EP_NAME_KEYS = listOf("title", "name", "episode_title")
    private val TYPE_KEYS = listOf("kind", "type")
    private val CHANNEL_KEYS = listOf("source_name", "line_name", "channel", "source", "line", "from", "name")
    private val URL_KEYS = listOf("url", "play_url", "video_url", "m3u8", "src", "link")
    private const val DEFAULT_TYPE_MAPPING = "main=MainStory\nsp=SP\nova=OVA\noad=OAD\nmovie=MainStory\nex=SP"

    private val TOKEN = Regex("""\.([A-Za-z0-9_\-]+)|\[\*]|\['([^']+)']""")
    private val PLAIN_KEY = Regex("[A-Za-z0-9_\\-]+")

    // ---------- 迷你 JSONPath ----------

    fun select(doc: JsonElement, path: String): List<JsonElement> {
        if (!path.startsWith("$")) return emptyList()
        var cur = listOf(doc)
        var pos = 1
        while (pos < path.length) {
            val m = TOKEN.matchAt(path, pos) ?: return emptyList()
            cur = if (m.value == "[*]") {
                cur.flatMap { (it as? JsonArray)?.toList() ?: emptyList() }
            } else {
                val key = m.groupValues[1].ifEmpty { m.groupValues[2] }
                cur.mapNotNull { (it as? JsonObject)?.get(key) }
            }
            pos = m.range.last + 1
        }
        return cur
    }

    private fun isScalar(v: JsonElement?) = v is JsonPrimitive && v !is JsonNull
    private fun str(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun isString(v: JsonElement?) = v is JsonPrimitive && v.isString
    private fun isHttp(v: JsonElement?) = isString(v) && str(v)!!.startsWith("http")

    /** 所有"元素均为对象"的数组：(路径如 $.data.list[*], 元素)，浅层在前。 */
    fun findArrays(doc: JsonElement, path: String = "$"): List<Pair<String, List<JsonObject>>> {
        val out = ArrayList<Pair<String, List<JsonObject>>>()
        when (doc) {
            is JsonArray -> if (doc.isNotEmpty() && doc.all { it is JsonObject }) {
                out += "$path[*]" to doc.map { it as JsonObject }
            }
            is JsonObject -> for ((k, v) in doc) {
                if (v is JsonArray || v is JsonObject) {
                    out += findArrays(v, if (PLAIN_KEY.matches(k)) "$path.$k" else "$path['$k']")
                }
            }
            else -> {}
        }
        return out.sortedBy { it.first.count { c -> c == '.' } }
    }

    private fun firstKey(items: List<JsonObject>, keys: List<String>, pred: (JsonElement?) -> Boolean = ::isScalar): String? =
        keys.firstOrNull { k -> items.count { pred(it[k]) } >= maxOf(1, items.size / 2) }

    private fun idKey(items: List<JsonObject>, exclude: Set<String> = emptySet()): String? {
        val cands = items.first().filter { it.key !in exclude && isScalar(it.value) }.keys.toList()
        val pri = cands.filter { it == "id" } + cands.filter { it.endsWith("_id") || it.endsWith("Id") } +
            cands.filter { it in setOf("slug", "uid", "vod_id") }
        return (pri + cands).firstOrNull { k ->
            val vals = items.map { it[k] }
            vals.all { isScalar(it) && str(it) != "" } && vals.map { str(it) }.toSet().size == vals.size
        }
    }

    // ---------- 三个阶段的推导 ----------

    fun inferSearch(resp: JsonElement, keyword: String): Map<String, Any?> {
        val key = norm(keyword)
        var best: Triple<Int, Pair<String, List<JsonObject>>, String>? = null
        for ((path, arr) in findArrays(resp)) {
            for (nk in NAME_KEYS + arr.first().keys.filter { it !in NAME_KEYS }) {
                val hits = arr.count { isString(it[nk]) && key in norm(str(it[nk])) }
                if (hits > 0 && (best == null || hits > best.first)) best = Triple(hits, path to arr, nk)
            }
            if (best != null) break // 浅层优先
        }
        val (_, pa, nk) = best ?: throw IllegalArgumentException("响应里没有找到包含关键词的条目：检查关键词、请求，或这个接口不是搜索接口")
        val (path, arr) = pa
        val ik = idKey(arr, setOf(nk)) ?: throw IllegalArgumentException("条目里找不到可用作 ID 的字段（需要每项唯一且非空）")
        val ak = ALIAS_KEYS.firstOrNull { it in arr.first() && it != nk }
        return mapOf(
            "selectItems" to path, "selectName" to "$.$nk", "selectId" to "$.$ik",
            "selectAliases" to (ak?.let { "$.$it" } ?: ""),
        )
    }

    data class SubjectInference(
        val episodes: Map<String, Any?>, val channelFormatId: String, val grouped: Map<String, Any?>?,
    )

    fun inferSubject(resp: JsonElement): SubjectInference {
        val arrays = findArrays(resp)
        val ep = arrays.firstNotNullOfOrNull { (p, a) -> firstKey(a, SORT_KEYS)?.let { Triple(p, a, it) } }
            ?: throw IllegalArgumentException("详情响应里没有找到剧集数组（需要带集数字段，如 episode_number）")
        val (path, arr, sk) = ep
        val nk = firstKey(arr, EP_NAME_KEYS) ?: sk
        val ik = idKey(arr, setOf(nk, sk))
        val tk = firstKey(arr, TYPE_KEYS)
        val uk = firstKey(arr, URL_KEYS, ::isHttp)
        val episodes = mapOf(
            "selectEpisodes" to path, "selectName" to "$.$nk", "selectId" to (ik?.let { "$.$it" } ?: ""),
            "selectSort" to "$.$sk", "selectType" to (tk?.let { "$.$it" } ?: ""),
            "selectVideoUrl" to (uk?.let { "$.$it" } ?: ""),
        )
        // 与剧集同级的"线路"数组
        val chan = arrays.firstNotNullOfOrNull { (p, a) ->
            if (p == path || SORT_KEYS.any { it in a.first() }) null
            else firstKey(a, listOf("name", "title", "label"))?.let { p to it }
        }
        return if (chan != null) {
            SubjectInference(episodes, "grouped", mapOf("selectChannels" to chan.first, "selectChannelName" to "$.${chan.second}"))
        } else SubjectInference(episodes, "no-channel", null)
    }

    fun inferPlay(resp: JsonElement): Map<String, Any?> {
        for ((path, arr) in findArrays(resp)) {
            val uk = firstKey(arr, URL_KEYS, ::isHttp) ?: continue
            val ck = firstKey(arr, CHANNEL_KEYS.filter { it != uk })
            return mapOf(
                "selectCandidates" to path, "selectCandidateChannel" to (ck?.let { "$.$it" } ?: ""),
                "selectVideoUrl" to "$.$uk", "fallbackToFirstCandidate" to (ck == null),
            )
        }
        if (resp is JsonObject) {
            URL_KEYS.firstOrNull { isHttp(resp[it]) }?.let {
                return mapOf(
                    "selectCandidates" to "", "selectCandidateChannel" to "",
                    "selectVideoUrl" to "$.$it", "fallbackToFirstCandidate" to true,
                )
            }
        }
        throw IllegalArgumentException("播放响应里没有找到视频地址（需要以 http 开头的字符串）")
    }

    // ---------- 默认配置（与 Animeko 编辑器导出的空白模板同构） ----------

    private fun req(url: String = "", method: String = "GET", headers: String = "", body: String = "") =
        linkedMapOf<String, Any?>("url" to url, "method" to method, "headers" to headers, "body" to body)

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(key: String) = this[key] as MutableMap<String, Any?>

    fun defaultArgs(name: String = "JSON API"): MutableMap<String, Any?> = linkedMapOf(
        "name" to name, "description" to "", "iconUrl" to "",
        "searchConfig" to linkedMapOf<String, Any?>(
            "baseUrl" to "", "headers" to "", "userAgent" to "",
            "requestInterval" to 1000, "searchCacheTtl" to 7200000,
            "search" to linkedMapOf<String, Any?>(
                "request" to req(), "pageSize" to 20,
                "selectItems" to "$[*]", "selectName" to "$.title", "selectId" to "$.id",
                "selectAliases" to "", "subjectUrlTemplate" to "",
            ),
            "subject" to linkedMapOf<String, Any?>(
                "request" to req(),
                "episodes" to linkedMapOf<String, Any?>(
                    "selectEpisodes" to "$[*]", "selectName" to "$.title", "selectId" to "$.id",
                    "selectSort" to "$.episode_number", "selectType" to "$.kind",
                    "typeMapping" to DEFAULT_TYPE_MAPPING,
                    "matchEpisodeSortFromName" to "第\\s*(?<ep>.+)\\s*[话集]",
                    "nameTemplate" to "{sort} {name}", "episodeUrlTemplate" to "", "selectVideoUrl" to "",
                ),
                "channelFormatId" to "no-channel",
                "channelFormatGrouped" to linkedMapOf<String, Any?>("selectChannels" to "$.lines[*]", "selectChannelName" to "$.name"),
                "channelFormatDeclared" to linkedMapOf<String, Any?>("channels" to emptyList<Any?>()),
            ),
            "play" to linkedMapOf<String, Any?>(
                "request" to req(), "selectCandidates" to "", "selectCandidateChannel" to "",
                "selectVideoUrl" to "$.url", "fallbackToFirstCandidate" to true,
                "referer" to "", "videoHeaders" to "",
            ),
            "defaultResolution" to "1080P", "defaultSubtitleLanguage" to "CHS",
            "onlySupportsPlayers" to emptyList<Any?>(),
            "autoMatch" to linkedMapOf<String, Any?>(
                "enabled" to true, "searchUseOnlyFirstWord" to true, "searchRemoveSpecial" to true,
                "searchUseSubjectNamesCount" to 1, "preferShorterName" to true, "filterByEpisodeSort" to true,
            ),
        ),
        "tier" to 2, "channelTiers" to linkedMapOf<String, Any?>(),
    )

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k as String to toJson(x) })
        is List<*> -> JsonArray(v.map(::toJson))
        else -> throw IllegalArgumentException("不支持的类型: ${v::class}")
    }

    // ---------- 生成与测试 ----------

    data class Request(val url: String = "", val method: String = "GET", val headers: String = "", val body: String = "")

    private fun Request.toMap(keywordLiteral: String? = null): MutableMap<String, Any?> {
        fun fix(s: String) = if (keywordLiteral.isNullOrEmpty()) s else s.replace(keywordLiteral, "{keyword}")
        return req(fix(url), method, headers, fix(body))
    }

    /** request 中的关键词字面量会被换成 {keyword}。 */
    fun generate(
        name: String, searchRequest: Request, searchResponse: JsonElement, keyword: String,
        subjectRequest: Request? = null, subjectResponse: JsonElement? = null,
        playRequest: Request? = null, playResponse: JsonElement? = null,
        headers: String = "", baseUrl: String = "",
        pageSize: Int? = null, subjectUrlTemplate: String = "", episodeUrlTemplate: String = "",
    ): JsonObject {
        val args = defaultArgs(name)
        val cfg = args.m("searchConfig")
        cfg["baseUrl"] = baseUrl
        cfg["headers"] = headers
        val search = cfg.m("search")
        search["request"] = searchRequest.toMap(keyword)
        search.putAll(inferSearch(searchResponse, keyword))
        if (pageSize != null) search["pageSize"] = pageSize
        search["subjectUrlTemplate"] = subjectUrlTemplate
        if (episodeUrlTemplate.isNotEmpty()) cfg.m("subject").m("episodes")["episodeUrlTemplate"] = episodeUrlTemplate

        if (subjectRequest != null && subjectResponse != null) {
            val sub = cfg.m("subject")
            sub["request"] = subjectRequest.toMap()
            val inf = inferSubject(subjectResponse)
            sub.m("episodes").putAll(inf.episodes)
            sub["channelFormatId"] = inf.channelFormatId
            inf.grouped?.let { sub["channelFormatGrouped"] = it }
        }
        if (playRequest != null && playResponse != null) {
            val play = cfg.m("play")
            play["request"] = playRequest.toMap()
            play.putAll(inferPlay(playResponse))
        }
        return toJson(linkedMapOf("factoryId" to "json-api", "version" to 1, "arguments" to args)) as JsonObject
    }

    /** Animeko 编辑器导出/导入用的外层包装。 */
    fun wrapExport(rule: JsonObject): JsonObject =
        toJson(mapOf("schemaVersion" to 2, "mediaSources" to listOf(rule))) as JsonObject

    private fun sel(o: JsonElement?, k: String): String = str((o as? JsonObject)?.get(k)) ?: ""

    fun test(
        rule: JsonObject, keyword: String, searchResponse: JsonElement,
        subjectResponse: JsonElement? = null, playResponse: JsonElement? = null,
    ): TestReport {
        val cfg = ((rule["arguments"] as JsonObject)["searchConfig"]) as JsonObject
        val s = cfg["search"] as JsonObject
        val items = select(searchResponse, sel(s, "selectItems"))
        if (items.isEmpty()) return TestReport(false, "搜索：selectItems 没有选中任何条目")
        val names = items.flatMap { select(it, sel(s, "selectName")) }.map { str(it) ?: it.toString() }
        val ids = items.flatMap { select(it, sel(s, "selectId")) }.map { str(it) ?: it.toString() }
        if (names.size != items.size || ids.size != items.size) return TestReport(false, "搜索：名称或 ID 的数量与条目数不一致", names, ids)
        if (ids.toSet().size != ids.size) return TestReport(false, "搜索：ID 有重复", names, ids)
        if (names.none { norm(keyword) in norm(it) }) return TestReport(false, "搜索：没有标题包含关键词", names, ids)
        val msg = ArrayList<String>().apply { add("搜索 ${items.size} 项") }

        if (subjectResponse != null) {
            val sub = cfg["subject"] as JsonObject
            val e = sub["episodes"] as JsonObject
            val eps = select(subjectResponse, sel(e, "selectEpisodes"))
            if (eps.isEmpty()) return TestReport(false, "详情：selectEpisodes 没有选中任何剧集", names, ids)
            if (eps.flatMap { select(it, sel(e, "selectSort")) }.size != eps.size) return TestReport(false, "详情：集数字段缺失", names, ids)
            msg += "剧集 ${eps.size} 集"
            if (sel(sub, "channelFormatId") == "grouped") {
                val g = sub["channelFormatGrouped"] as JsonObject
                val ch = select(subjectResponse, sel(g, "selectChannels"))
                if (ch.isEmpty()) return TestReport(false, "详情：selectChannels 没有选中任何线路", names, ids)
                msg += "线路 ${ch.size} 条"
            }
        }
        if (playResponse != null) {
            val p = cfg["play"] as JsonObject
            val cands = if (sel(p, "selectCandidates").isEmpty()) listOf(playResponse) else select(playResponse, sel(p, "selectCandidates"))
            val urls = cands.flatMap { select(it, sel(p, "selectVideoUrl")) }
            if (urls.isEmpty() || !urls.all(::isHttp)) return TestReport(false, "播放：没有取到以 http 开头的视频地址", names, ids)
            msg += "视频地址 ${urls.size} 个"
        }
        return TestReport(true, "通过：" + msg.joinToString("，"), names, ids)
    }

    private fun norm(s: String?) = (s ?: "").replace(Regex("\\s+"), "").lowercase()
}
