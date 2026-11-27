package com.foldmedia.app.storage

import android.content.Context
import android.webkit.CookieManager
import com.foldmedia.app.model.PlatformType
import java.io.File

/**
 * Manages locally persisted WebView cookies and optional manual Cookie overrides.
 * Distinguishes anonymous visitor/anti-bot cookies (e.g., `a1`, `ttwid`, `guest_id`, `YSC`)
 * from real authenticated session tokens (`web_session`, `sessionid`, `auth_token`, `SAPISID`).
 */
class CookieVault(context: Context) {

    private val prefs = context.getSharedPreferences("foldmedia_cookies", Context.MODE_PRIVATE)

    fun getCookieHeader(platform: PlatformType, url: String? = null): String {
        val manual = prefs.getString("manual_${platform.name}", "").orEmpty().trim()
        if (manual.isNotBlank()) return manual

        val urls = buildList {
            if (!url.isNullOrBlank()) add(url)
            addAll(domainUrlsFor(platform))
        }.distinct()

        val merged = LinkedHashMap<String, String>()
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return ""
        for (target in urls) {
            val raw = runCatching { cm.getCookie(target).orEmpty() }.getOrDefault("")
            if (raw.isBlank()) continue
            raw.split(';').forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        merged.putIfAbsent(key, value)
                    }
                }
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Returns true ONLY when user saved a manual cookie OR WebView holds a genuine logged-in
     * session cookie for [platform] — anonymous visitor cookies return false.
     */
    fun hasCookie(platform: PlatformType): Boolean {
        val manual = prefs.getString("manual_${platform.name}", "").orEmpty().trim()
        if (manual.isNotBlank()) return true
        return hasAuthenticatedCookieKeys(getCookieHeader(platform), platform)
    }

    /**
     * Returns the cookie header ONLY when genuinely authenticated, preventing stale/visitor
     * cookies from poisoning anonymous SSR / yt-dlp requests.
     */
    fun getAuthenticatedCookieHeader(platform: PlatformType, url: String? = null): String {
        val manual = prefs.getString("manual_${platform.name}", "").orEmpty().trim()
        if (manual.isNotBlank()) return manual
        val full = getCookieHeader(platform, url)
        return if (hasAuthenticatedCookieKeys(full, platform)) full else ""
    }

    fun setManualCookie(platform: PlatformType, cookieHeader: String) {
        prefs.edit().putString("manual_${platform.name}", cookieHeader.trim()).apply()
    }

    fun getManualCookie(platform: PlatformType): String {
        return prefs.getString("manual_${platform.name}", "").orEmpty()
    }

    fun clearPlatformCookies(platform: PlatformType) {
        prefs.edit().remove("manual_${platform.name}").apply()
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        domainUrlsFor(platform).forEach { target ->
            val raw = cm.getCookie(target).orEmpty()
            raw.split(';').forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotBlank()) {
                    cm.setCookie(target, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        cm.flush()
    }

    /**
     * Exports authenticated platform cookies in Netscape HTTP Cookie File format for yt-dlp `--cookies`.
     */
    fun exportNetscapeCookieFile(platform: PlatformType, cacheDir: File): File? {
        val raw = getAuthenticatedCookieHeader(platform)
        if (raw.isBlank()) return null
        val domain = when (platform) {
            PlatformType.DOUYIN -> ".douyin.com"
            PlatformType.XIAOHONGSHU -> ".xiaohongshu.com"
            PlatformType.X_TWITTER -> ".x.com"
            PlatformType.YOUTUBE -> ".youtube.com"
            PlatformType.UNKNOWN -> return null
        }
        val file = File(cacheDir, "cookies_${platform.name.lowercase()}.txt")
        val lines = buildString {
            appendLine("# Netscape HTTP Cookie File")
            raw.split(';').forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val name = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (name.isNotBlank()) {
                        appendLine("$domain\tTRUE\t/\tTRUE\t0\t$name\t$value")
                    }
                }
            }
        }
        file.writeText(lines, Charsets.UTF_8)
        return file
    }

    private fun domainUrlsFor(platform: PlatformType): List<String> = when (platform) {
        PlatformType.XIAOHONGSHU -> listOf(
            "https://www.xiaohongshu.com/",
            "https://edith.xiaohongshu.com/",
            "https://xiaohongshu.com/"
        )
        PlatformType.DOUYIN -> listOf(
            "https://www.douyin.com/",
            "https://sso.douyin.com/",
            "https://www.iesdouyin.com/",
            "https://douyin.com/"
        )
        PlatformType.X_TWITTER -> listOf(
            "https://x.com/",
            "https://twitter.com/",
            "https://api.x.com/"
        )
        PlatformType.YOUTUBE -> listOf(
            "https://m.youtube.com/",
            "https://www.youtube.com/",
            "https://accounts.google.com/"
        )
        PlatformType.UNKNOWN -> emptyList()
    }

    companion object {
        private val XHS_AUTH_KEYS = setOf("web_session")
        private val DOUYIN_AUTH_KEYS = setOf("sessionid", "sessionid_ss", "sid_tt", "uid_tt", "sid_guard")
        private val X_AUTH_KEYS = setOf("auth_token")
        private val YOUTUBE_AUTH_KEYS = setOf("SAPISID", "__Secure-3PSID", "__Secure-1PSID", "SID")

        fun hasAuthenticatedCookieKeys(rawCookie: String, platform: PlatformType): Boolean {
            if (rawCookie.isBlank()) return false
            val presentKeys = rawCookie.split(';')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else {
                        val k = part.substring(0, idx).trim()
                        val v = part.substring(idx + 1).trim()
                        if (k.isNotBlank() && v.isNotBlank()) k else null
                    }
                }
                .toSet()

            val expectedKeys = when (platform) {
                PlatformType.XIAOHONGSHU -> XHS_AUTH_KEYS
                PlatformType.DOUYIN -> DOUYIN_AUTH_KEYS
                PlatformType.X_TWITTER -> X_AUTH_KEYS
                PlatformType.YOUTUBE -> YOUTUBE_AUTH_KEYS
                PlatformType.UNKNOWN -> return false
            }
            return presentKeys.any { key ->
                expectedKeys.any { target -> target.equals(key, ignoreCase = false) }
            }
        }
    }
}
