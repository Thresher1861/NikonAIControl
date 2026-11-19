package ru.dimar.nikonaicontrol

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ZebraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isZebraEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) overexposedMask = null
            invalidate()
        }

    private var overexposedMask: BooleanArray? = null
    private var maskW = 0
    private var maskH = 0
    private var stripePhase = 0

    private val zebraPaint = Paint().apply {
        color = Color.parseColor("#CCFF3D00")
        strokeWidth = 3f
    }

    fun analyzeFrame(bmp: Bitmap) {
        if (!isZebraEnabled || bmp.isRecycled) return
        val step = 8
        val w = bmp.width / step
        val h = bmp.height / step
        if (w <= 0 || h <= 0) return

        val mask = BooleanArray(w * h)
        val rowPixels = IntArray(bmp.width)
        var hasHighlight = false

        for (y in 0 until h) {
            val srcY = y * step
            if (srcY >= bmp.height) break
            bmp.getPixels(rowPixels, 0, bmp.width, 0, srcY, bmp.width, 1)
            for (x in 0 until w) {
                val c = rowPixels[x * step]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                if (luma >= 242) {
                    mask[y * w + x] = true
                    hasHighlight = true
                }
            }
        }

        maskW = w
        maskH = h
        overexposedMask = if (hasHighlight) mask else null
        stripePhase = (stripePhase + 4) % 16
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mask = overexposedMask ?: return
        if (!isZebraEnabled || maskW == 0 || maskH == 0) return

        val cellW = width.toFloat() / maskW
        val cellH = height.toFloat() / maskH

        for (y in 0 until maskH) {
            val rowOffset = y * maskW
            val top = y * cellH
            for (x in 0 until maskW) {
                if (mask[rowOffset + x] && ((x + y + stripePhase / 4) % 2 == 0)) {
                    val left = x * cellW
                    canvas.drawLine(left, top + cellH, left + cellW, top, zebraPaint)
                }
            }
        }
    }
}