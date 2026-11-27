package com.foldmedia.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.glass
import top.yukonga.miuix.kmp.glass.glassShadow
import top.yukonga.miuix.kmp.material.GlassBackground
import top.yukonga.miuix.kmp.material.GlassBlend
import top.yukonga.miuix.kmp.material.GlassBlur
import top.yukonga.miuix.kmp.material.GlassColorBlendMode
import top.yukonga.miuix.kmp.material.GlassColorLayer
import top.yukonga.miuix.kmp.material.GlassEdge
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassInner
import top.yukonga.miuix.kmp.material.GlassLight
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassReflect
import top.yukonga.miuix.kmp.material.GlassRefract
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassStyle
import kotlin.math.abs

data class LiquidDockItem(
    val icon: ImageVector,
    val label: String,
    val badge: (@Composable () -> Unit)? = null
)

/**
 * Crystal-clear Liquid Glass Dock:
 * - Outer pill uses ultra-transparent AGSL glass (`blurRadius = 3.dp`, `ior = 2.6f`, specular rim)
 * - Active/dragging thumb renders a clear water-droplet lens (`ior = 3.6f`, specular rim)
 *   UNDER the crisp foreground icon & label row so dragging feels glassy & luminous without smudging icons.
 */
@Composable
fun LiquidGlassNavigationDock(
    items: List<LiquidDockItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    selectedColor: Color,
    unselectedColor: Color,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null
) {
    if (items.isEmpty()) return
    val isDark = isSystemInDarkTheme()
    val index = selectedIndex.coerceIn(0, items.lastIndex)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()

    val barHeight = 56.dp
    val contentInsetDp = 8.dp
    val overhangDp = 4.dp
    val contentInsetPx = with(density) { contentInsetDp.toPx() }
    val overhangPx = with(density) { overhangDp.toPx() }

    val barWidthState = remember { mutableIntStateOf(0) }
    var isPressedOrDragging by remember { mutableStateOf(false) }
    var activeHoverIndex by remember { mutableIntStateOf(-1) }
    var settleGeneration by remember { mutableIntStateOf(0) }
    var lastSettledIndex by remember { mutableIntStateOf(index) }

    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }

    val leadingSpring: SpringSpec<Float> = remember { spring(dampingRatio = 0.72f, stiffness = 420f) }
    val trailingSpring: SpringSpec<Float> = remember { spring(dampingRatio = 0.78f, stiffness = 280f) }
    val followSpring: SpringSpec<Float> = remember { spring(dampingRatio = 0.96f, stiffness = 1100f) }

    fun animateIndicator(
        newLeft: Float,
        newRight: Float,
        lSpring: SpringSpec<Float>,
        rSpring: SpringSpec<Float>
    ) {
        if (left.targetValue == newLeft && right.targetValue == newRight) return
        scope.launch { left.animateTo(newLeft, lSpring) }
        scope.launch { right.animateTo(newRight, rSpring) }
    }

    LaunchedEffect(index, settleGeneration, items.size, contentInsetPx, overhangPx) {
        snapshotFlow { barWidthState.intValue }.collectLatest { currentBarWidth ->
            if (isPressedOrDragging) return@collectLatest
            if (currentBarWidth <= contentInsetPx * 2f) return@collectLatest
            val slotWidth = ((currentBarWidth - contentInsetPx * 2f) / items.size).coerceAtLeast(0f)
            val targetLeft = contentInsetPx - overhangPx + index * slotWidth
            val targetRight = contentInsetPx + overhangPx + (index + 1) * slotWidth
            if (right.value <= left.value || lastSettledIndex == index) {
                left.snapTo(targetLeft)
                right.snapTo(targetRight)
            } else {
                val movingRight = targetLeft >= left.value
                animateIndicator(
                    newLeft = targetLeft,
                    newRight = targetRight,
                    lSpring = if (movingRight) trailingSpring else leadingSpring,
                    rSpring = if (movingRight) leadingSpring else trailingSpring
                )
            }
            lastSettledIndex = index
        }
    }

    val dragLift by animateFloatAsState(
        targetValue = if (isPressedOrDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 460f),
        label = "liquidDropletLift"
    )

    val onSelectState by rememberUpdatedState(onSelect)

    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val captionBottom = with(density) { WindowInsets.captionBar.getBottom(this).toDp() }
    val sysBottom = maxOf(navBottom, captionBottom)
    val bottomMargin = when {
        sysBottom <= 16.dp -> 18.dp
        sysBottom >= 28.dp -> sysBottom
        else -> 10.dp + sysBottom
    }

    val cutoutInsets = WindowInsets.displayCutout
    val navInsets = WindowInsets.navigationBars
    val horizontalInsets = remember(cutoutInsets, navInsets) {
        cutoutInsets.union(navInsets).only(WindowInsetsSides.Horizontal)
    }

    val capsuleShape = remember { GlassShape(CornerSize(50), CornerSize(50), CornerSize(50), CornerSize(50)) }
    val dropletShape = remember { GlassShape(CornerSize(50), CornerSize(50), CornerSize(50), CornerSize(50)) }

    // Crystal-clear Liquid Glass Dock outer style (high light transmission, visible rim refraction, low fog)
    val outerGlassStyle = remember(isDark) {
        GlassStyle(
            blend = GlassBlend(
                curveA = 0.5f,
                curveB = 1.0f,
                curveC = 0.0f,
                curveD = 1.0f,
                amount = 0.14f,
                saturation = 1.25f,
                brightness = if (isDark) 0.04f else 0.10f,
                darker = 0.05f,
                darkerStart = 0.65f,
                darkerEnd = 0.90f,
            ),
            inner = GlassInner(
                bottom = 0.04f,
                tint = if (isDark) Color(0xFF1E222A) else Color.White,
                tintStrength = 0.04f,
                colorWhite = 0.02f,
                colorMix = 0.18f,
                colorPow = 1.1f,
                alpha = 1.0f,
            ),
            edge = GlassEdge(
                width = 58.0f,
                pow = 3.8f,
                thickness = 58.0f,
                reflectOffset = 700.0f,
            ),
            reflect = GlassReflect(lighten = 1.0f, strength = 0.72f),
            light = GlassLight(
                directionX = -0.4f,
                directionY = 0.6f,
                directionZ = -0.8f,
                intensity = 1.25f,
                oppositeIntensity = 0.75f,
                angleRange = 1.4f,
            ),
            refract = GlassRefract(ior = 2.6f),
            background = GlassBackground(
                saturation = 1.1f,
                brightness = 0.0f,
                burn = 0.0f,
                unShade = 0.0f,
            ),
            blur = GlassBlur(small = 10.0f, big = 10.0f),
        )
    }

    val outerDockMaterial = remember(isDark) {
        GlassMaterial(
            blurRadius = 3.5.dp,
            first = GlassColorLayer(
                if (isDark) Color(0x2412151C) else Color(0x12FFFFFF),
                GlassColorBlendMode.SrcOver
            )
        )
    }

    // Pure transparent optical droplet lens style for dragging/active pill
    val dropletGlassStyle = remember(isDark) {
        GlassStyle(
            blend = GlassBlend(
                curveA = 0.4f,
                curveB = 1.2f,
                curveC = 0.2f,
                curveD = 0.8f,
                amount = 0.18f,
                saturation = 1.18f,
                brightness = if (isDark) 0.08f else 0.16f,
                darker = 0.06f,
                darkerStart = 0.65f,
                darkerEnd = 0.95f,
            ),
            inner = GlassInner(
                bottom = 0.05f,
                tint = Color.White,
                tintStrength = 0.04f,
                colorWhite = 0.06f,
                colorMix = 0.22f,
                colorPow = 1.1f,
                alpha = 1.0f,
            ),
            edge = GlassEdge(
                width = 68.0f,
                pow = 3.5f,
                thickness = 70.0f,
                reflectOffset = 900.0f,
            ),
            reflect = GlassReflect(lighten = 1.2f, strength = 1.05f),
            light = GlassLight(
                directionX = -0.45f,
                directionY = 0.65f,
                directionZ = -0.8f,
                intensity = 1.55f,
                oppositeIntensity = 0.95f,
                angleRange = 1.4f,
            ),
            refract = GlassRefract(ior = 3.6f),
            background = GlassBackground(
                saturation = 1.15f,
                brightness = 0.0f,
                burn = 0.0f,
                unShade = 0.0f,
            ),
            blur = GlassBlur(small = 0.0f, big = 0.0f),
        )
    }

    val dropletMaterial = remember(isDark, selectedColor) {
        GlassMaterial(
            blurRadius = 1.5.dp,
            first = GlassColorLayer(
                if (isDark) selectedColor.copy(alpha = 0.14f) else Color(0x1EFFFFFF),
                GlassColorBlendMode.SrcOver
            )
        )
    }

    Row(
        modifier = modifier
            .windowInsetsPadding(horizontalInsets)
            .padding(bottom = bottomMargin)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(barHeight)
                .glassShadow(shape = capsuleShape, shadow = GlassShadows.Float)
                .glass(
                    backdrop = backdrop,
                    shape = capsuleShape,
                    style = outerGlassStyle,
                    material = outerDockMaterial,
                    stroke = GlassEdgeStrokes.forTheme(isDark),
                    shading = true
                )
                .border(
                    width = 0.5.dp,
                    color = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f),
                    shape = CircleShape
                )
                .onSizeChanged { barWidthState.intValue = it.width }
                .pointerInput(items.size, contentInsetPx, overhangPx, layoutDirection) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        if (width <= contentInsetPx * 2f) return@awaitEachGesture
                        val slotWidth = (width - contentInsetPx * 2f) / items.size
                        fun logicalX(x: Float) = if (layoutDirection == LayoutDirection.Rtl) width - x else x
                        fun indexAt(x: Float) = ((x - contentInsetPx) / slotWidth)
                            .toInt()
                            .coerceIn(0, items.lastIndex)

                        val minLeft = contentInsetPx - overhangPx
                        val maxLeft = width - contentInsetPx + overhangPx - (slotWidth + overhangPx * 2f)
                        val downX = logicalX(down.position.x)
                        var currentX = downX
                        var currentSlot = indexAt(downX)
                        isPressedOrDragging = true
                        activeHoverIndex = currentSlot

                        val initialLeft = (minLeft + currentSlot * slotWidth).coerceIn(minLeft, maxLeft.coerceAtLeast(minLeft))
                        animateIndicator(
                            newLeft = initialLeft,
                            newRight = initialLeft + slotWidth + overhangPx * 2f,
                            lSpring = followSpring,
                            rSpring = followSpring
                        )
                        var grabOffset = Float.NaN
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                if (!change.pressed) {
                                    if (change.changedToUp()) {
                                        onSelectState(indexAt(currentX))
                                        change.consume()
                                    }
                                    break
                                }
                                val pointerDelta = change.position.x - change.previousPosition.x
                                val logicalDelta = if (layoutDirection == LayoutDirection.Rtl) -pointerDelta else pointerDelta
                                currentX += logicalDelta
                                val x = logicalX(change.position.x)
                                if (grabOffset.isNaN()) {
                                    if (abs(currentX - downX) < viewConfiguration.touchSlop) {
                                        activeHoverIndex = indexAt(currentX)
                                        continue
                                    }
                                    grabOffset = downX - initialLeft
                                }
                                val pillW = slotWidth + overhangPx * 2f
                                val rawLeft = (x - grabOffset).coerceIn(minLeft, maxLeft.coerceAtLeast(minLeft))
                                val stretch = (abs(logicalDelta) * 2.8f).coerceAtMost(34f)
                                val stretchedLeft = if (logicalDelta > 0f && rawLeft > minLeft) rawLeft - stretch else rawLeft
                                val stretchedRight = if (logicalDelta < 0f && rawLeft < maxLeft) rawLeft + pillW + stretch else rawLeft + pillW
                                animateIndicator(
                                    newLeft = stretchedLeft.coerceAtLeast(minLeft),
                                    newRight = stretchedRight.coerceAtMost(width - contentInsetPx + overhangPx),
                                    lSpring = followSpring,
                                    rSpring = followSpring
                                )
                                activeHoverIndex = indexAt(currentX)
                                change.consume()
                            }
                        } finally {
                            isPressedOrDragging = false
                            activeHoverIndex = -1
                            settleGeneration++
                        }
                    }
                }
        ) {
            // 1. Sliding glass droplet indicator rendered UNDERNEATH the crisp icons & text
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(vertical = 4.dp)
                    .layout { measurable, constraints ->
                        val hasSize = constraints.maxWidth > (contentInsetPx * 2f).toInt()
                        val fallbackSlot = if (hasSize) (constraints.maxWidth - contentInsetPx * 2f) / items.size else 0f
                        val rawL = if (right.value <= left.value) contentInsetPx - overhangPx + index * fallbackSlot else left.value
                        val rawR = if (right.value <= left.value) contentInsetPx + overhangPx + (index + 1) * fallbackSlot else right.value
                        val clampedL = rawL.coerceIn((contentInsetPx - overhangPx).coerceAtLeast(0f), constraints.maxWidth.toFloat())
                        val clampedR = rawR.coerceIn(clampedL, (constraints.maxWidth - contentInsetPx + overhangPx).coerceAtLeast(clampedL))
                        val startPx = clampedL.fastRoundToInt()
                        val pillWidth = (clampedR.fastRoundToInt() - startPx).coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = pillWidth, maxWidth = pillWidth))
                        layout(constraints.maxWidth, placeable.height) {
                            placeable.placeRelative(IntOffset(startPx, 0))
                        }
                    }
                    .graphicsLayer {
                        scaleX = 1f + 0.04f * dragLift
                        scaleY = 1f + 0.10f * dragLift
                    }
                    .glassShadow(shape = dropletShape, shadow = GlassShadows.Float, alpha = 0.25f + 0.65f * dragLift)
                    .glass(
                        backdrop = backdrop,
                        shape = dropletShape,
                        style = dropletGlassStyle,
                        material = dropletMaterial,
                        stroke = GlassEdgeStrokes.forTheme(isDark),
                        shading = true
                    )
                    .background(
                        color = selectedColor.copy(alpha = if (isDark) 0.22f - 0.12f * dragLift else 0.14f - 0.09f * dragLift),
                        shape = CircleShape
                    )
                    .border(
                        width = (0.6f + 0.6f * dragLift).dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (isDark) 0.28f + 0.22f * dragLift else 0.72f + 0.20f * dragLift),
                                Color.White.copy(alpha = if (isDark) 0.06f else 0.18f),
                                Color.White.copy(alpha = if (isDark) 0.18f else 0.45f)
                            )
                        ),
                        shape = CircleShape
                    )
            )

            // 2. Razor-sharp tab icons & labels above the crystal droplet
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .padding(horizontal = contentInsetDp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val effectiveIndex = if (activeHoverIndex in items.indices) activeHoverIndex else index
                items.forEachIndexed { pos, item ->
                    val isActive = pos == effectiveIndex
                    val tint by animateColorAsState(
                        targetValue = if (isActive) selectedColor else unselectedColor,
                        label = "dockItemTint"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isActive && isPressedOrDragging) 1.09f else 1f,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
                        label = "dockItemScale"
                    )
                    val tintProducer = remember { ColorProducer { tint } }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(barHeight)
                            .graphicsLayer {
                                scaleX = iconScale
                                scaleY = iconScale
                            }
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                selected = (pos == index)
                                contentDescription = item.label
                                onClick {
                                    onSelectState(pos)
                                    true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            BadgedBox(badge = { item.badge?.invoke() }) {
                                Icon(
                                    painter = rememberVectorPainter(item.icon),
                                    tint = tintProducer,
                                    contentDescription = null,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            Text(
                                text = item.label,
                                fontSize = 10.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = trailingAction != null,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.7f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(10.dp))
                trailingAction?.invoke()
            }
        }
    }
}
