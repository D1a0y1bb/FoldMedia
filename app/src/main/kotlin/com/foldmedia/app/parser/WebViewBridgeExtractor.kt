package com.foldmedia.app.parser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device Chromium WebView fallback extractor.
 * Uses Desktop Chrome UA for Xiaohongshu so complete multi-image & Live Photo streams render
 * without mobile App-download walls, and validates that state JSON actually contains media items
 * before resolving `pollState` (preventing empty shell returns).
 */
class WebViewBridgeExtractor(private val appContext: Context) {

    enum class TargetStateType {
        XIAOHONGSHU_INITIAL_STATE,
        DOUYIN_ROUTER_DATA
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractStateJson(
        url: String,
        targetType: TargetStateType,
        timeoutMs: Long = 16_000L
    ): Result<String> {
        val deferred = CompletableDeferred<Result<String>>()
        val mainHandler = Handler(Looper.getMainLooper())
        var webViewRef: WebView? = null
        var pollingStopped = false

        fun cleanup() {
            pollingStopped = true
            mainHandler.post {
                runCatching {
                    webViewRef?.stopLoading()
                    webViewRef?.destroy()
                }
                webViewRef = null
            }
        }

        mainHandler.post {
            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)

                val wv = WebView(appContext)
                webViewRef = wv
                cm.setAcceptThirdPartyCookies(wv, true)

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = when (targetType) {
                        TargetStateType.XIAOHONGSHU_INITIAL_STATE ->
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        TargetStateType.DOUYIN_ROUTER_DATA ->
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.50 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
                    }
                }

                val jsProbe = when (targetType) {
                    TargetStateType.XIAOHONGSHU_INITIAL_STATE ->
                        """
                        (function(){
                          try {
                            var s = window.__INITIAL_STATE__;
                            if (s && s.note && s.note.noteDetailMap) {
                              var keys = Object.keys(s.note.noteDetailMap);
                              for (var i = 0; i < keys.length; i++) {
                                var entry = s.note.noteDetailMap[keys[i]];
                                if (entry && entry.note && ((entry.note.imageList && entry.note.imageList.length > 0) || entry.note.video)) {
                                  return JSON.stringify(s);
                                }
                              }
                            }
                            return "";
                          } catch(e) { return ""; }
                        })();
                        """.trimIndent()
                    TargetStateType.DOUYIN_ROUTER_DATA ->
                        """
                        (function(){
                          try {
                            var r = window._ROUTER_DATA;
                            if (r && r.loaderData) {
                              var keys = Object.keys(r.loaderData);
                              for (var i = 0; i < keys.length; i++) {
                                var node = r.loaderData[keys[i]];
                                if (node && node.videoInfoRes && node.videoInfoRes.item_list && node.videoInfoRes.item_list.length > 0) {
                                  return JSON.stringify(r);
                                }
                              }
                            }
                            var rd = document.getElementById('RENDER_DATA');
                            if (rd && rd.textContent && rd.textContent.length > 20) {
                              var decoded = decodeURIComponent(rd.textContent);
                              if (decoded.indexOf('awemeDetail') >= 0 || decoded.indexOf('item_list') >= 0) {
                                return decoded;
                              }
                            }
                            return "";
                          } catch(e) { return ""; }
                        })();
                        """.trimIndent()
                }

                fun pollState(attempt: Int) {
                    if (pollingStopped || deferred.isCompleted) return
                    wv.evaluateJavascript(jsProbe) { raw ->
                        if (pollingStopped || deferred.isCompleted) return@evaluateJavascript
                        val unquoted = decodeJsString(raw)
                        if (unquoted.length > 32 && unquoted.startsWith("{")) {
                            cm.flush()
                            deferred.complete(Result.success(unquoted))
                        } else if (attempt < 18) {
                            mainHandler.postDelayed({ pollState(attempt + 1) }, 750L)
                        }
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val scheme = request?.url?.scheme?.lowercase() ?: return false
                        return scheme != "http" && scheme != "https"
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        pollState(0)
                    }
                }

                wv.loadUrl(url)
            } catch (t: Throwable) {
                if (!deferred.isCompleted) {
                    deferred.complete(Result.failure(t))
                }
            }
        }

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: Result.failure(IllegalStateException("页面可能触发了登录墙或滑块校验，请先在「设置」完成真实登录后重试"))
        cleanup()
        return result
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return ""
        return if (raw.startsWith("\"") && raw.endsWith("\"")) {
            val inner = raw.substring(1, raw.length - 1)
            buildString(inner.length) {
                var i = 0
                while (i < inner.length) {
                    val ch = inner[i]
                    if (ch == '\\' && i + 1 < inner.length) {
                        when (val next = inner[i + 1]) {
                            '"' -> append('"')
                            '\\' -> append('\\')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            '/' -> append('/')
                            'u' -> {
                                if (i + 5 < inner.length) {
                                    val hex = inner.substring(i + 2, i + 6)
                                    val code = hex.toIntOrNull(16)
                                    if (code != null) {
                                        append(code.toChar())
                                        i += 6
                                        continue
                                    }
                                }
                                append(ch)
                                append(next)
                            }
                            else -> append(next)
                        }
                        i += 2
                    } else {
                        append(ch)
                        i++
                    }
                }
            }
        } else {
            raw
        }
    }
}
