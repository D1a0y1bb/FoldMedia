package com.foldmedia.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.util.Log
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.QualityOption
import com.foldmedia.app.model.VideoSubtitleRemoveMode
import com.foldmedia.app.parser.LinkSanitizer
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Wraps the on-device `youtubedl-android` runtime (`yt-dlp` + `ffmpeg`)
 * for Scheme A (100% local execution without any external parsing server).
 */
class YtDlpEngine(private val appContext: Context) {

    data class EngineStatus(
        val initialized: Boolean = false,
        val initializing: Boolean = false,
        val version: String = "未初始化",
        val ffmpegReady: Boolean = false,
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
                val ver = runCatching { YoutubeDL.getInstance().version(appContext)?.takeIf { it.isNotBlank() } }.getOrNull()
                    ?: runCatching { YoutubeDL.getInstance().versionName(appContext)?.takeIf { it.isNotBlank() } }.getOrNull()
                    ?: "已就绪"
                _status.value = EngineStatus(
                    initialized = true,
                    initializing = false,
                    version = ver,
                    ffmpegReady = ffmpegOk
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
            val ver = runCatching { YoutubeDL.getInstance().version(appContext)?.takeIf { it.isNotBlank() } }.getOrNull()
                ?: runCatching { YoutubeDL.getInstance().versionName(appContext)?.takeIf { it.isNotBlank() } }.getOrNull()
                ?: "已更新"
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
        cookieFile: File? = null,
        timeoutMs: Long = 30_000L
    ): ExtractedPost = withContext(Dispatchers.IO) {
        check(ensureInitialized()) { "本地 yt-dlp 引擎初始化失败: ${_status.value.lastError.orEmpty()}" }

        val processId = "extract_${System.nanoTime()}"
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "12")
            addOption("--retries", "1")
            addOption("--extractor-retries", "1")
            resolveSystemProxyUrl()?.let { proxyUrl ->
                addOption("--proxy", proxyUrl)
            }
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
        }

        val jsonRoot: JsonObject = try {
            withTimeout(timeoutMs) {
                val response = YoutubeDL.getInstance().execute(request, processId)
                val jsonOut = response.out.trim()
                check(jsonOut.isNotBlank()) { response.err.ifBlank { "yt-dlp 未返回有效媒体信息" } }
                val firstJsonLine = jsonOut.lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: jsonOut
                lenientJson.parseToJsonElement(firstJsonLine).jsonObject
            }
        } catch (ce: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw ce
        }

        val infoId = jsonRoot["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val infoWidth = jsonRoot["width"]?.jsonPrimitive?.intOrNull ?: 0
        val infoHeight = jsonRoot["height"]?.jsonPrimitive?.intOrNull ?: 0
        val formatHeights = (jsonRoot["formats"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("height")?.jsonPrimitive?.intOrNull }
            .filter { it > 0 }
        val maxHeight = formatHeights.maxOrNull() ?: infoHeight
        val qualities = buildQualityOptions(maxHeight)
        val title = jsonRoot["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "${platform.displayName} 视频"
        val caption = jsonRoot["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val uploader = jsonRoot["uploader"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val thumb = jsonRoot["thumbnail"]?.jsonPrimitive?.contentOrNull
        val durationSec = jsonRoot["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val durationMs = (durationSec * 1000.0).toLong().coerceAtLeast(0L)

        val videoItem = MediaItem(
            id = infoId ?: "yt_${System.currentTimeMillis()}",
            index = 0,
            kind = MediaKind.VIDEO,
            title = title,
            imageUrl = thumb,
            videoUrl = url,
            width = infoWidth,
            height = infoHeight,
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
        val systemProxy = resolveSystemProxyUrl()

        if (LinkSanitizer.isHlsStreamUrl(url)) {
            onProgress(0.15f, "FFmpeg 拉取 m3u8 音视频流...")
            val hlsResult = runCatching {
                downloadHlsViaFFmpeg(
                    hlsUrl = url,
                    outputDir = outputDir,
                    isAudioOnly = isAudio,
                    proxyUrl = null, // tun0 VPN or direct network; retry with proxy if needed
                    onProgress = onProgress
                )
            }.recoverCatching { firstErr ->
                if (!systemProxy.isNullOrBlank()) {
                    Log.w("YtDlpEngine", "Direct FFmpeg HLS failed, retrying via proxy=$systemProxy", firstErr)
                    outputDir.listFiles()?.forEach { it.delete() }
                    downloadHlsViaFFmpeg(
                        hlsUrl = url,
                        outputDir = outputDir,
                        isAudioOnly = isAudio,
                        proxyUrl = systemProxy,
                        onProgress = onProgress
                    )
                } else {
                    throw firstErr
                }
            }
            if (hlsResult.isSuccess) {
                return@withContext hlsResult.getOrThrow()
            }
            Log.e("YtDlpEngine", "FFmpeg HLS direct merge failed", hlsResult.exceptionOrNull())
            throw IllegalStateException(
                "HLS 合并失败: ${hlsResult.exceptionOrNull()?.message ?: "无法读取 m3u8 流"}",
                hlsResult.exceptionOrNull()
            )
        }

        val rawSelector = qualityOption?.formatSelector.orEmpty()
        val primarySelector = when {
            rawSelector.startsWith("hls_merge:") -> "bestvideo+bestaudio/best"
            rawSelector.startsWith("hls_audio:") -> "bestaudio/best"
            rawSelector.isBlank() -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
            else -> rawSelector
        }

        val processId = "dl_${System.nanoTime()}"
        val attemptError = runCatching {
            runYtDlpDownload(
                url = url,
                outputDir = outputDir,
                formatSelector = primarySelector,
                isAudio = isAudio,
                cookieFile = cookieFile,
                proxyUrl = systemProxy,
                processId = processId,
                onProgress = onProgress
            )
        }.exceptionOrNull()

        if (attemptError != null) {
            if (attemptError is CancellationException) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                throw attemptError
            }
            Log.e("YtDlpEngine", "Primary download failed (proxy=$systemProxy)", attemptError)

            // Retry once with direct socket (if VPN tun0 already handles routing) and relaxed format selector
            outputDir.listFiles()?.forEach { it.delete() }
            val fallbackSelector = if (isAudio) "bestaudio/best" else "bestvideo+bestaudio/best"
            val retryId = "dl_retry_${System.nanoTime()}"
            try {
                runYtDlpDownload(
                    url = url,
                    outputDir = outputDir,
                    formatSelector = fallbackSelector,
                    isAudio = isAudio,
                    cookieFile = cookieFile,
                    proxyUrl = null,
                    processId = retryId,
                    onProgress = onProgress
                )
            } catch (ce: CancellationException) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(retryId) }
                throw ce
            } catch (retryErr: Throwable) {
                Log.e("YtDlpEngine", "Retry download failed", retryErr)
                throw IllegalStateException(formatYtDlpError(retryErr), retryErr)
            }
        }

        val produced = outputDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp 下载完成但未生成媒体文件")

        produced
    }

    private fun runYtDlpDownload(
        url: String,
        outputDir: File,
        formatSelector: String,
        isAudio: Boolean,
        cookieFile: File?,
        proxyUrl: String?,
        processId: String,
        onProgress: (Float, String) -> Unit
    ) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--downloader", "m3u8:ffmpeg")
            addOption("--socket-timeout", "20")
            addOption("--retries", "2")
            addOption("-f", formatSelector)
            if (!proxyUrl.isNullOrBlank()) {
                addOption("--proxy", proxyUrl)
            }
            if (isAudio) {
                addOption("-x")
                addOption("--audio-format", "m4a")
            } else {
                addOption("--merge-output-format", "mp4")
                addOption("--remux-video", "mp4")
            }
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
            addOption("-o", File(outputDir, "media_%(id)s.%(ext)s").absolutePath)
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
            val fraction = if (progress >= 0f) (progress / 100f).coerceIn(0f, 1f) else 0f
            val msg = buildString {
                if (progress >= 0f) append("${progress.toInt()}%")
                if (etaInSeconds > 0) append(" · 剩余 ${etaInSeconds}s")
                if (isEmpty() && !line.isNullOrBlank()) append(line.take(48))
            }
            onProgress(fraction, msg)
        }
    }

    private fun formatYtDlpError(err: Throwable): String {
        val raw = err.message.orEmpty()
        val errorLines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("WARNING:", ignoreCase = true) }
        return errorLines.firstOrNull { it.startsWith("ERROR:", ignoreCase = true) }
            ?: errorLines.lastOrNull()
            ?: raw.ifBlank { "yt-dlp 下载失败" }
    }

    private fun downloadHlsViaFFmpeg(
        hlsUrl: String,
        outputDir: File,
        isAudioOnly: Boolean,
        proxyUrl: String?,
        onProgress: (Float, String) -> Unit
    ): File {
        val ffmpegBin = File(appContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        check(ffmpegBin.exists()) { "未找到内置 libffmpeg.so" }

        val packagesDir = File(appContext.noBackupFilesDir, "youtubedl-android/packages")
        val ldPath = listOf(
            File(packagesDir, "python/usr/lib").absolutePath,
            File(packagesDir, "ffmpeg/usr/lib").absolutePath
        ).joinToString(":")
        val sslCert = File(packagesDir, "python/usr/etc/tls/cert.pem").absolutePath

        val outExt = if (isAudioOnly) "m4a" else "mp4"
        val outFile = File(outputDir, "hls_merged_${System.currentTimeMillis()}.$outExt")

        fun runFfmpeg(useAacBsf: Boolean): Pair<Int, String> {
            if (outFile.exists()) outFile.delete()
            val cmd = buildList {
                add(ffmpegBin.absolutePath)
                add("-y")
                add("-loglevel")
                add("error")
                add("-user_agent")
                add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                if (hlsUrl.contains("twimg.com", ignoreCase = true)) {
                    add("-headers")
                    add("Referer: https://x.com/\r\n")
                }
                add("-i")
                add(hlsUrl)
                if (isAudioOnly) {
                    add("-vn")
                    add("-c:a")
                    add("aac")
                    add("-b:a")
                    add("192k")
                } else {
                    add("-c")
                    add("copy")
                    if (useAacBsf) {
                        add("-bsf:a")
                        add("aac_adtstoasc")
                    }
                    add("-movflags")
                    add("+faststart")
                }
                add(outFile.absolutePath)
            }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", ldPath)
                put("SSL_CERT_FILE", sslCert)
                if (!proxyUrl.isNullOrBlank()) {
                    put("http_proxy", proxyUrl)
                    put("https_proxy", proxyUrl)
                    put("HTTP_PROXY", proxyUrl)
                    put("HTTPS_PROXY", proxyUrl)
                }
            }
            val process = pb.start()
            val stderr = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return exitCode to stderr
        }

        onProgress(0.45f, if (isAudioOnly) "FFmpeg 提取音频中..." else "FFmpeg 合并音视频为 MP4...")
        var (exitCode, stderr) = runFfmpeg(useAacBsf = !isAudioOnly)
        if ((exitCode != 0 || !outFile.exists() || outFile.length() == 0L) && !isAudioOnly) {
            Log.w("YtDlpEngine", "FFmpeg with aac_adtstoasc failed ($exitCode): $stderr; retrying plain copy")
            val second = runFfmpeg(useAacBsf = false)
            exitCode = second.first
            stderr = second.second
        }
        if (exitCode != 0 || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException(stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "FFmpeg 退出码 $exitCode")
        }
        onProgress(0.95f, "HLS 合并完成")
        return outFile
    }

    /**
     * Strips soft subtitle tracks or blurs/crops burned-in bottom subtitles locally via bundled `libffmpeg.so`.
     */
    suspend fun removeVideoSubtitles(
        inputFile: File,
        outputDir: File,
        mode: VideoSubtitleRemoveMode,
        onProgress: (Float, String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        if (mode == VideoSubtitleRemoveMode.OFF) return@withContext inputFile
        check(ensureInitialized()) { "本地 FFmpeg 引擎尚未就绪" }
        val ffmpegBin = File(appContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        check(ffmpegBin.exists()) { "未找到内置 libffmpeg.so" }

        val packagesDir = File(appContext.noBackupFilesDir, "youtubedl-android/packages")
        val ldPath = listOf(
            File(packagesDir, "python/usr/lib").absolutePath,
            File(packagesDir, "ffmpeg/usr/lib").absolutePath
        ).joinToString(":")
        val sslCert = File(packagesDir, "python/usr/etc/tls/cert.pem").absolutePath

        val (vw, vh) = probeEffectiveVideoSize(inputFile)
        val filter = buildSubtitleRemovalFilter(vw, vh, mode)
        val outFile = File(outputDir, "nosub_${System.currentTimeMillis()}.mp4")

        fun runFfmpegCmd(reencodeAudio: Boolean): Pair<Int, String> {
            if (outFile.exists()) outFile.delete()
            val cmd = buildList {
                add(ffmpegBin.absolutePath)
                add("-y")
                add("-loglevel")
                add("error")
                add("-i")
                add(inputFile.absolutePath)
                if (filter != null) {
                    add("-vf")
                    add(filter)
                    add("-sn")
                    add("-c:v")
                    add("libx264")
                    add("-preset")
                    add("veryfast")
                    add("-crf")
                    add("20")
                    add("-pix_fmt")
                    add("yuv420p")
                    if (reencodeAudio) {
                        add("-c:a")
                        add("aac")
                        add("-b:a")
                        add("192k")
                    } else {
                        add("-c:a")
                        add("copy")
                    }
                } else {
                    add("-map")
                    add("0:v")
                    add("-map")
                    add("0:a?")
                    add("-c")
                    add("copy")
                    add("-sn")
                }
                add("-movflags")
                add("+faststart")
                add(outFile.absolutePath)
            }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().apply {
                put("LD_LIBRARY_PATH", ldPath)
                put("SSL_CERT_FILE", sslCert)
            }
            val process = pb.start()
            val stderr = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            return code to stderr
        }

        onProgress(0.88f, "FFmpeg ${mode.label} 处理中...")
        var (exitCode, stderr) = runFfmpegCmd(reencodeAudio = false)
        if ((exitCode != 0 || !outFile.exists() || outFile.length() == 0L) && filter != null) {
            Log.w("YtDlpEngine", "Subtitle removal failed with audio copy ($exitCode): $stderr; retrying AAC")
            val retry = runFfmpegCmd(reencodeAudio = true)
            exitCode = retry.first
            stderr = retry.second
        }
        if (exitCode != 0 || !outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException(
                "FFmpeg 去字幕失败: ${stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: "退出码 $exitCode"}"
            )
        }
        onProgress(0.94f, "去字幕封装完成")
        outFile
    }

    private fun probeEffectiveVideoSize(videoFile: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Throwable) {
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun resolveSystemProxyUrl(): String? {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val proxyInfo = cm?.defaultProxy
            val host = proxyInfo?.host?.trim()
            val port = proxyInfo?.port ?: -1
            if (!host.isNullOrBlank() && port in 1..65535) {
                return "http://$host:$port"
            }
        }
        val sysHost = System.getProperty("https.proxyHost")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
        val sysPort = System.getProperty("https.proxyPort")?.toIntOrNull()
            ?: System.getProperty("http.proxyPort")?.toIntOrNull()
            ?: -1
        if (!sysHost.isNullOrBlank() && sysPort in 1..65535) {
            return "http://$sysHost:$sysPort"
        }
        return null
    }

    private fun buildQualityOptions(maxHeight: Int): List<QualityOption> {

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
            formatSelector = "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
            resolution = "1080p"
        )
        options += QualityOption(
            id = "hd_720",
            label = "720P 流畅省流",
            formatSelector = "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best",
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

    companion object {
        private val lenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Builds a safe FFmpeg `-vf` expression for bottom hard-subtitle removal.
         * Ensures coordinates stay strictly within `[1, width/height - 2]` and even crop height.
         */
        internal fun buildSubtitleRemovalFilter(
            width: Int,
            height: Int,
            mode: VideoSubtitleRemoveMode
        ): String? = when (mode) {
            VideoSubtitleRemoveMode.OFF, VideoSubtitleRemoveMode.SOFT_STRIP -> null
            VideoSubtitleRemoveMode.DELOGO_BOTTOM -> {
                if (width < 64 || height < 64) {
                    null
                } else {
                    val x = (width * 0.08).toInt().coerceIn(2, (width - 20).coerceAtLeast(2))
                    val y = (height * 0.84).toInt().coerceIn(2, (height - 20).coerceAtLeast(2))
                    val w = (width - 2 * x).coerceIn(16, (width - x - 2).coerceAtLeast(16))
                    val h = (height - y - 4).coerceIn(16, (height - y - 2).coerceAtLeast(16))
                    "delogo=x=$x:y=$y:w=$w:h=$h"
                }
            }
            VideoSubtitleRemoveMode.CROP_BOTTOM -> {
                if (height < 64) {
                    null
                } else {
                    val cropH = (((height * 0.86).toInt() / 2) * 2).coerceIn(32, height)
                    "crop=iw:$cropH:0:0"
                }
            }
        }
    }
}
