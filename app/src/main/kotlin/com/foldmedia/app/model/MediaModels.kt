package com.foldmedia.app.model

import androidx.compose.ui.graphics.Color

/** Supported source platforms for on-device media extraction. */
enum class PlatformType(
    val displayName: String,
    val shortBadge: String,
    val accentColor: Color,
    val loginUrl: String,
    val cookieDomainHint: String
) {
    DOUYIN(
        displayName = "抖音",
        shortBadge = "抖音",
        accentColor = Color(0xFF161823),
        loginUrl = "https://www.douyin.com/",
        cookieDomainHint = "douyin.com"
    ),
    XIAOHONGSHU(
        displayName = "小红书",
        shortBadge = "小红书",
        accentColor = Color(0xFFFF2442),
        loginUrl = "https://www.xiaohongshu.com/",
        cookieDomainHint = "xiaohongshu.com"
    ),
    X_TWITTER(
        displayName = "X / Twitter",
        shortBadge = "X",
        accentColor = Color(0xFF1D9BF0),
        loginUrl = "https://x.com/i/flow/login",
        cookieDomainHint = "x.com"
    ),
    YOUTUBE(
        displayName = "YouTube",
        shortBadge = "油管",
        accentColor = Color(0xFFFF0033),
        loginUrl = "https://m.youtube.com/",
        cookieDomainHint = "youtube.com"
    ),
    WECHAT(
        displayName = "微信公众/贴图",
        shortBadge = "微信",
        accentColor = Color(0xFF07C160),
        loginUrl = "",
        cookieDomainHint = "weixin.qq.com"
    ),
    UNKNOWN(
        displayName = "通用链接",
        shortBadge = "通用",
        accentColor = Color(0xFF6B7280),
        loginUrl = "",
        cookieDomainHint = ""
    )
}

/** Media asset types extracted from a post. */
enum class MediaKind(val label: String) {
    IMAGE("原图"),
    LIVE_PHOTO("实况"),
    VIDEO("视频"),
    AUDIO("音频")
}

/** Downloadable format / stream option (e.g., YouTube resolutions or audio-only). */
data class QualityOption(
    val id: String,
    val label: String,
    val formatSelector: String,
    val isAudioOnly: Boolean = false,
    val resolution: String? = null
)

/** Single extractable item belonging to a parsed post. */
data class MediaItem(
    val id: String,
    val index: Int,
    val kind: MediaKind,
    val title: String,
    val imageUrl: String? = null,
    val videoUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val isSelected: Boolean = true,
    val qualityOptions: List<QualityOption> = emptyList(),
    val selectedQualityId: String? = null,
    val backupVideoUrls: List<String> = emptyList(),
    val requiresYtDlpDownload: Boolean = false
)

/** Parsed result returned by any local platform extractor. */
data class ExtractedPost(
    val id: String,
    val platform: PlatformType,
    val originalInput: String,
    val resolvedUrl: String,
    val title: String,
    val caption: String,
    val authorName: String,
    val authorHandle: String = "",
    val items: List<MediaItem>,
    val engineUsed: String,
    val extractedAtMillis: Long = System.currentTimeMillis()
) {
    val imageCount: Int get() = items.count { it.kind == MediaKind.IMAGE }
    val livePhotoCount: Int get() = items.count { it.kind == MediaKind.LIVE_PHOTO }
    val videoCount: Int get() = items.count { it.kind == MediaKind.VIDEO }
    val selectedCount: Int get() = items.count { it.isSelected }
}

/** User preference for how Live Photos (实况照片) should be saved on Xiaomi HyperOS. */
enum class LivePhotoSaveMode(val label: String, val description: String) {
    XIAOMI_MOTION_PHOTO(
        label = "小米实况照片 (MVIMG)",
        description = "合成标准 Motion Photo JPEG，小米相册直接识别"
    ),
    SEPARATE_FILES(
        label = "分开保存图片 + 视频",
        description = "分别导出静态原图与 MP4 微视频文件"
    ),
    BOTH(
        label = "双份保存 (实况 + 原件)",
        description = "既生成小米实况照片，又保留独立原图与 MP4"
    )
}

enum class DownloadStatus(val label: String) {
    QUEUED("排队中"),
    DOWNLOADING("下载中"),
    MERGING_LIVE_PHOTO("合成小米实况中"),
    SAVING_TO_GALLERY("写入系统相册"),
    COMPLETED("已保存到相册"),
    FAILED("失败")
}

data class DownloadTask(
    val id: String,
    val postTitle: String,
    val platform: PlatformType,
    val item: MediaItem,
    val livePhotoSaveMode: LivePhotoSaveMode,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val savedDisplayPaths: List<String> = emptyList(),
    val errorMessage: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

enum class AppTab(val label: String) {
    EXTRACT("提取"),
    TASKS("任务"),
    ENGINES("账号与设置")
}
