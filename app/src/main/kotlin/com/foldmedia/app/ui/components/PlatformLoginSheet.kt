package com.foldmedia.app.ui.components

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foldmedia.app.model.PlatformType
import com.foldmedia.app.storage.CookieVault
import com.foldmedia.app.ui.theme.FoldColors
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun PlatformType.nativeAppPackage(): String? = when (this) {
    PlatformType.DOUYIN -> "com.ss.android.ugc.aweme"
    PlatformType.XIAOHONGSHU -> "com.xingin.xhs"
    PlatformType.X_TWITTER -> "com.twitter.android"
    PlatformType.YOUTUBE -> "com.google.android.youtube"
    PlatformType.WECHAT -> "com.tencent.mm"
    PlatformType.UNKNOWN -> null
}

fun isNativeAppInstalled(context: Context, platform: PlatformType): Boolean {
    val pkg = platform.nativeAppPackage() ?: return false
    return runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)
}

fun launchNativePlatformApp(context: Context, platform: PlatformType): Boolean {
    val pkg = platform.nativeAppPackage() ?: return false
    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: return false
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 15; Xiaomi 18 Fold) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_QR_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * Built-in local WebView login sheet with real session verification:
 * - Polls CookieManager for genuine platform auth cookies (`web_session`, `sessionid`, `auth_token`, `SAPISID`)
 * - Blocks saving false-positive visitor sessions and warns user until login completes
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlatformLoginDialog(
    platform: PlatformType,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val cookieVault = remember(context) { CookieVault(context) }
    val hasNativeApp = remember(platform) { isNativeAppInstalled(context, platform) }
    val supportsQrDesktop = platform == PlatformType.DOUYIN || platform == PlatformType.XIAOHONGSHU
    var useDesktopQr by remember(platform) { mutableStateOf(supportsQrDesktop) }
    var isSessionReady by remember(platform) { mutableStateOf(cookieVault.hasCookie(platform)) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(platform) {
        while (true) {
            isSessionReady = cookieVault.hasCookie(platform)
            delay(1200L)
        }
    }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onDone) {
                        Icon(imageVector = Icons.Rounded.Close, contentDescription = "关闭")
                    }
                    Column {
                        Text(
                            text = "登录 ${platform.displayName}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isSessionReady) {
                                "已检测到真实登录态，点击右侧完成保存"
                            } else {
                                "尚未登录（请先完成扫码或页面授权，再点完成）"
                            },
                            fontSize = 11.sp,
                            color = if (isSessionReady) FoldColors.SuccessGreen
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Button(
                    onClick = {
                        runCatching { CookieManager.getInstance().flush() }
                        val ok = cookieVault.hasCookie(platform)
                        isSessionReady = ok
                        if (ok) {
                            Toast.makeText(context, "${platform.displayName} 登录凭证已生效", Toast.LENGTH_SHORT).show()
                            onDone()
                        } else {
                            Toast.makeText(
                                context,
                                "未检测到 ${platform.displayName} 登录态（仅游客 Cookie 无效），请先在下方完成扫码或账号登录",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    minWidth = 0.dp,
                    minHeight = 36.dp,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSessionReady) "保存凭证" else "检测登录",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onPrimary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (supportsQrDesktop) {
                    QuickLoginActionPill(
                        icon = Icons.Rounded.QrCode2,
                        label = if (useDesktopQr) "切换手机版" else "切换扫码页",
                        highlighted = useDesktopQr,
                        onClick = {
                            useDesktopQr = !useDesktopQr
                            webViewRef?.apply {
                                settings.userAgentString = if (useDesktopQr) DESKTOP_QR_UA else MOBILE_UA
                                settings.useWideViewPort = useDesktopQr
                                settings.loadWithOverviewMode = useDesktopQr
                                loadUrl(platform.loginUrl)
                            }
                        }
                    )
                }
                if (hasNativeApp && supportsQrDesktop) {
                    QuickLoginActionPill(
                        icon = Icons.Rounded.QrCodeScanner,
                        label = "存码去${platform.shortBadge}扫",
                        highlighted = false,
                        onClick = {
                            val wv = webViewRef
                            if (wv != null && saveWebViewSnapshotToGallery(context, wv, platform)) {
                                Toast.makeText(
                                    context,
                                    "已截屏保存二维码，请在${platform.displayName}扫一扫选取相册图确认后返回",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            launchNativePlatformApp(context, platform)
                        }
                    )
                }
                if (hasNativeApp) {
                    QuickLoginActionPill(
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        label = "打开本机${platform.shortBadge}",
                        highlighted = false,
                        onClick = {
                            if (!launchNativePlatformApp(context, platform)) {
                                Toast.makeText(context, "未能打开 ${platform.displayName}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }

            AndroidView(
                factory = { ctx ->
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    WebView(ctx).apply {
                        webViewRef = this
                        cm.setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = useDesktopQr
                            loadWithOverviewMode = useDesktopQr
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString = if (useDesktopQr) DESKTOP_QR_UA else MOBILE_UA
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val scheme = uri.scheme?.lowercase() ?: return false
                                if (scheme == "http" || scheme == "https") return false
                                return handleExternalAuthUri(ctx, uri)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                cm.flush()
                                isSessionReady = cookieVault.hasCookie(platform)
                                if (platform == PlatformType.DOUYIN && useDesktopQr && !isSessionReady) {
                                    view?.evaluateJavascript(
                                        """
                                        (function(){
                                            setTimeout(function(){
                                                var btn = document.querySelector('button[class*="login"], [id*="login-button"]');
                                                if (!btn) {
                                                    var els = document.querySelectorAll('button, a, div');
                                                    for (var i = 0; i < els.length; i++) {
                                                        if (els[i].innerText && els[i].innerText.trim() === '登录') {
                                                            btn = els[i];
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (btn) btn.click();
                                            }, 1000);
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }
                        }
                        loadUrl(platform.loginUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        }
    }
}

@Composable
private fun QuickLoginActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) FoldColors.HyperBlue.copy(alpha = 0.14f)
                else MiuixTheme.colorScheme.secondaryContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) FoldColors.HyperBlue else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlighted) FoldColors.HyperBlue else MiuixTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

private fun handleExternalAuthUri(context: Context, uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val targetIntent = runCatching {
        if (scheme == "intent") {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                removeExtra("browser_fallback_url")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }.getOrNull() ?: return true

    val launched = runCatching {
        context.startActivity(targetIntent)
        true
    }.getOrDefault(false)
    if (!launched) {
        Toast.makeText(context, "本机未找到可直接处理的授权组件", Toast.LENGTH_SHORT).show()
    }
    return true
}

private fun saveWebViewSnapshotToGallery(
    context: Context,
    webView: WebView,
    platform: PlatformType
): Boolean {
    if (webView.width <= 0 || webView.height <= 0) return false
    return runCatching {
        val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        val name = "FoldMedia_QR_${platform.name}_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/FoldMedia")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(outUri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(outUri, values, null, null)
        bitmap.recycle()
        true
    }.getOrDefault(false)
}
