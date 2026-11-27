package com.foldmedia.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.foldmedia.app.model.ExtractedPost
import com.foldmedia.app.model.LivePhotoSaveMode
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.ui.MainUiState
import com.foldmedia.app.ui.components.KindBadgePill
import com.foldmedia.app.ui.components.MediaStageBox
import com.foldmedia.app.ui.fold.FoldPosture
import com.foldmedia.app.ui.fold.FoldableUiMetrics
import com.foldmedia.app.ui.theme.FoldColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Adaptive extraction workspace built for Xiaomi 18 Fold:
 * 1) COVER_COMPACT (5.38" wide cover screen): sleek vertical flow + 2-col batch grid + bottom bar
 * 2) INNER_DUAL_FLAT / INNER_BOOK_HALF (7.58" inner display): Miuix dual-pane avoiding the fold crease
 * 3) INNER_TABLETOP_HALF (Half-opened horizontal): Upper stage preview + lower batch control strip
 */
@Composable
fun ExtractWorkspaceScreen(
    uiState: MainUiState,
    foldMetrics: FoldableUiMetrics,
    onUrlChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onApplyClipboardBanner: () -> Unit,
    onDismissClipboardBanner: () -> Unit,
    onExtract: () -> Unit,
    onSelectHistoryPost: (ExtractedPost) -> Unit,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOnlyLive: () -> Unit,
    onSelectOnlyImages: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectQuality: (String, String) -> Unit,
    onChangeLiveMode: (LivePhotoSaveMode) -> Unit,
    onOpenPreview: (MediaItem) -> Unit,
    onDownloadSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        foldMetrics.isTabletop -> TabletopExtractLayout(
            uiState = uiState,
            foldMetrics = foldMetrics,
            onUrlChange = onUrlChange,
            onPasteFromClipboard = onPasteFromClipboard,
            onExtract = onExtract,
            onToggleItem = onToggleItem,
            onSelectAll = onSelectAll,
            onSelectOnlyLive = onSelectOnlyLive,
            onOpenPreview = onOpenPreview,
            onDownloadSelected = onDownloadSelected,
            modifier = modifier
        )

        foldMetrics.isDualPane -> DualPaneInnerLayout(
            uiState = uiState,
            foldMetrics = foldMetrics,
            onUrlChange = onUrlChange,
            onPasteFromClipboard = onPasteFromClipboard,
            onApplyClipboardBanner = onApplyClipboardBanner,
            onDismissClipboardBanner = onDismissClipboardBanner,
            onExtract = onExtract,
            onSelectHistoryPost = onSelectHistoryPost,
            onToggleItem = onToggleItem,
            onSelectAll = onSelectAll,
            onSelectOnlyLive = onSelectOnlyLive,
            onSelectOnlyImages = onSelectOnlyImages,
            onClearSelection = onClearSelection,
            onSelectQuality = onSelectQuality,
            onChangeLiveMode = onChangeLiveMode,
            onOpenPreview = onOpenPreview,
            onDownloadSelected = onDownloadSelected,
            modifier = modifier
        )

        else -> CoverCompactLayout(
            uiState = uiState,
            foldMetrics = foldMetrics,
            onUrlChange = onUrlChange,
            onPasteFromClipboard = onPasteFromClipboard,
            onApplyClipboardBanner = onApplyClipboardBanner,
            onDismissClipboardBanner = onDismissClipboardBanner,
            onExtract = onExtract,
            onToggleItem = onToggleItem,
            onSelectAll = onSelectAll,
            onSelectOnlyLive = onSelectOnlyLive,
            onSelectOnlyImages = onSelectOnlyImages,
            onClearSelection = onClearSelection,
            onSelectQuality = onSelectQuality,
            onChangeLiveMode = onChangeLiveMode,
            onOpenPreview = onOpenPreview,
            onDownloadSelected = onDownloadSelected,
            modifier = modifier
        )
    }
}

@Composable
private fun CoverCompactLayout(
    uiState: MainUiState,
    foldMetrics: FoldableUiMetrics,
    onUrlChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onApplyClipboardBanner: () -> Unit,
    onDismissClipboardBanner: () -> Unit,
    onExtract: () -> Unit,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOnlyLive: () -> Unit,
    onSelectOnlyImages: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectQuality: (String, String) -> Unit,
    onChangeLiveMode: (LivePhotoSaveMode) -> Unit,
    onOpenPreview: (MediaItem) -> Unit,
    onDownloadSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!uiState.clipboardBannerUrl.isNullOrBlank()) {
            item {
                ClipboardBannerCard(
                    text = uiState.clipboardBannerUrl,
                    onExtract = onApplyClipboardBanner,
                    onDismiss = onDismissClipboardBanner
                )
            }
        }

        item {
            LinkInputCard(
                uiState = uiState,
                onUrlChange = onUrlChange,
                onPasteFromClipboard = onPasteFromClipboard,
                onExtract = onExtract
            )
        }

        val post = uiState.currentPost
        if (post != null) {
            item {
                PostSummaryAndToolbarCard(
                    post = post,
                    livePhotoSaveMode = uiState.livePhotoSaveMode,
                    onChangeLiveMode = onChangeLiveMode,
                    onSelectAll = onSelectAll,
                    onSelectOnlyLive = onSelectOnlyLive,
                    onSelectOnlyImages = onSelectOnlyImages,
                    onClearSelection = onClearSelection,
                    onSelectQuality = onSelectQuality,
                    onDownloadSelected = onDownloadSelected
                )
            }

            val rows = post.items.chunked(if (post.items.size == 1) 1 else foldMetrics.gridColumnCount)
            items(rows) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowItems.forEach { item ->
                        MediaGridTile(
                            item = item,
                            isSingleFullCard = post.items.size == 1,
                            onToggle = { onToggleItem(item.id) },
                            onPreview = { onOpenPreview(item) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (post.items.size > 1 && rowItems.size < foldMetrics.gridColumnCount) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            item {
                SupportedPlatformsGuideCard()
            }
        }
    }
}

@Composable
private fun DualPaneInnerLayout(
    uiState: MainUiState,
    foldMetrics: FoldableUiMetrics,
    onUrlChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onApplyClipboardBanner: () -> Unit,
    onDismissClipboardBanner: () -> Unit,
    onExtract: () -> Unit,
    onSelectHistoryPost: (ExtractedPost) -> Unit,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOnlyLive: () -> Unit,
    onSelectOnlyImages: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectQuality: (String, String) -> Unit,
    onChangeLiveMode: (LivePhotoSaveMode) -> Unit,
    onOpenPreview: (MediaItem) -> Unit,
    onDownloadSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Left Pane: Input & Recent History List (or quick share tip)
        LazyColumn(
            modifier = Modifier
                .weight(foldMetrics.leftPaneWeight)
                .fillMaxHeight(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.clipboardBannerUrl.isNullOrBlank()) {
                item {
                    ClipboardBannerCard(
                        text = uiState.clipboardBannerUrl,
                        onExtract = onApplyClipboardBanner,
                        onDismiss = onDismissClipboardBanner
                    )
                }
            }

            item {
                LinkInputCard(
                    uiState = uiState,
                    onUrlChange = onUrlChange,
                    onPasteFromClipboard = onPasteFromClipboard,
                    onExtract = onExtract
                )
            }

            if (uiState.historyPosts.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "最近提取",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "${uiState.historyPosts.size} 条",
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            uiState.historyPosts.forEach { historyPost ->
                                val current = uiState.currentPost
                                val isCurrent = current != null &&
                                    historyPost.id == current.id &&
                                    historyPost.platform == current.platform
                                val coverThumb = historyPost.items.firstOrNull()?.imageUrl
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isCurrent) FoldColors.HyperBlue.copy(alpha = 0.08f)
                                            else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                                        )
                                        .clickable { onSelectHistoryPost(historyPost) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MiuixTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!coverThumb.isNullOrBlank()) {
                                            AsyncImage(
                                                model = coverThumb,
                                                contentDescription = historyPost.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            PlatformBadgePill(platform = historyPost.platform)
                                        }
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = historyPost.title,
                                            fontSize = 13.sp,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (isCurrent) FoldColors.HyperBlue else MiuixTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${historyPost.platform.shortBadge} · ${historyPost.items.size} 项媒体",
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    }
                                    if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(FoldColors.HyperBlue)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    QuickWorkflowHintCard()
                }
            }
        }

        // Hinge Safe Area Spacer for Xiaomi 18 Fold
        Spacer(modifier = Modifier.width(foldMetrics.hingeSpacerDp))

        // Right Pane: Multi-Image Checkbox Grid scrolling continuously beneath the translucent Liquid Glass Dock
        Box(
            modifier = Modifier
                .weight(foldMetrics.rightPaneWeight)
                .fillMaxHeight()
        ) {
            val post = uiState.currentPost
            if (post == null) {
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    SupportedPlatformsGuideCard(modifier = Modifier.fillMaxWidth())
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(foldMetrics.gridColumnCount),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        PostSummaryAndToolbarCard(
                            post = post,
                            livePhotoSaveMode = uiState.livePhotoSaveMode,
                            onChangeLiveMode = onChangeLiveMode,
                            onSelectAll = onSelectAll,
                            onSelectOnlyLive = onSelectOnlyLive,
                            onSelectOnlyImages = onSelectOnlyImages,
                            onClearSelection = onClearSelection,
                            onSelectQuality = onSelectQuality,
                            onDownloadSelected = onDownloadSelected
                        )
                    }
                    if (post.items.size == 1) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            val singleItem = post.items.first()
                            MediaGridTile(
                                item = singleItem,
                                isSingleFullCard = true,
                                onToggle = { onToggleItem(singleItem.id) },
                                onPreview = { onOpenPreview(singleItem) }
                            )
                        }
                    } else {
                        items(post.items, key = { it.id }) { item ->
                            MediaGridTile(
                                item = item,
                                isSingleFullCard = false,
                                onToggle = { onToggleItem(item.id) },
                                onPreview = { onOpenPreview(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletopExtractLayout(
    uiState: MainUiState,
    foldMetrics: FoldableUiMetrics,
    onUrlChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onExtract: () -> Unit,
    onToggleItem: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOnlyLive: () -> Unit,
    onOpenPreview: (MediaItem) -> Unit,
    onDownloadSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val post = uiState.currentPost
    val focusedItem = uiState.previewItem ?: post?.items?.firstOrNull()
    var playMotion by remember(focusedItem?.id) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top half above horizontal hinge: Live Photo / Video Stage
        Box(
            modifier = Modifier
                .weight(foldMetrics.leftPaneWeight)
                .fillMaxWidth()
        ) {
            if (focusedItem != null) {
                MediaStageBox(
                    item = focusedItem,
                    playMotion = playMotion,
                    onTogglePlayMotion = { playMotion = !playMotion },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                SupportedPlatformsGuideCard(modifier = Modifier.fillMaxSize())
            }
        }

        // Physical horizontal hinge gap
        Spacer(modifier = Modifier.height(foldMetrics.hingeSpacerDp))

        // Bottom half below horizontal hinge: Link input + horizontal multi-select strip
        Column(
            modifier = Modifier
                .weight(foldMetrics.rightPaneWeight)
                .fillMaxWidth()
                .padding(bottom = 76.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LinkInputCard(
                uiState = uiState,
                onUrlChange = onUrlChange,
                onPasteFromClipboard = onPasteFromClipboard,
                onExtract = onExtract,
                compact = true
            )

            if (post != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectableChip(label = "全选", selected = false, onClick = onSelectAll)
                        if (post.livePhotoCount > 0) {
                            SelectableChip(label = "仅实况", selected = false, onClick = onSelectOnlyLive)
                        }
                    }
                    Button(
                        onClick = onDownloadSelected,
                        enabled = post.selectedCount > 0,
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text("保存已选 (${post.selectedCount})", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(post.items, key = { it.id }) { item ->
                        MediaGridTile(
                            item = item,
                            onToggle = { onToggleItem(item.id) },
                            onPreview = { onOpenPreview(item) },
                            modifier = Modifier
                                .width(136.dp)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkInputCard(
    uiState: MainUiState,
    onUrlChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onExtract: () -> Unit,
    compact: Boolean = false
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "链接 / 分享口令提取",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (uiState.detectedPlatform != PlatformType.UNKNOWN) {
                        PlatformBadgePill(platform = uiState.detectedPlatform)
                    }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onPasteFromClipboard)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = FoldColors.HyperBlue
                    )
                    Text("粘贴", fontSize = 12.sp, color = FoldColors.HyperBlue)
                }
            }

            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer)
                    .border(
                        width = if (isFocused) 1.5.dp else 0.dp,
                        color = if (isFocused) FoldColors.HyperBlue else Color.Transparent,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = uiState.urlInput,
                    onValueChange = onUrlChange,
                    singleLine = compact,
                    minLines = if (compact) 1 else 2,
                    maxLines = if (compact) 1 else 4,
                    cursorBrush = SolidColor(FoldColors.HyperBlue),
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = if (uiState.urlInput.isNotBlank()) 24.dp else 0.dp)
                        .onFocusChanged { isFocused = it.isFocused },
                    decorationBox = { innerTextField ->
                        Box {
                            if (uiState.urlInput.isEmpty()) {
                                Text(
                                    text = "粘贴抖音 / 小红书分享文案、X 或 YouTube 链接",
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (uiState.urlInput.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "清空",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { onUrlChange("") }
                    )
                }
            }

            if (!uiState.extractError.isNullOrBlank()) {
                Text(
                    text = uiState.extractError,
                    fontSize = 12.sp,
                    color = FoldColors.DangerRed
                )
            }

            Button(
                onClick = onExtract,
                enabled = !uiState.isExtracting,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("本地引擎解析中...", color = MiuixTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("立即提取视频 / 原图 / 实况", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostSummaryAndToolbarCard(
    post: ExtractedPost,
    livePhotoSaveMode: LivePhotoSaveMode,
    onChangeLiveMode: (LivePhotoSaveMode) -> Unit,
    onSelectAll: () -> Unit,
    onSelectOnlyLive: () -> Unit,
    onSelectOnlyImages: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectQuality: (String, String) -> Unit,
    onDownloadSelected: () -> Unit
) {
    val context = LocalContext.current
    var captionExpanded by remember(post.id) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlatformBadgePill(platform = post.platform)
                    Text(
                        text = post.engineUsed,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                if (post.caption.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("caption", post.caption))
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = FoldColors.HyperBlue
                        )
                        Text("复制文案", fontSize = 12.sp, color = FoldColors.HyperBlue)
                    }
                }
            }

            Text(
                text = post.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (post.authorName.isNotBlank() || post.caption.isNotBlank()) {
                val sub = buildString {
                    if (post.authorName.isNotBlank()) append("作者: ${post.authorName}")
                    if (post.authorHandle.isNotBlank()) append(" (${post.authorHandle})")
                }
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                if (post.caption.isNotBlank() && post.caption != post.title) {
                    Text(
                        text = post.caption,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = if (captionExpanded) 8 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { captionExpanded = !captionExpanded }
                    )
                }
            }

            // Quality pills when a video item exposes multiple streams (e.g. YouTube 4K/1080P/M4A)
            post.items.firstOrNull { it.qualityOptions.isNotEmpty() }?.let { videoItem ->
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    videoItem.qualityOptions.forEach { opt ->
                        SelectableChip(
                            label = opt.label,
                            selected = opt.id == videoItem.selectedQualityId,
                            onClick = { onSelectQuality(videoItem.id, opt.id) }
                        )
                    }
                }
            }

            if (post.livePhotoCount > 0) {
                val entries = LivePhotoSaveMode.entries
                val nextMode = entries[(entries.indexOf(livePhotoSaveMode) + 1) % entries.size]
                SelectableChip(
                    label = "实况策略: ${livePhotoSaveMode.label} (点此切换)",
                    selected = true,
                    onClick = { onChangeLiveMode(nextMode) }
                )
            }

            if (post.items.size > 1) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectableChip(
                        label = "全选 (${post.items.size})",
                        selected = post.selectedCount == post.items.size,
                        onClick = onSelectAll
                    )
                    if (post.livePhotoCount > 0) {
                        SelectableChip(
                            label = "仅实况 (${post.livePhotoCount})",
                            selected = false,
                            onClick = onSelectOnlyLive
                        )
                    }
                    if (post.imageCount > 0) {
                        SelectableChip(
                            label = "仅静态图 (${post.imageCount})",
                            selected = false,
                            onClick = onSelectOnlyImages
                        )
                    }
                    SelectableChip(
                        label = "清空勾选",
                        selected = post.selectedCount == 0,
                        onClick = onClearSelection
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已选 ${post.selectedCount} / ${post.items.size} 项",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    val liveSelected = post.items.count { it.isSelected && it.kind == MediaKind.LIVE_PHOTO }
                    if (liveSelected > 0) {
                        Text(
                            text = "含 $liveSelected 张实况照片，按小米规范导出",
                            fontSize = 11.sp,
                            color = FoldColors.LiveAmber
                        )
                    }
                }
                Button(
                    onClick = onDownloadSelected,
                    enabled = post.selectedCount > 0,
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "保存选中",
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridTile(
    item: MediaItem,
    isSingleFullCard: Boolean = false,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (item.isSelected && !isSingleFullCard) FoldColors.HyperBlue else Color.Transparent
    val tileRatio = if (isSingleFullCard) {
        if (item.width > 0 && item.height > 0) {
            (item.width.toFloat() / item.height.toFloat()).coerceIn(0.85f, 1.78f)
        } else {
            1.6f
        }
    } else {
        0.82f
    }

    Box(
        modifier = modifier
            .aspectRatio(tileRatio)
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .border(
                width = if (item.isSelected && !isSingleFullCard) 2.dp else 0.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onPreview)
    ) {
        if (!item.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        if (item.kind == MediaKind.VIDEO && isSingleFullCard) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Top-left type badge (原图 / 实况 / 视频)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (item.kind == MediaKind.LIVE_PHOTO) {
                    Icon(
                        imageVector = Icons.Rounded.MotionPhotosOn,
                        contentDescription = null,
                        tint = FoldColors.LiveAmber,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = item.kind.label,
                    color = if (item.kind == MediaKind.LIVE_PHOTO) FoldColors.LiveAmber else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Sleek circular HyperOS/iOS-style check badge (shown cleanly for multi-item batch grids & optional toggle)
        if (!isSingleFullCard) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(onClick = onToggle)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.isSelected) FoldColors.HyperBlue
                        else Color.Black.copy(alpha = 0.28f)
                    )
                    .border(
                        width = 1.5.dp,
                        color = Color.White.copy(alpha = if (item.isSelected) 0.95f else 0.85f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (item.isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "已选",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // Bottom caption strip
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.48f))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.width > 0 && item.height > 0) {
                    Text(
                        text = "${item.width}×${item.height}",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickWorkflowHintCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "快捷导入方式",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = "在抖音、小红书、X 或 YouTube 内点击「分享 -> 叠影提取」，或直接复制链接后回到本页即可自动识别。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun ClipboardBannerCard(
    text: String,
    onExtract: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(FoldColors.HyperBlue.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "检测到剪贴板分享链接",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = FoldColors.HyperBlue
                )
                Text(
                    text = text,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onExtract,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(
                        text = "一键提取",
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "忽略",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportedPlatformsGuideCard(modifier: Modifier = Modifier) {
    val platformCards = listOf(
        Triple(PlatformType.DOUYIN, "无水印视频 · 图集 · 实况", "自动清洗分享口令，识别图集内嵌实况视频流"),
        Triple(PlatformType.XIAOHONGSHU, "无水印原图 · 小米实况", "本地提取高清原图并合成 MVIMG 动态照片"),
        Triple(PlatformType.X_TWITTER, "多图原图 · 高清视频", "自动请求 name=orig 无压缩原图与最高码率 MP4"),
        Triple(PlatformType.YOUTUBE, "4K / 1080P · 纯音频", "内置 yt-dlp + FFmpeg + Aria2c 本地混流导出")
    )

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "支持平台与本地提取能力",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            platformCards.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { (platform, subtitle, detail) ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            PlatformBadgePill(platform = platform)
                            Text(
                                text = subtitle,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = detail,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlatformBadgePill(platform: PlatformType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(platform.accentColor.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = platform.shortBadge,
            color = if (platform == PlatformType.DOUYIN) MiuixTheme.colorScheme.onSurface else platform.accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
