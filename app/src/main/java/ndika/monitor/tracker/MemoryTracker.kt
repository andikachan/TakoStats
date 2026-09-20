package ndika.monitor.tracker

import android.app.ActivityManager
import android.content.Context
import ndika.monitor.model.PerformanceMetrics

class MemoryTracker(context: Context) : ITracker {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

    override fun update(metrics: PerformanceMetrics) {
        if (activityManager == null) return
        try {
            activityManager.getMemoryInfo(memoryInfo)
            val totalBytes = memoryInfo.totalMem
            val availBytes = memoryInfo.availMem
            val usedBytes = totalBytes - availBytes

            val usedMb = (usedBytes / (1024 * 1024)).toInt()
            val usedPct = (usedBytes.toDouble() / totalBytes.toDouble() * 100.0).toFloat()

            metrics.memoryUsageMb = usedMb
            metrics.memoryUsagePercentage = usedPct.coerceIn(0f, 100f)
        } catch (_: Exception) {}
    }
}
