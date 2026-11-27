package com.foldmedia.app.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.foldmedia.app.model.MediaItem
import com.foldmedia.app.model.MediaKind
import com.foldmedia.app.ui.theme.FoldColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Full-resolution preview dialog supporting still images, live-photo micro-video playback,
 * direct MP4 playback, and yt-dlp quality tier selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPreviewDialog(
    item: MediaItem,
    onToggleSelect: () -> Unit,
    onSelectQuality: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var playMotion by remember(item.id) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .clickable(onClick = onDismiss)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            KindBadgePill(kind = item.kind)
                            Text(
                                text = item.title,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭预览"
                            )
                        }
                    }

                    MediaStageBox(
                        item = item,
                        playMotion = playMotion,
                        onTogglePlayMotion = { playMotion = !playMotion },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 440.dp)
                    )

                    if (item.qualityOptions.isNotEmpty()) {
                        Text(
                            text = "画质与格式选择",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item.qualityOptions.forEach { opt ->
                                val selected = opt.id == item.selectedQualityId
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) FoldColors.HyperBlue.copy(alpha = 0.16f)
                                            else MiuixTheme.colorScheme.secondaryContainer
                                        )
                                        .border(
                                            width = if (selected) 1.5.dp else 0.5.dp,
                                            color = if (selected) FoldColors.HyperBlue else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onSelectQuality(opt.id) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = opt.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) FoldColors.HyperBlue else MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onToggleSelect)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Checkbox(
                                state = ToggleableState(item.isSelected),
                                onClick = onToggleSelect
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (item.isSelected) "已勾选待保存" else "未勾选",
                                fontSize = 14.sp
                            )
                        }

                        Button(onClick = onDismiss) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable media stage used in both the Preview Dialog and Xiaomi 18 Fold Tabletop upper pane.
 */
@Composable
fun MediaStageBox(
    item: MediaItem,
    playMotion: Boolean,
    onTogglePlayMotion: () -> Unit,
    modifier: Modifier = Modifier
) {
    val streamUrl = item.videoUrl
    val canStreamInline = !item.requiresYtDlpDownload && !streamUrl.isNullOrBlank() &&
        (item.kind == MediaKind.LIVE_PHOTO || item.kind == MediaKind.VIDEO)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF101216)),
        contentAlignment = Alignment.Center
    ) {
        if (playMotion && !item.requiresYtDlpDownload && !streamUrl.isNullOrBlank() &&
            (item.kind == MediaKind.LIVE_PHOTO || item.kind == MediaKind.VIDEO)
        ) {
            InlineVideoPlayer(
                videoUrl = streamUrl,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (canStreamInline) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(onClick = onTogglePlayMotion)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (playMotion) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = when {
                            playMotion -> "停止预览"
                            item.kind == MediaKind.LIVE_PHOTO -> "试看实况"
                            else -> "播放视频"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (item.width > 0 && item.height > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${item.width} × ${item.height}",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun InlineVideoPlayer(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(videoUrl) {
        onDispose {
            runCatching { videoViewRef?.stopPlayback() }
        }
    }

    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                videoViewRef = this
                val headers = buildStreamHeaders(videoUrl)
                setVideoURI(Uri.parse(videoUrl), headers)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
            }
        },
        update = { view ->
            if (!view.isPlaying) {
                view.setVideoURI(Uri.parse(videoUrl), buildStreamHeaders(videoUrl))
                view.start()
            }
        },
        modifier = modifier
    )
}

private fun buildStreamHeaders(url: String): Map<String, String> = when {
    url.contains("douyin", ignoreCase = true) || url.contains("amemv", ignoreCase = true) ->
        mapOf("Referer" to "https://www.douyin.com/")
    url.contains("xhscdn", ignoreCase = true) || url.contains("xiaohongshu", ignoreCase = true) ->
        mapOf("Referer" to "https://www.xiaohongshu.com/")
    url.contains("twimg", ignoreCase = true) ->
        mapOf("Referer" to "https://x.com/")
    else -> emptyMap()
}

@Composable
fun KindBadgePill(kind: MediaKind) {
    val bg = when (kind) {
        MediaKind.LIVE_PHOTO -> FoldColors.LiveAmber.copy(alpha = 0.2f)
        MediaKind.VIDEO -> FoldColors.HyperBlue.copy(alpha = 0.18f)
        MediaKind.IMAGE -> FoldColors.SuccessGreen.copy(alpha = 0.18f)
        MediaKind.AUDIO -> Color(0xFF9333EA).copy(alpha = 0.18f)
    }
    val fg = when (kind) {
        MediaKind.LIVE_PHOTO -> FoldColors.LiveAmber
        MediaKind.VIDEO -> FoldColors.HyperBlue
        MediaKind.IMAGE -> FoldColors.SuccessGreen
        MediaKind.AUDIO -> Color(0xFF9333EA)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (kind == MediaKind.LIVE_PHOTO) {
            Icon(
                imageVector = Icons.Rounded.MotionPhotosOn,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = kind.label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
