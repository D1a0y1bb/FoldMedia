package com.foldmedia.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foldmedia.app.BuildConfig
import com.foldmedia.app.ui.theme.FoldColors
import com.foldmedia.app.update.AppReleaseInfo
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    downloadStatus: String,
    downloadedApkReady: Boolean,
    errorMessage: String?,
    onDownloadAndInstall: () -> Unit,
    onInstallReadyApk: () -> Unit,
    onDismiss: (ignoreThisVersion: Boolean) -> Unit
) {
    val context = LocalContext.current
    val sizeMb = if (release.apkSizeBytes > 0L) {
        String.format("%.1f MB", release.apkSizeBytes / (1024.0 * 1024.0))
    } else {
        "APK 安装包"
    }

    Dialog(
        onDismissRequest = { if (!isDownloading) onDismiss(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(FoldColors.HyperBlue.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint = FoldColors.HyperBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "发现新版本 ${release.tagName}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} → ${release.tagName} · $sizeMb",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = release.releaseNotes.ifBlank { "包含体验优化与稳定性修复，建议升级到最新版本。" },
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }

                    if (isDownloading) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = { downloadProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = FoldColors.HyperBlue
                            )
                            Text(
                                text = downloadStatus.ifBlank { "正在下载新版本安装包..." },
                                fontSize = 12.sp,
                                color = FoldColors.HyperBlue
                            )
                        }
                    } else if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = FoldColors.DangerRed
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onDismiss(false) },
                            enabled = !isDownloading,
                            minWidth = 0.dp,
                            minHeight = 40.dp,
                            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("稍后再说", fontSize = 13.sp, maxLines = 1)
                        }
                        if (release.htmlUrl.isNotBlank()) {
                            Text(
                                text = "浏览器打开",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = FoldColors.HyperBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (downloadedApkReady) onInstallReadyApk() else onDownloadAndInstall()
                            },
                            enabled = !isDownloading,
                            minWidth = 0.dp,
                            minHeight = 40.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(
                                text = when {
                                    isDownloading -> "下载中..."
                                    downloadedApkReady -> "立即安装"
                                    else -> "下载并升级"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                color = MiuixTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    if (!isDownloading) {
                        Text(
                            text = "忽略此版本不再弹窗提示",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clickable { onDismiss(true) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
