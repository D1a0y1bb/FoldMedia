package com.foldmedia.app.parser

import android.content.Context
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.storage.CookieVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Scheme A (100% on-device) multi-stage extraction orchestrator.
 * Combines direct HTTP/JSON SSR parsing, WebView DOM fallback, and `yt-dlp` native engine.
 */
class MediaExtractorRepository(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val cookieVault: CookieVault,
    private val webViewBridge: WebViewBridgeExtractor,
    private val ytDlpEngine: YtDlpEngine
) {

    suspend fun extract(rawInput: String): ExtractedPost = withContext(Dispatchers.IO) {
        val cleanUrl = LinkSanitizer.extractCleanUrl(rawInput)
            ?: throw IllegalArgumentException("未识别到有效的 http/https 链接，请粘贴包含分享链接的文本")

        val resolvedUrl = resolveRedirectUrl(cleanUrl)
        val platform = resolvePlatform(cleanUrl, resolvedUrl)

        when (platform) {
            PlatformType.X_TWITTER -> extractTwitter(rawInput, resolvedUrl)
            PlatformType.XIAOHONGSHU -> extractXiaohongshu(rawInput, resolvedUrl)
            PlatformType.DOUYIN -> extractDouyin(rawInput, resolvedUrl)
            PlatformType.YOUTUBE -> extractWithYtDlp(rawInput, resolvedUrl, PlatformType.YOUTUBE)
            PlatformType.UNKNOWN -> extractWithYtDlp(rawInput, resolvedUrl, PlatformType.UNKNOWN)
        }
    }

    private fun resolvePlatform(initialUrl: String, resolvedUrl: String): PlatformType {
        val fromResolved = LinkSanitizer.detectPlatform(resolvedUrl)
        return if (fromResolved != PlatformType.UNKNOWN) fromResolved else LinkSanitizer.detectPlatform(initialUrl)
    }

    private fun resolveRedirectUrl(url: String): String {
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var current = url
        repeat(8) {
            val req = Request.Builder()
                .url(current)
                .header("User-Agent", MOBILE_SAFARI_UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                .build()
            val nextUrl = runCatching {
                noRedirectClient.newCall(req).execute().use { resp ->
                    val location = if (resp.isRedirect) resp.header("Location") else null
                    if (!location.isNullOrBlank()) {
                        location
                    } else if (current.contains("xhslink.com", ignoreCase = true) ||
                        current.contains("t.co", ignoreCase = true)
                    ) {
                        extractHtmlRedirect(resp.body.string())
                    } else {
                        null
                    }
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return current

            val absoluteNext = if (nextUrl.startsWith("http")) {
                nextUrl
            } else {
                val baseHost = LinkSanitizer.extractHost(current)
                "https://$baseHost${if (nextUrl.startsWith("/")) nextUrl else "/$nextUrl"}"
            }

            // Stop before anti-bot walls so noteId/xsec_token from previous hop stay intact for WebView
            if (absoluteNext.contains("/404", ignoreCase = true) ||
                absoluteNext.contains("/login", ignoreCase = true) ||
                absoluteNext.contains("captcha", ignoreCase = true)
            ) {
                return current
            }
            current = absoluteNext
        }
        return current
    }

    private fun extractHtmlRedirect(html: String): String? {
        if (html.isBlank()) return null
        val metaMatch = Regex(
            """http-equiv=["']refresh["'][^>]*content=["'][^"']*url=([^"'\s>]+)""",
            RegexOption.IGNORE_CASE
        ).find(html)
        if (metaMatch != null) return metaMatch.groupValues[1]
        val jsMatch = Regex(
            """(?:window\.location(?:\.href)?\s*=|location\.replace\()\s*["'](https?://[^"']+)["']"""
        ).find(html)
        return jsMatch?.groupValues?.getOrNull(1)
    }

    private suspend fun extractTwitter(rawInput: String, resolvedUrl: String): ExtractedPost {
        val tweetId = LinkSanitizer.extractTweetId(resolvedUrl)
        var lastError: Throwable? = null

        // Stage 1: FxTwitter v2 API (extracts multi-photos + videos + GIFs cleanly)
        if (!tweetId.isNullOrBlank()) {
            val fxResult = runCatching {
                val body = fetchText("https://api.fxtwitter.com/2/status/$tweetId", DESKTOP_CHROME_UA)
                PlatformJsonParsers.parseFxTwitterJson(body, rawInput, resolvedUrl)
            }
            if (fxResult.isSuccess) return fxResult.getOrThrow()
            lastError = fxResult.exceptionOrNull()

            // Stage 2: X official public syndication endpoint
            val synResult = runCatching {
                val body = fetchText(
                    "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en",
                    DESKTOP_CHROME_UA
                )
                PlatformJsonParsers.parseSyndicationTwitterJson(body, rawInput, resolvedUrl)
            }
            if (synResult.isSuccess) return synResult.getOrThrow()
            lastError = synResult.exceptionOrNull()
        }

        // Stage 3: On-device yt-dlp fallback (useful for video tweets or cookie-gated content)
        return runCatching {
            extractWithYtDlp(rawInput, resolvedUrl, PlatformType.X_TWITTER)
        }.getOrElse { ytErr ->
            throw IllegalStateException(
                "X 推文提取失败：${lastError?.message ?: ytErr.message}（若是敏感/仅登录可见内容，请在「账号与设置」中登录 X）",
                ytErr
            )
        }
    }

    private suspend fun extractXiaohongshu(rawInput: String, resolvedUrl: String): ExtractedPost {
        val noteId = LinkSanitizer.extractXhsNoteId(resolvedUrl)
        val canonicalUrl = if (!noteId.isNullOrBlank() && resolvedUrl.contains("/discovery/item/")) {
            resolvedUrl.replace("/discovery/item/$noteId", "/explore/$noteId")
        } else {
            resolvedUrl
        }
        val cookie = cookieVault.getAuthenticatedCookieHeader(PlatformType.XIAOHONGSHU, canonicalUrl)
        var lastError: Throwable? = null

        // Stage 1: Direct OkHttp SSR HTML extraction (try canonical explore URL & resolvedUrl)
        for (targetUrl in listOf(canonicalUrl, resolvedUrl).distinct()) {
            val directResult = runCatching {
                val html = fetchText(
                    url = targetUrl,
                    userAgent = DESKTOP_CHROME_UA,
                    cookieHeader = cookie,
                    referer = "https://www.xiaohongshu.com/"
                )
                PlatformJsonParsers.parseXiaohongshuInitialState(html, rawInput, resolvedUrl)
            }
            if (directResult.isSuccess) return directResult.getOrThrow()
            lastError = directResult.exceptionOrNull()
        }

        // Stage 2: Real Chromium Desktop WebView DOM extraction with device cookies
        val webViewJson = webViewBridge.extractStateJson(
            url = canonicalUrl,
            targetType = WebViewBridgeExtractor.TargetStateType.XIAOHONGSHU_INITIAL_STATE
        )
        if (webViewJson.isSuccess) {
            val parsed = runCatching {
                PlatformJsonParsers.parseXiaohongshuInitialState(
                    rawHtmlOrJson = webViewJson.getOrThrow(),
                    originalInput = rawInput,
                    resolvedUrl = resolvedUrl
                ).copy(engineUsed = "小红书 WebView 实况解析")
            }
            if (parsed.isSuccess) return parsed.getOrThrow()
            lastError = parsed.exceptionOrNull()
        } else {
            lastError = webViewJson.exceptionOrNull()
        }

        // Stage 3: yt-dlp fallback (works on some XHS video notes)
        return runCatching {
            extractWithYtDlp(rawInput, canonicalUrl, PlatformType.XIAOHONGSHU)
        }.getOrElse {
            val authTip = if (cookie.isBlank()) {
                "当前未检测到小红书真实登录态（web_session），请前往「设置」完成扫码/授权后再试。"
            } else {
                "触发临时风控校验或原笔记不可见，可尝试在分享链接中保留完整口令或重新登录一次。"
            }
            throw IllegalStateException(
                "小红书解析失败：${lastError?.message ?: "需要登录验证"}。$authTip",
                lastError
            )
        }
    }

    private suspend fun extractDouyin(rawInput: String, resolvedUrl: String): ExtractedPost {
        val awemeId = LinkSanitizer.extractDouyinItemId(resolvedUrl)
        val shareUrl = if (!awemeId.isNullOrBlank()) {
            "https://www.iesdouyin.com/share/video/$awemeId/"
        } else {
            resolvedUrl
        }
        val cookie = cookieVault.getAuthenticatedCookieHeader(PlatformType.DOUYIN, resolvedUrl)
        var lastError: Throwable? = null

        // Stage 1: Direct OkHttp iesdouyin share page (_ROUTER_DATA) — try anonymous clean request first if not logged in
        val directResult = runCatching {
            val html = fetchText(
                url = shareUrl,
                userAgent = MOBILE_SAFARI_UA,
                cookieHeader = cookie,
                referer = if (cookie.isNotBlank()) "https://www.douyin.com/" else null
            )
            PlatformJsonParsers.parseDouyinRouterData(html, rawInput, resolvedUrl)
        }
        if (directResult.isSuccess) return directResult.getOrThrow()
        lastError = directResult.exceptionOrNull()

        // Stage 2: Real Chromium WebView DOM extraction across iesdouyin share & original resolved URL
        for (targetUrl in listOf(shareUrl, resolvedUrl).distinct()) {
            val webViewJson = webViewBridge.extractStateJson(
                url = targetUrl,
                targetType = WebViewBridgeExtractor.TargetStateType.DOUYIN_ROUTER_DATA
            )
            if (webViewJson.isSuccess) {
                val parsed = runCatching {
                    PlatformJsonParsers.parseDouyinRouterData(
                        rawHtmlOrJson = webViewJson.getOrThrow(),
                        originalInput = rawInput,
                        resolvedUrl = resolvedUrl
                    ).copy(engineUsed = "抖音 WebView 实况解析")
                }
                if (parsed.isSuccess) return parsed.getOrThrow()
                lastError = parsed.exceptionOrNull()
            } else {
                lastError = webViewJson.exceptionOrNull()
            }
        }

        // Stage 3: yt-dlp fallback for standard videos
        return runCatching {
            extractWithYtDlp(rawInput, resolvedUrl, PlatformType.DOUYIN)
        }.getOrElse {
            throw IllegalStateException(
                "抖音解析失败：${lastError?.message ?: "触发风控校验"}。可在「设置」完成抖音扫码授权后重试。",
                lastError
            )
        }
    }

    private suspend fun extractWithYtDlp(
        rawInput: String,
        resolvedUrl: String,
        platform: PlatformType
    ): ExtractedPost {
        val cookieFile: File? = cookieVault.exportNetscapeCookieFile(platform, appContext.cacheDir)
        return ytDlpEngine.extractPost(
            url = resolvedUrl,
            originalInput = rawInput,
            platform = platform,
            cookieFile = cookieFile
        )
    }

    private fun fetchText(
        url: String,
        userAgent: String,
        cookieHeader: String = "",
        referer: String? = null
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (cookieHeader.isNotBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        httpClient.newCall(builder.build()).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${body.take(120)}")
            }
            return body
        }
    }

    suspend fun downloadBinaryBytes(url: String, platform: PlatformType): ByteArray = withContext(Dispatchers.IO) {
        val cookie = cookieVault.getCookieHeader(platform, url)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_SAFARI_UA)
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
                when (platform) {
                    PlatformType.DOUYIN -> header("Referer", "https://www.douyin.com/")
                    PlatformType.XIAOHONGSHU -> header("Referer", "https://www.xiaohongshu.com/")
                    PlatformType.X_TWITTER -> header("Referer", "https://x.com/")
                    else -> Unit
                }
            }
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("下载资源失败 (HTTP ${resp.code})")
            }
            resp.body.bytes()
        }
    }

    companion object {
        private const val MOBILE_SAFARI_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.50 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
