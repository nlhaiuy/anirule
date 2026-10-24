package app.rulegen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.serialization.json.Json
import rulegen.Flow
import kotlin.concurrent.thread

/** 只有一个输入框：输入网址 → 点一下 → 自动完成；下方的 WebView 可见，遇到验证码时可以手动处理。 */
class MainActivity : AppCompatActivity() {
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }
    private var resultJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val urlInput = EditText(this).apply {
            hint = "输入网站网址，如 https://example.com"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val start = Button(this).apply { text = "自动生成规则" }
        val share = Button(this).apply { text = "复制 / 分享规则 JSON"; isEnabled = false }
        val status = TextView(this).apply { text = "就绪"; setPadding(8, 8, 8, 8) }
        val web = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(start, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(share, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        val captureJs = assets.open("capture.js").bufferedReader().readText()
        val browser = WebViewBrowser(web, captureJs)

        start.setOnClickListener {
            var url = urlInput.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            if (!url.startsWith("http")) url = "https://$url"
            start.isEnabled = false
            share.isEnabled = false
            status.text = "正在自动搜索、点进详情、点第一集……"
            thread {
                val r = try { Flow.run(url, browser) } catch (e: Exception) {
                    runOnUiThread { status.text = "出错: ${e.message}"; start.isEnabled = true }
                    return@thread
                }
                val export = r.rule?.let { rulegen.JsonApi.wrapExport(it) }
                resultJson = export?.let { pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) } ?: ""
                runOnUiThread {
                    status.text = buildString {
                        append(if (r.ok) (if (r.partial) "部分成功（${r.mode}）" else "成功（${r.mode}）") else "失败")
                        r.report?.let { append("\n").append(it.message) }
                        if (r.notes.isNotEmpty()) append("\n").append(r.notes.joinToString("\n"))
                    }
                    start.isEnabled = true
                    share.isEnabled = resultJson.isNotEmpty()
                }
            }
        }
        share.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("rule", resultJson))
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, resultJson), "分享规则"))
        }
    }
}
