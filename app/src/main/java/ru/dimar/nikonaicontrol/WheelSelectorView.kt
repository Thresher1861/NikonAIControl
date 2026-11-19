package ru.dimar.nikonaicontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class WheelSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var items: List<String> = emptyList()
        set(value) {
            field = value
            if (selectedIndex >= value.size) selectedIndex = maxOf(0, value.size - 1)
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, maxOf(0, items.size - 1))
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var onValueChanged: ((Int) -> Unit)? = null

    private var lastTouchX = 0f
    private var dragAccumulator = 0f
    private val stepPx = 65f

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val sideTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return

        val cx = width / 2f
        val cy = height * 0.62f

        var x = 10f
        while (x < width - 10f) {
            canvas.drawLine(x, height * 0.18f, x, height * 0.80f, tickPaint)
            x += 16f
        }

        canvas.drawLine(cx - 50f, height - 6f, cx + 50f, height - 6f, linePaint)

        for (offset in -1..1) {
            val idx = selectedIndex + offset
            if (idx in items.indices) {
                val drawX = cx + offset * (width * 0.31f)
                val paint = if (offset == 0) centerTextPaint else sideTextPaint
                canvas.drawText(items[idx], drawX, cy, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                dragAccumulator = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                dragAccumulator += dx
                if (abs(dragAccumulator) >= stepPx) {
                    val steps = (dragAccumulator / stepPx).toInt()
                    dragAccumulator -= steps * stepPx
                    val newIdx = (selectedIndex - steps).coerceIn(0, items.size - 1)
                    if (newIdx != selectedIndex) {
                        selectedIndex = newIdx
                        onValueChanged?.invoke(newIdx)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}