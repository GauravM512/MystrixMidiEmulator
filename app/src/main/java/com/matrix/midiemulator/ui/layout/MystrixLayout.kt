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
    var edgeTouchEnabled: Boolean = false

    override fun recomputeMetrics(width: Int, height: Int) {
        val size = viewSize(width, height)
        // Reserve margin for edge touch hit areas only if enabled
        val margin = if (edgeTouchEnabled) 16f * density else 0f
        gridLeft = margin
        gridTop = margin
        val effectiveSize = size - margin * 2
        
        cellWidth = (effectiveSize - gap * (NoteMap.GRID_COLS + 1)) / NoteMap.GRID_COLS
        cellHeight = (effectiveSize - gap * (NoteMap.GRID_ROWS + 1)) / NoteMap.GRID_ROWS
    }

    override fun draw(canvas: Canvas, state: PadRenderState) {
        if (showEdgeLights) {
            drawEdgeBacklight(canvas, state)
        }
        drawCenterPads(canvas, state, PAD_CORNER_RADIUS_DP * density)
    }

    override fun noteAt(x: Float, y: Float): Int? {
        val center = centerPadNoteAt(x, y)
        if (center != null) return center

        if (!edgeTouchEnabled) return null

        // 1. Top Edge (Touch 100-107)
        if (y < gridInnerTop() && y > 0) {
            val col = ((x - gridInnerLeft()) / (cellWidth + gap)).toInt().coerceIn(0, NoteMap.GRID_COLS - 1)
            return 100 + col
        }

        // 2. Bottom Edge (Touch 108-115)
        if (y > gridInnerBottom() && y < viewHeight) {
            val col = ((x - gridInnerLeft()) / (cellWidth + gap)).toInt().coerceIn(0, NoteMap.GRID_COLS - 1)
            return 108 + col
        }

        // 3. Left Edge (Touch 108-115)
        if (x < gridInnerLeft() && x > 0) {
            val visualRow = ((y - gridInnerTop()) / (cellHeight + gap)).toInt().coerceIn(0, NoteMap.GRID_ROWS - 1)
            return 108 + visualRow
        }

        // 4. Right Edge (Touch 100-107)
        if (x > gridInnerRight() && x < viewWidth) {
            val visualRow = ((y - gridInnerTop()) / (cellHeight + gap)).toInt().coerceIn(0, NoteMap.GRID_ROWS - 1)
            return 100 + visualRow
        }

        return null
    }

    private fun drawEdgeBacklight(canvas: Canvas, state: PadRenderState) {
        val edgeBand = gap
        
        // Top Edge (Index 0-7)
        for (i in 0 until NoteMap.GRID_COLS) {
            val cellLeft = padLeftForCol(i)
            val cellRight = cellLeft + cellWidth
            drawGlowRect(canvas, RectF(cellLeft, gridInnerTop() - edgeBand, cellRight, gridInnerTop()), state.edgeColors[i], state)
        }

        // Bottom Edge (Index 16-23)
        for (i in 0 until NoteMap.GRID_COLS) {
            val cellLeft = padLeftForCol(i)
            val cellRight = cellLeft + cellWidth
            drawGlowRect(
                canvas,
                RectF(cellLeft, gridInnerBottom(), cellRight, gridInnerBottom() + edgeBand),
                state.edgeColors[23 - i],
                state
            )
        }

        // Right Edge (Index 8-15)
        for (visualRow in 0 until NoteMap.GRID_ROWS) {
            val cellTop = padTopForRow(NoteMap.GRID_ROWS - 1 - visualRow)
            val cellBottom = cellTop + cellHeight
            drawGlowRect(
                canvas,
                RectF(gridInnerRight(), cellTop, gridInnerRight() + edgeBand, cellBottom),
                state.edgeColors[visualRow + 8],
                state
            )
        }

        // Left Edge (Index 24-31)
        for (visualRow in 0 until NoteMap.GRID_ROWS) {
            val cellTop = padTopForRow(NoteMap.GRID_ROWS - 1 - visualRow)
            val cellBottom = cellTop + cellHeight
            drawGlowRect(
                canvas,
                RectF(gridInnerLeft() - edgeBand, cellTop, gridInnerLeft(), cellBottom),
                state.edgeColors[31 - visualRow],
                state
            )
        }
    }

    private fun drawGlowRect(canvas: Canvas, rect: RectF, color: Int, state: PadRenderState) {
        if (color == LedPalette.OFF_COLOR) return

        val radius = 10f * density
        val litColor = applyEffectBrightness(color, state.brightnessScale)

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = withAlpha(litColor, scaledAlpha(255, state.brightnessScale))
        canvas.drawRoundRect(rect, radius, radius, paint)
    }


    private companion object {
        private const val PAD_CORNER_RADIUS_DP = 6f
    }
}
