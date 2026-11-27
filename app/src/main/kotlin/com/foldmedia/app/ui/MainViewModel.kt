package com.foldmedia.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.foldmedia.app.parser.LinkSanitizer
import com.foldmedia.app.ui.fold.FoldPreviewOverride
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
    val extractError: String? = null,
    val currentPost: ExtractedPost? = null,
    val historyPosts: List<ExtractedPost> = emptyList(),
    val tasks: List<DownloadTask> = emptyList(),
    val livePhotoSaveMode: LivePhotoSaveMode = LivePhotoSaveMode.XIAOMI_MOTION_PHOTO,
    val foldPreviewOverride: FoldPreviewOverride = FoldPreviewOverride.AUTO,
    val previewItem: MediaItem? = null,
    val showPreviewDialog: Boolean = false,
    val activeLoginPlatform: PlatformType? = null,
    val isUpdatingEngine: Boolean = false,
    val engineUpdateMessage: String? = null,
    val cookieStatusMap: Map<PlatformType, Boolean> = emptyMap(),
    val clipboardBannerUrl: String? = null,
    val statusNotice: String? = null
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
    private val coordinator = DownloadManagerCoordinator(
        appContext = app,
        httpClient = app.okHttpClient,
        cookieVault = cookieVault,
        extractorRepository = repository,
        ytDlpEngine = ytDlpEngine,
        mediaSaver = app.mediaSaver
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val ytDlpStatus: StateFlow<YtDlpEngine.EngineStatus> = ytDlpEngine.status

    init {
        refreshCookieStatuses()
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
            _uiState.update { it.copy(extractError = "请先粘贴抖音、小红书、X 或 YouTube 的分享文本/链接") }
            return
        }
        if (_uiState.value.isExtracting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExtracting = true, extractError = null, statusNotice = null) }
            val result = runCatching { repository.extract(raw) }
            result.onSuccess { post ->
                _uiState.update { state ->
                    val updatedHistory = listOf(post) + state.historyPosts.filterNot { it.id == post.id && it.platform == post.platform }.take(14)
                    state.copy(
                        isExtracting = false,
                        currentPost = post,
                        historyPosts = updatedHistory,
                        detectedPlatform = post.platform,
                        previewItem = post.items.firstOrNull(),
                        extractError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        extractError = err.message ?: "解析失败，请检查网络或尝试登录对应平台"
                    )
                }
            }
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
}
