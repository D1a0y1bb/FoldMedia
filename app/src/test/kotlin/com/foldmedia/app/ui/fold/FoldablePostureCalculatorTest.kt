package com.foldmedia.app.ui.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldablePostureCalculatorTest {

    @Test
    fun xiaomi18FoldCoverScreenUsesCompactSinglePane() {
        val metrics = FoldablePostureCalculator.calculate(
            windowWidthDp = 412f,
            windowHeightDp = 915f,
            hingeState = null,
            hingeOrientation = null
        )
        assertEquals(FoldPosture.COVER_COMPACT, metrics.posture)
        assertFalse(metrics.isDualPane)
        assertFalse(metrics.isTabletop)
        assertEquals(2, metrics.gridColumnCount)
    }

    @Test
    fun xiaomi18FoldInnerFlatMediumScreenUnlocksDualPane() {
        // Xiaomi 18 Fold 7.58" inner display (~680dp wide even in portrait unfolded)
        val metrics = FoldablePostureCalculator.calculate(
            windowWidthDp = 680f,
            windowHeightDp = 760f,
            hingeState = "FLAT",
            hingeOrientation = "VERTICAL"
        )
        assertEquals(FoldPosture.INNER_DUAL_FLAT, metrics.posture)
        assertTrue(metrics.isDualPane)
        assertEquals(0.40f, metrics.leftPaneWeight, 0.01f)
        assertEquals(0.60f, metrics.rightPaneWeight, 0.01f)
    }

    @Test
    fun xiaomi18FoldHalfOpenedBookModeAlignsToHinge() {
        val metrics = FoldablePostureCalculator.calculate(
            windowWidthDp = 760f,
            windowHeightDp = 720f,
            hingeState = "HALF_OPENED",
            hingeOrientation = "VERTICAL",
            hingeLeftDp = 370f,
            hingeRightDp = 390f,
            hingeSizeDp = 20f
        )
        assertEquals(FoldPosture.INNER_BOOK_HALF, metrics.posture)
        assertTrue(metrics.isDualPane)
        assertTrue(metrics.hingeSpacerDp.value >= 20f)
        assertTrue(metrics.leftPaneWeight in 0.45f..0.52f)
    }

    @Test
    fun xiaomi18FoldHalfOpenedHorizontalTriggersTabletop() {
        val metrics = FoldablePostureCalculator.calculate(
            windowWidthDp = 760f,
            windowHeightDp = 720f,
            hingeState = "HALF_OPENED",
            hingeOrientation = "HORIZONTAL",
            hingeSizeDp = 16f
        )
        assertEquals(FoldPosture.INNER_TABLETOP_HALF, metrics.posture)
        assertTrue(metrics.isTabletop)
        assertFalse(metrics.isDualPane)
    }
}
