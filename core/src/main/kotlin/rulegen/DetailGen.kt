package rulegen

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.IdentityHashMap

/**
 * 从详情页推导 web-selector 的"步骤 2：搜索剧集"（线路 + 剧集）。
 * 完全基于页面结构：找"文本像集数的链接"成片的列表，再找数量与列表一致的线路标签。不针对任何站点。
 */
object DetailGen {
    private val DEFAULT_CHANNEL_REGEX = "^(?<ch>.+?)(\\d+)?$"

    // 集数文本的识别（要求较严，避免把"第1季""2024"之类当成集数）
    private val EP_TEXTS = listOf(
        Regex("^第\\s*\\d+(?:\\.\\d+)?\\s*[话話集期回].{0,14}$"),
        Regex("^(?:EP|Ep|ep|E)\\s*\\.?\\s*\\d+.{0,14}$"),
        Regex("^0*\\d{1,4}(?:\\.\\d+)?$"),
        Regex("^\\d{1,4}\\s*[话話集]$"),
    )
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")

    // 候选的集数提取正则（Animeko 约定：命名组 ep）。按顺序取第一个对全部集数文本都成立的
    private val EP_REGEX_CANDIDATES = listOf(
        "第\\s*(?<ep>.+)\\s*[话集]",
        "第\\s*(?<ep>\\d+(?:\\.\\d+)?)\\s*[话話集期回]",
        "^(?<ep>\\d+(?:\\.\\d+)?)\$",
        "^(?:EP|Ep|ep|E)\\s*\\.?\\s*(?<ep>\\d+(?:\\.\\d+)?)",
        "(?<ep>\\d+(?:\\.\\d+)?)",
    )

    data class Result(val config: Map<String, JsonElement>, val note: String? = null)

    private fun isEpisodeText(t: String) = t.length <= 24 && EP_TEXTS.any { it.matches(t) }

    private fun anchorText(a: Element) = a.text().trim()

    /** 剧集链接所在的"列表元素"：先穿过只含它一个孩子的包装（如 li > a），再取父节点。 */
    private fun listElementOf(anchor: Element): Element? {
        var unit: Element = anchor
        while (unit.parent() != null && unit.parent()!!.children().size == 1 && !RuleGen.isRoot(unit.parent())) unit = unit.parent()!!
        return unit.parent()?.takeIf { !RuleGen.isRoot(it) } ?: unit.parent()
    }

    private fun number(t: String): Double? = NUMBER.find(t)?.value?.toDoubleOrNull()

    fun generate(html: String): Result? {
        val doc = Jsoup.parse(html)
        val order = IdentityHashMap<Element, Int>().also { m -> doc.allElements.forEachIndexed { i, e -> m[e] = i } }

        // 1. 所有"像集数"的链接，按列表元素分组
        val byList = LinkedHashMap<Element, MutableList<Element>>()
        for (a in doc.select("a[href]")) {
            if (RuleGen.inSkipped(a)) continue
            val href = a.attr("href").trim()
            if (href.isEmpty() || href == "#" || href.startsWith("javascript:", ignoreCase = true)) continue
            if (!isEpisodeText(anchorText(a))) continue
            val list = listElementOf(a) ?: continue
            byList.getOrPut(list) { ArrayList() } += a
        }
        if (byList.isEmpty()) return null

        // 2. 按列表的结构签名分组，选"最像剧集列表"的一组
        data class Group(val sig: String, val lists: List<Element>, val anchors: List<Element>, val score: Double)
        val groups = byList.entries.groupBy { RuleGen.chainOf(it.key).joinToString(" > ") }.map { (sig, es) ->
            val anchors = es.flatMap { it.value }
            val nums = anchors.mapNotNull { number(anchorText(it)) }.toSet()
            // 有第 1 集且序号连续的列表几乎一定是剧集；其次看链接总数
            val looksLikeEpisodes = nums.isNotEmpty() && nums.contains(1.0) && (nums.max() - nums.min()) <= nums.size * 2
            // 文本里带"第N集/EP N"这类明确标记，也算剧集（如从第 13 集开始的续作）
            val explicit = anchors.any { t -> anchorText(t).let { EP_TEXTS[0].matches(it) || EP_TEXTS[1].matches(it) || EP_TEXTS[3].matches(it) } }
            Group(sig, es.map { it.key }, anchors, if (looksLikeEpisodes || explicit) nums.size + (if (looksLikeEpisodes) 1000.0 else 0.0) + anchors.size / 1000.0 else -1.0)
        }
        // 只有"2024""1 2 3 页码"这类纯数字、又不像从第 1 集开始的列表，不当作剧集
        val best = groups.filter { it.score >= 0 }.maxByOrNull { it.score } ?: return null
        val minAnchors = if (best.anchors.size >= 2) 2 else 1
        val lists = best.lists.filter { l -> byList[l]!!.size >= minAnchors }.ifEmpty { best.lists }
        val anchors = lists.flatMap { byList[it]!! }

        val regex = EP_REGEX_CANDIDATES.firstOrNull { r ->
            val re = Regex(r)
            anchors.all { re.find(anchorText(it))?.groups?.get("ep") != null }
        } ?: return null

        val listSel = RuleGen.minimalSelector(doc, lists)
        val inList = relativeSelector(lists, anchors)

        // 3. 线路名称：数量与列表一致、位于列表之前、结构相同的一组短文本元素
        if (lists.size >= 2) {
            val names = findChannelNames(doc, order, lists)
            if (names != null) {
                return Result(mapOf(
                    "channelFormatId" to JsonPrimitive("index-grouped"),
                    "selectorChannelFormatFlattened" to obj(
                        "selectChannelNames" to RuleGen.minimalSelector(doc, names),
                        "matchChannelName" to DEFAULT_CHANNEL_REGEX,
                        "selectEpisodeLists" to listSel,
                        "selectEpisodesFromList" to inList,
                        "selectEpisodeLinksFromList" to "",
                        "matchEpisodeSortFromName" to regex,
                    ),
                ))
            }
            // 找不到线路名：退回"无线路"，把所有列表的剧集放在一起
            val sel = RuleGen.minimalSelector(doc, anchors)
            return Result(noChannel(sel, regex), "页面上有 ${lists.size} 个剧集列表，但没找到对应数量的线路名称，已按无线路处理（集数可能重复）")
        }
        return Result(noChannel(RuleGen.minimalSelector(doc, anchors), regex))
    }

    private fun noChannel(selectEpisodes: String, regex: String): Map<String, JsonElement> = mapOf(
        "channelFormatId" to JsonPrimitive("no-channel"),
        "selectorChannelFormatNoChannel" to obj(
            "selectEpisodes" to selectEpisodes, "selectEpisodeLinks" to "", "matchEpisodeSortFromName" to regex,
        ),
    )

    private fun obj(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })

    /** 相对列表元素的剧集选择器：优先最短的 "a"，必须恰好选中那批剧集链接。 */
    private fun relativeSelector(lists: List<Element>, anchors: List<Element>): String {
        val want = anchors.toSet()
        fun ok(sel: String) = lists.flatMap { l -> try { l.select(sel) } catch (_: Exception) { emptyList() } }.toSet() == want
        if (ok("a")) return "a"
        val chain = ArrayList<String>()
        var cur: Element? = anchors.first()
        while (cur != null && cur !in lists) {
            chain += cur.tagName() + cur.classNames().filter { it.isNotEmpty() && !it.any(Char::isDigit) }.joinToString("") { ".$it" }
            cur = cur.parent()
        }
        chain.reverse()
        for (d in 1..chain.size) {
            val sel = chain.takeLast(d).joinToString(" > ")
            if (ok(sel)) return sel
        }
        return "a"
    }

    private fun findChannelNames(doc: Document, order: IdentityHashMap<Element, Int>, lists: List<Element>): List<Element>? {
        val firstListPos = lists.minOf { order[it] ?: Int.MAX_VALUE }
        val n = lists.size
        val candidates = doc.allElements.filter { e ->
            e.tagName() !in setOf("html", "body", "head", "script", "style", "ul", "ol") &&
                !RuleGen.inSkipped(e) && e.children().size <= 2 &&
                e.text().trim().length in 1..20 &&
                lists.none { l -> e === l || generateSequence(e) { it.parent() }.any { it === l } } &&
                lists.none { l -> generateSequence(l) { it.parent() }.any { it === e } }
        }
        data class G(val sig: String, val els: List<Element>)
        val groups = candidates.groupBy { RuleGen.chainOf(it).joinToString(" > ") }
            .map { G(it.key, it.value) }
            .filter { it.els.size == n && it.els.map { e -> e.text().trim() }.toSet().size == n }
        if (groups.isEmpty()) return null
        // 在列表之前、离列表最近的优先；同一位置里更深（更具体）的元素优先
        val best = groups.minWithOrNull(
            compareBy<G> { g -> val p = order[g.els.last()] ?: Int.MAX_VALUE; if (p < firstListPos) firstListPos - p else 100_000 + p }
                .thenByDescending { it.sig.count { c -> c == '>' } },
        )
        return best?.els
    }

    /** 用生成的配置在详情页上实际跑一遍。cfg 是 searchConfig 对象。 */
    fun test(cfg: JsonObject, html: String): TestReport {
        val doc = Jsoup.parse(html)
        fun s(o: JsonElement?, k: String) = (o as? JsonObject)?.get(k)?.jsonPrimitive?.content ?: ""
        val fid = s(cfg, "channelFormatId")
        try {
            when (fid) {
                "no-channel" -> {
                    val c = cfg["selectorChannelFormatNoChannel"]
                    val eps = doc.select(s(c, "selectEpisodes"))
                    if (eps.isEmpty()) return TestReport(false, "剧集：selectEpisodes 没有选中任何元素")
                    val re = Regex(s(c, "matchEpisodeSortFromName"))
                    val bad = eps.count { re.find(it.text().trim())?.groups?.get("ep") == null }
                    if (bad > 0) return TestReport(false, "剧集：有 $bad 个无法提取集数")
                    if (eps.any { it.attr("href").isEmpty() }) return TestReport(false, "剧集：存在空链接")
                    return TestReport(true, "剧集 ${eps.size} 集（无线路）")
                }
                "index-grouped" -> {
                    val c = cfg["selectorChannelFormatFlattened"]
                    val names = doc.select(s(c, "selectChannelNames")).map { it.text().trim() }
                    val lists = doc.select(s(c, "selectEpisodeLists"))
                    if (names.isEmpty() || lists.isEmpty()) return TestReport(false, "线路：没有选中线路名称或剧集列表")
                    if (names.size != lists.size) return TestReport(false, "线路：名称 ${names.size} 个与列表 ${lists.size} 个数量不一致")
                    val chRe = Regex(s(c, "matchChannelName"))
                    if (names.any { chRe.find(it)?.groups?.get("ch") == null }) return TestReport(false, "线路：名称无法按正则提取")
                    val epRe = Regex(s(c, "matchEpisodeSortFromName"))
                    var total = 0
                    for (l in lists) {
                        val eps = l.select(s(c, "selectEpisodesFromList"))
                        if (eps.isEmpty()) return TestReport(false, "线路：有列表选不到剧集")
                        if (eps.any { epRe.find(it.text().trim())?.groups?.get("ep") == null }) return TestReport(false, "剧集：有无法提取集数的项")
                        total += eps.size
                    }
                    return TestReport(true, "线路 ${names.size} 条，剧集共 $total 个")
                }
                else -> return TestReport(false, "未生成线路/剧集配置")
            }
        } catch (e: Exception) {
            return TestReport(false, "选择器无效: ${e.message}")
        }
    }
}
