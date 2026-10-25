package app.rulegen

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import rulegen.Browser
import rulegen.CaptureRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把真实 WebView 适配成 core 里的 Browser 接口。
 * 所有 Browser 方法都是阻塞的，必须在后台线程调用（不能在主线程）。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewBrowser(private val webView: WebView, private val captureJs: String) : Browser {
    private val ui = Handler(Looper.getMainLooper())
    private val recs = ArrayList<CaptureRecord>()
    @Volatile private var loading = false
    @Volatile private var pageLatch: CountDownLatch? = null
    @Volatile private var lastRecordAt = 0L
    private var defaultUa = ""

    private inner class Bridge {
        @JavascriptInterface
        fun onEvent(json: String) {
            val o = JSONObject(json)
            val type = o.optString("type")
            if (type == "nav") return // 网址变化通过 currentUrl() 读取
            val headers = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            synchronized(recs) {
                recs += CaptureRecord(
                    type, o.optString("url"), o.optString("method", "GET"), headers,
                    o.optString("body"), if (o.has("status")) o.optInt("status") else null, o.optString("response"),
                )
            }
            lastRecordAt = System.currentTimeMillis()
        }
    }

    init {
        ui.post {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            defaultUa = webView.settings.userAgentString
            webView.addJavascriptInterface(Bridge(), "AndroidRG")
            val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (early) WebViewCompat.addDocumentStartJavaScript(webView, captureJs, setOf("*"))
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    if (!early) view.evaluateJavascript(captureJs, null) // 旧版 WebView 的退路：稍晚注入，可能漏掉最早的请求
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    pageLatch?.countDown()
                }
            }
        }
    }

    /** 桌面版：桌面 UA + 宽视口。桌面页面的搜索框和链接结构通常更完整，也更接近 Animeko 直接请求时拿到的页面。 */
    override fun setDesktopMode(desktop: Boolean) {
        val latch = CountDownLatch(1)
        ui.post {
            webView.settings.userAgentString = if (desktop) DESKTOP_UA else defaultUa
            webView.settings.useWideViewPort = desktop
            webView.settings.loadWithOverviewMode = desktop
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    override fun load(url: String) {
        val latch = CountDownLatch(1)
        pageLatch = latch
        ui.post { webView.loadUrl(url) }
        latch.await(20, TimeUnit.SECONDS)
    }

    override fun call(fn: String, vararg args: String): String {
        val js = "(function(){try{return String(__rg.$fn(${args.joinToString(",") { JSONObject.quote(it) }}))}catch(e){return 'error:'+e}})()"
        val out = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        ui.post {
            webView.evaluateJavascript(js) { raw ->
                out[0] = (JSONTokener(raw ?: "null").nextValue() as? String) ?: ""
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return out[0] ?: ""
    }

    override fun currentUrl(): String {
        val out = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        ui.post { out[0] = webView.url; latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        return out[0] ?: ""
    }

    /** 页面不在加载，且 1.2 秒内没有新的网络记录；最长等 8 秒。 */
    override fun waitIdle() {
        val deadline = System.currentTimeMillis() + 8_000
        lastRecordAt = maxOf(lastRecordAt, System.currentTimeMillis() - 600)
        while (System.currentTimeMillis() < deadline) {
            if (!loading && System.currentTimeMillis() - lastRecordAt > 1_200) return
            Thread.sleep(200)
        }
    }

    override fun records(): List<CaptureRecord> = synchronized(recs) { ArrayList(recs) }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
