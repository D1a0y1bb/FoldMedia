package com.foldmedia.app.parser

import android.content.Context
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.QualityOption
import com.foldmedia.app.storage.CookieVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Scheme A (100% on-device) multi-stage extraction orchestrator.
 * Combines direct HTTP/JSON SSR parsing, WebView DOM fallback, and `yt-dlp` + `ffmpeg` native engine.
 */
class MediaExtractorRepository(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val cookieVault: CookieVault,
    private val webViewBridge: WebViewBridgeExtractor,
    private val ytDlpEngine: YtDlpEngine
) {

    private val fastHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun extract(
        rawInput: String,
        onStageUpdate: (String) -> Unit = {}
    ): ExtractedPost = withContext(Dispatchers.IO) {
        val cleanUrl = LinkSanitizer.extractCleanUrl(rawInput)
            ?: throw IllegalArgumentException("未识别到有效的 http/https 链接，请粘贴包含分享链接的文本")

        val resolvedUrl = if (LinkSanitizer.isShortRedirectUrl(cleanUrl)) {
            onStageUpdate("正在展开短链接...")
            resolveRedirectUrl(cleanUrl)
        } else {
            cleanUrl
        }

        if (LinkSanitizer.isHlsStreamUrl(resolvedUrl)) {
            return@withContext buildDirectHlsPost(rawInput, resolvedUrl)
        }

        val platform = resolvePlatform(cleanUrl, resolvedUrl)
        when (platform) {
            PlatformType.X_TWITTER -> extractTwitter(rawInput, resolvedUrl, onStageUpdate)
            PlatformType.XIAOHONGSHU -> extractXiaohongshu(rawInput, resolvedUrl, onStageUpdate)
            PlatformType.DOUYIN -> extractDouyin(rawInput, resolvedUrl, onStageUpdate)
            PlatformType.WECHAT -> extractWeChat(rawInput, resolvedUrl, onStageUpdate)
            PlatformType.YOUTUBE -> {
                onStageUpdate("yt-dlp 本地解析油管流中...")
                extractWithYtDlp(rawInput, resolvedUrl, PlatformType.YOUTUBE)
            }
            PlatformType.UNKNOWN -> {
                onStageUpdate("通用本地引擎解析中...")
                extractWithYtDlp(rawInput, resolvedUrl, PlatformType.UNKNOWN)
            }
        }
    }

    private fun buildDirectHlsPost(rawInput: String, hlsUrl: String): ExtractedPost {
        val platform = LinkSanitizer.detectPlatform(hlsUrl)
        val options = listOf(
            QualityOption(
                id = "hls_best_mp4",
                label = "HLS 音视频合并 (最高画质 MP4)",
                formatSelector = "bestvideo+bestaudio/best",
                resolution = "MP4"
            ),
            QualityOption(
                id = "audio_m4a",
                label = "仅提取音频 (M4A)",
                formatSelector = "bestaudio/best",
                isAudioOnly = true,
                resolution = "M4A"
            )
        )
        val item = MediaItem(
            id = "hls_${System.currentTimeMillis()}",
            index = 0,
            kind = MediaKind.VIDEO,
            title = "HLS / m3u8 视频流",
            videoUrl = hlsUrl,
            qualityOptions = options,
            selectedQualityId = options.first().id,
            requiresYtDlpDownload = true
        )
        return ExtractedPost(
            id = item.id,
            platform = platform,
            originalInput = rawInput,
            resolvedUrl = hlsUrl,
            title = "m3u8 音视频合流任务",
            caption = hlsUrl,
            authorName = "",
            items = listOf(item),
            engineUsed = "HLS / FFmpeg 合并"
        )
    }

    private fun resolvePlatform(initialUrl: String, resolvedUrl: String): PlatformType {
        val fromResolved = LinkSanitizer.detectPlatform(resolvedUrl)
        return if (fromResolved != PlatformType.UNKNOWN) fromResolved else LinkSanitizer.detectPlatform(initialUrl)
    }

    private fun resolveRedirectUrl(url: String): String {
        val noRedirectClient = fastHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var current = url
        repeat(6) {
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

    private suspend fun extractTwitter(
        rawInput: String,
        resolvedUrl: String,
        onStageUpdate: (String) -> Unit
    ): ExtractedPost {
        val tweetId = LinkSanitizer.extractTweetId(resolvedUrl)
        val hasXCookie = cookieVault.getAuthenticatedCookieHeader(PlatformType.X_TWITTER, resolvedUrl).isNotBlank()
        var lastError: Throwable? = null

        if (!tweetId.isNullOrBlank()) {
            // Stage 1: FxTwitter v2 API
            onStageUpdate("正在直连 FxTwitter...")
            val fxResult = runCatching {
                val body = fetchText("https://api.fxtwitter.com/2/status/$tweetId", DESKTOP_CHROME_UA, useFastClient = true)
                PlatformJsonParsers.parseFxTwitterJson(body, rawInput, resolvedUrl)
            }
            if (fxResult.isSuccess) return fxResult.getOrThrow()
            lastError = fxResult.exceptionOrNull()

            // Stage 2: VxTwitter mirror (supports many sensitive/NSFW public tweets & HLS streams)
            onStageUpdate("正在尝试 VxTwitter 镜像...")
            val vxResult = runCatching {
                val body = fetchText("https://api.vxtwitter.com/Twitter/status/$tweetId", DESKTOP_CHROME_UA, useFastClient = true)
                PlatformJsonParsers.parseVxTwitterJson(body, rawInput, resolvedUrl)
            }
            if (vxResult.isSuccess) return vxResult.getOrThrow()
            lastError = vxResult.exceptionOrNull()

            // Stage 3: X Syndication endpoint with computed token
            onStageUpdate("正在请求 X Syndication...")
            val token = LinkSanitizer.computeTwitterSyndicationToken(tweetId)
            val synResult = runCatching {
                val body = fetchText(
                    url = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en&token=$token",
                    userAgent = DESKTOP_CHROME_UA,
                    useFastClient = true
                )
                PlatformJsonParsers.parseSyndicationTwitterJson(body, rawInput, resolvedUrl)
            }
            if (synResult.isSuccess) return synResult.getOrThrow()
            lastError = synResult.exceptionOrNull()
        }

        val errMsg = lastError?.message.orEmpty()
        val isExplicitLoginWall = errMsg.contains("401") ||
            errMsg.contains("403") ||
            errMsg.contains("NSFW", ignoreCase = true) ||
            errMsg.contains("sensitive", ignoreCase = true) ||
            errMsg.contains("adult", ignoreCase = true) ||
            errMsg.contains("protected", ignoreCase = true) ||
            errMsg.contains("PRIVATE", ignoreCase = true) ||
            errMsg.contains("not found", ignoreCase = true)

        if (!hasXCookie && isExplicitLoginWall) {
            throw IllegalStateException(
                "该推文为敏感/成人或受限内容，免登镜像已被 X 官方拦截。请前往「设置 -> X / Twitter」登录后再提取。"
            )
        }

        // Stage 4: Timeout-protected yt-dlp fallback (supports authenticated X cookies + HLS merging)
        onStageUpdate("本地 yt-dlp 引擎兜底解析中...")
        return runCatching {
            extractWithYtDlp(rawInput, resolvedUrl, PlatformType.X_TWITTER, timeoutMs = 28_000L)
        }.getOrElse { ytErr ->
            val hint = if (hasXCookie) {
                "请检查网络连接或代理可用性，或尝试在「设置」重新登录 X"
            } else {
                "若为敏感/18+内容或网络不可达，请在「设置」登录 X 或检查代理后重试"
            }
            throw IllegalStateException(
                "X 推文提取失败：${lastError?.message ?: ytErr.message}（$hint）",
                ytErr
            )
        }
    }

    private suspend fun extractWeChat(
        rawInput: String,
        resolvedUrl: String,
        onStageUpdate: (String) -> Unit
    ): ExtractedPost {
        onStageUpdate("正在解析微信页面...")
        val directResult = runCatching {
            val html = fetchText(
                url = resolvedUrl,
                userAgent = WECHAT_MOBILE_UA,
                referer = "https://mp.weixin.qq.com/",
                useFastClient = true
            )
            PlatformJsonParsers.parseWeChatHtml(html, rawInput, resolvedUrl)
        }
        if (directResult.isSuccess) return directResult.getOrThrow()
        var lastError = directResult.exceptionOrNull()

        onStageUpdate("正在使用 WebView 渲染微信页面...")
        val webViewHtml = webViewBridge.extractStateJson(
            url = resolvedUrl,
            targetType = WebViewBridgeExtractor.TargetStateType.WECHAT_PAGE_HTML,
            timeoutMs = 14_000L
        )
        if (webViewHtml.isSuccess) {
            val parsed = runCatching {
                PlatformJsonParsers.parseWeChatHtml(webViewHtml.getOrThrow(), rawInput, resolvedUrl)
                    .copy(engineUsed = "微信 WebView 本地解析")
            }
            if (parsed.isSuccess) return parsed.getOrThrow()
            lastError = parsed.exceptionOrNull()
        } else {
            lastError = webViewHtml.exceptionOrNull()
        }

        throw IllegalStateException(
            "微信页面解析失败：${lastError?.message ?: "请确认链接为公开的微信公众号文章或贴图页面"}",
            lastError
        )
    }

    private suspend fun extractXiaohongshu(
        rawInput: String,
        resolvedUrl: String,
        onStageUpdate: (String) -> Unit
    ): ExtractedPost {
        val noteId = LinkSanitizer.extractXhsNoteId(resolvedUrl)
        val canonicalUrl = if (!noteId.isNullOrBlank() && resolvedUrl.contains("/discovery/item/")) {
            resolvedUrl.replace("/discovery/item/$noteId", "/explore/$noteId")
        } else {
            resolvedUrl
        }
        val cookie = cookieVault.getAuthenticatedCookieHeader(PlatformType.XIAOHONGSHU, canonicalUrl)
        var lastError: Throwable? = null

        onStageUpdate("正在解析小红书图文/实况...")
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

        onStageUpdate("正在通过 WebView 提取小红书...")
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

        onStageUpdate("本地 yt-dlp 引擎兜底解析中...")
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

    private suspend fun extractDouyin(
        rawInput: String,
        resolvedUrl: String,
        onStageUpdate: (String) -> Unit
    ): ExtractedPost {
        val awemeId = LinkSanitizer.extractDouyinItemId(resolvedUrl)
        val shareKind = LinkSanitizer.detectDouyinShareKind(resolvedUrl)
        val isLikelyLiveCarousel = shareKind == "slides" ||
            shareKind == "note" ||
            resolvedUrl.contains("is_slides=1", ignoreCase = true)
        val shareCandidates = if (!awemeId.isNullOrBlank()) {
            when (shareKind) {
                "slides" -> listOf(
                    "https://www.iesdouyin.com/share/slides/$awemeId/",
                    "https://www.iesdouyin.com/share/note/$awemeId/",
                    "https://www.iesdouyin.com/share/video/$awemeId/"
                )
                "note" -> listOf(
                    "https://www.iesdouyin.com/share/note/$awemeId/",
                    "https://www.iesdouyin.com/share/slides/$awemeId/",
                    "https://www.iesdouyin.com/share/video/$awemeId/"
                )
                else -> listOf(
                    "https://www.iesdouyin.com/share/video/$awemeId/",
                    "https://www.iesdouyin.com/share/slides/$awemeId/"
                )
            }
        } else {
            listOf(resolvedUrl)
        }

        val cookie = cookieVault.getAuthenticatedCookieHeader(PlatformType.DOUYIN, resolvedUrl)
        var lastError: Throwable? = null
        var bestFallbackPost: ExtractedPost? = null

        onStageUpdate("正在解析抖音视频/实况图集...")
        for (targetUrl in shareCandidates) {
            val directResult = runCatching {
                val html = fetchText(
                    url = targetUrl,
                    userAgent = MOBILE_SAFARI_UA,
                    cookieHeader = cookie,
                    referer = if (cookie.isNotBlank()) "https://www.douyin.com/" else null
                )
                PlatformJsonParsers.parseDouyinRouterData(html, rawInput, resolvedUrl)
            }
            if (directResult.isSuccess) {
                val candidate = directResult.getOrThrow()
                if (candidate.livePhotoCount > 0 || candidate.videoCount > 0) {
                    return candidate
                }
                if (bestFallbackPost == null) bestFallbackPost = candidate
                if (!isLikelyLiveCarousel && shareKind == "video" && targetUrl.contains("/share/video/")) {
                    return candidate
                }
            } else {
                lastError = directResult.exceptionOrNull()
            }
        }

        // For slides/notes (or image carousels where mobile SSR stripped Live Photo streams),
        // fetch Douyin's PC Web detail API via on-device Chromium WebView so bdms signs the request.
        if (!awemeId.isNullOrBlank() && (isLikelyLiveCarousel || (bestFallbackPost != null && bestFallbackPost.livePhotoCount == 0))) {
            onStageUpdate("正在提取抖音实况...")
            val pcDetailUrls = if (isLikelyLiveCarousel) {
                listOf("https://www.douyin.com/note/$awemeId")
            } else {
                listOf("https://www.douyin.com/note/$awemeId", "https://www.douyin.com/video/$awemeId")
            }
            for (pcUrl in pcDetailUrls) {
                val webDetailJson = webViewBridge.extractStateJson(
                    url = pcUrl,
                    targetType = WebViewBridgeExtractor.TargetStateType.DOUYIN_WEB_DETAIL,
                    timeoutMs = 14_000L
                )
                if (webDetailJson.isSuccess) {
                    val parsed = runCatching {
                        PlatformJsonParsers.parseDouyinRouterData(
                            rawHtmlOrJson = webDetailJson.getOrThrow(),
                            originalInput = rawInput,
                            resolvedUrl = resolvedUrl
                        ).copy(engineUsed = "抖音高清实况解析")
                    }
                    if (parsed.isSuccess) {
                        val candidate = parsed.getOrThrow()
                        if (candidate.livePhotoCount > 0 || candidate.videoCount > 0 || bestFallbackPost == null) {
                            return candidate
                        }
                    } else {
                        lastError = parsed.exceptionOrNull()
                    }
                } else {
                    lastError = webDetailJson.exceptionOrNull()
                }
            }
        }

        if (bestFallbackPost != null) return bestFallbackPost

        onStageUpdate("正在通过 WebView 提取抖音实况...")
        for (targetUrl in (shareCandidates + resolvedUrl).distinct()) {
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

        onStageUpdate("本地 yt-dlp 引擎兜底解析中...")
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
        platform: PlatformType,
        timeoutMs: Long = 30_000L
    ): ExtractedPost {
        val cookieFile: File? = cookieVault.exportNetscapeCookieFile(platform, appContext.cacheDir)
        return ytDlpEngine.extractPost(
            url = resolvedUrl,
            originalInput = rawInput,
            platform = platform,
            cookieFile = cookieFile,
            timeoutMs = timeoutMs
        )
    }

    private fun fetchText(
        url: String,
        userAgent: String,
        cookieHeader: String = "",
        referer: String? = null,
        useFastClient: Boolean = false
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
        val client = if (useFastClient) fastHttpClient else httpClient
        client.newCall(builder.build()).execute().use { resp ->
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
                    PlatformType.WECHAT -> header("Referer", "https://mp.weixin.qq.com/")
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
        private const val WECHAT_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 15; Xiaomi 18 Fold) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50"
    }
}
