package com.foldmedia.app.parser

import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.QualityOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        val tweet = root["tweet"]?.asObjectOrNull()
            ?: root["status"]?.asObjectOrNull()
            ?: throw IllegalStateException("X 响应中缺少 tweet/status 字段")

        val tweetId = tweet["id"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractTweetId(resolvedUrl)
            ?: "x_${System.currentTimeMillis()}"
        val caption = tweet["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorObj = tweet["author"]?.asObjectOrNull() ?: root["author"]?.asObjectOrNull()
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
            val selection = selectFxVideoStreams(video) ?: return@forEachIndexed
            val thumb = video["thumbnail_url"]?.jsonPrimitive?.contentOrNull
                ?.let(LinkSanitizer::normalizeHttps)
            val durationSec = video["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            items += buildTwitterVideoItem(
                id = "${tweetId}_video_$idx",
                index = items.size,
                title = "视频 ${idx + 1}",
                thumbUrl = thumb,
                mp4Url = selection.mp4Url,
                hlsUrl = selection.hlsUrl,
                tweetUrl = resolvedUrl,
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
                    val selection = selectFxVideoStreams(node)
                    val mp4 = selection?.mp4Url ?: url.takeUnless(LinkSanitizer::isHlsStreamUrl)
                    val hls = selection?.hlsUrl ?: url.takeIf(LinkSanitizer::isHlsStreamUrl)
                    items += buildTwitterVideoItem(
                        id = "${tweetId}_all_$idx",
                        index = items.size,
                        title = if (type == "gif") "动图视频 ${items.size + 1}" else "视频 ${items.size + 1}",
                        thumbUrl = node["thumbnail_url"]?.jsonPrimitive?.contentOrNull?.let(LinkSanitizer::normalizeHttps),
                        mp4Url = mp4,
                        hlsUrl = hls,
                        tweetUrl = resolvedUrl,
                        width = node["width"]?.jsonPrimitive?.intOrNull ?: 0,
                        height = node["height"]?.jsonPrimitive?.intOrNull ?: 0,
                        durationMs = 0L
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

    private data class TwitterVideoSelection(
        val mp4Url: String?,
        val hlsUrl: String?
    )

    private fun selectFxVideoStreams(videoObj: JsonObject): TwitterVideoSelection? {
        val variants = buildList {
            videoObj["formats"]?.asArrayOrNull()?.mapNotNull { it as? JsonObject }?.let(::addAll)
            videoObj["variants"]?.asArrayOrNull()?.mapNotNull { it as? JsonObject }?.let(::addAll)
        }
        val mp4Best = variants
            .filter { v ->
                val ct = v["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val container = v["container"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val u = v["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (container.equals("mp4", ignoreCase = true) ||
                    ct.contains("mp4", ignoreCase = true) ||
                    u.contains(".mp4", ignoreCase = true)) &&
                    !LinkSanitizer.isHlsStreamUrl(u)
            }
            .maxByOrNull { it["bitrate"]?.jsonPrimitive?.longOrNull ?: 0L }
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val hlsVariant = variants
            .firstOrNull { v ->
                val ct = v["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val container = v["container"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val u = v["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                container.equals("m3u8", ignoreCase = true) ||
                    ct.contains("mpegurl", ignoreCase = true) ||
                    LinkSanitizer.isHlsStreamUrl(u)
            }
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val fallbackUrl = videoObj["url"]?.jsonPrimitive?.contentOrNull
        val mp4 = mp4Best ?: fallbackUrl?.takeUnless(LinkSanitizer::isHlsStreamUrl)
        val hls = hlsVariant ?: fallbackUrl?.takeIf(LinkSanitizer::isHlsStreamUrl)
        if (mp4.isNullOrBlank() && hls.isNullOrBlank()) return null
        return TwitterVideoSelection(
            mp4Url = mp4?.let(LinkSanitizer::normalizeHttps),
            hlsUrl = hls?.let(LinkSanitizer::normalizeHttps)
        )
    }

    private fun buildTwitterVideoItem(
        id: String,
        index: Int,
        title: String,
        thumbUrl: String?,
        mp4Url: String?,
        hlsUrl: String?,
        tweetUrl: String,
        width: Int,
        height: Int,
        durationMs: Long
    ): MediaItem {
        val hasMp4 = !mp4Url.isNullOrBlank()
        val hasHls = !hlsUrl.isNullOrBlank()
        val hlsTarget = hlsUrl ?: tweetUrl
        val options = when {
            hasMp4 && hasHls -> listOf(
                QualityOption(
                    id = "direct_mp4",
                    label = "原画直链 (MP4)",
                    formatSelector = "direct:$mp4Url",
                    resolution = if (height > 0) "${height}p" else "MP4"
                ),
                QualityOption(
                    id = "hls_merge_mp4",
                    label = "HLS 音视频合并 (MP4)",
                    formatSelector = "hls_merge:$hlsTarget",
                    resolution = "FFmpeg"
                ),
                QualityOption(
                    id = "audio_m4a",
                    label = "仅提取音频 (M4A)",
                    formatSelector = "hls_audio:$hlsTarget",
                    isAudioOnly = true,
                    resolution = "M4A"
                )
            )
            hasHls -> listOf(
                QualityOption(
                    id = "hls_best_mp4",
                    label = "HLS 音视频合并 (最高画质 MP4)",
                    formatSelector = "bestvideo+bestaudio/best",
                    resolution = if (height > 0) "${height}p" else "MP4"
                ),
                QualityOption(
                    id = "audio_m4a",
                    label = "仅提取音频 (M4A)",
                    formatSelector = "bestaudio/best",
                    isAudioOnly = true,
                    resolution = "M4A"
                )
            )
            else -> emptyList()
        }

        return MediaItem(
            id = id,
            index = index,
            kind = MediaKind.VIDEO,
            title = title,
            imageUrl = thumbUrl,
            videoUrl = mp4Url ?: hlsUrl,
            backupVideoUrls = if (hasMp4 && hasHls) listOf(hlsUrl) else emptyList(),
            width = width,
            height = height,
            durationMs = durationMs,
            qualityOptions = options,
            selectedQualityId = options.firstOrNull()?.id,
            requiresYtDlpDownload = !hasMp4 && hasHls
        )
    }

    /**
     * Parses responses from `https://api.vxtwitter.com/Twitter/status/{id}` as a reliable mirror
     * for X tweets (including many sensitive-flagged public posts and HLS-only video variants).
     */
    fun parseVxTwitterJson(
        rawJson: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val errorMsg = root["error"]?.jsonPrimitive?.contentOrNull
        if (!errorMsg.isNullOrBlank()) {
            throw IllegalStateException("VxTwitter 返回错误: $errorMsg")
        }

        val tweetId = root["tweetID"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractTweetId(resolvedUrl)
            ?: "x_${System.currentTimeMillis()}"
        val caption = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorName = root["user_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val screenName = root["user_screen_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorHandle = if (screenName.isNotBlank()) "@$screenName" else ""

        val items = mutableListOf<MediaItem>()
        val mediaExtended = root["media_extended"]?.asArrayOrNull().orEmpty()
        mediaExtended.forEachIndexed { idx, el ->
            val node = el.asObjectOrNull() ?: return@forEachIndexed
            val type = node["type"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val url = node["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val sizeObj = node["size"]?.asObjectOrNull()
            val width = sizeObj?.get("width")?.jsonPrimitive?.intOrNull ?: 0
            val height = sizeObj?.get("height")?.jsonPrimitive?.intOrNull ?: 0

            if (type == "video" || type == "gif") {
                val normalized = LinkSanitizer.normalizeHttps(url)
                val isHls = LinkSanitizer.isHlsStreamUrl(normalized)
                items += buildTwitterVideoItem(
                    id = "${tweetId}_vx_$idx",
                    index = items.size,
                    title = if (type == "gif") "动图视频 ${items.size + 1}" else "视频 ${items.size + 1}",
                    thumbUrl = node["thumbnail_url"]?.jsonPrimitive?.contentOrNull?.let(LinkSanitizer::normalizeHttps),
                    mp4Url = normalized.takeUnless { isHls },
                    hlsUrl = normalized.takeIf { isHls },
                    tweetUrl = resolvedUrl,
                    width = width,
                    height = height,
                    durationMs = node["duration_millis"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            } else {
                items += MediaItem(
                    id = "${tweetId}_vx_$idx",
                    index = items.size,
                    kind = MediaKind.IMAGE,
                    title = "图片 ${items.size + 1}",
                    imageUrl = LinkSanitizer.upgradeTwitterImageToOriginal(url),
                    width = width,
                    height = height
                )
            }
        }

        if (items.isEmpty()) {
            val fallbackUrls = root["mediaURLs"]?.asArrayOrNull().orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            fallbackUrls.forEachIndexed { idx, url ->
                val normalized = LinkSanitizer.normalizeHttps(url)
                if (normalized.contains(".mp4", ignoreCase = true) || LinkSanitizer.isHlsStreamUrl(normalized)) {
                    val isHls = LinkSanitizer.isHlsStreamUrl(normalized)
                    items += buildTwitterVideoItem(
                        id = "${tweetId}_vxu_$idx",
                        index = items.size,
                        title = "视频 ${items.size + 1}",
                        thumbUrl = null,
                        mp4Url = normalized.takeUnless { isHls },
                        hlsUrl = normalized.takeIf { isHls },
                        tweetUrl = resolvedUrl,
                        width = 0,
                        height = 0,
                        durationMs = 0L
                    )
                } else {
                    items += MediaItem(
                        id = "${tweetId}_vxu_$idx",
                        index = items.size,
                        kind = MediaKind.IMAGE,
                        title = "图片 ${items.size + 1}",
                        imageUrl = LinkSanitizer.upgradeTwitterImageToOriginal(normalized)
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
            engineUsed = "VxTwitter 镜像直连"
        )
    }

    /**
     * Fallback parser for `https://cdn.syndication.twimg.com/tweet-result?id={id}&lang=en&token=...`.
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
            val variants = videoObj["variants"]?.asArrayOrNull().orEmpty().mapNotNull { it as? JsonObject }
            val bestMp4 = variants
                .filter { v ->
                    val type = v["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val src = v["src"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    (type.contains("mp4", ignoreCase = true) || src.contains(".mp4", ignoreCase = true)) &&
                        !LinkSanitizer.isHlsStreamUrl(src)
                }
                .lastOrNull()
                ?.get("src")?.jsonPrimitive?.contentOrNull
            val hlsSrc = variants
                .firstOrNull { v ->
                    val type = v["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val src = v["src"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    type.contains("mpegurl", ignoreCase = true) || LinkSanitizer.isHlsStreamUrl(src)
                }
                ?.get("src")?.jsonPrimitive?.contentOrNull
            val fallbackMedia = videoObj["mediaUrl"]?.jsonPrimitive?.contentOrNull
            val mp4 = bestMp4 ?: fallbackMedia?.takeUnless(LinkSanitizer::isHlsStreamUrl)
            val hls = hlsSrc ?: fallbackMedia?.takeIf(LinkSanitizer::isHlsStreamUrl)
            if (!mp4.isNullOrBlank() || !hls.isNullOrBlank()) {
                items += buildTwitterVideoItem(
                    id = "${tweetId}_syn_video",
                    index = items.size,
                    title = "推文视频",
                    thumbUrl = videoObj["poster"]?.jsonPrimitive?.contentOrNull?.let(LinkSanitizer::normalizeHttps),
                    mp4Url = mp4?.let(LinkSanitizer::normalizeHttps),
                    hlsUrl = hls?.let(LinkSanitizer::normalizeHttps),
                    tweetUrl = resolvedUrl,
                    width = 0,
                    height = 0,
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
                val videoInfoRes = node["videoInfoRes"]?.asObjectOrNull()
                val itemList = videoInfoRes?.get("item_list")?.asArrayOrNull()
                if (!itemList.isNullOrEmpty()) {
                    item = itemList[0].asObjectOrNull()
                    if (item != null) break
                }
                val directNode = node["aweme_detail"]?.asObjectOrNull()
                    ?: node["awemeDetail"]?.asObjectOrNull()
                    ?: node["noteDetail"]?.asObjectOrNull()
                if (directNode != null) {
                    item = directNode
                    break
                }
            }
        }
        if (item == null) {
            item = root["app"]?.asObjectOrNull()?.get("videoDetail")?.asObjectOrNull()
                ?: root["aweme"]?.asObjectOrNull()?.get("detail")?.asObjectOrNull()
                ?: root["detail"]?.asObjectOrNull()
                ?: root["aweme_detail"]?.asObjectOrNull()
                ?: root["awemeDetail"]?.asObjectOrNull()
                ?: root["aweme_details"]?.asArrayOrNull()?.firstOrNull()?.asObjectOrNull()
        }

        val aweme = item ?: throw IllegalStateException("未在抖音数据中找到作品详情（可能触发验证或需扫码登录）")
        val awemeId = aweme["aweme_id"]?.jsonPrimitive?.contentOrNull
            ?: aweme["awemeId"]?.jsonPrimitive?.contentOrNull
            ?: root["awemeId"]?.jsonPrimitive?.contentOrNull
            ?: LinkSanitizer.extractDouyinItemId(resolvedUrl)
            ?: "dy_${System.currentTimeMillis()}"
        val desc = aweme["desc"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authorName = aweme["author"]?.asObjectOrNull()
            ?.get("nickname")?.jsonPrimitive?.contentOrNull
            ?: aweme["authorInfo"]?.asObjectOrNull()
                ?.get("nickname")?.jsonPrimitive?.contentOrNull
            ?: ""

        val items = mutableListOf<MediaItem>()
        val imagesArray = aweme["images"]?.asArrayOrNull()
            ?: aweme["image_infos"]?.asArrayOrNull()
            ?: aweme["imageInfos"]?.asArrayOrNull()

        if (!imagesArray.isNullOrEmpty()) {
            imagesArray.forEachIndexed { idx, el ->
                val imgObj = el.asObjectOrNull() ?: return@forEachIndexed
                // url_list / urlList is watermark-free; download_url_list / downloadUrlList often contains ~tplv-dy-water-v2 watermarks
                val cleanUrls = (imgObj["url_list"]?.asArrayOrNull() ?: imgObj["urlList"]?.asArrayOrNull())
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    ?.map(LinkSanitizer::normalizeHttps)
                    .orEmpty()
                val downloadUrls = (imgObj["download_url_list"]?.asArrayOrNull() ?: imgObj["downloadUrlList"]?.asArrayOrNull())
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    ?.map(LinkSanitizer::normalizeHttps)
                    .orEmpty()
                val preferredClean = cleanUrls.filterNot { it.contains("dy-water", ignoreCase = true) }
                    .ifEmpty { cleanUrls }
                val candidateUrls = preferredClean.ifEmpty { downloadUrls }.distinct()
                // Prefer jpg/jpeg URL if CDN provides both webp and jpeg, otherwise first URL
                val bestImageUrl = candidateUrls.firstOrNull {
                    it.contains(".jpeg", ignoreCase = true) || it.contains(".jpg", ignoreCase = true)
                } ?: candidateUrls.firstOrNull() ?: return@forEachIndexed

                // Douyin Live Photos nest the motion video inside each image object
                // across play_addr/playAddr, bit_rate/bitRateList (prefer H.264), play_addr_h264, download_addr, or live_photo_video
                val livePlayUrls = extractDouyinImageLiveUrls(imgObj)

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
            val playUrls = extractDouyinVideoPlayUrls(videoObj)

            if (playUrls.isEmpty()) {
                throw IllegalStateException("未能提取抖音无水印视频地址")
            }

            val coverUrl = (videoObj["cover"]?.asObjectOrNull()?.let { it["url_list"] ?: it["urlList"] }?.asArrayOrNull()
                ?: videoObj["coverUrlList"]?.asArrayOrNull())
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

    private fun extractDouyinImageLiveUrls(imgObj: JsonObject): List<String> {
        val urls = LinkedHashSet<String>()
        val videoObj = imgObj["video"]?.asObjectOrNull()
        if (videoObj != null) {
            urls += extractDouyinVideoPlayUrls(videoObj)
        }
        val liveVideoObj = imgObj["live_photo_video"]?.asObjectOrNull()
            ?: imgObj["livePhotoVideo"]?.asObjectOrNull()
        if (liveVideoObj != null) {
            urls += extractDouyinVideoPlayUrls(liveVideoObj)
        }
        return urls.toList()
    }

    private fun extractDouyinVideoPlayUrls(videoObj: JsonObject): List<String> {
        val urls = LinkedHashSet<String>()

        fun addAddrElement(addrEl: JsonElement?) {
            if (addrEl == null || addrEl is JsonNull) return
            when (addrEl) {
                is JsonPrimitive -> {
                    val u = addrEl.contentOrNull?.takeIf { it.startsWith("http") || it.startsWith("//") }
                    if (u != null) urls += LinkSanitizer.stripDouyinWatermark(u)
                }
                is JsonArray -> {
                    addrEl.forEach { el ->
                        when (el) {
                            is JsonPrimitive -> el.contentOrNull?.takeIf { it.startsWith("http") || it.startsWith("//") }
                                ?.let { urls += LinkSanitizer.stripDouyinWatermark(it) }
                            is JsonObject -> el["src"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.startsWith("http") || it.startsWith("//") }
                                ?.let { urls += LinkSanitizer.stripDouyinWatermark(it) }
                            else -> Unit
                        }
                    }
                }
                is JsonObject -> {
                    (addrEl["url_list"] ?: addrEl["urlList"])?.let(::addAddrElement)
                    addrEl["src"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.startsWith("http") || it.startsWith("//") }
                        ?.let { urls += LinkSanitizer.stripDouyinWatermark(it) }
                }
                is JsonNull -> Unit
            }
        }

        // Prefer highest-bitrate H.264 entry from bit_rate / bitRateList so Xiaomi Motion Photo plays smoothly
        val bitRates = (videoObj["bit_rate"]?.asArrayOrNull()
            ?: videoObj["bitRateList"]?.asArrayOrNull()).orEmpty().mapNotNull { it.asObjectOrNull() }
        bitRates
            .sortedWith(
                compareBy<JsonObject> {
                    it["is_h265"]?.jsonPrimitive?.intOrNull
                        ?: it["isH265"]?.jsonPrimitive?.intOrNull
                        ?: 0
                }.thenByDescending {
                    it["bit_rate"]?.jsonPrimitive?.longOrNull
                        ?: it["bitRate"]?.jsonPrimitive?.longOrNull
                        ?: 0L
                }
            )
            .forEach { entry ->
                addAddrElement(entry["play_addr"] ?: entry["playAddr"])
            }

        addAddrElement(videoObj["play_addr_h264"] ?: videoObj["playAddrH264"])
        addAddrElement(videoObj["play_addr"] ?: videoObj["playAddr"])
        addAddrElement(videoObj["playApi"])
        addAddrElement(videoObj["download_addr"] ?: videoObj["downloadAddr"])

        return urls.toList()
    }

    /**
     * Parses WeChat Official Account articles (`mp.weixin.qq.com/s/...`) and WeChat sticker store/pack pages.
     * Extracts article cover, full-resolution inline images, animated GIFs/stickers, embedded voice/video, and text.
     */
    fun parseWeChatHtml(
        rawHtml: String,
        originalInput: String,
        resolvedUrl: String
    ): ExtractedPost {
        val articleId = LinkSanitizer.extractWeChatArticleId(resolvedUrl) ?: "wx_${System.currentTimeMillis()}"
        val isStickerPage = resolvedUrl.contains("sticker.weixin.qq.com", ignoreCase = true) ||
            resolvedUrl.contains("emoticon", ignoreCase = true)

        val title = firstNonBlank(
            extractJsVar(rawHtml, "msg_title"),
            extractMetaContent(rawHtml, "og:title"),
            extractMetaContent(rawHtml, "twitter:title"),
            extractElementTextById(rawHtml, "activity-name"),
            extractHtmlTagText(rawHtml, "h1"),
            extractHtmlTagText(rawHtml, "title")
        ).let(::decodeHtmlEntities).ifBlank { if (isStickerPage) "微信表情贴图" else "微信公众号文章" }

        val authorName = firstNonBlank(
            extractJsVar(rawHtml, "nickname"),
            extractElementTextById(rawHtml, "js_name"),
            extractMetaContent(rawHtml, "author"),
            extractMetaContent(rawHtml, "og:site_name")
        ).let(::decodeHtmlEntities)

        val desc = firstNonBlank(
            extractJsVar(rawHtml, "msg_desc"),
            extractMetaContent(rawHtml, "og:description"),
            extractMetaContent(rawHtml, "description")
        ).let(::decodeHtmlEntities)

        val bodyText = extractWeChatBodyText(rawHtml)
        val caption = when {
            bodyText.isNotBlank() -> bodyText
            else -> desc
        }

        val seenUrls = LinkedHashSet<String>()
        val items = mutableListOf<MediaItem>()

        fun addImageCandidate(rawUrl: String?, explicitLabel: String? = null) {
            if (rawUrl.isNullOrBlank()) return
            val trimmed = rawUrl.trim()
            if (trimmed.startsWith("data:", ignoreCase = true)) return
            val normalized = LinkSanitizer.normalizeWeChatImageUrl(trimmed)
            if (!normalized.startsWith("http", ignoreCase = true)) return
            if (isWeChatUiChromeAsset(normalized)) return
            if (!seenUrls.add(normalized)) return

            val isAnimated = normalized.contains("wx_fmt=gif", ignoreCase = true) ||
                normalized.substringBefore('?').endsWith(".gif", ignoreCase = true) ||
                normalized.contains("mmemoticon.qpic.cn", ignoreCase = true)
            val label = explicitLabel ?: when {
                isStickerPage && isAnimated -> "动态贴图 ${items.size + 1}"
                isStickerPage -> "贴图 ${items.size + 1}"
                isAnimated -> "动图 ${items.size + 1}"
                else -> "配图 ${items.size + 1}"
            }
            items += MediaItem(
                id = "${articleId}_wx_img_${items.size}",
                index = items.size,
                kind = MediaKind.IMAGE,
                title = label,
                imageUrl = normalized
            )
        }

        // 1. Article cover (if present and not on sticker page)
        val coverUrl = firstNonBlank(
            extractJsVar(rawHtml, "msg_cdn_url"),
            extractMetaContent(rawHtml, "og:image")
        )
        if (coverUrl.isNotBlank() && !isStickerPage) {
            addImageCandidate(coverUrl, explicitLabel = "文章封面")
        }

        // 2. Embedded stickers / article <img> tags (prefer data-src then data-original then src)
        val imgTagRegex = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
        imgTagRegex.findAll(rawHtml).forEach { tagMatch ->
            val tag = tagMatch.value
            val candidate = firstNonBlank(
                extractAttr(tag, "data-src"),
                extractAttr(tag, "data-original"),
                extractAttr(tag, "data-gif-url"),
                extractAttr(tag, "src")
            )
            addImageCandidate(candidate)
        }

        // 3. CSS background-image: url(...)
        Regex("""background-image\s*:\s*url\((['"]?)(https?:[^)'"]+)\1\)""", RegexOption.IGNORE_CASE)
            .findAll(rawHtml)
            .forEach { m -> addImageCandidate(m.groupValues[2]) }

        // 4. Sticker JSON / embedded image host URLs (mmbiz / mmemoticon / emoticon / shp.qpic.cn / wxapp.tc.qq.com)
        Regex("""["'](https?:\\?/\\?/[^"'\s<>]*(?:mmbiz\.qpic\.cn|mmemoticon\.qpic\.cn|emoticon\.qpic\.cn|shp\.qpic\.cn|wxapp\.tc\.qq\.com)[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE)
            .findAll(rawHtml)
            .forEach { m -> addImageCandidate(m.groupValues[1]) }

        // 5. Embedded WeChat OA MP4 videos (mpvideo.qpic.cn or mp.weixin.qq.com/mp/videoplayer)
        Regex("""["'](https?:\\?/\\?/mpvideo\.qpic\.cn[^"'\s<>]+\.mp4[^"'\s<>]*)["']""", RegexOption.IGNORE_CASE)
            .findAll(rawHtml)
            .forEach { m ->
                val videoUrl = LinkSanitizer.normalizeWeChatImageUrl(m.groupValues[1])
                if (seenUrls.add(videoUrl)) {
                    items += MediaItem(
                        id = "${articleId}_wx_vid_${items.size}",
                        index = items.size,
                        kind = MediaKind.VIDEO,
                        title = "文章视频 ${items.size + 1}",
                        imageUrl = coverUrl.takeIf { it.isNotBlank() }?.let(LinkSanitizer::normalizeWeChatImageUrl),
                        videoUrl = videoUrl
                    )
                }
            }

        // 6. Embedded WeChat OA audio (voice_encode_fileid)
        Regex("""voice_encode_fileid\s*[:=]\s*["']([A-Za-z0-9_=+-]+)["']""")
            .findAll(rawHtml)
            .forEach { m ->
                val voiceId = m.groupValues[1]
                val audioUrl = "https://res.wx.qq.com/voice/getvoice?mediaid=$voiceId"
                if (seenUrls.add(audioUrl)) {
                    items += MediaItem(
                        id = "${articleId}_wx_aud_${items.size}",
                        index = items.size,
                        kind = MediaKind.AUDIO,
                        title = "文章音频 ${items.size + 1}",
                        imageUrl = coverUrl.takeIf { it.isNotBlank() }?.let(LinkSanitizer::normalizeWeChatImageUrl),
                        videoUrl = audioUrl
                    )
                }
            }

        if (items.isEmpty()) {
            throw IllegalStateException("未在该微信页面检测到可提取的图片、贴图或音视频（可能需在微信内打开或已过期）")
        }

        return ExtractedPost(
            id = articleId,
            platform = PlatformType.WECHAT,
            originalInput = originalInput,
            resolvedUrl = resolvedUrl,
            title = title.take(80),
            caption = caption,
            authorName = authorName,
            items = items,
            engineUsed = if (isStickerPage) "微信贴图本地解析" else "微信公众号本地解析"
        )
    }

    private fun isWeChatUiChromeAsset(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("res.wx.qq.com") ||
            lower.contains("wx_fed/assets") ||
            lower.contains("favicon.ico") ||
            lower.contains("/mmopen/") && lower.endsWith("/132")
    }

    private fun extractJsVar(html: String, varName: String): String {
        val singleOrDouble = Regex(
            """(?:var\s+|window\.)?$varName\s*=\s*(?:htmlDecode\()?\s*["']([^"'\r\n]+)["']""",
            RegexOption.IGNORE_CASE
        )
        return singleOrDouble.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractMetaContent(html: String, key: String): String {
        val p1 = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["'][^>]+content\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        p1.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
        val p2 = Regex(
            """<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]+(?:property|name)\s*=\s*["']${Regex.escape(key)}["']""",
            RegexOption.IGNORE_CASE
        )
        return p2.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractElementTextById(html: String, id: String): String {
        val m = Regex(
            """<[^>]+id\s*=\s*["']${Regex.escape(id)}["'][^>]*>(.*?)</[a-zA-Z0-9]+>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return ""
        return m.groupValues[1].replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun extractHtmlTagText(html: String, tagName: String): String {
        val m = Regex(
            """<$tagName\b[^>]*>(.*?)</$tagName>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return ""
        return m.groupValues[1].replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    private fun extractAttr(tag: String, attrName: String): String {
        val m = Regex("""\b$attrName\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?: return ""
        return m.groupValues[1].ifBlank { m.groupValues[2] }
    }

    private fun extractWeChatBodyText(html: String): String {
        val idx = html.indexOf("id=\"js_content\"", ignoreCase = true)
            .let { if (it >= 0) it else html.indexOf("id='js_content'", ignoreCase = true) }
        if (idx < 0) return ""
        val gt = html.indexOf('>', idx)
        if (gt < 0 || gt + 1 >= html.length) return ""
        val rawEnd = minOf(html.length, gt + 1 + 48_000)
        var snippet = html.substring(gt + 1, rawEnd)
        val qrStop = snippet.indexOf("id=\"js_pc_qr_code\"", ignoreCase = true)
        if (qrStop > 0) snippet = snippet.substring(0, qrStop)
        val text = snippet
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<(?:br|p|div|section|li|h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .let(::decodeHtmlEntities)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return text.take(1500)
    }

    private fun decodeHtmlEntities(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("\\x26amp;", "&")
    }

    private fun firstNonBlank(vararg candidates: String): String {
        return candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
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
