package ndika.monitor.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import java.util.Locale
import kotlin.math.max

class FrameTimesChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseChartView(context, attrs, defStyleAttr) {

    private val target60Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val target30Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    init {
        linePaint.color = Color.parseColor("#AB47BC")
        linePaint.strokeWidth = 2f
        fillPaint.color = Color.parseColor("#22AB47BC")
        yUnit = " ms"
    }

    fun setFrameTimes(times: FloatArray) {
        val maxVal = max(40f, (times.maxOrNull() ?: 16.6f) * 1.2f)
        setData(times, customMin = 0f, customMax = maxVal, unit = " ms")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 40f
        val right = width.toFloat() - paddingRight.toFloat() - 20f
        val top = paddingTop.toFloat() + 20f
        val bottom = height.toFloat() - paddingBottom.toFloat() - 40f
        val plotH = bottom - top

        // Draw 16.6ms (60 FPS target) reference line
        if (maxValue >= 16.6f) {
            val norm16 = (16.6f - minValue) / max(0.001f, maxValue - minValue)
            val y16 = bottom - (plotH * norm16.coerceIn(0f, 1f))
            canvas.drawLine(left, y16, right, y16, target60Paint)
            canvas.drawText("16.6 ms (60 FPS)", right - 180f, y16 - 6f, target60Paint)
        }

        // Draw 33.3ms (30 FPS target) reference line
        if (maxValue >= 33.3f) {
            val norm33 = (33.3f - minValue) / max(0.001f, maxValue - minValue)
            val y33 = bottom - (plotH * norm33.coerceIn(0f, 1f))
            canvas.drawLine(left, y33, right, y33, target30Paint)
            canvas.drawText("33.3 ms (30 FPS)", right - 180f, y33 - 6f, target30Paint)
        }
    }
}
