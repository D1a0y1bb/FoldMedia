package com.foldmedia.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foldmedia.app.model.AppTab
import com.foldmedia.app.ui.MainViewModel
import com.foldmedia.app.ui.components.LiquidDockItem
import com.foldmedia.app.ui.components.LiquidGlassNavigationDock
import com.foldmedia.app.ui.components.MediaPreviewDialog
import com.foldmedia.app.ui.components.PlatformLoginDialog
import com.foldmedia.app.ui.fold.FoldableUiMetrics
import com.foldmedia.app.ui.fold.rememberFoldableUiMetrics
import com.foldmedia.app.ui.screens.EnginesAndAccountsScreen
import com.foldmedia.app.ui.screens.ExtractWorkspaceScreen
import com.foldmedia.app.ui.screens.TasksScreen
import com.foldmedia.app.ui.theme.FoldColors
import com.foldmedia.app.ui.theme.FoldMediaTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButton
import top.yukonga.miuix.kmp.glass.GlassFloatingActionButtonDefaults
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShareIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val ytDlpStatus by viewModel.ytDlpStatus.collectAsStateWithLifecycle()
            val foldMetrics = rememberFoldableUiMetrics(
                activity = this,
                previewOverride = uiState.foldPreviewOverride
            )

            FoldMediaTheme {
                val canDownloadSelected = uiState.currentTab == AppTab.EXTRACT &&
                    (uiState.currentPost?.selectedCount ?: 0) > 0
                FoldMediaRootShell(
                    foldMetrics = foldMetrics,
                    currentTab = uiState.currentTab,
                    activeTaskCount = uiState.activeTaskCount,
                    canDownloadSelected = canDownloadSelected,
                    statusNotice = uiState.statusNotice,
                    onSelectTab = viewModel::selectTab,
                    onDownloadSelected = viewModel::downloadSelectedItems,
                    onDismissNotice = viewModel::dismissNotice
                ) { contentModifier ->
                    when (uiState.currentTab) {
                        AppTab.EXTRACT -> ExtractWorkspaceScreen(
                            uiState = uiState,
                            foldMetrics = foldMetrics,
                            onUrlChange = viewModel::updateUrlInput,
                            onPasteFromClipboard = {
                                readClipboardText()?.let { viewModel.updateUrlInput(it) }
                            },
                            onApplyClipboardBanner = viewModel::applyClipboardAndExtract,
                            onDismissClipboardBanner = viewModel::dismissClipboardBanner,
                            onExtract = viewModel::extractCurrentUrl,
                            onSelectHistoryPost = viewModel::selectHistoryPost,
                            onToggleItem = viewModel::toggleItemSelection,
                            onSelectAll = viewModel::selectAllItems,
                            onSelectOnlyLive = viewModel::selectOnlyLivePhotos,
                            onSelectOnlyImages = viewModel::selectOnlyStaticImages,
                            onClearSelection = viewModel::clearItemSelection,
                            onSelectQuality = viewModel::selectQualityOption,
                            onChangeLiveMode = viewModel::setLivePhotoSaveMode,
                            onOpenPreview = viewModel::openPreview,
                            onDownloadSelected = viewModel::downloadSelectedItems,
                            modifier = contentModifier
                        )

                        AppTab.TASKS -> TasksScreen(
                            uiState = uiState,
                            foldMetrics = foldMetrics,
                            onClearFinished = viewModel::clearCompletedTasks,
                            modifier = contentModifier
                        )

                        AppTab.ENGINES -> EnginesAndAccountsScreen(
                            uiState = uiState,
                            ytDlpStatus = ytDlpStatus,
                            foldMetrics = foldMetrics,
                            onUpdateEngine = viewModel::updateYtDlpEngine,
                            onOpenLogin = viewModel::openPlatformLogin,
                            onClearLogin = viewModel::clearPlatformLogin,
                            onSaveManualCookie = viewModel::saveManualCookie,
                            onChangeLivePhotoMode = viewModel::setLivePhotoSaveMode,
                            onChangeFoldOverride = viewModel::setFoldPreviewOverride,
                            modifier = contentModifier
                        )
                    }
                }

                // Only show modal dialog when user explicitly taps an item in non-Tabletop layouts
                val previewItem = uiState.previewItem
                if (uiState.showPreviewDialog && previewItem != null && !foldMetrics.isTabletop && uiState.currentTab == AppTab.EXTRACT) {
                    MediaPreviewDialog(
                        item = previewItem,
                        onToggleSelect = { viewModel.toggleItemSelection(previewItem.id) },
                        onSelectQuality = { qId -> viewModel.selectQualityOption(previewItem.id, qId) },
                        onDismiss = viewModel::closePreview
                    )
                }

                uiState.activeLoginPlatform?.let { platform ->
                    PlatformLoginDialog(
                        platform = platform,
                        onDone = viewModel::closePlatformLogin
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            readClipboardText()?.let { viewModel.onClipboardDetected(it) }
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                viewModel.handleIncomingShareText(sharedText)
            }
        }
    }

    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun FoldMediaRootShell(
    foldMetrics: FoldableUiMetrics,
    currentTab: AppTab,
    activeTaskCount: Int,
    canDownloadSelected: Boolean,
    statusNotice: String?,
    onSelectTab: (AppTab) -> Unit,
    onDownloadSelected: () -> Unit,
    onDismissNotice: () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    val tabs = AppTab.entries
    val isDark = isSystemInDarkTheme()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val navItems = remember(activeTaskCount) {
        tabs.map { tab ->
            val icon: ImageVector = when (tab) {
                AppTab.EXTRACT -> Icons.Rounded.AutoAwesome
                AppTab.TASKS -> Icons.Rounded.Download
                AppTab.ENGINES -> Icons.Rounded.Settings
            }
            val label = when (tab) {
                AppTab.EXTRACT -> "提取"
                AppTab.TASKS -> if (activeTaskCount > 0) "任务$activeTaskCount" else "任务"
                AppTab.ENGINES -> "设置"
            }
            val badge: (@Composable () -> Unit)? = if (tab == AppTab.TASKS && activeTaskCount > 0) {
                {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(FoldColors.HyperBlue)
                    )
                }
            } else {
                null
            }
            LiquidDockItem(
                icon = icon,
                label = label,
                badge = badge
            )
        }
    }

    val capsuleMaxWidth = when {
        foldMetrics.isDualPane && canDownloadSelected -> 382.dp
        foldMetrics.isDualPane -> 310.dp
        canDownloadSelected -> 324.dp
        else -> 256.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Edge-to-edge full-screen content layer recorded into backdrop for real-time Liquid Glass refraction
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(surfaceColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = 6.dp)
            ) {
                if (!statusNotice.isNullOrBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(FoldColors.SuccessGreen.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = statusNotice,
                                fontSize = 12.sp,
                                color = FoldColors.SuccessGreen,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissNotice) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "关闭提示",
                                    tint = FoldColors.SuccessGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                content(Modifier.weight(1f).fillMaxWidth())
            }
        }

        // CoolApk / iOS26-style ultra-transparent Liquid Glass Dock with real refractive water-droplet drag lens
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = capsuleMaxWidth)
                .padding(horizontal = 12.dp)
        ) {
            val baseFabMaterial = GlassFloatingActionButtonDefaults.material()
            val actionMaterial = remember(baseFabMaterial) {
                baseFabMaterial.copy(
                    blurRadius = 6.dp,
                    third = GlassColorLayer(
                        FoldColors.HyperBlue.copy(alpha = 0.80f),
                        GlassColorBlendMode.SrcOver
                    )
                )
            }
            LiquidGlassNavigationDock(
                items = navItems,
                selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0),
                onSelect = { idx -> tabs.getOrNull(idx)?.let(onSelectTab) },
                backdrop = backdrop,
                selectedColor = FoldColors.HyperBlue,
                unselectedColor = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                trailingAction = if (canDownloadSelected) {
                    {
                        GlassFloatingActionButton(
                            onClick = onDownloadSelected,
                            backdrop = backdrop,
                            material = actionMaterial,
                            fill = FoldColors.HyperBlue,
                            size = 54.dp
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "保存选中",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                } else {
                    null
                }
            )
        }
    }
}
