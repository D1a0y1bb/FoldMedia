package com.foldmedia.app.parser

import com.foldmedia.app.model.PlatformType

/**
 * Pure Kotlin URL sanitizer, share-text cleaner, and platform detector.
 * Handles messy Chinese share texts from Douyin/Xiaohongshu as well as X/YouTube links.
 */
object LinkSanitizer {

    private val urlRegex = Regex(
        """https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
        RegexOption.IGNORE_CASE
    )

    private val trailingTrimChars = charArrayOf(
        ',', '.', '!', '?', ';', ':', ')', ']', '}', '"', '\'',
        '，', '。', '！', '？', '；', '：', '）', '】', '」', '》', '、'
    )

    /** Extracts the first clean HTTP/HTTPS URL from arbitrary share text. */
    fun extractCleanUrl(rawInput: String): String? {
        val cleanedInput = rawInput
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .trim()
        val rawMatch = urlRegex.find(cleanedInput)?.value ?: return null
        return rawMatch.trimEnd(*trailingTrimChars)
    }

    /** Detects which supported platform a URL belongs to. */
    fun detectPlatform(url: String): PlatformType {
        val host = extractHost(url).lowercase()
        return when {
            host.contains("douyin.com") || host.contains("iesdouyin.com") -> PlatformType.DOUYIN
            host.contains("xiaohongshu.com") || host.contains("xhslink.com") -> PlatformType.XIAOHONGSHU
            host == "x.com" || host.endsWith(".x.com") ||
                host.contains("twitter.com") || host.contains("fxtwitter.com") ||
                host.contains("vxtwitter.com") || host == "t.co" ||
                host == "video.twimg.com" || host == "pbs.twimg.com" -> PlatformType.X_TWITTER
            host.contains("youtube.com") || host == "youtu.be" -> PlatformType.YOUTUBE
            host.contains("mp.weixin.qq.com") || host.contains("sticker.weixin.qq.com") ||
                host == "weixin.qq.com" || host.endsWith(".weixin.qq.com") ||
                host == "mmbiz.qpic.cn" || host == "mmemoticon.qpic.cn" ||
                host == "emoticon.qpic.cn" || host == "wx.qq.com" -> PlatformType.WECHAT
            else -> PlatformType.UNKNOWN
        }
    }

    fun isShortRedirectUrl(url: String): Boolean {
        val host = extractHost(url).lowercase()
        return host == "v.douyin.com" ||
            host == "xhslink.com" ||
            host.endsWith(".xhslink.com") ||
            host == "t.co" ||
            host == "youtu.be" ||
            host == "b23.tv" ||
            host == "dwz.cn" ||
            (host.contains("iesdouyin.com") && extractDouyinItemId(url) == null)
    }

    fun isHlsStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u8") || lower.contains(".m3u8")
    }

    fun detectDouyinShareKind(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("/slides/") -> "slides"
            lower.contains("/note/") -> "note"
            else -> "video"
        }
    }

    fun extractWeChatArticleId(url: String): String? {
        val shortMatch = Regex("""/s/([A-Za-z0-9_-]+)""").find(url)
        if (shortMatch != null) return shortMatch.groupValues[1]
        val midMatch = Regex("""[?&](?:mid|__biz|product_id|pid)=([^&#]+)""").find(url)
        return midMatch?.groupValues?.getOrNull(1)
    }

    fun normalizeWeChatImageUrl(rawUrl: String): String {
        var url = rawUrl.trim()
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        url = normalizeHttps(url)
        if (url.contains("mmbiz.qpic.cn") || url.contains("mmbiz.qlogo.cn")) {
            url = url.replace(Regex("""/640(\?|$)"""), "/0$1")
            url = url.replace(Regex("""([?&])tp=webp(?:&|$)""")) { m ->
                if (m.groupValues[1] == "?" && m.value.endsWith("&")) "?" else if (m.value.endsWith("&")) "&" else ""
            }.trimEnd('?', '&')
        }
        return url
    }

    /**
     * Computes X/Twitter public syndication endpoint token:
     * `((Number(tweetId) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '')`
     */
    fun computeTwitterSyndicationToken(tweetId: String): String {
        val idNum = tweetId.toDoubleOrNull() ?: return "1"
        val value = (idNum / 1e15) * Math.PI
        if (!value.isFinite() || value <= 0.0) return "1"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        var intPart = value.toLong()
        if (intPart == 0L) {
            sb.append('0')
        } else {
            val intDigits = StringBuilder()
            while (intPart > 0L) {
                intDigits.append(chars[(intPart % 36L).toInt()])
                intPart /= 36L
            }
            sb.append(intDigits.reverse())
        }
        var frac = value - value.toLong().toDouble()
        if (frac > 0.0) {
            sb.append('.')
            repeat(12) {
                frac *= 36.0
                val digit = frac.toInt().coerceIn(0, 35)
                sb.append(chars[digit])
                frac -= digit.toDouble()
                if (frac <= 1e-12) return@repeat
            }
        }
        return sb.toString().replace("0", "").replace(".", "").ifBlank { "1" }
    }

    fun extractHost(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':')
    }

    fun extractTweetId(url: String): String? {
        val match = Regex("""/(?:status|statuses)/(\d+)""").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    fun extractDouyinItemId(url: String): String? {
        val pathMatch = Regex("""/(?:video|note|slides)/(\d+)""").find(url)
        if (pathMatch != null) return pathMatch.groupValues[1]
        val queryMatch = Regex("""[?&]modal_id=(\d+)""").find(url)
        return queryMatch?.groupValues?.getOrNull(1)
    }

    fun extractXhsNoteId(url: String): String? {
        val match = Regex("""/(?:explore|discovery/item|notes?)/([A-Za-z0-9]+)""").find(url)
        if (match != null) return match.groupValues[1]
        val profileNote = Regex("""/user/profile/[A-Za-z0-9]+/([A-Za-z0-9]+)""").find(url)
        return profileNote?.groupValues?.getOrNull(1)
    }

    fun extractYouTubeVideoId(url: String): String? {
        val shortMatch = Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""").find(url)
        if (shortMatch != null) return shortMatch.groupValues[1]
        val pathMatch = Regex("""/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{6,})""").find(url)
        if (pathMatch != null) return pathMatch.groupValues[1]
        val watchMatch = Regex("""[?&]v=([A-Za-z0-9_-]{6,})""").find(url)
        return watchMatch?.groupValues?.getOrNull(1)
    }

    /** Ensures X/Twitter image URLs request original uncompressed quality (`name=orig`). */
    fun upgradeTwitterImageToOriginal(url: String): String {
        if (!url.contains("pbs.twimg.com/media/")) return normalizeHttps(url)
        val normalized = normalizeHttps(url)
        return when {
            normalized.contains("name=") -> normalized.replace(Regex("""name=[A-Za-z0-9_]+"""), "name=orig")
            normalized.contains("?") -> "$normalized&name=orig"
            else -> "$normalized?name=orig"
        }
    }

    fun normalizeHttps(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") && !trimmed.contains("127.0.0.1") ->
                "https://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
    }

    /** Replaces Douyin watermarked path markers with clean playback path. */
    fun stripDouyinWatermark(url: String): String {
        return normalizeHttps(url).replace("/playwm/", "/play/")
    }
}
