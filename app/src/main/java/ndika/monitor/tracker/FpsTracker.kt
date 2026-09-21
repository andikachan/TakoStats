package ndika.monitor.tracker

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.recorder.SessionRecorder
import ndika.monitor.util.ShellUtils

class FpsTracker : ITracker, Choreographer.FrameCallback {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastFrameTimeNanos = 0L
    private var frameCount = 0
    private var lastCalculationTimeMs = 0L

    @Volatile
    private var currentFps = 60.0f
    @Volatile
    private var currentLayerName = ""

    private val frameTimes = LongArray(120)
    private var frameIndex = 0
    private var lastSurfaceFlingerTimestamp = 0L

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
                val deltaMs = deltaNanos / 1_000_000.0f
                SessionRecorder.recordFrameTime(deltaMs)
            }
        }
        lastFrameTimeNanos = frameTimeNanos
        frameCount++

        val now = System.currentTimeMillis()
        val elapsed = now - lastCalculationTimeMs
        if (elapsed >= 500) {
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
        // Try reading hardware FPS from SurfaceFlinger if elevated access is available
        val sfFps = querySurfaceFlingerFps()
        if (sfFps > 0f) {
            metrics.fps = sfFps
        } else {
            metrics.fps = currentFps
        }

        // Query active foreground layer name
        val layer = queryActiveLayerName()
        if (layer.isNotBlank()) {
            metrics.layerName = layer
            currentLayerName = layer
        } else if (currentLayerName.isNotBlank()) {
            metrics.layerName = currentLayerName
        }
    }

    private fun querySurfaceFlingerFps(): Float {
        try {
            val out = ShellUtils.exec("dumpsys SurfaceFlinger --latency 2>/dev/null | tail -n 60")
            if (out.isBlank()) return -1f

            val lines = out.lines()
            if (lines.size < 5) return -1f

            val timestamps = mutableListOf<Long>()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val presentTime = parts[1].toLongOrNull() ?: 0L
                    if (presentTime > 0L && presentTime != Long.MAX_VALUE && presentTime != 0x7fffffffffffffffL) {
                        timestamps.add(presentTime)
                    }
                }
            }

            if (timestamps.size >= 2) {
                val validTimestamps = if (lastSurfaceFlingerTimestamp > 0L) {
                    timestamps.filter { it > lastSurfaceFlingerTimestamp }
                } else {
                    timestamps.takeLast(30)
                }

                if (validTimestamps.isNotEmpty()) {
                    lastSurfaceFlingerTimestamp = validTimestamps.last()
                    if (validTimestamps.size >= 2) {
                        val durationNanos = validTimestamps.last() - validTimestamps.first()
                        if (durationNanos > 0) {
                            val fps = ((validTimestamps.size - 1) * 1_000_000_000.0 / durationNanos).toFloat()
                            return fps.coerceIn(0f, 240f)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return -1f
    }

    private fun queryActiveLayerName(): String {
        try {
            val out = ShellUtils.exec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'")
            if (out.isNotBlank()) {
                val regex = Regex("([a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+)")
                val match = regex.find(out)
                if (match != null) {
                    return match.value
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    override fun stop() {
        isRunning = false
        handler.post {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }
}
