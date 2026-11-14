package com.cue.offlinebrain.overlay

/** Integer screen coordinates for the top-left corner of an overlay bubble. */
data class OverlayPosition(val x: Int, val y: Int)

/** Insets that reserve screen space for system or application UI. Negative values are treated as zero. */
data class OverlayInsets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

/**
 * Pure positioning rules for a draggable overlay.
 *
 * Positions are top-left coordinates. The usable rectangle is the screen rectangle after insets;
 * the bubble is kept fully within it whenever its dimensions fit. If it is larger than the usable
 * rectangle, the position is pinned to the leading usable edge (or zero when it cannot fit on the
 * physical screen). Invalid/non-positive screen or bubble dimensions normalize to (0, 0).
 */
object OverlayPositionPolicy {
    /** Clamp a persisted position against current screen, bubble, and inset dimensions. */
    @JvmStatic
    fun clamp(
        position: OverlayPosition,
        screenWidth: Int,
        screenHeight: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        insets: OverlayInsets = OverlayInsets()
    ): OverlayPosition {
        if (screenWidth <= 0 || screenHeight <= 0 || bubbleWidth <= 0 || bubbleHeight <= 0) {
            return OverlayPosition(0, 0)
        }
        val left = insets.left.coerceAtLeast(0).coerceAtMost(screenWidth)
        val top = insets.top.coerceAtLeast(0).coerceAtMost(screenHeight)
        val rightInset = insets.right.coerceAtLeast(0).toLong()
        val bottomInset = insets.bottom.coerceAtLeast(0).toLong()
        val maxPhysicalX = (screenWidth.toLong() - bubbleWidth.toLong()).coerceAtLeast(0L)
        val maxPhysicalY = (screenHeight.toLong() - bubbleHeight.toLong()).coerceAtLeast(0L)
        val maxUsableX = screenWidth.toLong() - rightInset - bubbleWidth.toLong()
        val maxUsableY = screenHeight.toLong() - bottomInset - bubbleHeight.toLong()
        val maxX = if (maxUsableX >= left.toLong()) maxUsableX else minOf(left.toLong(), maxPhysicalX)
        val maxY = if (maxUsableY >= top.toLong()) maxUsableY else minOf(top.toLong(), maxPhysicalY)
        return OverlayPosition(
            position.x.toLong().coerceIn(left.coerceAtMost(maxX.toIntSafe()).toLong(), maxX).toInt(),
            position.y.toLong().coerceIn(top.coerceAtMost(maxY.toIntSafe()).toLong(), maxY).toInt()
        )
    }

    /** Apply a drag delta and clamp the result; wide intermediate arithmetic prevents overflow. */
    @JvmStatic
    fun applyDrag(
        position: OverlayPosition,
        deltaX: Int,
        deltaY: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleWidth: Int,
        bubbleHeight: Int,
        insets: OverlayInsets = OverlayInsets()
    ): OverlayPosition {
        val base = clamp(position, screenWidth, screenHeight, bubbleWidth, bubbleHeight, insets)
        if (screenWidth <= 0 || screenHeight <= 0 || bubbleWidth <= 0 || bubbleHeight <= 0) return base
        val raw = OverlayPosition(
            (base.x.toLong() + deltaX.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            (base.y.toLong() + deltaY.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        )
        return clamp(raw, screenWidth, screenHeight, bubbleWidth, bubbleHeight, insets)
    }

    private fun Long.toIntSafe(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}
