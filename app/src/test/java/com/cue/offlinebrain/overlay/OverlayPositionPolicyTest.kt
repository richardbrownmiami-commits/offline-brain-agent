package com.cue.offlinebrain.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionPolicyTest {
    @Test fun clampsAllCorners() {
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(-5, -8), 200, 160, 40, 30))
        assertEquals(OverlayPosition(160, 0), OverlayPositionPolicy.clamp(OverlayPosition(Int.MAX_VALUE, 0), 200, 160, 40, 30))
        assertEquals(OverlayPosition(0, 130), OverlayPositionPolicy.clamp(OverlayPosition(0, Int.MAX_VALUE), 200, 160, 40, 30))
        assertEquals(OverlayPosition(160, 130), OverlayPositionPolicy.clamp(OverlayPosition(Int.MAX_VALUE, Int.MAX_VALUE), 200, 160, 40, 30))
    }

    @Test fun respectsInsetsAndNormalizesNegativeInsets() {
        assertEquals(
            OverlayPosition(12, 9),
            OverlayPositionPolicy.clamp(OverlayPosition(0, 0), 200, 160, 40, 30, OverlayInsets(12, 9, 15, 11))
        )
        assertEquals(
            OverlayPosition(0, 0),
            OverlayPositionPolicy.clamp(OverlayPosition(0, 0), 200, 160, 40, 30, OverlayInsets(-5, -1, -8, -2))
        )
        assertEquals(
            OverlayPosition(145, 119),
            OverlayPositionPolicy.clamp(OverlayPosition(Int.MAX_VALUE, Int.MAX_VALUE), 200, 160, 40, 30, OverlayInsets(12, 9, 15, 11))
        )
    }

    @Test fun reclampsPersistedPositionAfterRotationOrDimensionChange() {
        val persisted = OverlayPosition(700, 900)
        assertEquals(OverlayPosition(260, 450), OverlayPositionPolicy.clamp(persisted, 300, 500, 40, 50))
        assertEquals(OverlayPosition(450, 260), OverlayPositionPolicy.clamp(persisted, 500, 300, 50, 40))
        assertEquals(OverlayPosition(60, 70), OverlayPositionPolicy.clamp(OverlayPosition(70, 80), 100, 100, 40, 30))
    }

    @Test fun handlesNegativeAndExtremePersistedCoordinates() {
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(Int.MIN_VALUE, Int.MIN_VALUE), 100, 80, 20, 10))
        assertEquals(OverlayPosition(80, 70), OverlayPositionPolicy.clamp(OverlayPosition(Int.MAX_VALUE, Int.MAX_VALUE), 100, 80, 20, 10))
    }

    @Test fun dragClampsAtEdgesAndDoesNotOverflow() {
        assertEquals(OverlayPosition(80, 70), OverlayPositionPolicy.applyDrag(OverlayPosition(70, 60), 100, 100, 100, 80, 20, 10))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.applyDrag(OverlayPosition(10, 5), -100, -100, 100, 80, 20, 10))
        assertEquals(OverlayPosition(80, 70), OverlayPositionPolicy.applyDrag(OverlayPosition(40, 30), Int.MAX_VALUE, Int.MAX_VALUE, 100, 80, 20, 10))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.applyDrag(OverlayPosition(40, 30), Int.MIN_VALUE, Int.MIN_VALUE, 100, 80, 20, 10))
    }

    @Test fun invalidGeometryNeverThrowsAndNormalizesToOrigin() {
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(4, 5), 0, 80, 10, 10))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(4, 5), 80, -1, 10, 10))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(4, 5), 80, 80, 0, 10))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(4, 5), 80, 80, 10, -3))
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.applyDrag(OverlayPosition(4, 5), 10, 10, 0, 80, 10, 10))
    }

    @Test fun oversizedBubbleAndInsetsWithEmptyAvailableRangesRemainSafe() {
        assertEquals(OverlayPosition(0, 0), OverlayPositionPolicy.clamp(OverlayPosition(99, 99), 40, 30, 60, 50))
        assertEquals(
            OverlayPosition(30, 20),
            OverlayPositionPolicy.clamp(OverlayPosition(100, 100), 100, 80, 70, 50, OverlayInsets(30, 20, 40, 40))
        )
        assertEquals(
            OverlayPosition(70, 60),
            OverlayPositionPolicy.clamp(OverlayPosition(100, 100), 100, 80, 30, 20, OverlayInsets(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        )
    }
}
