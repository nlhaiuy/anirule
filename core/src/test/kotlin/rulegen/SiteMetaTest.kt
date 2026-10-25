package rulegen

import kotlin.test.Test
import kotlin.test.assertEquals

class SiteMetaTest {
    private fun meta(head: String, url: String = "https://www.example.com/") =
        SiteMeta.extract("<html><head>$head</head><body></body></html>", url)

    @Test
    fun ogSiteNameWins() {
        assertEquals("我的动漫站", meta("""<title>别的标题</title><meta property="og:site_name" content="我的动漫站">""").name)
    }

    @Test
    fun titleFirstNonGenericSegment() {
        assertEquals("樱花动漫", meta("<title>樱花动漫_樱花动漫官网_在线观看</title>").name)
        assertEquals("樱花动漫", meta("<title>樱花动漫 - 在线观看全集 - 高清</title>").name)
    }

    @Test
    fun genericWordsAtTheFrontAreSkipped() {
        assertEquals("某某影视", meta("<title>在线观看 - 某某影视</title>").name)
    }

    @Test
    fun noTitleFallsBackToHost() {
        assertEquals("example.com", meta("").name)
    }

    @Test
    fun appleTouchIconPreferred() {
        val m = meta("""<link rel="icon" href="/a.ico"><link rel="apple-touch-icon" href="/t.png">""")
        assertEquals("https://www.example.com/t.png", m.iconUrl)
    }

    @Test
    fun shortcutIconAndRelativePath() {
        assertEquals("https://www.example.com/static/fav.ico", meta("""<link rel="shortcut icon" href="static/fav.ico">""").iconUrl)
    }

    @Test
    fun protocolRelativeIcon() {
        assertEquals("https://cdn.x.com/i.png", meta("""<link rel="icon" href="//cdn.x.com/i.png">""").iconUrl)
    }

    @Test
    fun noIconFallsBackToFavicon() {
        assertEquals("https://www.example.com/favicon.ico", meta("").iconUrl)
    }

    @Test
    fun dataUrlIconSkipped() {
        assertEquals("https://www.example.com/favicon.ico", meta("""<link rel="icon" href="data:image/png;base64,AAAA">""").iconUrl)
    }
}
