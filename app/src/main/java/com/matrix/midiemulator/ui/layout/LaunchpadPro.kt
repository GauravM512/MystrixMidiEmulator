package com.matrix.midiemulator.ui.layout

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap

internal class LaunchpadPro(
    density: Float,
    gap: Float
) : BasePadLayout(density, gap) {

    private var edgeButtonRadius = 0f

    override fun recomputeMetrics(width: Int, height: Int) {
        val size = viewSize(width, height)
        val baseCell = (size - gap * (NoteMap.GRID_COLS + 1)) / NoteMap.GRID_COLS
        val edgeDiameter = baseCell * 0.58f
        edgeButtonRadius = edgeDiameter / 2f
        val reserve = edgeDiameter + gap * 1.8f
        val available = (size - reserve * 2f).coerceAtLeast(gap * (NoteMap.GRID_COLS + 1) + 8f)

        gridLeft = reserve
        gridTop = reserve
        cellWidth = (available - gap * (NoteMap.GRID_COLS + 1)) / NoteMap.GRID_COLS
        cellHeight = (available - gap * (NoteMap.GRID_ROWS + 1)) / NoteMap.GRID_ROWS
    }

    override fun draw(canvas: Canvas, state: PadRenderState) {
        drawSideButtons(canvas, state)
        drawCenterPads(canvas, state, PAD_CORNER_RADIUS_DP * density)
    }

    override fun noteAt(x: Float, y: Float): Int? {
        return edgeNoteAt(x, y) ?: centerPadNoteAt(x, y)
    }

    private fun drawSideButtons(canvas: Canvas, state: PadRenderState) {
        val topY = topEdgeY()
        val bottomY = bottomEdgeY()
        val leftX = leftEdgeX()
        val rightX = rightEdgeX()

        for (i in 0 until NoteMap.GRID_COLS) {
            drawSideButton(canvas, 28 + i, padLeftForCol(i) + cellWidth / 2f, topY, state)
        }

        for (i in 0 until NoteMap.GRID_COLS) {
            drawSideButton(canvas, 116 + i, padLeftForCol(i) + cellWidth / 2f, bottomY, state)
        }

        for (i in 0 until NoteMap.GRID_ROWS) {
            drawSideButton(canvas, 108 + i, leftX, gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f, state)
        }

        for (i in 0 until NoteMap.GRID_ROWS) {
            drawSideButton(canvas, 100 + i, rightX, gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f, state)
        }

        drawSideButton(canvas, 27, rightX, topY, state)
    }

    private fun drawSideButton(canvas: Canvas, note: Int, cx: Float, cy: Float, state: PadRenderState) {
        val edgeColor = edgeColorForNote(note, state)

        if (note == 27) {
            drawCornerButton(canvas, cx, cy, edgeColor, state)
            if (state.padPressed[note]) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                paint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(cx, cy, edgeButtonRadius * 0.58f, paint)
            }
            return
        }

        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B111D.toInt()
        canvas.drawCircle(cx, cy, edgeButtonRadius * 0.95f, paint)

        if (edgeColor == LedPalette.OFF_COLOR) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = applyPadBrightness(0xFF7A8088.toInt(), state.brightnessScale, true)
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.88f, paint)
        } else {
            val litColor = applyEffectBrightness(edgeColor, state.brightnessScale)
            val glowRadius = edgeButtonRadius * 1.75f
            paint.shader = RadialGradient(
                cx,
                cy,
                glowRadius,
                withAlpha(litColor, scaledAlpha(86, state.brightnessScale)),
                withAlpha(litColor, 0),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, glowRadius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f * density
            paint.color = withAlpha(litColor, 230)
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.88f, paint)
        }

        if (state.padPressed[note]) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = 0xAAFFFFFF.toInt()
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.70f, paint)
        }
    }

    private fun drawCornerButton(canvas: Canvas, cx: Float, cy: Float, edgeColor: Int, state: PadRenderState) {
        if (edgeColor == LedPalette.OFF_COLOR) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = applyPadBrightness(0xFF5F6771.toInt(), state.brightnessScale, true)
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.80f, paint)
            return
        }

        val litColor = applyEffectBrightness(edgeColor, state.brightnessScale)
        val glowRadius = edgeButtonRadius * 1.65f
        paint.shader = RadialGradient(
            cx,
            cy,
            glowRadius,
            withAlpha(litColor, scaledAlpha(80, state.brightnessScale)),
            withAlpha(litColor, 0),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, glowRadius, paint)
        paint.shader = null

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(litColor, 245)
        canvas.drawCircle(cx, cy, edgeButtonRadius * 0.80f, paint)
    }

    private fun edgeNoteAt(x: Float, y: Float): Int? {
        val hitRadius = edgeButtonRadius * 1.1f
        val hitRadiusSq = hitRadius * hitRadius

        for (note in EDGE_HIT_NOTES) {
            val center = edgeButtonCenterForNote(note) ?: continue
            val dx = x - center.first
            val dy = y - center.second
            if (dx * dx + dy * dy <= hitRadiusSq) return note
        }

        return null
    }

    private fun edgeButtonCenterForNote(note: Int): Pair<Float, Float>? {
        return when (note) {
            27 -> Pair(rightEdgeX(), topEdgeY())
            in 28..35 -> {
                val i = note - 28
                Pair(padLeftForCol(i) + cellWidth / 2f, topEdgeY())
            }
            in 116..123 -> {
                val i = note - 116
                Pair(padLeftForCol(i) + cellWidth / 2f, bottomEdgeY())
            }
            in 108..115 -> {
                val i = note - 108
                Pair(leftEdgeX(), gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f)
            }
            in 100..107 -> {
                val i = note - 100
                Pair(rightEdgeX(), gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f)
            }
            else -> null
        }
    }

    private fun topEdgeY(): Float = gridInnerTop() - edgeButtonRadius - gap * 0.8f
    private fun bottomEdgeY(): Float = gridInnerBottom() + edgeButtonRadius + gap * 0.8f
    private fun leftEdgeX(): Float = gridInnerLeft() - edgeButtonRadius - gap * 0.8f
    private fun rightEdgeX(): Float = gridInnerRight() + edgeButtonRadius + gap * 0.8f

    private companion object {
        private const val PAD_CORNER_RADIUS_DP = 6f

        private val EDGE_HIT_NOTES = intArrayOf(
            28, 29, 30, 31, 32, 33, 34, 35,
            100, 101, 102, 103, 104, 105, 106, 107,
            108, 109, 110, 111, 112, 113, 114, 115,
            116, 117, 118, 119, 120, 121, 122, 123
        )
    }
}
