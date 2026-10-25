package rulegen

import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** 由调用方提供；失败时抛异常。Android 上可用 OkHttp 实现。 */
fun interface Fetcher {
    fun fetch(url: String): String
}

data class DiscoverResult(
    val ok: Boolean,
    val rule: JsonObject? = null,
    val report: TestReport? = null,
    val searchUrl: String = "",
    val keyword: String = "",
    val notes: List<String> = emptyList(),
)

/** 只输入一个网址：自动找搜索入口、选关键词、生成并测试规则。 */
object Discover {
    val KEYWORDS = listOf("葬送的芙莉莲", "孤独摇滚", "药屋少女的呢喃", "咒术回战", "鬼灭之刃")
    private val COMMON_PATHS = listOf(
        "/search.html?wd={keyword}",
        "/vodsearch/-------------.html?wd={keyword}",
        "/index.php/vod/search.html?wd={keyword}",
        "/search/?q={keyword}",
        "/search?keyword={keyword}",
        "/?s={keyword}",
        "/vodsearch/{keyword}-------------.html",
        "/search/{keyword}.html",
        "/search/{keyword}/",
    )
    private val TEXT_INPUT_NAMES = setOf("wd", "q", "keyword", "key", "kw", "s", "searchword", "query", "search")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 从首页的搜索表单推导搜索链接模板（只支持 GET）。 */
    fun templatesFromHome(homeHtml: String, baseUrl: String, notes: MutableList<String>): List<String> {
        val doc = Jsoup.parse(homeHtml)
        val out = ArrayList<String>()
        for (form in doc.select("form")) {
            val field = form.select("input").firstOrNull { inp ->
                val t = inp.attr("type").ifEmpty { "text" }.lowercase()
                inp.attr("name").isNotEmpty() && (t == "text" || t == "search" || inp.attr("name") in TEXT_INPUT_NAMES)
            } ?: continue
            if (form.attr("method").ifEmpty { "get" }.lowercase() != "get") {
                notes += "发现 POST 搜索表单，暂不支持自动生成（需要 json-api 或手动配置）"
                continue
            }
            val action = URI(baseUrl).resolve(form.attr("action").ifEmpty { baseUrl }).toString()
            val params = form.select("input[type=hidden][name]").map { "${enc(it.attr("name"))}=${enc(it.attr("value"))}" } +
                "${field.attr("name")}={keyword}"
            out += action + (if ("?" in action) "&" else "?") + params.joinToString("&")
        }
        return out
    }

    fun candidates(url: String, homeHtml: String?, notes: MutableList<String>): List<String> {
        // URI 不接受花括号，先把占位符换掉再解析
        val u = URI(url.replace("{keyword}", "KEYWORD"))
        val origin = "${u.scheme}://${u.authority}"
        val all = ArrayList<String>()
        if ("{keyword}" in url) all += url
        if (homeHtml != null) all += templatesFromHome(homeHtml, url, notes)
        all += COMMON_PATHS.map { origin + it }
        return all.distinct()
    }

    fun discover(url: String, fetcher: Fetcher, keywords: List<String> = KEYWORDS, name: String = "示例动漫"): DiscoverResult {
        val notes = ArrayList<String>()
        var home: String? = null
        if ("{keyword}" !in url) {
            try { home = fetcher.fetch(url) } catch (e: Exception) { notes += "首页无法访问: ${e.message}" }
        }
        var last: TestReport? = null
        for (tpl in candidates(url, home, notes)) {
            for (kw in keywords) {
                val real = tpl.replace("{keyword}", enc(kw))
                val html = try { fetcher.fetch(real) } catch (e: Exception) {
                    notes += "请求失败 $real: ${e.message}"
                    break // 这个模板整体不可用，换下一个
                }
                if (html.trimStart().firstOrNull() in setOf('{', '[')) {
                    notes += "$tpl 返回 JSON，需要 json-api 规则（暂未支持）"
                    break
                }
                val rule = try { RuleGen.generateRule(html, kw, tpl, name) } catch (e: GenerateException) {
                    last = TestReport(false, e.message ?: "")
                    continue
                }
                val rep = RuleGen.testRule(rule, html, kw)
                if (rep.ok) return DiscoverResult(true, rule, rep, tpl, kw, notes)
                last = rep
            }
        }
        notes += "所有搜索入口与关键词都没有生成可用规则" + (last?.let { "（最后一次: ${it.message}）" } ?: "")
        return DiscoverResult(false, null, last, notes = notes)
    }
}
