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
import kotlin.math.min

open class BaseChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    protected val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33888888")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    protected val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 28f
    }

    protected val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    protected val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#224CAF50")
        style = Paint.Style.FILL
    }

    protected val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    protected val markerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")
        style = Paint.Style.FILL
    }

    protected var dataPoints: FloatArray = FloatArray(0)
    protected var minValue: Float = 0f
    protected var maxValue: Float = 100f
    protected var yUnit: String = ""

    protected var touchX: Float = -1f
    protected var isTouching = false

    var onPointSelected: ((index: Int, value: Float) -> Unit)? = null

    fun setData(points: FloatArray, customMin: Float? = null, customMax: Float? = null, unit: String = "") {
        dataPoints = points
        yUnit = unit
        if (points.isNotEmpty()) {
            val minP = points.minOrNull() ?: 0f
            val maxP = points.maxOrNull() ?: 100f
            minValue = customMin ?: (minP * 0.9f).coerceAtLeast(0f)
            maxValue = customMax ?: max(maxP * 1.1f, minValue + 1f)
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x.coerceIn(paddingLeft.toFloat(), (width - paddingRight).toFloat())
                isTouching = true
                calculateSelectedPoint()
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

    private fun calculateSelectedPoint() {
        if (dataPoints.isEmpty() || touchX < 0) return
        val plotW = width - paddingLeft - paddingRight
        val relX = (touchX - paddingLeft).coerceIn(0f, plotW.toFloat())
        val index = ((relX / plotW) * (dataPoints.size - 1)).toInt().coerceIn(0, dataPoints.size - 1)
        onPointSelected?.invoke(index, dataPoints[index])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 40f
        val top = paddingTop.toFloat() + 20f
        val right = width.toFloat() - paddingRight.toFloat() - 20f
        val bottom = height.toFloat() - paddingBottom.toFloat() - 40f

        val plotW = right - left
        val plotH = bottom - top

        if (plotW <= 0 || plotH <= 0) return

        // 1. Draw Grid & Y-Axis Labels
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val y = bottom - (plotH * i / gridSteps)
            val v = minValue + (maxValue - minValue) * (i.toFloat() / gridSteps)
            canvas.drawLine(left, y, right, y, gridPaint)
            val label = String.format(Locale.US, "%.0f%s", v, yUnit)
            canvas.drawText(label, 4f, y + 8f, textPaint)
        }

        if (dataPoints.isEmpty()) {
            val noDataText = "No data"
            canvas.drawText(noDataText, left + plotW / 2 - 40f, top + plotH / 2, textPaint)
            return
        }

        // 2. Plot Curve
        val path = Path()
        val fillPath = Path()
        fillPath.moveTo(left, bottom)

        val count = dataPoints.size
        for (i in 0 until count) {
            val x = left + (plotW * i / max(1, count - 1))
            val normY = (dataPoints[i] - minValue) / max(0.001f, maxValue - minValue)
            val y = bottom - (plotH * normY.coerceIn(0f, 1f))

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(left + plotW, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // 3. Touch Scrubber Cursor
        if (isTouching && touchX >= left && touchX <= right) {
            canvas.drawLine(touchX, top, touchX, bottom, markerPaint)

            val relX = (touchX - left).coerceIn(0f, plotW)
            val idx = ((relX / plotW) * (count - 1)).toInt().coerceIn(0, count - 1)
            val valAtTouch = dataPoints[idx]
            val normY = (valAtTouch - minValue) / max(0.001f, maxValue - minValue)
            val ptY = bottom - (plotH * normY.coerceIn(0f, 1f))

            canvas.drawCircle(touchX, ptY, 8f, markerCirclePaint)

            val tooltip = String.format(Locale.US, "%.1f %s", valAtTouch, yUnit)
            val tooltipX = (touchX + 15f).coerceAtMost(right - 100f)
            val tooltipY = (ptY - 15f).coerceAtLeast(top + 30f)
            canvas.drawText(tooltip, tooltipX, tooltipY, textPaint)
        }
    }
}
