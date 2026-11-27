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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text as RichText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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

internal sealed interface ReleaseNoteBlock {
    data class Heading(val text: String) : ReleaseNoteBlock
    data class Bullet(val marker: String?, val text: String, val indented: Boolean = false) : ReleaseNoteBlock
    data class Paragraph(val text: String) : ReleaseNoteBlock
}

internal sealed interface InlineSegment {
    data class Plain(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Link(val label: String, val url: String) : InlineSegment
}

private val numberedItemRegex = Regex("""^(\d{1,2})\.\s+(.+)$""")
private val inlineTokenRegex = Regex("""(\*\*([^*]+)\*\*|`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\))""")

internal fun parseReleaseNotesBlocks(markdown: String): List<ReleaseNoteBlock> {
    val lines = markdown.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<ReleaseNoteBlock>()
    var seenBodyContent = false

    for (rawLine in lines) {
        val leadingSpaces = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        if (trimmed.startsWith("#")) {
            val headingText = trimmed.trimStart('#').trim()
            if (headingText.isEmpty()) continue
            if (headingText.contains("下载说明")) {
                // In-app updater automatically picks the matching ABI APK; omit file download section
                break
            }
            if (!seenBodyContent && (headingText.contains("FoldMedia", ignoreCase = true) || headingText.contains("叠影提取"))) {
                // Dialog header already shows app name and target version badge
                continue
            }
            seenBodyContent = true
            blocks += ReleaseNoteBlock.Heading(headingText)
            continue
        }

        val numMatch = numberedItemRegex.matchEntire(trimmed)
        if (numMatch != null) {
            seenBodyContent = true
            blocks += ReleaseNoteBlock.Bullet(
                marker = numMatch.groupValues[1],
                text = numMatch.groupValues[2].trim(),
                indented = leadingSpaces >= 2
            )
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")) {
            seenBodyContent = true
            blocks += ReleaseNoteBlock.Bullet(
                marker = null,
                text = trimmed.substring(2).trim(),
                indented = leadingSpaces >= 2
            )
            continue
        }

        seenBodyContent = true
        blocks += ReleaseNoteBlock.Paragraph(trimmed)
    }

    return blocks
}

internal fun parseInlineSegments(raw: String): List<InlineSegment> {
    if (raw.isEmpty()) return emptyList()
    val segments = mutableListOf<InlineSegment>()
    var cursor = 0
    for (match in inlineTokenRegex.findAll(raw)) {
        if (match.range.first > cursor) {
            segments += InlineSegment.Plain(raw.substring(cursor, match.range.first))
        }
        val boldGroup = match.groupValues[2]
        val codeGroup = match.groupValues[3]
        val linkLabel = match.groupValues[4]
        val linkUrl = match.groupValues[5]
        when {
            boldGroup.isNotEmpty() -> segments += InlineSegment.Bold(boldGroup)
            codeGroup.isNotEmpty() -> segments += InlineSegment.Code(codeGroup)
            linkLabel.isNotEmpty() -> segments += InlineSegment.Link(linkLabel, linkUrl)
        }
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) {
        segments += InlineSegment.Plain(raw.substring(cursor))
    }
    return segments
}

private fun buildMarkdownAnnotatedString(
    raw: String,
    accentColor: Color,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    appendInlineSegments(raw, accentColor, codeBackground, baseBold = false)
}

private fun AnnotatedString.Builder.appendInlineSegments(
    raw: String,
    accentColor: Color,
    codeBackground: Color,
    baseBold: Boolean
) {
    for (segment in parseInlineSegments(raw)) {
        when (segment) {
            is InlineSegment.Plain -> {
                if (baseBold) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(segment.text) }
                } else {
                    append(segment.text)
                }
            }
            is InlineSegment.Bold -> {
                appendInlineSegments(segment.text, accentColor, codeBackground, baseBold = true)
            }
            is InlineSegment.Code -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = if (baseBold) FontWeight.SemiBold else FontWeight.Normal,
                        color = accentColor,
                        background = codeBackground
                    )
                ) {
                    append(" ${segment.text} ")
                }
            }
            is InlineSegment.Link -> {
                withStyle(
                    SpanStyle(
                        fontWeight = if (baseBold) FontWeight.SemiBold else FontWeight.Normal,
                        color = accentColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(segment.label)
                }
            }
        }
    }
}

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
    val blocks = remember(release.releaseNotes) {
        parseReleaseNotesBlocks(release.releaseNotes).ifEmpty {
            listOf(ReleaseNoteBlock.Paragraph("包含体验优化与稳定性修复，建议升级到最新版本。"))
        }
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
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(FoldColors.HyperBlue.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint = FoldColors.HyperBlue,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "发现新版本 ${release.tagName}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(FoldColors.HyperBlue.copy(alpha = 0.12f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "v${BuildConfig.VERSION_NAME} → ${release.tagName}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = FoldColors.HyperBlue
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MiuixTheme.colorScheme.secondaryContainer)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = sizeMb,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp, max = 230.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        val onSurface = MiuixTheme.colorScheme.onSurface
                        val onSurfaceSub = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        val codeBg = FoldColors.HyperBlue.copy(alpha = 0.10f)

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            blocks.forEachIndexed { idx, block ->
                                when (block) {
                                    is ReleaseNoteBlock.Heading -> {
                                        RichText(
                                            text = buildMarkdownAnnotatedString(block.text, FoldColors.HyperBlue, codeBg),
                                            style = TextStyle(
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                lineHeight = 19.sp,
                                                color = onSurface
                                            ),
                                            modifier = Modifier.padding(top = if (idx == 0) 0.dp else 4.dp)
                                        )
                                    }
                                    is ReleaseNoteBlock.Bullet -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = if (block.indented) 14.dp else 0.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            if (block.marker != null) {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 2.dp)
                                                        .size(18.dp)
                                                        .clip(CircleShape)
                                                        .background(FoldColors.HyperBlue.copy(alpha = 0.14f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = block.marker,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = FoldColors.HyperBlue
                                                    )
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .padding(top = 7.dp)
                                                        .size(5.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (block.indented) onSurfaceSub else FoldColors.HyperBlue
                                                        )
                                                )
                                            }
                                            RichText(
                                                text = buildMarkdownAnnotatedString(block.text, FoldColors.HyperBlue, codeBg),
                                                style = TextStyle(
                                                    fontSize = 12.5.sp,
                                                    lineHeight = 19.sp,
                                                    color = if (block.indented) onSurfaceSub else onSurface
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                    is ReleaseNoteBlock.Paragraph -> {
                                        RichText(
                                            text = buildMarkdownAnnotatedString(block.text, FoldColors.HyperBlue, codeBg),
                                            style = TextStyle(
                                                fontSize = 12.5.sp,
                                                lineHeight = 19.sp,
                                                color = onSurface
                                            )
                                        )
                                    }
                                }
                            }
                        }
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onDismiss(false) },
                            enabled = !isDownloading,
                            minWidth = 0.dp,
                            minHeight = 42.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier
                                .weight(0.9f)
                                .height(42.dp)
                        ) {
                            Text("稍后再说", fontSize = 13.sp, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                if (downloadedApkReady) onInstallReadyApk() else onDownloadAndInstall()
                            },
                            enabled = !isDownloading,
                            minWidth = 0.dp,
                            minHeight = 42.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier
                                .weight(1.4f)
                                .height(42.dp)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (release.htmlUrl.isNotBlank()) {
                            Text(
                                text = "去 GitHub 查看",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = FoldColors.HyperBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }
                                    }
                                    .padding(vertical = 2.dp)
                            )
                        } else {
                            Box(modifier = Modifier)
                        }
                        if (!isDownloading) {
                            Text(
                                text = "忽略此版本",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onDismiss(true) }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
