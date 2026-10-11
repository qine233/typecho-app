package noline.zhouzhou.util

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import noline.zhouzhou.ui.theme.LocalFontScale

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlContent(
    html: String,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = false,
    baseUrl: String? = null,
    onHeightChanged: (Int) -> Unit = {}
) {
    val fontScale = LocalFontScale.current

    val cssStyle = """
        <style>
            * { box-sizing: border-box; }
            body {
                font-family: -apple-system, 'Segoe UI', 'Noto Sans SC', 'PingFang SC', sans-serif;
                font-size: ${16 * fontScale}px;
                line-height: 1.75;
                color: ${if (darkTheme) "#EDE0DA" else "#201A17"};
                background: transparent;
                margin: 0;
                padding: 0;
                word-wrap: break-word;
                overflow-wrap: break-word;
            }
            img { max-width: 100%; height: auto; border-radius: 8px; margin: 8px 0; }
            a { color: ${if (darkTheme) "#FFB596" else "#8B5E3C"}; text-decoration: none; }
            blockquote {
                border-left: 3px solid ${if (darkTheme) "#FFB596" else "#8B5E3C"};
                margin: 12px 0; padding: 8px 16px;
                background: ${if (darkTheme) "rgba(255,181,150,0.08)" else "rgba(139,94,60,0.06)"};
                border-radius: 0 8px 8px 0;
                color: ${if (darkTheme) "#D8C2BA" else "#53433D"};
            }
            code {
                background: ${if (darkTheme) "rgba(255,255,255,0.08)" else "rgba(0,0,0,0.05)"};
                padding: 2px 6px; border-radius: 4px;
                font-family: 'SF Mono', 'Fira Code', monospace;
                font-size: ${14 * fontScale}px;
            }
            pre {
                background: ${if (darkTheme) "#2A2320" else "#F5F0EC"};
                padding: 16px; border-radius: 12px;
                overflow-x: auto; margin: 12px 0;
            }
            pre code { background: transparent; padding: 0; }
            h1, h2, h3, h4, h5, h6 {
                font-weight: 600;
                margin: 20px 0 10px;
                color: ${if (darkTheme) "#EDE0DA" else "#201A17"};
            }
            h1 { font-size: ${24 * fontScale}px; }
            h2 { font-size: ${20 * fontScale}px; }
            h3 { font-size: ${18 * fontScale}px; }
            hr {
                border: none; height: 1px;
                background: ${if (darkTheme) "#53433D" else "#D8C2BA"};
                margin: 20px 0;
            }
            table { border-collapse: collapse; width: 100%; margin: 12px 0; }
            th, td {
                border: 1px solid ${if (darkTheme) "#53433D" else "#D8C2BA"};
                padding: 8px 12px; text-align: left;
            }
            th {
                background: ${if (darkTheme) "#2A2320" else "#F5F0EC"};
                font-weight: 600;
            }
            ul, ol { padding-left: 24px; margin: 8px 0; }
            li { margin: 4px 0; }
        </style>
    """.trimIndent()

    var webViewHeightDp by remember { mutableFloatStateOf(1f) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Receives the document height from the page. The page re-measures on layout
    // changes (ResizeObserver) and once every <img> settles, so late-loading images
    // grow the WebView instead of being clipped by a one-shot measurement.
    val bridge = remember {
        object {
            @JavascriptInterface
            fun reportHeight(cssPx: Double) {
                if (cssPx <= 0) return
                mainHandler.post { webViewHeightDp = cssPx.toFloat() + 8f }
            }
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(HEIGHT_OBSERVER_JS, null)
                    }
                }
                webChromeClient = WebChromeClient()
            }
        },
        update = { webView ->
            val fullHtml = """<!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    $cssStyle
                </head>
                <body>$html</body>
                </html>""".trimIndent()
            webView.loadDataWithBaseURL(baseUrl, fullHtml, "text/html", "UTF-8", null)
        },
        onRelease = { webView ->
            webView.removeJavascriptInterface("AndroidBridge")
            webView.destroy()
        },
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeightDp.dp)
    )
}

/**
 * Injected after load. Reports the document height immediately, whenever layout
 * changes (ResizeObserver), and again once every image has finished loading —
 * images without explicit dimensions change height asynchronously.
 */
private const val HEIGHT_OBSERVER_JS = """
(function() {
    function report() {
        // 只看 body 自己的内容高度：documentElement.scrollHeight 至少等于视口高度，
        // 而视口高度就是我们刚设给 WebView 的高度，两者会互相撑大，留出一大片空白。
        var b = document.body;
        var h = Math.max(b.scrollHeight, b.offsetHeight);
        if (window.AndroidBridge) window.AndroidBridge.reportHeight(h);
    }
    report();
    if (typeof ResizeObserver !== 'undefined') {
        new ResizeObserver(report).observe(document.body);
    }
    var imgs = Array.prototype.slice.call(document.images);
    var pending = imgs.filter(function(i) { return !i.complete; });
    pending.forEach(function(i) { i.addEventListener('load', report); i.addEventListener('error', report); });
    window.addEventListener('load', report);
    setTimeout(report, 200);
    setTimeout(report, 800);
    setTimeout(report, 2000);
})();
"""
