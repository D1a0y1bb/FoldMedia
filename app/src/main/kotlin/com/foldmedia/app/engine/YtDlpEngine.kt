package com.foldmedia.app.engine

import android.content.Context
import android.util.Log
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.QualityOption
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps the on-device `youtubedl-android` runtime (`yt-dlp` + `ffmpeg` + `aria2c`)
 * for Scheme A (100% local execution without any external parsing server).
 */
class YtDlpEngine(private val appContext: Context) {

    data class EngineStatus(
        val initialized: Boolean = false,
        val initializing: Boolean = false,
        val version: String = "未初始化",
        val ffmpegReady: Boolean = false,
        val aria2cReady: Boolean = false,
        val lastError: String? = null
    )

    private val initMutex = Mutex()
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.IO) {
        if (_status.value.initialized) return@withContext true
        initMutex.withLock {
            if (_status.value.initialized) return@withLock true
            _status.value = _status.value.copy(initializing = true, lastError = null)
            return@withLock try {
                YoutubeDL.getInstance().init(appContext)
                val ffmpegOk = runCatching { FFmpeg.getInstance().init(appContext) }.isSuccess
                val ariaOk = runCatching { Aria2c.getInstance().init(appContext) }.isSuccess
                val ver = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
                    ?: "已就绪"
                _status.value = EngineStatus(
                    initialized = true,
                    initializing = false,
                    version = ver,
                    ffmpegReady = ffmpegOk,
                    aria2cReady = ariaOk
                )
                true
            } catch (t: Throwable) {
                Log.e("YtDlpEngine", "Initialization failed", t)
                _status.value = EngineStatus(
                    initialized = false,
                    initializing = false,
                    version = "初始化失败",
                    lastError = t.message
                )
                false
            }
        }
    }

    suspend fun updateEngine(useNightly: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) {
            return@withContext Result.failure(IllegalStateException("引擎尚未初始化成功"))
        }
        runCatching {
            val channel = if (useNightly) UpdateChannel.NIGHTLY else UpdateChannel.STABLE
            val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
            val ver = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull() ?: "已更新"
            _status.value = _status.value.copy(version = ver, lastError = null)
            when (updateStatus) {
                YoutubeDL.UpdateStatus.DONE -> "已更新到最新版 ($ver)"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "当前已是最新版 ($ver)"
                else -> "更新状态: $updateStatus ($ver)"
            }
        }
    }

    suspend fun extractPost(
        url: String,
        originalInput: String,
        platform: PlatformType,
        cookieFile: File? = null
    ): ExtractedPost = withContext(Dispatchers.IO) {
        check(ensureInitialized()) { "本地 yt-dlp 引擎初始化失败: ${_status.value.lastError.orEmpty()}" }

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
        }
        val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
        val qualities = buildQualityOptions(info)
        val title = info.title?.takeIf { it.isNotBlank() } ?: "${platform.displayName} 视频"
        val caption = info.description.orEmpty()
        val uploader = info.uploader.orEmpty()
        val thumb = info.thumbnail
        val durationMs = (info.duration.toLong().coerceAtLeast(0L)) * 1000L

        val videoItem = MediaItem(
            id = info.id?.takeIf { it.isNotBlank() } ?: "yt_${System.currentTimeMillis()}",
            index = 0,
            kind = MediaKind.VIDEO,
            title = title,
            imageUrl = thumb,
            videoUrl = url,
            width = info.width,
            height = info.height,
            durationMs = durationMs,
            isSelected = true,
            qualityOptions = qualities,
            selectedQualityId = qualities.firstOrNull()?.id,
            requiresYtDlpDownload = true
        )

        ExtractedPost(
            id = videoItem.id,
            platform = platform,
            originalInput = originalInput,
            resolvedUrl = url,
            title = title,
            caption = caption,
            authorName = uploader,
            items = listOf(videoItem),
            engineUsed = "yt-dlp 本地引擎"
        )
    }

    suspend fun downloadMedia(
        url: String,
        outputDir: File,
        qualityOption: QualityOption?,
        cookieFile: File? = null,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        check(ensureInitialized()) { "本地 yt-dlp 引擎未就绪" }
        if (!outputDir.exists()) outputDir.mkdirs()

        // Clean stale temp outputs in this dedicated job folder
        outputDir.listFiles()?.forEach { it.delete() }

        val isAudio = qualityOption?.isAudioOnly == true
        val formatSelector = qualityOption?.formatSelector
            ?: "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-f", formatSelector)
            if (isAudio) {
                addOption("-x")
                addOption("--audio-format", "m4a")
            } else {
                addOption("--merge-output-format", "mp4")
            }
            if (_status.value.aria2cReady) {
                addOption("--downloader", "libaria2c.so")
            }
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
            addOption("-o", File(outputDir, "media_%(id)s.%(ext)s").absolutePath)
        }

        YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
            val fraction = if (progress >= 0f) (progress / 100f).coerceIn(0f, 1f) else 0f
            val msg = buildString {
                if (progress >= 0f) append("${progress.toInt()}%")
                if (etaInSeconds > 0) append(" · 剩余 ${etaInSeconds}s")
                if (isEmpty() && !line.isNullOrBlank()) append(line.take(48))
            }
            onProgress(fraction, msg)
        }

        val produced = outputDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp 下载完成但未生成媒体文件")

        produced
    }

    private fun buildQualityOptions(info: VideoInfo): List<QualityOption> {
        val maxHeight = info.formats.orEmpty()
            .mapNotNull { it.height }
            .filter { it > 0 }
            .maxOrNull() ?: info.height

        val options = mutableListOf<QualityOption>()
        if (maxHeight >= 2160) {
            options += QualityOption(
                id = "best_4k",
                label = "4K 超清 (${maxHeight}p)",
                formatSelector = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                resolution = "${maxHeight}p"
            )
        } else if (maxHeight > 1080) {
            options += QualityOption(
                id = "best_2k",
                label = "2K 原画 (${maxHeight}p)",
                formatSelector = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best",
                resolution = "${maxHeight}p"
            )
        }

        options += QualityOption(
            id = "fhd_1080",
            label = "1080P 高清 (MP4)",
            formatSelector = "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]/best",
            resolution = "1080p"
        )
        options += QualityOption(
            id = "hd_720",
            label = "720P 流畅省流",
            formatSelector = "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]/best",
            resolution = "720p"
        )
        options += QualityOption(
            id = "audio_m4a",
            label = "仅提取音频 (M4A)",
            formatSelector = "bestaudio/best",
            isAudioOnly = true,
            resolution = "音频"
        )
        return options
    }
}
