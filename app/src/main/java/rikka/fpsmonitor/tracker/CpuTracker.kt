package rikka.fpsmonitor.tracker

import rikka.fpsmonitor.model.PerformanceMetrics
import java.io.File
import java.io.RandomAccessFile

class CpuTracker : ITracker {

    private var lastTotalTime = 0L
    private var lastWorkTime = 0L
    private var coreCount = Runtime.getRuntime().availableProcessors()
    private val coreFrequencies = IntArray(coreCount)
    private var cpuThermalPath: String? = null

    init {
        findCpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        // 1. Calculate CPU Usage %
        val usage = readCpuUsageFromProcStat()
        if (usage >= 0) {
            metrics.cpuUsage = usage
        }

        // 2. Read CPU Frequency (Max or Average among active cores)
        var maxFreqKhz = 0
        for (i in 0 until coreCount) {
            val freq = readCoreFrequency(i)
            coreFrequencies[i] = freq
            if (freq > maxFreqKhz) {
                maxFreqKhz = freq
            }
        }
        metrics.cpuFrequencyGhz = if (maxFreqKhz > 0) maxFreqKhz / 1_000_000.0f else 0.0f
        metrics.coreFrequencies = coreFrequencies.clone()

        // 3. Read CPU Temperature
        metrics.cpuTemperature = readCpuTemperature()
    }

    private fun readCpuUsageFromProcStat(): Float {
        try {
            val file = File("/proc/stat")
            if (!file.exists() || !file.canRead()) return -1f

            val line = file.bufferedReader().use { it.readLine() } ?: return -1f
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return -1f

            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val work = user + nice + system + irq + softirq + steal

            if (lastTotalTime != 0L) {
                val totalDelta = total - lastTotalTime
                val workDelta = work - lastWorkTime
                if (totalDelta > 0) {
                    val percent = (workDelta.toFloat() / totalDelta.toFloat()) * 100.0f
                    lastTotalTime = total
                    lastWorkTime = work
                    return percent.coerceIn(0.0f, 100.0f)
                }
            }
            lastTotalTime = total
            lastWorkTime = work
        } catch (_: Exception) {}
        return -1f
    }

    private fun readCoreFrequency(core: Int): Int {
        val paths = arrayOf(
            "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq"
        )
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val text = f.readText().trim()
                    return text.toIntOrNull() ?: 0
                }
            } catch (_: Exception) {}
        }
        return 0
    }

    private fun findCpuThermalZone() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (!thermalDir.exists()) return

            val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return
            for (zone in zones) {
                val typeFile = File(zone, "type")
                if (typeFile.exists() && typeFile.canRead()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("ap")) {
                        val tempFile = File(zone, "temp")
                        if (tempFile.exists() && tempFile.canRead()) {
                            cpuThermalPath = tempFile.absolutePath
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun readCpuTemperature(): Float {
        val path = cpuThermalPath ?: return 0f
        try {
            val file = File(path)
            if (file.exists()) {
                val tempStr = file.readText().trim()
                val tempRaw = tempStr.toFloatOrNull() ?: return 0f
                return if (tempRaw > 1000) tempRaw / 1000f else tempRaw
            }
        } catch (_: Exception) {}
        return 0f
    }
}
