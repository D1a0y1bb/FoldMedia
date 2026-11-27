package com.foldmedia.app.parser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        DOUYIN_ROUTER_DATA,
        DOUYIN_WEB_DETAIL,
        WECHAT_PAGE_HTML
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
                        TargetStateType.XIAOHONGSHU_INITIAL_STATE,
                        TargetStateType.DOUYIN_WEB_DETAIL ->
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        TargetStateType.DOUYIN_ROUTER_DATA ->
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.50 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
                        TargetStateType.WECHAT_PAGE_HTML ->
                            "Mozilla/5.0 (Linux; Android 15; Xiaomi 18 Fold) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50"
                    }
                }

                val douyinHookScript = """
                    (function(){
                      if (window.__FOLDMEDIA_HOOKED__) return;
                      window.__FOLDMEDIA_HOOKED__ = true;
                      window.__FOLDMEDIA_APIS__ = window.__FOLDMEDIA_APIS__ || [];
                      function recordApi(url, status, txt) {
                        var u = String(url || '');
                        if (u.indexOf('aweme') >= 0 || u.indexOf('slides') >= 0 || u.indexOf('detail') >= 0 || u.indexOf('note') >= 0) {
                          window.__FOLDMEDIA_APIS__.push(status + ':' + (txt ? txt.length : 0) + ':' + u.slice(0, 140));
                          if (window.__FOLDMEDIA_APIS__.length > 15) window.__FOLDMEDIA_APIS__.shift();
                        }
                        if (txt && (txt.indexOf('aweme_detail') >= 0 || txt.indexOf('awemeDetail') >= 0 || txt.indexOf('"aweme_details"') >= 0)) {
                          window.__FOLDMEDIA_DOUYIN_DETAIL__ = txt;
                          window.__FOLDMEDIA_DETAIL_STATUS__ = status;
                        } else if (u.indexOf('/aweme/v1/web/aweme/detail') >= 0) {
                          window.__FOLDMEDIA_DETAIL_STATUS__ = status;
                          window.__FOLDMEDIA_DETAIL_ERR__ = (txt || '').slice(0, 240);
                        }
                      }
                      var origOpen = XMLHttpRequest.prototype.open;
                      var origSend = XMLHttpRequest.prototype.send;
                      XMLHttpRequest.prototype.open = function(method, url) {
                        this.__foldUrl = String(url || '');
                        return origOpen.apply(this, arguments);
                      };
                      XMLHttpRequest.prototype.send = function() {
                        var xhr = this;
                        xhr.addEventListener('load', function() {
                          try { recordApi(xhr.__foldUrl, xhr.status, xhr.responseText || ''); } catch(e) {}
                        });
                        return origSend.apply(this, arguments);
                      };
                      if (window.fetch) {
                        var origFetch = window.fetch;
                        window.fetch = function(input) {
                          var urlStr = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                          return origFetch.apply(this, arguments).then(function(resp) {
                            try {
                              var clone = resp.clone();
                              clone.text().then(function(txt) {
                                recordApi(urlStr, resp.status, txt || '');
                              }).catch(function(){});
                            } catch(e) {}
                            return resp;
                          });
                        };
                      }
                    })();
                """.trimIndent()

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
                            if (s && s.noteData && s.noteData.data && s.noteData.data.noteData) {
                              var n = s.noteData.data.noteData;
                              if ((n.imageList && n.imageList.length > 0) || n.video) {
                                return JSON.stringify(s);
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
                                if (node && ((node.videoInfoRes && node.videoInfoRes.item_list && node.videoInfoRes.item_list.length > 0) || node.aweme_detail || node.awemeDetail || node.noteDetail)) {
                                  return JSON.stringify(r);
                                }
                              }
                            }
                            var rd = document.getElementById('RENDER_DATA');
                            if (rd && rd.textContent && rd.textContent.length > 20) {
                              var decoded = decodeURIComponent(rd.textContent);
                              if (decoded.indexOf('awemeDetail') >= 0 || decoded.indexOf('aweme_detail') >= 0 || decoded.indexOf('item_list') >= 0) {
                                return decoded;
                              }
                            }
                            return "";
                          } catch(e) { return ""; }
                        })();
                        """.trimIndent()
                    TargetStateType.DOUYIN_WEB_DETAIL ->
                        """
                        (function(){
                          try {
                            if (window.__FOLDMEDIA_DOUYIN_DETAIL__ && window.__FOLDMEDIA_DOUYIN_DETAIL__.length > 64) {
                              return window.__FOLDMEDIA_DOUYIN_DETAIL__;
                            }
                            var paceChunks = [];
                            var scripts = document.querySelectorAll('script');
                            for (var si = 0; si < scripts.length; si++) {
                              var scTxt = scripts[si].textContent || '';
                              var pushPos = scTxt.indexOf('__pace_f.push(');
                              if (pushPos < 0) continue;
                              var lbrack = scTxt.indexOf('[', pushPos);
                              var rbrack = scTxt.lastIndexOf(']');
                              if (lbrack >= 0 && rbrack > lbrack) {
                                try {
                                  var parsedArr = JSON.parse(scTxt.slice(lbrack, rbrack + 1));
                                  if (Array.isArray(parsedArr) && typeof parsedArr[1] === 'string') {
                                    paceChunks.push(parsedArr[1]);
                                  }
                                } catch(e) {}
                              }
                            }
                            if (paceChunks.length > 0) {
                              var joined = paceChunks.join('');
                              var curId = (location.pathname.match(/(\d{15,25})/) || [])[1] || '';
                              var anchorMarkers = curId
                                ? ['{"awemeId":"' + curId + '"', '{"awemeId":', '{"aweme":{"statusCode":', '{"detail":{"entertainmentVideoType":', '{"aweme_detail":', '{"awemeDetail":']
                                : ['{"awemeId":', '{"aweme":{"statusCode":', '{"detail":{"entertainmentVideoType":', '{"aweme_detail":', '{"awemeDetail":'];
                              for (var mi = 0; mi < anchorMarkers.length; mi++) {
                                var startIdx = joined.indexOf(anchorMarkers[mi]);
                                if (startIdx < 0) continue;
                                var depth = 0;
                                var inStr = false;
                                var esc = false;
                                var endIdx = -1;
                                for (var ci = startIdx; ci < joined.length; ci++) {
                                  var ch = joined.charAt(ci);
                                  if (inStr) {
                                    if (esc) { esc = false; }
                                    else if (ch === '\\') { esc = true; }
                                    else if (ch === '"') { inStr = false; }
                                  } else {
                                    if (ch === '"') { inStr = true; }
                                    else if (ch === '{') { depth++; }
                                    else if (ch === '}') {
                                      depth--;
                                      if (depth === 0) { endIdx = ci + 1; break; }
                                    }
                                  }
                                }
                                if (endIdx > startIdx) {
                                  var candidate = joined.slice(startIdx, endIdx);
                                  if (candidate.indexOf('"images"') >= 0 || candidate.indexOf('"video"') >= 0) {
                                    try {
                                      JSON.parse(candidate);
                                      return candidate;
                                    } catch(e) {}
                                  }
                                }
                              }
                            }
                            var rd = document.getElementById('RENDER_DATA');
                            if (rd && rd.textContent && rd.textContent.length > 20) {
                              try {
                                var decoded = decodeURIComponent(rd.textContent);
                                var rdObj = JSON.parse(decoded);
                                var rdDetail = (rdObj && rdObj.app && rdObj.app.videoDetail) ||
                                    (rdObj && rdObj.aweme && rdObj.aweme.detail) ||
                                    (rdObj && (rdObj.aweme_detail || rdObj.awemeDetail)) ||
                                    null;
                                if (rdDetail && (rdDetail.images || rdDetail.video)) {
                                  return decoded;
                                }
                              } catch(e) {}
                            }
                            if (!window.__FOLDMEDIA_ACTIVE_FETCHED__) {
                              var m = location.pathname.match(/(\d{15,25})/);
                              if (m && m[1]) {
                                window.__FOLDMEDIA_ACTIVE_FETCHED__ = true;
                                var apiUrl = '/aweme/v1/web/aweme/detail/?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id=' + m[1] + '&pc_client_type=1&version_code=290100&version_name=29.1.0&cookie_enabled=true&platform=PC';
                                var x = new XMLHttpRequest();
                                x.open('GET', apiUrl, true);
                                x.withCredentials = true;
                                x.onreadystatechange = function() {
                                  if (x.readyState === 4) {
                                    window.__FOLDMEDIA_DETAIL_STATUS__ = x.status;
                                    if (x.status === 200 && x.responseText && x.responseText.indexOf('aweme_detail') >= 0) {
                                      window.__FOLDMEDIA_DOUYIN_DETAIL__ = x.responseText;
                                    } else {
                                      window.__FOLDMEDIA_DETAIL_ERR__ = (x.responseText || '').slice(0, 240);
                                    }
                                  }
                                };
                                x.send();
                              }
                            }
                            return "";
                          } catch(e) { return ""; }
                        })();
                        """.trimIndent()
                    TargetStateType.WECHAT_PAGE_HTML ->
                        """
                        (function(){
                          try {
                            var html = document.documentElement ? document.documentElement.outerHTML : "";
                            if (html && (html.indexOf('js_content') >= 0 || html.indexOf('mmbiz.qpic.cn') >= 0 || html.indexOf('mmemoticon.qpic.cn') >= 0 || html.indexOf('emoticon') >= 0)) {
                              return html;
                            }
                            return "";
                          } catch(e) { return ""; }
                        })();
                        """.trimIndent()
                }

                val maxAttempts = if (targetType == TargetStateType.DOUYIN_WEB_DETAIL) 16 else 18
                fun pollState(attempt: Int) {
                    if (pollingStopped || deferred.isCompleted) return
                    wv.evaluateJavascript(jsProbe) { raw ->
                        if (pollingStopped || deferred.isCompleted) return@evaluateJavascript
                        val unquoted = decodeJsString(raw)
                        val valid = when (targetType) {
                            TargetStateType.WECHAT_PAGE_HTML -> unquoted.length > 128 && unquoted.contains("<")
                            else -> unquoted.length > 32 && unquoted.startsWith("{")
                        }
                        if (valid) {
                            if (targetType == TargetStateType.DOUYIN_WEB_DETAIL) {
                                Log.d("WebViewBridge", "DOUYIN_WEB_DETAIL captured len=${unquoted.length} at attempt=$attempt head=${unquoted.take(220)}")
                            }
                            cm.flush()
                            deferred.complete(Result.success(unquoted))
                        } else if (attempt < maxAttempts) {
                            if (targetType == TargetStateType.DOUYIN_WEB_DETAIL && (attempt == 5 || attempt == 10)) {
                                wv.evaluateJavascript(
                                    "JSON.stringify({href:location.href,title:document.title,status:window.__FOLDMEDIA_DETAIL_STATUS__,err:window.__FOLDMEDIA_DETAIL_ERR__})"
                                ) { diag ->
                                    Log.d("WebViewBridge", "DOUYIN_WEB_DETAIL diag($attempt): $diag")
                                }
                            }
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

                    override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, startedUrl, favicon)
                        if (targetType == TargetStateType.DOUYIN_WEB_DETAIL) {
                            view?.evaluateJavascript(douyinHookScript, null)
                        }
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        if (targetType == TargetStateType.DOUYIN_WEB_DETAIL) {
                            view?.evaluateJavascript(douyinHookScript, null)
                        }
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
