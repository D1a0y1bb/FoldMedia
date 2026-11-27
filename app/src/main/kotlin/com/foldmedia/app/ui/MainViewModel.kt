package com.foldmedia.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.foldmedia.app.BuildConfig
import com.foldmedia.app.FoldMediaApp
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.media.DownloadManagerCoordinator
import com.foldmedia.app.model.AppTab
import com.foldmedia.app.model.DownloadStatus
import com.foldmedia.app.model.DownloadTask
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.LivePhotoSaveMode
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.model.VideoSubtitleRemoveMode
import com.foldmedia.app.parser.LinkSanitizer
import com.foldmedia.app.ui.fold.FoldPreviewOverride
import com.foldmedia.app.update.AppReleaseInfo
import com.foldmedia.app.update.InstallLaunchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val currentTab: AppTab = AppTab.EXTRACT,
    val urlInput: String = "",
    val detectedPlatform: PlatformType = PlatformType.UNKNOWN,
    val isExtracting: Boolean = false,
    val extractStageText: String = "本地引擎解析中...",
    val extractError: String? = null,
    val currentPost: ExtractedPost? = null,
    val historyPosts: List<ExtractedPost> = emptyList(),
    val tasks: List<DownloadTask> = emptyList(),
    val livePhotoSaveMode: LivePhotoSaveMode = LivePhotoSaveMode.XIAOMI_MOTION_PHOTO,
    val videoSubtitleRemoveMode: VideoSubtitleRemoveMode = VideoSubtitleRemoveMode.OFF,
    val foldPreviewOverride: FoldPreviewOverride = FoldPreviewOverride.AUTO,
    val previewItem: MediaItem? = null,
    val showPreviewDialog: Boolean = false,
    val activeLoginPlatform: PlatformType? = null,
    val isUpdatingEngine: Boolean = false,
    val engineUpdateMessage: String? = null,
    val cookieStatusMap: Map<PlatformType, Boolean> = emptyMap(),
    val clipboardBannerUrl: String? = null,
    val statusNotice: String? = null,
    val availableUpdate: AppReleaseInfo? = null,
    val showUpdateDialog: Boolean = false,
    val isCheckingAppUpdate: Boolean = false,
    val appUpdateMessage: String? = null,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val updateDownloadStatus: String = "",
    val downloadedUpdateApkPath: String? = null
) {
    val activeTaskCount: Int
        get() = tasks.count {
            it.status == DownloadStatus.QUEUED ||
                it.status == DownloadStatus.DOWNLOADING ||
                it.status == DownloadStatus.MERGING_LIVE_PHOTO ||
                it.status == DownloadStatus.SAVING_TO_GALLERY
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as FoldMediaApp
    private val repository = app.extractorRepository
    private val cookieVault = app.cookieVault
    private val ytDlpEngine = app.ytDlpEngine
    private val appUpdateManager = app.appUpdateManager
    private val coordinator = DownloadManagerCoordinator(
        appContext = app,
        httpClient = app.okHttpClient,
        cookieVault = cookieVault,
        extractorRepository = repository,
        ytDlpEngine = ytDlpEngine,
        mediaSaver = app.mediaSaver
    )

    private val prefs = app.getSharedPreferences("foldmedia_prefs", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(
        MainUiState(
            videoSubtitleRemoveMode = runCatching {
                prefs.getString("subtitle_remove_mode", VideoSubtitleRemoveMode.OFF.name)
                    ?.let(VideoSubtitleRemoveMode::valueOf)
            }.getOrNull() ?: VideoSubtitleRemoveMode.OFF
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val ytDlpStatus: StateFlow<YtDlpEngine.EngineStatus> = ytDlpEngine.status
    private var extractJob: Job? = null

    init {
        refreshCookieStatuses()
        viewModelScope.launch {
            delay(1500L)
            checkForAppUpdate(manual = false)
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun updateUrlInput(value: String) {
        val cleanUrl = LinkSanitizer.extractCleanUrl(value)
        val platform = cleanUrl?.let(LinkSanitizer::detectPlatform) ?: PlatformType.UNKNOWN
        _uiState.update {
            it.copy(
                urlInput = value,
                detectedPlatform = platform,
                extractError = null
            )
        }
    }

    fun handleIncomingShareText(sharedText: String) {
        updateUrlInput(sharedText)
        _uiState.update { it.copy(currentTab = AppTab.EXTRACT, clipboardBannerUrl = null) }
        extractCurrentUrl()
    }

    fun onClipboardDetected(clipboardText: String) {
        val cleanUrl = LinkSanitizer.extractCleanUrl(clipboardText) ?: return
        val platform = LinkSanitizer.detectPlatform(cleanUrl)
        if (platform == PlatformType.UNKNOWN) return
        if (cleanUrl == _uiState.value.urlInput.trim() ||
            cleanUrl == _uiState.value.currentPost?.resolvedUrl
        ) {
            return
        }
        _uiState.update { it.copy(clipboardBannerUrl = clipboardText) }
    }

    fun applyClipboardAndExtract() {
        val text = _uiState.value.clipboardBannerUrl ?: return
        _uiState.update { it.copy(clipboardBannerUrl = null) }
        updateUrlInput(text)
        extractCurrentUrl()
    }

    fun dismissClipboardBanner() {
        _uiState.update { it.copy(clipboardBannerUrl = null) }
    }

    fun extractCurrentUrl() {
        val raw = _uiState.value.urlInput.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(extractError = "请先粘贴抖音、小红书、微信公众号/贴图、X 或 YouTube 链接") }
            return
        }
        if (_uiState.value.isExtracting) return

        extractJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtracting = true,
                    extractStageText = "正在识别链接...",
                    extractError = null,
                    statusNotice = null
                )
            }
            val result = runCatching {
                repository.extract(raw) { stage ->
                    _uiState.update { it.copy(extractStageText = stage) }
                }
            }
            result.onSuccess { post ->
                _uiState.update { state ->
                    val updatedHistory = listOf(post) + state.historyPosts.filterNot { it.id == post.id && it.platform == post.platform }.take(14)
                    state.copy(
                        isExtracting = false,
                        extractStageText = "本地引擎解析中...",
                        currentPost = post,
                        historyPosts = updatedHistory,
                        detectedPlatform = post.platform,
                        previewItem = post.items.firstOrNull(),
                        extractError = null
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            extractStageText = "本地引擎解析中..."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            extractStageText = "本地引擎解析中...",
                            extractError = err.message ?: "解析失败，请检查网络或尝试登录对应平台"
                        )
                    }
                }
            }
        }
    }

    fun cancelExtraction() {
        extractJob?.cancel()
        extractJob = null
        _uiState.update {
            it.copy(
                isExtracting = false,
                extractStageText = "本地引擎解析中...",
                statusNotice = "已取消当前解析"
            )
        }
    }

    fun selectHistoryPost(post: ExtractedPost) {
        _uiState.update {
            it.copy(
                currentPost = post,
                detectedPlatform = post.platform,
                previewItem = post.items.firstOrNull(),
                extractError = null
            )
        }
    }

    fun toggleItemSelection(itemId: String) {
        _uiState.update { state ->
            val post = state.currentPost ?: return@update state
            val updatedItems = post.items.map { item ->
                if (item.id == itemId) item.copy(isSelected = !item.isSelected) else item
            }
            val updatedPost = post.copy(items = updatedItems)
            state.copy(
                currentPost = updatedPost,
                historyPosts = state.historyPosts.map { if (it.id == updatedPost.id) updatedPost else it }
            )
        }
    }

    fun selectAllItems() = applySelectionFilter { true }

    fun clearItemSelection() = applySelectionFilter { false }

    fun selectOnlyLivePhotos() = applySelectionFilter { it.kind == MediaKind.LIVE_PHOTO }

    fun selectOnlyStaticImages() = applySelectionFilter { it.kind == MediaKind.IMAGE }

    private fun applySelectionFilter(predicate: (MediaItem) -> Boolean) {
        _uiState.update { state ->
            val post = state.currentPost ?: return@update state
            val updatedPost = post.copy(items = post.items.map { it.copy(isSelected = predicate(it)) })
            state.copy(
                currentPost = updatedPost,
                historyPosts = state.historyPosts.map { if (it.id == updatedPost.id) updatedPost else it }
            )
        }
    }

    fun selectQualityOption(itemId: String, qualityId: String) {
        _uiState.update { state ->
            val post = state.currentPost ?: return@update state
            val updatedPost = post.copy(
                items = post.items.map { item ->
                    if (item.id == itemId) item.copy(selectedQualityId = qualityId) else item
                }
            )
            state.copy(currentPost = updatedPost)
        }
    }

    fun setLivePhotoSaveMode(mode: LivePhotoSaveMode) {
        _uiState.update { it.copy(livePhotoSaveMode = mode) }
    }

    fun setVideoSubtitleRemoveMode(mode: VideoSubtitleRemoveMode) {
        prefs.edit().putString("subtitle_remove_mode", mode.name).apply()
        _uiState.update { it.copy(videoSubtitleRemoveMode = mode) }
    }

    fun setFoldPreviewOverride(override: FoldPreviewOverride) {
        _uiState.update { it.copy(foldPreviewOverride = override) }
    }

    fun cycleFoldPreviewOverride() {
        val entries = FoldPreviewOverride.entries
        _uiState.update { state ->
            val next = entries[(entries.indexOf(state.foldPreviewOverride) + 1) % entries.size]
            state.copy(foldPreviewOverride = next)
        }
    }

    fun openPreview(item: MediaItem) {
        _uiState.update { it.copy(previewItem = item, showPreviewDialog = true) }
    }

    fun closePreview() {
        _uiState.update { it.copy(showPreviewDialog = false) }
    }

    fun downloadSelectedItems() {
        val state = _uiState.value
        val post = state.currentPost ?: return
        val selected = post.items.filter { it.isSelected }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(statusNotice = "请至少勾选一项图片或视频") }
            return
        }

        coordinator.enqueueItems(
            scope = viewModelScope,
            post = post,
            items = selected,
            livePhotoMode = state.livePhotoSaveMode,
            subtitleRemoveMode = state.videoSubtitleRemoveMode,
            onTaskCreated = { newTask ->
                _uiState.update { s ->
                    s.copy(
                        tasks = listOf(newTask) + s.tasks,
                        statusNotice = "已加入 ${selected.size} 项下载任务（可在「任务」查看进度）"
                    )
                }
            },
            onTaskUpdated = { taskId, transform ->
                _uiState.update { s ->
                    s.copy(tasks = s.tasks.map { if (it.id == taskId) transform(it) else it })
                }
            }
        )
    }

    fun clearCompletedTasks() {
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.filterNot {
                    it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED
                }
            )
        }
    }

    fun openPlatformLogin(platform: PlatformType) {
        _uiState.update { it.copy(activeLoginPlatform = platform) }
    }

    fun closePlatformLogin() {
        refreshCookieStatuses()
        _uiState.update { it.copy(activeLoginPlatform = null) }
    }

    fun clearPlatformLogin(platform: PlatformType) {
        cookieVault.clearPlatformCookies(platform)
        refreshCookieStatuses()
        _uiState.update { it.copy(statusNotice = "已清除 ${platform.displayName} 本地登录凭证") }
    }

    fun saveManualCookie(platform: PlatformType, cookieText: String) {
        cookieVault.setManualCookie(platform, cookieText)
        refreshCookieStatuses()
        _uiState.update { it.copy(statusNotice = "已更新 ${platform.displayName} 手动 Cookie") }
    }

    fun refreshCookieStatuses() {
        val map = listOf(
            PlatformType.DOUYIN,
            PlatformType.XIAOHONGSHU,
            PlatformType.X_TWITTER,
            PlatformType.YOUTUBE
        ).associateWith { cookieVault.hasCookie(it) }
        _uiState.update { it.copy(cookieStatusMap = map) }
    }

    fun updateYtDlpEngine(useNightly: Boolean = false) {
        if (_uiState.value.isUpdatingEngine) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingEngine = true, engineUpdateMessage = "正在更新 yt-dlp 本地内核...") }
            val result = ytDlpEngine.updateEngine(useNightly)
            _uiState.update {
                it.copy(
                    isUpdatingEngine = false,
                    engineUpdateMessage = result.getOrElse { err -> "更新失败: ${err.message}" }
                )
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun checkForAppUpdate(manual: Boolean) {
        if (_uiState.value.isCheckingAppUpdate || _uiState.value.isDownloadingUpdate) return
        viewModelScope.launch {
            if (manual) {
                _uiState.update {
                    it.copy(
                        isCheckingAppUpdate = true,
                        appUpdateMessage = "正在检查 GitHub 新版本..."
                    )
                }
            } else {
                _uiState.update { it.copy(isCheckingAppUpdate = true) }
            }
            val res = appUpdateManager.checkLatestRelease(BuildConfig.VERSION_NAME)
            res.onSuccess { release ->
                if (release == null) {
                    _uiState.update {
                        it.copy(
                            isCheckingAppUpdate = false,
                            availableUpdate = null,
                            appUpdateMessage = if (manual) "当前已是最新版本 (v${BuildConfig.VERSION_NAME})" else it.appUpdateMessage
                        )
                    }
                } else {
                    val ignoredTag = prefs.getString("ignored_update_tag", "").orEmpty()
                    val shouldPopDialog = manual || release.tagName != ignoredTag
                    _uiState.update {
                        it.copy(
                            isCheckingAppUpdate = false,
                            availableUpdate = release,
                            showUpdateDialog = shouldPopDialog,
                            appUpdateMessage = "发现新版本 ${release.tagName}"
                        )
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCheckingAppUpdate = false,
                        appUpdateMessage = if (manual) "检查更新失败: ${err.message ?: "网络异常"}" else it.appUpdateMessage
                    )
                }
            }
        }
    }

    fun openAvailableUpdateDialog() {
        if (_uiState.value.availableUpdate != null) {
            _uiState.update { it.copy(showUpdateDialog = true) }
        }
    }

    fun dismissUpdateDialog(ignoreThisVersion: Boolean = false) {
        val tag = _uiState.value.availableUpdate?.tagName
        if (ignoreThisVersion && !tag.isNullOrBlank()) {
            prefs.edit().putString("ignored_update_tag", tag).apply()
        }
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdateDownloadAndInstall() {
        val release = _uiState.value.availableUpdate ?: return
        if (_uiState.value.isDownloadingUpdate) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingUpdate = true,
                    updateDownloadProgress = 0f,
                    updateDownloadStatus = "准备下载 ${release.tagName}...",
                    appUpdateMessage = null
                )
            }
            val result = appUpdateManager.downloadUpdateApk(release) { progress, msg ->
                _uiState.update {
                    it.copy(
                        updateDownloadProgress = progress,
                        updateDownloadStatus = msg
                    )
                }
            }
            result.onSuccess { apkFile ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateDownloadProgress = 1f,
                        updateDownloadStatus = "下载完成，正在拉起系统安装器...",
                        downloadedUpdateApkPath = apkFile.absolutePath
                    )
                }
                installDownloadedUpdate()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDownloadingUpdate = false,
                        updateDownloadStatus = "",
                        appUpdateMessage = "更新包下载失败: ${err.message ?: "请检查网络后重试"}"
                    )
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val path = _uiState.value.downloadedUpdateApkPath ?: return
        val result = appUpdateManager.launchApkInstaller(app, File(path))
        when (result) {
            InstallLaunchResult.LAUNCHED -> {
                _uiState.update { it.copy(showUpdateDialog = false, statusNotice = "已打开系统安装器，按提示确认升级") }
            }
            InstallLaunchResult.NEED_PERMISSION -> {
                _uiState.update {
                    it.copy(appUpdateMessage = "请在系统设置中允许「叠影提取」安装未知应用，授权后返回点击「立即安装」")
                }
            }
            InstallLaunchResult.FAILED -> {
                _uiState.update {
                    it.copy(appUpdateMessage = "拉起系统安装器失败，可点击「浏览器打开」前往 GitHub 下载页面安装")
                }
            }
        }
    }
}
