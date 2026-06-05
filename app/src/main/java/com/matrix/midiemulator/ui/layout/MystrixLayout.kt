package com.matrix.midiemulator.ui.layout

import android.graphics.Canvas
import android.graphics.RectF
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap

internal class MystrixLayout(
    density: Float,
    gap: Float
) : BasePadLayout(density, gap) {

    var showEdgeLights: Boolean = true

    override fun recomputeMetrics(width: Int, height: Int) {
        val size = viewSize(width, height)
        gridLeft = 0f
        gridTop = 0f
        cellWidth = (size - gap * (NoteMap.GRID_COLS + 1)) / NoteMap.GRID_COLS
        cellHeight = (size - gap * (NoteMap.GRID_ROWS + 1)) / NoteMap.GRID_ROWS
    }

    override fun draw(canvas: Canvas, state: PadRenderState) {
        if (showEdgeLights) {
            drawEdgeBacklight(canvas, state)
        }
        drawCenterPads(canvas, state, PAD_CORNER_RADIUS_DP * density)
    }

    override fun noteAt(x: Float, y: Float): Int? {
        return centerPadNoteAt(x, y)
    }

    private fun drawEdgeBacklight(canvas: Canvas, state: PadRenderState) {
        val edgeBand = gap
        val leftMost = gap
        val rightMost = leftMost + NoteMap.GRID_COLS * cellWidth + (NoteMap.GRID_COLS - 1) * gap

        for (i in 0 until NoteMap.GRID_COLS) {
            val cellLeft = gap + i * (cellWidth + gap)
            val cellRight = cellLeft + cellWidth
            drawGlowRect(canvas, RectF(cellLeft, 0f, cellRight, edgeBand), state.edgeColors[i], EdgeSide.TOP, state)
        }

        for (i in 0 until NoteMap.GRID_COLS) {
            val cellLeft = gap + i * (cellWidth + gap)
            val cellRight = cellLeft + cellWidth
            drawGlowRect(
                canvas,
                RectF(cellLeft, viewHeight - edgeBand, cellRight, viewHeight.toFloat()),
                state.edgeColors[23 - i],
                EdgeSide.BOTTOM,
                state
            )
        }

        for (visualRow in 0 until NoteMap.GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight
            drawGlowRect(
                canvas,
                RectF(rightMost, cellTop, viewWidth.toFloat(), cellBottom),
                state.edgeColors[visualRow + 8],
                EdgeSide.RIGHT,
                state
            )
        }

        for (visualRow in 0 until NoteMap.GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight
            drawGlowRect(
                canvas,
                RectF(0f, cellTop, leftMost, cellBottom),
                state.edgeColors[31 - visualRow],
                EdgeSide.LEFT,
                state
            )
        }
    }

    private fun drawGlowRect(canvas: Canvas, rect: RectF, color: Int, side: EdgeSide, state: PadRenderState) {
        if (color == LedPalette.OFF_COLOR) return

        val radius = 10f * density
        val litColor = applyEffectBrightness(color, state.brightnessScale)

        fun outwardRect(along: Float, across: Float): RectF {
            return when (side) {
                EdgeSide.TOP -> RectF(rect.left - across, rect.top - along, rect.right + across, rect.bottom)
                EdgeSide.BOTTOM -> RectF(rect.left - across, rect.top, rect.right + across, rect.bottom + along)
                EdgeSide.LEFT -> RectF(rect.left - along, rect.top - across, rect.right, rect.bottom + across)
                EdgeSide.RIGHT -> RectF(rect.left, rect.top - across, rect.right + along, rect.bottom + across)
            }
        }

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = withAlpha(litColor, scaledAlpha(255, state.brightnessScale))
        canvas.drawRoundRect(rect, radius, radius, paint)

        val outerMostRect = outwardRect(40f * density, 12f * density)
        paint.color = withAlpha(litColor, scaledAlpha(10, state.brightnessScale))
        canvas.drawRoundRect(outerMostRect, radius + 20f * density, radius + 20f * density, paint)

        val outerRect = outwardRect(25f * density, 8f * density)
        paint.color = withAlpha(litColor, scaledAlpha(25, state.brightnessScale))
        canvas.drawRoundRect(outerRect, radius + 15f * density, radius + 15f * density, paint)

        val midRect = outwardRect(15f * density, 5f * density)
        paint.color = withAlpha(litColor, scaledAlpha(50, state.brightnessScale))
        canvas.drawRoundRect(midRect, radius + 9f * density, radius + 9f * density, paint)

        val innerRect = outwardRect(8f * density, 2f * density)
        paint.color = withAlpha(litColor, scaledAlpha(90, state.brightnessScale))
        canvas.drawRoundRect(innerRect, radius + 4f * density, radius + 4f * density, paint)
    }

    private enum class EdgeSide {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    private companion object {
        private const val PAD_CORNER_RADIUS_DP = 6f
    }
}
