package com.foldmedia.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.foldmedia.app.engine.YtDlpEngine
import com.foldmedia.app.model.DownloadStatus
import com.foldmedia.app.model.DownloadTask
import com.foldmedia.app.model.LivePhotoSaveMode
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.ui.MainUiState
import com.foldmedia.app.ui.components.KindBadgePill
import com.foldmedia.app.ui.components.isNativeAppInstalled
import com.foldmedia.app.ui.components.launchNativePlatformApp
import com.foldmedia.app.ui.fold.FoldPreviewOverride
import com.foldmedia.app.ui.fold.FoldableUiMetrics
import com.foldmedia.app.ui.theme.FoldColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TasksScreen(
    uiState: MainUiState,
    foldMetrics: FoldableUiMetrics,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        if (uiState.tasks.any { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClearFinished)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = FoldColors.HyperBlue
                    )
                    Text("清理已完成", color = FoldColors.HyperBlue, fontSize = 13.sp)
                }
            }
        }

        if (uiState.tasks.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("暂无提取任务", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        text = "在「提取」页勾选图片、实况照片或视频后，会自动加入此处并发写入小米系统相册",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            return
        }

        val columns = if (foldMetrics.isDualPane) 2 else 1
        LazyColumn(
            contentPadding = PaddingValues(bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val chunked = uiState.tasks.chunked(columns)
            items(chunked) { rowTasks ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowTasks.forEach { task ->
                        TaskItemCard(
                            task = task,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowTasks.size < columns) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskItemCard(
    task: DownloadTask,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (!task.item.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = task.item.imageUrl,
                            contentDescription = task.item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KindBadgePill(kind = task.item.kind)
                        Text(
                            text = task.platform.shortBadge,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Text(
                        text = "${task.postTitle} · ${task.item.title}",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = task.errorMessage ?: task.statusMessage,
                        fontSize = 12.sp,
                        color = if (task.status == DownloadStatus.FAILED) FoldColors.DangerRed
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when (task.status) {
                    DownloadStatus.COMPLETED -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = FoldColors.SuccessGreen
                    )
                    DownloadStatus.FAILED -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = FoldColors.DangerRed
                    )
                    else -> Text(
                        text = "${(task.progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FoldColors.HyperBlue
                    )
                }
            }

            if (task.status != DownloadStatus.COMPLETED && task.status != DownloadStatus.FAILED) {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = FoldColors.HyperBlue
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnginesAndAccountsScreen(
    uiState: MainUiState,
    ytDlpStatus: YtDlpEngine.EngineStatus,
    foldMetrics: FoldableUiMetrics,
    onUpdateEngine: (Boolean) -> Unit,
    onOpenLogin: (PlatformType) -> Unit,
    onClearLogin: (PlatformType) -> Unit,
    onSaveManualCookie: (PlatformType, String) -> Unit,
    onChangeLivePhotoMode: (LivePhotoSaveMode) -> Unit,
    onChangeFoldOverride: (FoldPreviewOverride) -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms = listOf(
        PlatformType.XIAOHONGSHU,
        PlatformType.DOUYIN,
        PlatformType.X_TWITTER,
        PlatformType.YOUTUBE
    )

    if (foldMetrics.isDualPane) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 104.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max)
            ) {
                Box(
                    modifier = Modifier
                        .weight(foldMetrics.leftPaneWeight)
                        .fillMaxHeight()
                ) {
                    UnifiedSettingsCard(
                        uiState = uiState,
                        ytDlpStatus = ytDlpStatus,
                        foldMetrics = foldMetrics,
                        onChangeFoldOverride = onChangeFoldOverride,
                        onChangeLivePhotoMode = onChangeLivePhotoMode,
                        onUpdateEngine = onUpdateEngine,
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Spacer(modifier = Modifier.width(foldMetrics.hingeSpacerDp))

                Column(
                    modifier = Modifier
                        .weight(foldMetrics.rightPaneWeight)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    platforms.forEach { platform ->
                        PlatformAccountCard(
                            platform = platform,
                            hasCookie = uiState.cookieStatusMap[platform] == true,
                            onOpenLogin = { onOpenLogin(platform) },
                            onClearLogin = { onClearLogin(platform) },
                            onSaveManualCookie = { onSaveManualCookie(platform, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            UnifiedSettingsCard(
                uiState = uiState,
                ytDlpStatus = ytDlpStatus,
                foldMetrics = foldMetrics,
                onChangeFoldOverride = onChangeFoldOverride,
                onChangeLivePhotoMode = onChangeLivePhotoMode,
                onUpdateEngine = onUpdateEngine
            )
        }
        items(platforms) { platform ->
            PlatformAccountCard(
                platform = platform,
                hasCookie = uiState.cookieStatusMap[platform] == true,
                onOpenLogin = { onOpenLogin(platform) },
                onClearLogin = { onClearLogin(platform) },
                onSaveManualCookie = { onSaveManualCookie(platform, it) }
            )
        }
    }
}

private fun spacedBetweenMin(minGap: Dp): Arrangement.Vertical = object : Arrangement.Vertical {
    override val spacing: Dp = minGap
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val minGapPx = minGap.roundToPx()
        val contentSum = sizes.sum() + minGapPx * (sizes.size - 1).coerceAtLeast(0)
        if (sizes.size <= 1 || totalSize <= contentSum) {
            with(Arrangement.spacedBy(minGap)) { arrange(totalSize, sizes, outPositions) }
        } else {
            with(Arrangement.SpaceBetween) { arrange(totalSize, sizes, outPositions) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnifiedSettingsCard(
    uiState: MainUiState,
    ytDlpStatus: YtDlpEngine.EngineStatus,
    foldMetrics: FoldableUiMetrics,
    onChangeFoldOverride: (FoldPreviewOverride) -> Unit,
    onChangeLivePhotoMode: (LivePhotoSaveMode) -> Unit,
    onUpdateEngine: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = spacedBetweenMin(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "实况照片（Live Photo）默认保存方式",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                LivePhotoSaveMode.entries.forEach { mode ->
                    val selected = uiState.livePhotoSaveMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) FoldColors.HyperBlue.copy(alpha = 0.12f)
                                else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            )
                            .clickable { onChangeLivePhotoMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                text = mode.description,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = FoldColors.HyperBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "界面布局偏好（当前：${foldMetrics.posture.badgeLabel}）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FoldPreviewOverride.entries.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { mode ->
                                val selected = uiState.foldPreviewOverride == mode
                                SelectableChip(
                                    label = mode.label,
                                    selected = selected,
                                    onClick = { onChangeFoldOverride(mode) },
                                    centerText = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "本地解析内核 (yt-dlp + FFmpeg)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "版本: ${ytDlpStatus.version} · FFmpeg ${if (ytDlpStatus.ffmpegReady) "✓" else "×"}",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                uiState.engineUpdateMessage?.let { msg ->
                    Text(text = msg, fontSize = 11.sp, color = FoldColors.HyperBlue)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onUpdateEngine(false) },
                        enabled = !uiState.isUpdatingEngine,
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (uiState.isUpdatingEngine) "更新中..." else "更新稳定版",
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                    Button(
                        onClick = { onUpdateEngine(true) },
                        enabled = !uiState.isUpdatingEngine,
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Nightly 版", fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformAccountCard(
    platform: PlatformType,
    hasCookie: Boolean,
    onOpenLogin: () -> Unit,
    onClearLogin: () -> Unit,
    onSaveManualCookie: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasNativeApp = remember(platform) { isNativeAppInstalled(context, platform) }
    var showManual by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = spacedBetweenMin(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (hasCookie) FoldColors.SuccessGreen else Color.Gray)
                    )
                    Column {
                        Text(platform.displayName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            text = when {
                                hasCookie -> "已登录本地账号（仅本机使用）"
                                hasNativeApp -> "本机已装客户端 · 支持扫码与唤起 App 授权"
                                else -> "未登录（公开内容可直接免登解析）"
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenLogin,
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(
                            text = if (hasCookie) "重登" else "扫码/授权",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                    if (hasCookie) {
                        Button(
                            onClick = onClearLogin,
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("清除", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showManual = !showManual }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = FoldColors.HyperBlue
                    )
                    Text(
                        text = if (showManual) "收起手动 Cookie 输入" else "或手动粘贴 Cookie",
                        fontSize = 12.sp,
                        color = FoldColors.HyperBlue
                    )
                }

                if (hasNativeApp) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { launchNativePlatformApp(context, platform) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = "打开${platform.shortBadge} App",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            if (showManual) {
                TextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = "粘贴 ${platform.displayName} Cookie",
                    useLabelAsPlaceholder = true,
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onSaveManualCookie(cookieInput)
                        showManual = false
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存手动 Cookie", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    centerText: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) FoldColors.HyperBlue.copy(alpha = 0.16f)
                else MiuixTheme.colorScheme.secondaryContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = if (centerText) Alignment.Center else Alignment.CenterStart
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centerText) TextAlign.Center else TextAlign.Start,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) FoldColors.HyperBlue else MiuixTheme.colorScheme.onSurface
        )
    }
}
