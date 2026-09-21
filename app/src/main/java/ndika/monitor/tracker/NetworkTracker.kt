package ndika.monitor.tracker

import android.net.TrafficStats
import ndika.monitor.model.PerformanceMetrics

class NetworkTracker : ITracker {

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastTimeMs = 0L

    override fun update(metrics: PerformanceMetrics) {
        val currentRx = try { TrafficStats.getTotalRxBytes() } catch (_: Exception) { 0L }
        val currentTx = try { TrafficStats.getTotalTxBytes() } catch (_: Exception) { 0L }
        val now = System.currentTimeMillis()

        if (lastRxBytes != -1L && lastTimeMs > 0L) {
            val timeDeltaSec = (now - lastTimeMs) / 1000.0f
            if (timeDeltaSec > 0) {
                val rxDelta = (currentRx - lastRxBytes).coerceAtLeast(0L)
                val txDelta = (currentTx - lastTxBytes).coerceAtLeast(0L)

                metrics.downloadSpeedBytesPerSec = (rxDelta / timeDeltaSec).toLong()
                metrics.uploadSpeedBytesPerSec = (txDelta / timeDeltaSec).toLong()
            }
        } else {
            metrics.downloadSpeedBytesPerSec = 0L
            metrics.uploadSpeedBytesPerSec = 0L
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimeMs = now
    }
}
