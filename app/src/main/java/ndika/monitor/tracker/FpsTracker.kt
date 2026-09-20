package ndika.monitor.tracker

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import ndika.monitor.model.PerformanceMetrics
import java.util.concurrent.atomic.AtomicInteger

class FpsTracker : ITracker, Choreographer.FrameCallback {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastFrameTimeNanos = 0L
    private var frameCount = 0
    private var lastCalculationTimeMs = 0L

    @Volatile
    private var currentFps = 60.0f
    private val frameTimes = LongArray(120)
    private var frameIndex = 0

    override fun start() {
        if (isRunning) return
        isRunning = true
        handler.post {
            lastFrameTimeNanos = 0L
            frameCount = 0
            lastCalculationTimeMs = System.currentTimeMillis()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameTimeNanos > 0L) {
            val deltaNanos = frameTimeNanos - lastFrameTimeNanos
            if (deltaNanos > 0L) {
                frameTimes[frameIndex % frameTimes.size] = deltaNanos
                frameIndex++
            }
        }
        lastFrameTimeNanos = frameTimeNanos
        frameCount++

        val now = System.currentTimeMillis()
        val elapsed = now - lastCalculationTimeMs
        if (elapsed >= 500) {
            // Calculate rolling average
            val samples = minOf(frameIndex, frameTimes.size)
            if (samples > 0) {
                var totalNanos = 0L
                for (i in 0 until samples) {
                    totalNanos += frameTimes[i]
                }
                if (totalNanos > 0) {
                    val avgDeltaSec = (totalNanos.toDouble() / samples) / 1_000_000_000.0
                    currentFps = (1.0 / avgDeltaSec).toFloat().coerceIn(0.0f, 240.0f)
                }
            } else {
                currentFps = (frameCount * 1000.0f / elapsed).coerceIn(0.0f, 240.0f)
            }
            frameCount = 0
            lastCalculationTimeMs = now
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun update(metrics: PerformanceMetrics) {
        metrics.fps = currentFps
    }

    override fun stop() {
        isRunning = false
        handler.post {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }
}
