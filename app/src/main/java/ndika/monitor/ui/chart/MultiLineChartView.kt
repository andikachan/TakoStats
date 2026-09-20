package ndika.monitor.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max

class MultiLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Series(
        val name: String,
        val color: Int,
        val points: FloatArray
    )

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33888888")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 26f
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val seriesList = mutableListOf<Series>()
    private var minValue = 0f
    private var maxValue = 100f
    private var yUnit = ""

    private var touchX = -1f
    private var isTouching = false

    fun setSeries(series: List<Series>, unit: String = "") {
        seriesList.clear()
        seriesList.addAll(series)
        yUnit = unit

        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE

        for (s in series) {
            for (p in s.points) {
                if (p > 0) {
                    if (p < minVal) minVal = p
                    if (p > maxVal) maxVal = p
                }
            }
        }

        if (minVal == Float.MAX_VALUE) minVal = 0f
        if (maxVal == Float.MIN_VALUE) maxVal = 100f

        minValue = (minVal * 0.9f).coerceAtLeast(0f)
        maxValue = max(maxVal * 1.1f, minValue + 5f)

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x.coerceIn(paddingLeft.toFloat(), (width - paddingRight).toFloat())
                isTouching = true
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                touchX = -1f
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 40f
        val top = paddingTop.toFloat() + 40f
        val right = width.toFloat() - paddingRight.toFloat() - 20f
        val bottom = height.toFloat() - paddingBottom.toFloat() - 40f

        val plotW = right - left
        val plotH = bottom - top

        if (plotW <= 0 || plotH <= 0) return

        // 1. Draw Legend on Top
        var legendX = left
        for (s in seriesList) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                textSize = 24f
            }
            canvas.drawCircle(legendX + 8f, top - 20f, 6f, p)
            canvas.drawText(s.name, legendX + 22f, top - 12f, p)
            legendX += p.measureText(s.name) + 40f
        }

        // 2. Draw Grid & Y-Axis Labels
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val y = bottom - (plotH * i / gridSteps)
            val v = minValue + (maxValue - minValue) * (i.toFloat() / gridSteps)
            canvas.drawLine(left, y, right, y, gridPaint)
            val label = String.format(Locale.US, "%.0f%s", v, yUnit)
            canvas.drawText(label, 4f, y + 8f, textPaint)
        }

        if (seriesList.isEmpty()) return

        // 3. Draw Each Series Line
        for (s in seriesList) {
            if (s.points.isEmpty()) continue

            val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                strokeWidth = 3f
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

            val path = Path()
            val count = s.points.size
            for (i in 0 until count) {
                val x = left + (plotW * i / max(1, count - 1))
                val normY = (s.points[i] - minValue) / max(0.001f, maxValue - minValue)
                val y = bottom - (plotH * normY.coerceIn(0f, 1f))

                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, pLine)
        }

        // 4. Touch Scrubber Cursor
        if (isTouching && touchX >= left && touchX <= right) {
            canvas.drawLine(touchX, top, touchX, bottom, markerPaint)

            var tooltipY = top + 20f
            for (s in seriesList) {
                if (s.points.isEmpty()) continue
                val relX = (touchX - left).coerceIn(0f, plotW)
                val idx = ((relX / plotW) * (s.points.size - 1)).toInt().coerceIn(0, s.points.size - 1)
                val valAtTouch = s.points[idx]

                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.color
                    textSize = 24f
                }
                val text = String.format(Locale.US, "%s: %.1f%s", s.name, valAtTouch, yUnit)
                val tipX = (touchX + 15f).coerceAtMost(right - 140f)
                canvas.drawText(text, tipX, tooltipY, p)
                tooltipY += 30f
            }
        }
    }
}
