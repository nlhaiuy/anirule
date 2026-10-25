package rulegen

import org.jsoup.Jsoup
import java.net.URI

/** 从站点首页提取数据源名称与图标。不针对任何具体站点，只读标准的 meta / link 标签。 */
object SiteMeta {
    data class Meta(val name: String, val iconUrl: String)

    // 标题里常见的"通用"片段，不是站点名
    private val GENERIC = Regex("在线|观看|免费|高清|首页|官网|最新|下载|手机版|全集|大全|\\bhome\\b|official", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("\\s*[-_|–—·｜,，:：、/]\\s*|\\s{2,}")

    fun extract(html: String, pageUrl: String): Meta {
        val doc = Jsoup.parse(html, pageUrl)
        return Meta(extractName(doc.title(), doc.select("meta[property=og:site_name]").attr("content"),
            doc.select("meta[name=application-name]").attr("content"), pageUrl), extractIcon(html, pageUrl))
    }

    fun extractName(title: String, ogSiteName: String, appName: String, pageUrl: String): String {
        listOf(ogSiteName, appName).map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { return it.take(20) }
        val parts = title.split(SPLIT).map { it.trim() }.filter { it.length >= 2 }
        val pick = parts.firstOrNull { !GENERIC.containsMatchIn(it) } ?: parts.firstOrNull()
        if (pick != null) return pick.take(20)
        return host(pageUrl)
    }

    fun extractIcon(html: String, pageUrl: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        val links = doc.select("link[rel][href]")
        fun relHas(e: org.jsoup.nodes.Element, word: String) = e.attr("rel").lowercase().split(Regex("\\s+")).any { it == word }
        val chosen = links.firstOrNull { relHas(it, "apple-touch-icon") }
            ?: links.firstOrNull { relHas(it, "icon") && !it.attr("href").startsWith("data:") }
            ?: links.firstOrNull { it.attr("rel").lowercase().contains("icon") && !it.attr("href").startsWith("data:") }
        val href = chosen?.attr("href")?.trim().orEmpty()
        resolve(pageUrl, href)?.let { return it }
        return resolve(pageUrl, "/favicon.ico") ?: ""
    }

    private fun resolve(base: String, ref: String): String? {
        if (ref.isEmpty()) return null
        return try {
            val b = URI(base.replace("{keyword}", "KEYWORD"))
            val u = if (ref.startsWith("//")) URI("${b.scheme}:$ref") else b.resolve(ref.replace(" ", "%20"))
            u.toString().takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
    }

    private fun host(url: String): String = try {
        URI(url.replace("{keyword}", "KEYWORD")).host?.removePrefix("www.") ?: "示例动漫"
    } catch (_: Exception) { "示例动漫" }
}
