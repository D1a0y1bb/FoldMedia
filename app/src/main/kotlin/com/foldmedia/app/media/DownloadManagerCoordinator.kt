package com.foldmedia.app.media

import android.content.Context
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.model.DownloadStatus
import com.foldmedia.app.model.DownloadTask
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.LivePhotoSaveMode
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.VideoSubtitleRemoveMode
import com.foldmedia.app.parser.LinkSanitizer
import com.foldmedia.app.parser.MediaExtractorRepository
import com.foldmedia.app.storage.CookieVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Concurrent on-device download & Xiaomi Live Photo synthesis pipeline.
 */
class DownloadManagerCoordinator(
    private val appContext: Context,
    private val httpClient: OkHttpClient,
    private val cookieVault: CookieVault,
    private val extractorRepository: MediaExtractorRepository,
    private val ytDlpEngine: YtDlpEngine,
    private val mediaSaver: GalleryMediaSaver
) {
    private val downloadSemaphore = Semaphore(permits = 3)

    fun enqueueItems(
        scope: CoroutineScope,
        post: ExtractedPost,
        items: List<MediaItem>,
        livePhotoMode: LivePhotoSaveMode,
        subtitleRemoveMode: VideoSubtitleRemoveMode = VideoSubtitleRemoveMode.OFF,
        onTaskCreated: (DownloadTask) -> Unit,
        onTaskUpdated: (String, (DownloadTask) -> DownloadTask) -> Unit
    ) {
        items.forEach { item ->
            val taskId = "${post.id}_${item.id}_${System.currentTimeMillis()}_${item.index}"
            val initialTask = DownloadTask(
                id = taskId,
                postTitle = post.title,
                platform = post.platform,
                item = item,
                livePhotoSaveMode = livePhotoMode,
                videoSubtitleRemoveMode = subtitleRemoveMode,
                status = DownloadStatus.QUEUED,
                statusMessage = "等待调度..."
            )
            onTaskCreated(initialTask)

            scope.launch(Dispatchers.IO) {
                downloadSemaphore.withPermit {
                    executeTask(
                        taskId = taskId,
                        post = post,
                        item = item,
                        livePhotoMode = livePhotoMode,
                        subtitleRemoveMode = subtitleRemoveMode,
                        onTaskUpdated = onTaskUpdated
                    )
                }
            }
        }
    }

    private suspend fun executeTask(
        taskId: String,
        post: ExtractedPost,
        item: MediaItem,
        livePhotoMode: LivePhotoSaveMode,
        subtitleRemoveMode: VideoSubtitleRemoveMode,
        onTaskUpdated: (String, (DownloadTask) -> DownloadTask) -> Unit
    ) {
        try {
            when (item.kind) {
                MediaKind.IMAGE -> handleImageDownload(taskId, post, item, onTaskUpdated)
                MediaKind.LIVE_PHOTO -> handleLivePhotoDownload(taskId, post, item, livePhotoMode, onTaskUpdated)
                MediaKind.VIDEO, MediaKind.AUDIO -> handleVideoDownload(taskId, post, item, subtitleRemoveMode, onTaskUpdated)
            }
        } catch (t: Throwable) {
            onTaskUpdated(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = "保存失败",
                    errorMessage = t.message ?: "未知错误"
                )
            }
        }
    }

    private suspend fun handleImageDownload(
        taskId: String,
        post: ExtractedPost,
        item: MediaItem,
        onTaskUpdated: (String, (DownloadTask) -> DownloadTask) -> Unit
    ) {
        val url = item.imageUrl ?: throw IllegalStateException("缺少图片下载地址")
        onTaskUpdated(taskId) {
            it.copy(status = DownloadStatus.DOWNLOADING, progress = 0.25f, statusMessage = "下载原图中...")
        }
        val bytes = extractorRepository.downloadBinaryBytes(url, post.platform)
        onTaskUpdated(taskId) {
            it.copy(status = DownloadStatus.SAVING_TO_GALLERY, progress = 0.85f, statusMessage = "写入系统相册...")
        }
        val saved = mediaSaver.saveImageBytes(
            imageBytes = bytes,
            baseName = "${post.platform.shortBadge}_${post.id}_${item.index + 1}"
        )
        onTaskUpdated(taskId) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                progress = 1f,
                statusMessage = "已保存到 ${saved.displayPath}",
                savedDisplayPaths = listOf(saved.displayPath)
            )
        }
    }

    private suspend fun handleLivePhotoDownload(
        taskId: String,
        post: ExtractedPost,
        item: MediaItem,
        livePhotoMode: LivePhotoSaveMode,
        onTaskUpdated: (String, (DownloadTask) -> DownloadTask) -> Unit
    ) {
        val imgUrl = item.imageUrl ?: throw IllegalStateException("缺少实况静态图地址")
        val videoCandidates = buildList {
            item.videoUrl?.let { add(it) }
            addAll(item.backupVideoUrls)
        }
        if (videoCandidates.isEmpty()) {
            handleImageDownload(taskId, post, item, onTaskUpdated)
            return
        }

        onTaskUpdated(taskId) {
            it.copy(status = DownloadStatus.DOWNLOADING, progress = 0.15f, statusMessage = "下载实况静态图...")
        }
        val rawImageBytes = extractorRepository.downloadBinaryBytes(imgUrl, post.platform)

        onTaskUpdated(taskId) {
            it.copy(status = DownloadStatus.DOWNLOADING, progress = 0.45f, statusMessage = "下载实况动态流...")
        }
        val mp4Bytes = downloadFirstAvailableBytes(videoCandidates, post.platform)

        val savedPaths = mutableListOf<String>()
        val tempDir = File(appContext.cacheDir, "live_tmp_$taskId").apply { mkdirs() }
        try {
            if (livePhotoMode == LivePhotoSaveMode.XIAOMI_MOTION_PHOTO || livePhotoMode == LivePhotoSaveMode.BOTH) {
                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.MERGING_LIVE_PHOTO,
                        progress = 0.72f,
                        statusMessage = "注入小米 XMP 合成实况照片..."
                    )
                }
                val jpegBytes = XiaomiMotionPhotoBuilder.ensureJpegBytes(rawImageBytes)
                val motionBytes = XiaomiMotionPhotoBuilder.buildMotionPhotoBytes(jpegBytes, mp4Bytes)
                val savedMotion = mediaSaver.saveMotionPhotoBytes(motionBytes, item.index + 1)
                savedPaths += savedMotion.displayPath
            }

            if (livePhotoMode == LivePhotoSaveMode.SEPARATE_FILES || livePhotoMode == LivePhotoSaveMode.BOTH) {
                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.SAVING_TO_GALLERY,
                        progress = 0.88f,
                        statusMessage = "保存独立原图与 MP4..."
                    )
                }
                val baseName = "${post.platform.shortBadge}_${post.id}_live_${item.index + 1}"
                val savedImg = mediaSaver.saveImageBytes(rawImageBytes, baseName)
                val tmpMp4 = File(tempDir, "$baseName.mp4").apply { writeBytes(mp4Bytes) }
                val savedVid = mediaSaver.saveVideoFile(tmpMp4, baseName)
                savedPaths += savedImg.displayPath
                savedPaths += savedVid.displayPath
            }

            onTaskUpdated(taskId) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    statusMessage = "已保存 (${savedPaths.joinToString(" & ")})",
                    savedDisplayPaths = savedPaths
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun handleVideoDownload(
        taskId: String,
        post: ExtractedPost,
        item: MediaItem,
        subtitleRemoveMode: VideoSubtitleRemoveMode,
        onTaskUpdated: (String, (DownloadTask) -> DownloadTask) -> Unit
    ) {
        val tempDir = File(appContext.cacheDir, "dl_job_$taskId").apply { mkdirs() }
        try {
            val selectedQuality = item.qualityOptions.firstOrNull { it.id == item.selectedQualityId }
                ?: item.qualityOptions.firstOrNull()
            val selector = selectedQuality?.formatSelector.orEmpty()
            val explicitDirectUrl = selector.takeIf { it.startsWith("direct:") }?.removePrefix("direct:")
            val hlsOverrideUrl = when {
                selector.startsWith("hls_merge:") -> selector.removePrefix("hls_merge:")
                selector.startsWith("hls_audio:") -> selector.removePrefix("hls_audio:")
                else -> null
            }
            val effectiveTargetUrl = explicitDirectUrl ?: hlsOverrideUrl ?: item.videoUrl ?: post.resolvedUrl
            val useYtDlp = explicitDirectUrl == null && (
                item.requiresYtDlpDownload ||
                    hlsOverrideUrl != null ||
                    LinkSanitizer.isHlsStreamUrl(effectiveTargetUrl)
                )

            if (useYtDlp) {
                val cookieFile = cookieVault.exportNetscapeCookieFile(post.platform, appContext.cacheDir)
                val isHls = LinkSanitizer.isHlsStreamUrl(effectiveTargetUrl) || hlsOverrideUrl != null

                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0.05f,
                        statusMessage = if (isHls) "HLS 拉流并 FFmpeg 合并 MP4..." else "yt-dlp 解析拉流中..."
                    )
                }

                val outFile = ytDlpEngine.downloadMedia(
                    url = effectiveTargetUrl,
                    outputDir = tempDir,
                    qualityOption = selectedQuality,
                    cookieFile = cookieFile
                ) { fraction, msg ->
                    onTaskUpdated(taskId) {
                        it.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = (fraction * 0.9f).coerceIn(0.05f, 0.9f),
                            statusMessage = msg.ifBlank { if (isHls) "HLS 分片合成中..." else "下载视频流..." }
                        )
                    }
                }

                val isAudioSelected = selectedQuality?.isAudioOnly == true || item.kind == MediaKind.AUDIO
                val finalFile = if (!isAudioSelected && subtitleRemoveMode != VideoSubtitleRemoveMode.OFF) {
                    ytDlpEngine.removeVideoSubtitles(outFile, tempDir, subtitleRemoveMode) { frac, msg ->
                        onTaskUpdated(taskId) {
                            it.copy(
                                status = DownloadStatus.DOWNLOADING,
                                progress = frac.coerceIn(0.85f, 0.94f),
                                statusMessage = msg
                            )
                        }
                    }
                } else {
                    outFile
                }

                onTaskUpdated(taskId) {
                    it.copy(status = DownloadStatus.SAVING_TO_GALLERY, progress = 0.96f, statusMessage = "写入系统媒体库...")
                }

                val baseName = "${post.platform.shortBadge}_${post.title}_${post.id}"
                val saved = if (isAudioSelected) {
                    mediaSaver.saveAudioFile(finalFile, baseName)
                } else {
                    mediaSaver.saveVideoFile(finalFile, baseName)
                }

                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 1f,
                        statusMessage = "已保存至 ${saved.displayPath}",
                        savedDisplayPaths = listOf(saved.displayPath)
                    )
                }
            } else {
                val candidates = buildList {
                    if (explicitDirectUrl != null) add(explicitDirectUrl)
                    item.videoUrl?.let { if (!LinkSanitizer.isHlsStreamUrl(it)) add(it) }
                    item.backupVideoUrls.filterNot(LinkSanitizer::isHlsStreamUrl).forEach { add(it) }
                }.distinct()
                if (candidates.isEmpty()) throw IllegalStateException("缺少视频直链地址")

                val isAudio = item.kind == MediaKind.AUDIO || selectedQuality?.isAudioOnly == true
                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0.05f,
                        statusMessage = if (isAudio) "高速下载音频..." else "高速下载无水印视频..."
                    )
                }

                val ext = if (isAudio) "mp3" else "mp4"
                val tmpFile = File(tempDir, "media_${post.id}_${item.index}.$ext")
                downloadFirstAvailableToFile(candidates, post.platform, tmpFile) { progress ->
                    onTaskUpdated(taskId) {
                        it.copy(
                            status = DownloadStatus.DOWNLOADING,
                            progress = (progress * 0.9f).coerceIn(0.05f, 0.9f),
                            statusMessage = "下载中 ${(progress * 100).toInt()}%"
                        )
                    }
                }

                val finalFile = if (!isAudio && subtitleRemoveMode != VideoSubtitleRemoveMode.OFF) {
                    ytDlpEngine.removeVideoSubtitles(tmpFile, tempDir, subtitleRemoveMode) { frac, msg ->
                        onTaskUpdated(taskId) {
                            it.copy(
                                status = DownloadStatus.DOWNLOADING,
                                progress = frac.coerceIn(0.85f, 0.94f),
                                statusMessage = msg
                            )
                        }
                    }
                } else {
                    tmpFile
                }

                onTaskUpdated(taskId) {
                    it.copy(status = DownloadStatus.SAVING_TO_GALLERY, progress = 0.96f, statusMessage = "写入系统相册...")
                }

                val baseName = "${post.platform.shortBadge}_${post.title}_${post.id}"
                val saved = if (isAudio) {
                    mediaSaver.saveAudioFile(finalFile, baseName)
                } else {
                    mediaSaver.saveVideoFile(finalFile, baseName)
                }
                onTaskUpdated(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 1f,
                        statusMessage = "已保存至 ${saved.displayPath}",
                        savedDisplayPaths = listOf(saved.displayPath)
                    )
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun downloadFirstAvailableBytes(urls: List<String>, platform: PlatformType): ByteArray {
        var lastError: Throwable? = null
        for (url in urls) {
            val res = runCatching {
                val bytes = extractorRepository.downloadBinaryBytes(url, platform)
                require(bytes.size > 512 && !looksLikeHtml(bytes)) { "返回内容不是有效 MP4 视频流" }
                bytes
            }
            if (res.isSuccess) return res.getOrThrow()
            lastError = res.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("所有实况动态流备用源均下载失败")
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.UTF_8).trimStart().lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("{")
    }

    private fun downloadFirstAvailableToFile(
        urls: List<String>,
        platform: PlatformType,
        target: File,
        onProgress: (Float) -> Unit
    ) {
        var lastError: Throwable? = null
        for (url in urls) {
            val attempt = runCatching {
                val cookie = cookieVault.getCookieHeader(platform, url)
                val req = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.50 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
                    )
                    .apply {
                        if (cookie.isNotBlank()) header("Cookie", cookie)
                        if (platform == PlatformType.DOUYIN) header("Referer", "https://www.douyin.com/")
                        if (platform == PlatformType.XIAOHONGSHU) header("Referer", "https://www.xiaohongshu.com/")
                        if (platform == PlatformType.X_TWITTER) header("Referer", "https://x.com/")
                        if (platform == PlatformType.WECHAT) header("Referer", "https://mp.weixin.qq.com/")
                    }
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            val buf = ByteArray(32 * 1024)
                            var copied = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                copied += read
                                if (total > 0L) {
                                    onProgress((copied.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                            output.flush()
                        }
                    }
                }
            }
            if (attempt.isSuccess) return
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("视频下载失败")
    }
}
