package rulegen

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenerateException(message: String) : Exception(message)

data class TestReport(
    val ok: Boolean,
    val message: String,
    val names: List<String> = emptyList(),
    val links: List<String> = emptyList(),
)

/** Animeko web-selector 规则的自动生成与测试（只覆盖"搜索条目"这一步）。 */
object RuleGen {
    private val SKIP_TAGS = setOf(
        "script", "style", "head", "title", "meta", "link", "noscript",
        "input", "textarea", "option", "select", "button", "form",
    )
    private const val MAX_DEPTH = 10
    private val DIGIT = Regex("\\d")
    private val SPACES = Regex("\\s+")

    private fun norm(s: String?): String = (s ?: "").replace(SPACES, "").lowercase()

    private fun stableClasses(e: Element): List<String> =
        e.classNames().filter { it.isNotEmpty() && !DIGIT.containsMatchIn(it) }

    private fun isRoot(e: Element?): Boolean = e == null || e.tagName() in setOf("html", "body", "#root")

    /** 从 body 下到 e 的 "tag.class" 片段列表。 */
    private fun chainOf(e: Element): List<String> {
        val parts = ArrayList<String>()
        var cur: Element? = e
        while (cur != null && !isRoot(cur)) {
            parts += cur.tagName() + stableClasses(cur).joinToString("") { ".$it" }
            cur = cur.parent()
        }
        parts.reverse()
        return parts
    }

    private fun safeSelect(doc: Document, css: String): List<Element> =
        try { doc.select(css).toList() } catch (_: Exception) { emptyList() }

    /** 能精确选中 targets 的最短 CSS 选择器（只用 class 与 '>'，不用 nth）。 */
    private fun minimalSelector(doc: Document, targets: List<Element>): String {
        val chain = chainOf(targets.first())
        val full = chain.joinToString(" > ")
        val fullSet = safeSelect(doc, full).toSet()
        for (d in 1..minOf(chain.size, MAX_DEPTH)) {
            val sel = chain.takeLast(d).joinToString(" > ")
            if (safeSelect(doc, sel).toSet() == fullSet) return sel
        }
        return full
    }

    private fun inSkipped(e: Element): Boolean =
        e.tagName() in SKIP_TAGS || e.parents().any { it.tagName() in SKIP_TAGS }

    private fun findSeeds(doc: Document, keyword: String): List<Element> {
        val key = norm(keyword)
        if (key.isEmpty()) throw GenerateException("关键词为空")
        return doc.body().getAllElements().filter { el ->
            !inSkipped(el) &&
                key in norm(el.text()) &&
                el.children().none { c -> c.tagName() !in SKIP_TAGS && key in norm(c.text()) }
        }
    }

    /** name 元素本身是 a，或位于某个 a[href] 之内。 */
    private fun linkAnchorOf(name: Element): Element? {
        var cur: Element? = name
        while (cur != null && cur.tagName() != "body") {
            if (cur.tagName() == "a" && cur.hasAttr("href")) return cur
            cur = cur.parent()
        }
        return null
    }

    /** 向上找最近一个包含 a[href] 的祖先，取其第一个 a[href]。 */
    private fun siblingLinkOf(name: Element): Element? {
        var cur: Element? = name.parent()
        while (cur != null && !isRoot(cur)) {
            cur.selectFirst("a[href]")?.let { return it }
            cur = cur.parent()
        }
        return null
    }

    fun generateRule(html: String, keyword: String, searchUrl: String, name: String = "示例动漫"): JsonObject {
        val doc = Jsoup.parse(html)
        val seeds = findSeeds(doc, keyword)
        if (seeds.isEmpty()) {
            throw GenerateException("页面里没有找到包含关键词的元素：关键词没搜到结果，或结果由 JS 动态加载")
        }

        // 选出最像"结果列表"的一组：可解析的不同链接数多者优先
        data class Candidate(val score: Pair<Int, Int>, val linked: List<Pair<Element, Element>>)
        var best: Candidate? = null
        for ((_, els) in seeds.groupBy { chainOf(it).joinToString(" > ") }) {
            val linked = els.mapNotNull { e ->
                val a = linkAnchorOf(e) ?: siblingLinkOf(e)
                if (a != null && a.hasAttr("href")) e to a else null
            }
            if (linked.isEmpty()) continue
            val score = linked.map { it.second.attr("href") }.toSet().size to linked.size
            val b = best
            if (b == null || score.first > b.score.first ||
                (score.first == b.score.first && score.second > b.score.second)
            ) best = Candidate(score, linked)
        }
        val chosen = best ?: throw GenerateException("找到了关键词，但附近没有可用的链接 (a[href])")
        val linked = chosen.linked
        val inAnchor = linked.all { linkAnchorOf(it.first) != null }

        val args: Map<String, JsonElement> = if (inAnchor) {
            val sel = minimalSelector(doc, linked.map { linkAnchorOf(it.first)!! })
            mapOf(
                "subjectFormatId" to JsonPrimitive("a"),
                "selectorSubjectFormatA" to buildJsonObject { put("selectLists", sel) },
            )
        } else {
            val nameSel = minimalSelector(doc, linked.map { it.first })
            val linkSel = minimalSelector(doc, linked.map { it.second })
            mapOf(
                "subjectFormatId" to JsonPrimitive("indexed"),
                "selectorSubjectFormatIndexed" to buildJsonObject {
                    put("selectNames", nameSel)
                    put("selectLinks", linkSel)
                },
            )
        }

        return buildJsonObject {
            put("factoryId", "web-selector")
            put("version", 2)
            put("arguments", buildJsonObject {
                put("name", name)
                put("description", "")
                put("iconUrl", "")
                put("searchConfig", buildJsonObject {
                    put("searchUrl", searchUrl)
                    args.forEach { (k, v) -> put(k, v) }
                })
            })
        }
    }

    /** 用规则在页面上实际跑一遍，判断是否可用。 */
    fun testRule(rule: JsonObject, html: String, keyword: String): TestReport {
        val doc = Jsoup.parse(html)
        val cfg = (rule["arguments"] as JsonObject)["searchConfig"] as JsonObject
        fun str(o: JsonElement?, k: String) = ((o as? JsonObject)?.get(k) as? JsonPrimitive)?.content
        val names: List<String>
        val links: List<String>
        try {
            when (val fmt = str(cfg, "subjectFormatId")) {
                "a" -> {
                    val els = doc.select(str(cfg["selectorSubjectFormatA"], "selectLists")!!)
                    names = els.map { it.text().trim() }
                    links = els.filter { it.tagName() == "a" }.map { it.attr("href") }
                }
                "indexed" -> {
                    val c = cfg["selectorSubjectFormatIndexed"]
                    names = doc.select(str(c, "selectNames")!!).map { it.text().trim() }
                    links = doc.select(str(c, "selectLinks")!!).map { it.attr("href") }
                }
                else -> return TestReport(false, "暂不支持的条目格式: $fmt")
            }
        } catch (e: Exception) {
            return TestReport(false, "选择器无效: ${e.message}")
        }
        return when {
            names.isEmpty() -> TestReport(false, "选择器没有选中任何条目", names, links)
            names.size != links.size ->
                TestReport(false, "名称数 ${names.size} 与链接数 ${links.size} 不一致", names, links)
            links.any { it.isEmpty() } -> TestReport(false, "存在空链接", names, links)
            names.none { norm(keyword) in norm(it) } ->
                TestReport(false, "选中的条目里没有包含搜索关键词的标题", names, links)
            else -> TestReport(true, "通过：${names.size} 个条目", names, links)
        }
    }

    fun generateAndTest(html: String, keyword: String, searchUrl: String, name: String = "示例动漫"): Pair<JsonObject, TestReport> {
        val rule = generateRule(html, keyword, searchUrl, name)
        return rule to testRule(rule, html, keyword)
    }
}
