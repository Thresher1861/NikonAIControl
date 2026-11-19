package ru.dimar.nikonaicontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class FocusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var fx = -1f
    private var fy = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    fun setFocusPoint(x: Float, y: Float, isLocked: Boolean = false) {
        fx = x
        fy = y
        paint.color = if (isLocked) Color.GREEN else Color.WHITE
        invalidate()
    }

    fun setLocked(locked: Boolean) {
        paint.color = if (locked) Color.GREEN else Color.WHITE
        invalidate()
    }

    fun getNormalizedCoords(): Pair<Int, Int> {
        val cx = if (fx < 0f) width / 2f else fx
        val cy = if (fy < 0f) height / 2f else fy
        val nx = ((cx / width.coerceAtLeast(1)) * 640).toInt().coerceIn(0, 640)
        val ny = ((cy / height.coerceAtLeast(1)) * 480).toInt().coerceIn(0, 480)
        return Pair(nx, ny)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = if (fx < 0f) width / 2f else fx
        val cy = if (fy < 0f) height / 2f else fy
        val s = 55f
        canvas.drawRect(cx - s, cy - s, cx + s, cy + s, paint)
    }
}