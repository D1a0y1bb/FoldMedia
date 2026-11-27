package com.foldmedia.app.ui.fold

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlin.math.max

/**
 * Tailored adaptive posture states for medium-sized wide foldables ("中折叠"),
 * specifically Xiaomi 18 Fold (5.38" wide cover screen <-> 7.58" square-ish inner screen).
 */
enum class FoldPosture(val badgeLabel: String) {
    COVER_COMPACT("外屏单栏"),
    INNER_DUAL_FLAT("内屏双栏 · 展开"),
    INNER_BOOK_HALF("内屏书本 · 半折分栏"),
    INNER_TABLETOP_HALF("悬停桌面 · 上下分屏")
}

enum class FoldPreviewOverride(val label: String) {
    AUTO("自动跟随折叠"),
    FORCE_COVER("模拟 5.38\" 外屏"),
    FORCE_INNER_FLAT("模拟 7.58\" 内屏双栏"),
    FORCE_TABLETOP("模拟悬停桌面态")
}

@Immutable
data class FoldableUiMetrics(
    val posture: FoldPosture,
    val windowWidthDp: Dp,
    val windowHeightDp: Dp,
    val isDualPane: Boolean,
    val isTabletop: Boolean,
    val leftPaneWeight: Float,
    val rightPaneWeight: Float,
    val hingeSpacerDp: Dp,
    val gridColumnCount: Int
)

/**
 * Pure calculation helper so Xiaomi 18 Fold posture breakpoints and hinge-splitting math
 * can be thoroughly unit-tested on JVM without an Android device.
 */
object FoldablePostureCalculator {

    // Medium foldable (中折叠 7.58") portrait inner width is narrower than tablets,
    // so 560dp reliably unlocks dual-pane mode as soon as Xiaomi 18 Fold unfolds.
    private const val MEDIUM_FOLD_INNER_BREAKPOINT_DP = 560f

    fun calculate(
        windowWidthDp: Float,
        windowHeightDp: Float,
        hingeState: String?, // "FLAT" | "HALF_OPENED" | null
        hingeOrientation: String?, // "HORIZONTAL" | "VERTICAL" | null
        hingeLeftDp: Float = 0f,
        hingeRightDp: Float = 0f,
        hingeSizeDp: Float = 0f,
        previewOverride: FoldPreviewOverride = FoldPreviewOverride.AUTO
    ): FoldableUiMetrics {
        when (previewOverride) {
            FoldPreviewOverride.FORCE_COVER -> return FoldableUiMetrics(
                posture = FoldPosture.COVER_COMPACT,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = false,
                isTabletop = false,
                leftPaneWeight = 1f,
                rightPaneWeight = 0f,
                hingeSpacerDp = 0.dp,
                gridColumnCount = 2
            )
            FoldPreviewOverride.FORCE_INNER_FLAT -> return FoldableUiMetrics(
                posture = FoldPosture.INNER_DUAL_FLAT,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = true,
                isTabletop = false,
                leftPaneWeight = 0.40f,
                rightPaneWeight = 0.60f,
                hingeSpacerDp = 12.dp,
                gridColumnCount = if (windowWidthDp >= 780f) 3 else 2
            )
            FoldPreviewOverride.FORCE_TABLETOP -> return FoldableUiMetrics(
                posture = FoldPosture.INNER_TABLETOP_HALF,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = false,
                isTabletop = true,
                leftPaneWeight = 0.46f,
                rightPaneWeight = 0.54f,
                hingeSpacerDp = 16.dp,
                gridColumnCount = 3
            )
            FoldPreviewOverride.AUTO -> Unit
        }

        if (hingeState == "HALF_OPENED" && hingeOrientation == "HORIZONTAL") {
            return FoldableUiMetrics(
                posture = FoldPosture.INNER_TABLETOP_HALF,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = false,
                isTabletop = true,
                leftPaneWeight = 0.46f,
                rightPaneWeight = 0.54f,
                hingeSpacerDp = max(12f, hingeSizeDp).dp,
                gridColumnCount = if (windowWidthDp >= MEDIUM_FOLD_INNER_BREAKPOINT_DP) 3 else 2
            )
        }

        val hasWideInnerDisplay = windowWidthDp >= MEDIUM_FOLD_INNER_BREAKPOINT_DP
        if (!hasWideInnerDisplay) {
            return FoldableUiMetrics(
                posture = FoldPosture.COVER_COMPACT,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = false,
                isTabletop = false,
                leftPaneWeight = 1f,
                rightPaneWeight = 0f,
                hingeSpacerDp = 0.dp,
                gridColumnCount = 2
            )
        }

        if (hingeState == "HALF_OPENED" && hingeOrientation == "VERTICAL" && hingeRightDp > hingeLeftDp) {
            val leftFraction = (hingeLeftDp / windowWidthDp).coerceIn(0.35f, 0.52f)
            val rightFraction = (1f - leftFraction).coerceAtLeast(0.45f)
            return FoldableUiMetrics(
                posture = FoldPosture.INNER_BOOK_HALF,
                windowWidthDp = windowWidthDp.dp,
                windowHeightDp = windowHeightDp.dp,
                isDualPane = true,
                isTabletop = false,
                leftPaneWeight = leftFraction,
                rightPaneWeight = rightFraction,
                hingeSpacerDp = max(16f, hingeSizeDp + 12f).dp,
                gridColumnCount = 2
            )
        }

        val cols = when {
            windowWidthDp >= 940f -> 3
            else -> 2
        }
        return FoldableUiMetrics(
            posture = FoldPosture.INNER_DUAL_FLAT,
            windowWidthDp = windowWidthDp.dp,
            windowHeightDp = windowHeightDp.dp,
            isDualPane = true,
            isTabletop = false,
            leftPaneWeight = 0.40f,
            rightPaneWeight = 0.60f,
            hingeSpacerDp = max(12f, hingeSizeDp + 8f).dp,
            gridColumnCount = cols
        )
    }
}

@Composable
fun rememberFoldableUiMetrics(
    activity: Activity,
    previewOverride: FoldPreviewOverride
): FoldableUiMetrics {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val foldingFeature by produceState<FoldingFeature?>(initialValue = null, activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                value = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
            }
    }

    val currentBounds = WindowMetricsCalculator.getOrCreate()
        .computeCurrentWindowMetrics(activity)
        .bounds

    val widthDp = with(density) {
        if (currentBounds.width() > 0) currentBounds.width().toDp().value else configuration.screenWidthDp.toFloat()
    }
    val heightDp = with(density) {
        if (currentBounds.height() > 0) currentBounds.height().toDp().value else configuration.screenHeightDp.toFloat()
    }

    val hingeState = when (foldingFeature?.state) {
        FoldingFeature.State.HALF_OPENED -> "HALF_OPENED"
        FoldingFeature.State.FLAT -> "FLAT"
        else -> null
    }
    val hingeOrientation = when (foldingFeature?.orientation) {
        FoldingFeature.Orientation.HORIZONTAL -> "HORIZONTAL"
        FoldingFeature.Orientation.VERTICAL -> "VERTICAL"
        else -> null
    }
    val hingeBounds = foldingFeature?.bounds
    val hingeLeftDp = if (hingeBounds != null) with(density) { hingeBounds.left.toDp().value } else 0f
    val hingeRightDp = if (hingeBounds != null) with(density) { hingeBounds.right.toDp().value } else 0f
    val hingeSizeDp = if (hingeBounds != null) {
        with(density) {
            if (hingeOrientation == "HORIZONTAL") hingeBounds.height().toDp().value
            else hingeBounds.width().toDp().value
        }
    } else 0f

    return FoldablePostureCalculator.calculate(
        windowWidthDp = widthDp,
        windowHeightDp = heightDp,
        hingeState = hingeState,
        hingeOrientation = hingeOrientation,
        hingeLeftDp = hingeLeftDp,
        hingeRightDp = hingeRightDp,
        hingeSizeDp = hingeSizeDp,
        previewOverride = previewOverride
    )
}
