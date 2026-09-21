package ndika.monitor.tracker

import android.app.ActivityManager
import android.content.Context
import ndika.monitor.model.PerformanceMetrics
import java.io.File

class MemoryTracker(context: Context) : ITracker {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

    override fun update(metrics: PerformanceMetrics) {
        // Try reading directly from /proc/meminfo for exact kernel figures
        if (readFromProcMeminfo(metrics)) {
            return
        }

        // Fallback to ActivityManager
        if (activityManager == null) return
        try {
            activityManager.getMemoryInfo(memoryInfo)
            val totalBytes = memoryInfo.totalMem
            val availBytes = memoryInfo.availMem
            val usedBytes = totalBytes - availBytes

            val usedMb = (usedBytes / (1024 * 1024)).toInt()
            val usedPct = (usedBytes.toFloat() / totalBytes.toFloat() * 100.0f).coerceIn(0f, 100f)

            metrics.memoryUsageMb = usedMb
            metrics.memoryUsagePercentage = usedPct
        } catch (_: Exception) {}
    }

    private fun readFromProcMeminfo(metrics: PerformanceMetrics): Boolean {
        try {
            val file = File("/proc/meminfo")
            if (file.exists() && file.canRead()) {
                var memTotalKb = 0L
                var memAvailableKb = 0L
                var memFreeKb = 0L
                var buffersKb = 0L
                var cachedKb = 0L

                file.forEachLine { line ->
                    if (line.startsWith("MemTotal:")) {
                        memTotalKb = parseMeminfoKb(line)
                    } else if (line.startsWith("MemAvailable:")) {
                        memAvailableKb = parseMeminfoKb(line)
                    } else if (line.startsWith("MemFree:")) {
                        memFreeKb = parseMeminfoKb(line)
                    } else if (line.startsWith("Buffers:")) {
                        buffersKb = parseMeminfoKb(line)
                    } else if (line.startsWith("Cached:")) {
                        cachedKb = parseMeminfoKb(line)
                    }
                }

                if (memTotalKb > 0) {
                    val availableKb = if (memAvailableKb > 0) memAvailableKb else (memFreeKb + buffersKb + cachedKb)
                    val usedKb = (memTotalKb - availableKb).coerceAtLeast(0L)
                    val usedMb = (usedKb / 1024).toInt()
                    val usedPct = (usedKb.toFloat() / memTotalKb.toFloat() * 100.0f).coerceIn(0f, 100f)

                    metrics.memoryUsageMb = usedMb
                    metrics.memoryUsagePercentage = usedPct
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun parseMeminfoKb(line: String): Long {
        return line.substringAfter(":").trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
    }
}
