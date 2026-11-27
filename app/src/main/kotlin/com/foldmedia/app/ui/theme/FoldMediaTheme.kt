package com.foldmedia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

object FoldColors {
    val HyperBlue = Color(0xFF0D84FF)
    val LiveAmber = Color(0xFFFF8A00)
    val SuccessGreen = Color(0xFF16A34A)
    val DangerRed = Color(0xFFEF4444)
    val DouyinBg = Color(0xFF161823)
    val XhsRed = Color(0xFFFF2442)
    val XBlue = Color(0xFF1D9BF0)
    val YouTubeRed = Color(0xFFFF0033)
}

@Composable
fun FoldMediaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) darkColorScheme() else lightColorScheme()
    MiuixTheme(
        colors = colors,
        content = content
    )
}
