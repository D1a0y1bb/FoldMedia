package com.foldmedia.app.parser

import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure-Kotlin JSON & HTML-embedded-state parsers for X (Twitter), Xiaohongshu, and Douyin.
 * Built on kotlinx.serialization so all parsing logic can be tested cleanly on the JVM.
 */
object PlatformJsonParsers {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses responses from `https://api.fxtwitter.com/2/status/{id}` (or v1 `/user/status/{id}`).
     * Supports multi-photo tweets, videos, GIFs, and mixed-media tweets.
     */
    fun parseFxTwitterJson(
        rawJson: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 200
        if (code != 200) {
            val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown FxTwitter error ($code)"
            throw IllegalStateException("X 解析失败: $msg")
        }
        val tweet = root["tweet"]?.jsonObject
            ?: throw IllegalStateException("X 响应中缺少 tweet 字段")

        val tweetId = tweet["id"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractTweetId(resolvedUrl)
            ?: "x_${System.currentTimeMillis()}"
        val caption = tweet["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorObj = tweet["author"]?.jsonObject
        val authorName = authorObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorHandle = authorObj?.get("screen_name")?.jsonPrimitive?.contentOrNull
            ?.let { if (it.isNotBlank()) "@$it" else "" }
            .orEmpty()

        val items = mutableListOf<MediaItem>()
        val mediaObj = tweet["media"]?.jsonObject

        val photos = mediaObj?.get("photos")?.asArrayOrNull().orEmpty()
        photos.forEachIndexed { idx, el ->
            val photo = el.jsonObject
            val url = photo["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            items += MediaItem(
                id = "${tweetId}_photo_$idx",
                index = items.size,
                kind = MediaKind.IMAGE,
                title = "图片 ${items.size + 1}",
                imageUrl = LinkSanitizer.upgradeTwitterImageToOriginal(url),
                width = photo["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = photo["height"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        val videos = mediaObj?.get("videos")?.asArrayOrNull().orEmpty()
        videos.forEachIndexed { idx, el ->
            val video = el.jsonObject
            val bestUrl = selectBestFxVideoUrl(video) ?: return@forEachIndexed
            val thumb = video["thumbnail_url"]?.jsonPrimitive?.contentOrNull
                ?.let(LinkSanitizer::normalizeHttps)
            val durationSec = video["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            items += MediaItem(
                id = "${tweetId}_video_$idx",
                index = items.size,
                kind = MediaKind.VIDEO,
                title = "视频 ${idx + 1}",
                imageUrl = thumb,
                videoUrl = LinkSanitizer.normalizeHttps(bestUrl),
                width = video["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = video["height"]?.jsonPrimitive?.intOrNull ?: 0,
                durationMs = (durationSec * 1000).toLong()
            )
        }

        // Some payloads put items directly under media.all
        if (items.isEmpty()) {
            val allMedia = mediaObj?.get("all")?.asArrayOrNull().orEmpty()
            allMedia.forEachIndexed { idx, el ->
                val node = el.jsonObject
                val type = node["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = node["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                if (type == "video" || type == "gif") {
                    items += MediaItem(
                        id = "${tweetId}_all_$idx",
                        index = items.size,
                        kind = MediaKind.VIDEO,
                        title = if (type == "gif") "动图视频 ${items.size + 1}" else "视频 ${items.size + 1}",
                        imageUrl = node["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                        videoUrl = LinkSanitizer.normalizeHttps(url),
                        width = node["width"]?.jsonPrimitive?.intOrNull ?: 0,
                        height = node["height"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                } else {
                    items += MediaItem(
                        id = "${tweetId}_all_$idx",
                        index = items.size,
                        kind = MediaKind.IMAGE,
                        title = "图片 ${items.size + 1}",
                        imageUrl = LinkSanitizer.upgradeTwitterImageToOriginal(url),
                        width = node["width"]?.jsonPrimitive?.intOrNull ?: 0,
                        height = node["height"]?.jsonPrimitive?.intOrNull ?: 0
                    )
                }
            }
        }

        if (items.isEmpty()) {
            throw IllegalStateException("该推文不包含可提取的图片或视频")
        }

        val title = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
            ?: if (authorName.isNotBlank()) "$authorName 的推文" else "X 推文 $tweetId"

        return ExtractedPost(
            id = tweetId,
            platform = PlatformType.X_TWITTER,
            originalInput = originalInput,
            resolvedUrl = resolvedUrl,
            title = title,
            caption = caption,
            authorName = authorName,
            authorHandle = authorHandle,
            items = items,
            engineUsed = "FxTwitter 本地直连"
        )
    }

    private fun selectBestFxVideoUrl(videoObj: JsonObject): String? {
        val variants = videoObj["variants"]?.asArrayOrNull()
        if (!variants.isNullOrEmpty()) {
            val mp4Best = variants.mapNotNull { it as? JsonObject }
                .filter { v ->
                    val ct = v["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    ct.contains("mp4", ignoreCase = true) ||
                        v["url"]?.jsonPrimitive?.contentOrNull?.contains(".mp4") == true
                }
                .maxByOrNull { it["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L }
                ?.get("url")?.jsonPrimitive?.contentOrNull
            if (!mp4Best.isNullOrBlank()) return mp4Best
        }
        return videoObj["url"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Fallback parser for `https://cdn.syndication.twimg.com/tweet-result?id={id}&lang=en`.
     */
    fun parseSyndicationTwitterJson(
        rawJson: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val tweetId = root["id_str"]?.jsonPrimitive?.contentOrNull
            ?: root["id"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractTweetId(resolvedUrl)
            ?: "x_${System.currentTimeMillis()}"
        val caption = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userObj = root["user"]?.jsonObject
        val authorName = userObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorHandle = userObj?.get("screen_name")?.jsonPrimitive?.contentOrNull
            ?.let { if (it.isNotBlank()) "@$it" else "" }
            .orEmpty()

        val items = mutableListOf<MediaItem>()
        root["photos"]?.asArrayOrNull()?.forEachIndexed { idx, el ->
            val photo = el.jsonObject
            val url = photo["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            items += MediaItem(
                id = "${tweetId}_syn_photo_$idx",
                index = items.size,
                kind = MediaKind.IMAGE,
                title = "图片 ${items.size + 1}",
                imageUrl = LinkSanitizer.upgradeTwitterImageToOriginal(url),
                width = photo["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = photo["height"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        val videoObj = root["video"]?.asObjectOrNull()
        if (videoObj != null) {
            val variants = videoObj["variants"]?.asArrayOrNull().orEmpty()
            val bestVariantUrl = variants.mapNotNull { it as? JsonObject }
                .filter { v ->
                    val type = v["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    type.contains("mp4", ignoreCase = true) ||
                        v["src"]?.jsonPrimitive?.contentOrNull?.contains(".mp4") == true
                }
                .lastOrNull()
                ?.get("src")?.jsonPrimitive?.contentOrNull
            val mediaUrl = bestVariantUrl ?: videoObj["mediaUrl"]?.jsonPrimitive?.contentOrNull
            if (!mediaUrl.isNullOrBlank()) {
                items += MediaItem(
                    id = "${tweetId}_syn_video",
                    index = items.size,
                    kind = MediaKind.VIDEO,
                    title = "推文视频",
                    imageUrl = videoObj["poster"]?.jsonPrimitive?.contentOrNull
                        ?.let(LinkSanitizer::normalizeHttps),
                    videoUrl = LinkSanitizer.normalizeHttps(mediaUrl),
                    durationMs = videoObj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            }
        }

        if (items.isEmpty()) {
            throw IllegalStateException("Syndication 响应中未找到图片或视频")
        }

        return ExtractedPost(
            id = tweetId,
            platform = PlatformType.X_TWITTER,
            originalInput = originalInput,
            resolvedUrl = resolvedUrl,
            title = caption.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "X 推文 $tweetId",
            caption = caption,
            authorName = authorName,
            authorHandle = authorHandle,
            items = items,
            engineUsed = "X Syndication 本地"
        )
    }

    /**
     * Parses Xiaohongshu HTML or raw `window.__INITIAL_STATE__` JSON string.
     * Supports watermark-free original images, Live Photos (image + H.264/H.265 stream), and video notes.
     */
    fun parseXiaohongshuInitialState(
        rawHtmlOrJson: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val rawJson = extractAssignedJson(
            source = rawHtmlOrJson,
            markers = listOf("window.__INITIAL_STATE__=", "window.__INITIAL_STATE__ =")
        ) ?: throw IllegalStateException("小红书页面未包含 __INITIAL_STATE__，可能需要登录或触发人机验证")

        // Xiaohongshu SSR embeds literal `undefined` inside JSON objects
        val sanitizedJson = sanitizeJsUndefined(rawJson)
        val root = json.parseToJsonElement(sanitizedJson).jsonObject

        val noteDetailMap = root["note"]?.asObjectOrNull()
            ?.get("noteDetailMap")?.asObjectOrNull()
            ?: throw IllegalStateException("小红书数据中缺少 noteDetailMap（可能需在「引擎与账号」中登录小红书）")

        val noteContainer = noteDetailMap.entries.firstOrNull { (_, value) ->
            value.asObjectOrNull()?.get("note")?.asObjectOrNull() != null
        }?.value?.asObjectOrNull()
            ?: throw IllegalStateException("未能从 noteDetailMap 中定位笔记详情")

        val note = noteContainer["note"]?.asObjectOrNull()
            ?: throw IllegalStateException("笔记对象为空")

        val noteId = note["noteId"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractXhsNoteId(resolvedUrl)
            ?: "xhs_${System.currentTimeMillis()}"
        val title = note["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val desc = note["desc"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val noteType = note["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userObj = note["user"]?.asObjectOrNull()
        val authorName = userObj?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty()

        val items = mutableListOf<MediaItem>()

        // Video note handling
        val videoObj = note["video"]?.asObjectOrNull()
        val videoStreamUrls = extractXhsVideoStreams(videoObj)
        if (noteType == "video" || (videoStreamUrls.isNotEmpty() && note["imageList"]?.asArrayOrNull().isNullOrEmpty())) {
            val firstImage = note["imageList"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
            val coverUrl = extractXhsImageUrl(firstImage)
            if (videoStreamUrls.isNotEmpty()) {
                items += MediaItem(
                    id = "${noteId}_video",
                    index = 0,
                    kind = MediaKind.VIDEO,
                    title = title.ifBlank { "小红书视频" },
                    imageUrl = coverUrl,
                    videoUrl = videoStreamUrls.first(),
                    backupVideoUrls = videoStreamUrls.drop(1),
                    width = firstImage?.get("width")?.jsonPrimitive?.intOrNull ?: 0,
                    height = firstImage?.get("height")?.jsonPrimitive?.intOrNull ?: 0
                )
            }
        }

        // Multi-image / Live Photo carousel handling
        if (items.isEmpty()) {
            val imageList = note["imageList"]?.asArrayOrNull().orEmpty()
            imageList.forEachIndexed { idx, el ->
                val imgObj = el.asObjectOrNull() ?: return@forEachIndexed
                val imgUrl = extractXhsImageUrl(imgObj) ?: return@forEachIndexed
                val isLiveFlag = imgObj["livePhoto"]?.jsonPrimitive?.booleanOrNull == true
                val liveStreams = extractXhsImageLiveStreams(imgObj)
                val isLive = isLiveFlag || liveStreams.isNotEmpty()

                items += MediaItem(
                    id = "${noteId}_img_$idx",
                    index = idx,
                    kind = if (isLive && liveStreams.isNotEmpty()) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                    title = if (isLive && liveStreams.isNotEmpty()) "实况图 ${idx + 1}" else "原图 ${idx + 1}",
                    imageUrl = imgUrl,
                    videoUrl = liveStreams.firstOrNull(),
                    backupVideoUrls = liveStreams.drop(1),
                    width = imgObj["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = imgObj["height"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }
        }

        if (items.isEmpty()) {
            throw IllegalStateException("该小红书笔记未包含可提取的图片或视频")
        }

        val resolvedTitle = title.ifBlank {
            desc.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "小红书笔记 $noteId"
        }

        return ExtractedPost(
            id = noteId,
            platform = PlatformType.XIAOHONGSHU,
            originalInput = originalInput,
            resolvedUrl = resolvedUrl,
            title = resolvedTitle,
            caption = desc,
            authorName = authorName,
            items = items,
            engineUsed = "小红书本地 SSR 解析"
        )
    }

    private fun extractXhsImageUrl(imgObj: JsonObject?): String? {
        if (imgObj == null) return null
        val infoList = imgObj["infoList"]?.asArrayOrNull().orEmpty()
        // Prefer WB_DFT (default high-res) or first non-blank infoList entry, then urlDefault / urlPre / url
        val preferredInfo = infoList.mapNotNull { it.asObjectOrNull() }
            .firstOrNull { it["imageScene"]?.jsonPrimitive?.contentOrNull == "WB_DFT" }
            ?: infoList.firstOrNull()?.asObjectOrNull()
        val raw = preferredInfo?.get("url")?.jsonPrimitive?.contentOrNull
            ?: imgObj["urlDefault"]?.jsonPrimitive?.contentOrNull
            ?: imgObj["url"]?.jsonPrimitive?.contentOrNull
            ?: imgObj["urlPre"]?.jsonPrimitive?.contentOrNull
        return raw?.takeIf { it.isNotBlank() }?.let(LinkSanitizer::normalizeHttps)
    }

    private fun extractXhsImageLiveStreams(imgObj: JsonObject): List<String> {
        val stream = imgObj["stream"]?.asObjectOrNull() ?: return emptyList()
        return collectXhsStreamUrls(stream)
    }

    private fun extractXhsVideoStreams(videoObj: JsonObject?): List<String> {
        if (videoObj == null) return emptyList()
        val stream = videoObj["media"]?.asObjectOrNull()?.get("stream")?.asObjectOrNull()
            ?: videoObj["stream"]?.asObjectOrNull()
            ?: return emptyList()
        return collectXhsStreamUrls(stream)
    }

    private fun collectXhsStreamUrls(stream: JsonObject): List<String> {
        val urls = LinkedHashSet<String>()
        listOf("h264", "h265", "av1").forEach { codec ->
            stream[codec]?.asArrayOrNull()?.forEach { entry ->
                val obj = entry.asObjectOrNull() ?: return@forEach
                obj["masterUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    urls += LinkSanitizer.normalizeHttps(it)
                }
                obj["backupUrls"]?.asArrayOrNull()?.forEach { backup ->
                    backup.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        urls += LinkSanitizer.normalizeHttps(it)
                    }
                }
            }
        }
        return urls.toList()
    }

    /**
     * Parses Douyin share page HTML (`window._ROUTER_DATA`) or raw JSON.
     * Handles:
     * 1) Image carousels & Live Photos (`images[i].url_list` + `images[i].video.play_addr.url_list`)
     * 2) Standard watermark-free videos (`video.play_addr.url_list` with `/playwm/` -> `/play/`)
     */
    fun parseDouyinRouterData(
        rawHtmlOrJson: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val rawJson = extractAssignedJson(
            source = rawHtmlOrJson,
            markers = listOf("window._ROUTER_DATA =", "window._ROUTER_DATA=")
        ) ?: throw IllegalStateException("抖音页面未包含 _ROUTER_DATA，请尝试登录抖音账号或重试")

        val sanitizedJson = sanitizeJsUndefined(rawJson)
        val root = json.parseToJsonElement(sanitizedJson).jsonObject
        val loaderData = root["loaderData"]?.asObjectOrNull()

        var item: JsonObject? = null
        if (loaderData != null) {
            for ((_, value) in loaderData) {
                val node = value.asObjectOrNull() ?: continue
                val videoInfoRes = node["videoInfoRes"]?.asObjectOrNull() ?: continue
                val itemList = videoInfoRes["item_list"]?.asArrayOrNull()
                if (!itemList.isNullOrEmpty()) {
                    item = itemList[0].asObjectOrNull()
                    if (item != null) break
                }
            }
        }
        if (item == null) {
            item = root["app"]?.asObjectOrNull()?.get("videoDetail")?.asObjectOrNull()
                ?: root["aweme"]?.asObjectOrNull()?.get("detail")?.asObjectOrNull()
        }

        val aweme = item ?: throw IllegalStateException("未在抖音数据中找到作品详情（可能触发验证或需扫码登录）")
        val awemeId = aweme["aweme_id"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractDouyinItemId(resolvedUrl)
            ?: "dy_${System.currentTimeMillis()}"
        val desc = aweme["desc"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorName = aweme["author"]?.asObjectOrNull()
            ?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty()

        val items = mutableListOf<MediaItem>()
        val imagesArray = aweme["images"]?.asArrayOrNull()

        if (!imagesArray.isNullOrEmpty()) {
            imagesArray.forEachIndexed { idx, el ->
                val imgObj = el.asObjectOrNull() ?: return@forEachIndexed
                val urlList = imgObj["url_list"]?.asArrayOrNull()
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    ?.map(LinkSanitizer::normalizeHttps)
                    .orEmpty()
                // Prefer jpg/jpeg URL if CDN provides both webp and jpeg, otherwise first URL
                val bestImageUrl = urlList.firstOrNull {
                    it.contains(".jpeg", ignoreCase = true) || it.contains(".jpg", ignoreCase = true)
                } ?: urlList.firstOrNull() ?: return@forEachIndexed

                // Douyin Live Photos nest the motion video inside each image object
                val livePlayUrls = imgObj["video"]?.asObjectOrNull()
                    ?.get("play_addr")?.asObjectOrNull()
                    ?.get("url_list")?.asArrayOrNull()
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    ?.map(LinkSanitizer::stripDouyinWatermark)
                    .orEmpty()

                val isLivePhoto = livePlayUrls.isNotEmpty()
                items += MediaItem(
                    id = "${awemeId}_img_$idx",
                    index = idx,
                    kind = if (isLivePhoto) MediaKind.LIVE_PHOTO else MediaKind.IMAGE,
                    title = if (isLivePhoto) "实况图 ${idx + 1}" else "图集 ${idx + 1}",
                    imageUrl = bestImageUrl,
                    videoUrl = livePlayUrls.firstOrNull(),
                    backupVideoUrls = livePlayUrls.drop(1),
                    width = imgObj["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = imgObj["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    durationMs = imgObj["video"]?.asObjectOrNull()?.get("duration")?.jsonPrimitive?.longOrNull ?: 0L
                )
            }
        } else {
            val videoObj = aweme["video"]?.asObjectOrNull()
                ?: throw IllegalStateException("抖音作品中缺少 video 与 images 数据")
            val playUrls = videoObj["play_addr"]?.asObjectOrNull()
                ?.get("url_list")?.asArrayOrNull()
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                ?.map(LinkSanitizer::stripDouyinWatermark)
                .orEmpty()

            if (playUrls.isEmpty()) {
                throw IllegalStateException("未能提取抖音无水印视频地址")
            }

            val coverUrl = videoObj["cover"]?.asObjectOrNull()
                ?.get("url_list")?.asArrayOrNull()
                ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?.let(LinkSanitizer::normalizeHttps)

            items += MediaItem(
                id = "${awemeId}_video",
                index = 0,
                kind = MediaKind.VIDEO,
                title = desc.lineSequence().firstOrNull { it.isNotBlank() }?.take(48) ?: "抖音无水印视频",
                imageUrl = coverUrl,
                videoUrl = playUrls.first(),
                backupVideoUrls = playUrls.drop(1),
                width = videoObj["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = videoObj["height"]?.jsonPrimitive?.intOrNull ?: 0,
                durationMs = videoObj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }

        if (items.isEmpty()) {
            throw IllegalStateException("未提取到任何抖音图片或视频")
        }

        return ExtractedPost(
            id = awemeId,
            platform = PlatformType.DOUYIN,
            originalInput = originalInput,
            resolvedUrl = resolvedUrl,
            title = desc.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "抖音作品 $awemeId",
            caption = desc,
            authorName = authorName,
            items = items,
            engineUsed = "抖音本地 _ROUTER_DATA"
        )
    }

    /**
     * Extracts a balanced `{ ... }` JSON object assigned to a JS global variable or returns raw JSON if input starts with `{`.
     */
    internal fun extractAssignedJson(source: String, markers: List<String>): String? {
        val trimmed = source.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        var startBrace = -1
        for (marker in markers) {
            val idx = source.indexOf(marker)
            if (idx >= 0) {
                val braceIdx = source.indexOf('{', idx + marker.length)
                if (braceIdx >= 0) {
                    startBrace = braceIdx
                    break
                }
            }
        }
        if (startBrace < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in startBrace until source.length) {
            val ch = source[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return source.substring(startBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** Replaces JS `undefined` values outside strings with `null` so strict JSON parsers succeed. */
    internal fun sanitizeJsUndefined(rawJson: String): String {
        val out = StringBuilder(rawJson.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < rawJson.length) {
            val ch = rawJson[i]
            if (escaped) {
                out.append(ch)
                escaped = false
                i++
                continue
            }
            if (ch == '\\' && inString) {
                out.append(ch)
                escaped = true
                i++
                continue
            }
            if (ch == '"') {
                inString = !inString
                out.append(ch)
                i++
                continue
            }
            if (!inString && rawJson.startsWith("undefined", i)) {
                val prev = if (i > 0) rawJson[i - 1] else ' '
                val next = if (i + 9 < rawJson.length) rawJson[i + 9] else ' '
                if (!prev.isLetterOrDigit() && prev != '_' && !next.isLetterOrDigit() && next != '_') {
                    out.append("null")
                    i += 9
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray
}
