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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import noline.zhouzhou.ui.theme.LocalFontScale
import noline.zhouzhou.ui.theme.LocalNoLine
import noline.zhouzhou.ui.theme.NoLineTokens
import kotlin.math.roundToInt

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlContent(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String? = null
) {
    val fontScale = LocalFontScale.current
    val tokens = LocalNoLine.current
    val cssStyle = remember(tokens, fontScale) { articleCss(tokens, fontScale) }

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
                <body><article class="post-content">$html</article></body>
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

/** 代码块左上角的 macOS 红绿灯，和主题 --code-dots 同一份坐标。 */
private const val CODE_DOTS =
    "radial-gradient(circle at 20px 17px, #fc625d 0 6px, transparent 6px)," +
        "radial-gradient(circle at 40px 17px, #fdbc40 0 6px, transparent 6px)," +
        "radial-gradient(circle at 60px 17px, #35cd4b 0 6px, transparent 6px)"

/**
 * 正文排版对齐主题的 article.css：17px/1.7 正文、h2–h6 前挂暖金人字标、
 * 链接加粗压 2px 虚线、引用走强调色底、代码块保留深底红绿灯。
 * 颜色全部从 NoLineTokens 取，不再写第二套字面量，深色模式才不会漏。
 */
private fun articleCss(t: NoLineTokens, fontScale: Float): String {
    val body = (17f * fontScale)
    return """
        <style>
            * { box-sizing: border-box; }
            html, body { background: transparent; margin: 0; padding: 0; }
            body {
                font-family: "PingFang SC", "Noto Sans SC", "Microsoft YaHei", -apple-system, sans-serif;
                color: ${css(t.textMain)};
                -webkit-font-smoothing: antialiased;
            }
            .post-content {
                font-size: ${body}px;
                line-height: 1.7;
                letter-spacing: .035em;
                overflow-wrap: break-word;
                word-break: normal;
                padding: 0 2px;
            }
            .post-content > :first-child { margin-top: 0; }
            .post-content > :last-child { margin-bottom: 0; }
            .post-content p { margin: 1.5rem 0; }
            .post-content blockquote, .post-content pre, .post-content table,
            .post-content ul, .post-content ol, .post-content dl { margin: 0 0 1.5rem; }

            .post-content h1, .post-content h2, .post-content h3,
            .post-content h4, .post-content h5, .post-content h6 {
                position: relative;
                margin: 2.25rem 0 1rem;
                line-height: 1.3;
                font-weight: 700;
                color: ${css(t.textStrong)};
                letter-spacing: 0;
            }
            .post-content h1 { font-size: ${1.5 * body}px; }
            .post-content h2 { font-size: ${1.3 * body}px; }
            .post-content h3 { font-size: ${1.1 * body}px; }
            .post-content h4 { font-size: ${body}px; }
            .post-content h5 { font-size: ${0.9375 * body}px; }
            .post-content h6 { font-size: ${0.875 * body}px; color: ${css(t.textSecondary)}; }
            .post-content h2, .post-content h3, .post-content h4,
            .post-content h5, .post-content h6 { padding-left: 1.2em; }
            .post-content h2::before, .post-content h3::before, .post-content h4::before,
            .post-content h5::before, .post-content h6::before {
                content: "";
                position: absolute;
                left: 0;
                top: 50%;
                width: .42em;
                height: .42em;
                border-top: 2px solid ${css(t.headingMarker)};
                border-right: 2px solid ${css(t.headingMarker)};
                transform: translateY(-50%) rotate(45deg);
            }

            .post-content ul, .post-content ol {
                list-style: none;
                padding: 0;
                counter-reset: noline-li;
            }
            .post-content li {
                position: relative;
                display: flex;
                align-items: flex-start;
                gap: .5em;
                margin: .25rem 0;
            }
            .post-content ul > li::before {
                content: "";
                flex: 0 0 .45em;
                width: .45em;
                height: .45em;
                margin-top: .62em;
                border-radius: 50%;
                background-color: ${css(t.accent)};
                box-shadow: 0 0 0 .18em ${css(t.accent.copy(alpha = 0.24f))};
            }
            .post-content ol > li::before {
                content: counter(noline-li);
                counter-increment: noline-li;
                flex: 0 0 1.45em;
                height: 1.45em;
                margin-top: .125em;
                border-radius: 50%;
                background-color: ${css(t.accent)};
                color: ${css(t.onBrand)};
                font-size: .8em;
                font-weight: 700;
                line-height: 1.45em;
                text-align: center;
            }
            .post-content li > ul, .post-content li > ol { margin: .25rem 0 0; padding-left: .9em; }

            .post-content blockquote {
                padding: .75rem 1rem;
                border-left: .25em solid ${css(t.accent)};
                background-color: ${css(t.accentBg)};
                color: ${css(t.textSecondary)};
                border-radius: 0 6px 6px 0;
            }
            .post-content blockquote > :first-child { margin-top: 0; }
            .post-content blockquote > :last-child { margin-bottom: 0; }
            .post-content blockquote p { font-size: ${0.9375 * body}px; line-height: 1.7; }

            .post-content a {
                color: ${css(t.textStrong)};
                font-weight: 700;
                text-decoration: none;
                border-bottom: 2px dotted ${css(t.accent)};
            }
            .post-content a:has(img) { border-bottom: 0; }
            .post-content strong { color: ${css(t.textStrong)}; font-weight: 700; }
            .post-content del { color: ${css(t.textLight)}; }
            .post-content mark {
                background-color: ${css(t.markBg)};
                color: inherit;
                padding: 0 .15em;
                border-radius: 2px;
            }
            .post-content hr {
                height: 1px;
                margin: 2rem 0;
                border: 0;
                background-color: ${css(t.borderStrong)};
            }

            .post-content code {
                font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                font-size: 0.875em;
                padding: .15em .4em;
                background-color: ${css(t.surfaceCode)};
                color: ${css(t.accent)};
                border-radius: 4px;
            }
            .post-content pre {
                padding: 2rem 0 0;
                overflow: auto;
                border-radius: 8px;
                background-color: ${css(t.codeBg)};
                background-image: $CODE_DOTS;
                background-repeat: no-repeat;
                -webkit-overflow-scrolling: touch;
            }
            .post-content pre code {
                display: block;
                padding: .75em 1.1em 1em;
                background: none;
                color: ${css(t.codeFg)};
                font-size: ${0.875 * body}px;
                font-weight: 400;
                line-height: 1.6;
                letter-spacing: 0;
                border-radius: 0;
                white-space: pre;
            }

            .post-content img {
                display: block;
                max-width: 90%;
                max-height: 28rem;
                height: auto;
                margin: 1.5rem auto;
                border-radius: 16px;
            }
            .post-content figcaption {
                margin: -.75rem 0 1.5rem;
                font-size: ${0.8125 * body}px;
                line-height: 1.6;
                letter-spacing: 0;
                color: ${css(t.textLight)};
                text-align: center;
            }

            .post-content table {
                width: 100%;
                display: block;
                overflow-x: auto;
                border-collapse: collapse;
                font-size: ${0.9375 * body}px;
            }
            .post-content th, .post-content td {
                padding: .5rem .75rem;
                border: 1px solid ${css(t.borderStrong)};
                text-align: left;
            }
            .post-content th {
                background-color: ${css(t.surfaceSunken)};
                font-weight: 700;
                color: ${css(t.textStrong)};
            }
            .post-content tbody tr:nth-child(even) { background-color: ${css(t.hairline)}; }

            .post-content .footnotes {
                margin-top: 2rem;
                padding-top: .75rem;
                border-top: 1px solid ${css(t.cardBorder)};
                font-size: 0.875em;
                color: ${css(t.textSecondary)};
            }
            .post-content .footnote-ref { border-width: 0; }
        </style>
    """.trimIndent()
}

/** Compose Color → CSS rgba()，半透明令牌（accentBg、hairline）只能这么落进网页。 */
private fun css(color: Color): String {
    fun ch(v: Float) = (v * 255).roundToInt().coerceIn(0, 255)
    val a = ((color.alpha * 1000).roundToInt() / 1000.0)
    return "rgba(${ch(color.red)}, ${ch(color.green)}, ${ch(color.blue)}, $a)"
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
