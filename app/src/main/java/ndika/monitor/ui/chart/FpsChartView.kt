package ndika.monitor.ui.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import java.util.Locale
import kotlin.math.max

class FpsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseChartView(context, attrs, defStyleAttr) {

    private var avgFps: Float = 0f
    private var p95Fps: Float = 0f
    private var p99Fps: Float = 0f

    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    private val p95Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    init {
        linePaint.color = Color.parseColor("#29B6F6")
        fillPaint.color = Color.parseColor("#2229B6F6")
        yUnit = " FPS"
    }

    fun setFpsData(fpsPoints: FloatArray, avg: Float, p95: Float, p99: Float) {
        avgFps = avg
        p95Fps = p95
        p99Fps = p99
        val maxVal = max(65f, (fpsPoints.maxOrNull() ?: 60f) * 1.15f)
        setData(fpsPoints, customMin = 0f, customMax = maxVal, unit = " FPS")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 40f
        val right = width.toFloat() - paddingRight.toFloat() - 20f
        val top = paddingTop.toFloat() + 20f
        val bottom = height.toFloat() - paddingBottom.toFloat() - 40f
        val plotH = bottom - top

        if (avgFps > 0f) {
            val normAvg = (avgFps - minValue) / max(0.001f, maxValue - minValue)
            val avgY = bottom - (plotH * normAvg.coerceIn(0f, 1f))
            canvas.drawLine(left, avgY, right, avgY, avgPaint)
            canvas.drawText(String.format(Locale.US, "Avg %.1f", avgFps), right - 130f, avgY - 6f, avgPaint)
        }

        if (p95Fps > 0f) {
            val norm95 = (p95Fps - minValue) / max(0.001f, maxValue - minValue)
            val p95Y = bottom - (plotH * norm95.coerceIn(0f, 1f))
            canvas.drawLine(left, p95Y, right, p95Y, p95Paint)
            canvas.drawText(String.format(Locale.US, "95%% %.1f", p95Fps), right - 130f, p95Y - 6f, p95Paint)
        }
    }
}
