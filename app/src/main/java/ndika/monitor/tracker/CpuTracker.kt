package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
import java.io.File

class CpuTracker : ITracker {

    private var lastTotalTime = 0L
    private var lastIdleTime = 0L
    private val coreCount = Runtime.getRuntime().availableProcessors()
    private val coreFrequencies = IntArray(coreCount)
    private var cpuThermalPath: String? = null
    private var lastThermalDetectTimeMs = 0L

    init {
        findCpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        // 1. Calculate Real CPU Usage % from /proc/stat
        val usage = readCpuUsageFromProcStat()
        if (usage >= 0.0f) {
            metrics.cpuUsage = usage
        }

        // 2. Read All CPU Core Frequencies in high-speed batch
        readAllCoreFrequencies()
        var maxFreqKhz = 0
        for (f in coreFrequencies) {
            if (f > maxFreqKhz) {
                maxFreqKhz = f
            }
        }
        metrics.cpuFrequencyGhz = if (maxFreqKhz > 0) maxFreqKhz / 1_000_000.0f else 0.0f
        metrics.coreFrequencies = coreFrequencies.clone()

        // 3. Read CPU Temperature
        val temp = readCpuTemperature()
        if (temp > -1000f) {
            metrics.cpuTemperature = temp
        }
    }

    private fun readCpuUsageFromProcStat(): Float {
        var statLine: String? = null

        // Fast direct file read (0.01ms)
        try {
            val file = File("/proc/stat")
            if (file.exists() && file.canRead()) {
                statLine = file.bufferedReader().use { it.readLine() }
            }
        } catch (_: Exception) {}

        if (statLine.isNullOrBlank()) {
            val out = ShellUtils.exec("head -n 1 /proc/stat 2>/dev/null")
            if (out.startsWith("cpu")) {
                statLine = out
            }
        }

        if (statLine.isNullOrBlank()) return -1.0f

        try {
            val parts = statLine.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return -1.0f

            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val idleTime = idle + iowait

            if (lastTotalTime != 0L) {
                val totalDelta = total - lastTotalTime
                val idleDelta = idleTime - lastIdleTime
                if (totalDelta > 0) {
                    val percent = ((totalDelta - idleDelta).toFloat() / totalDelta.toFloat()) * 100.0f
                    lastTotalTime = total
                    lastIdleTime = idleTime
                    return percent.coerceIn(0.0f, 100.0f)
                }
            }
            lastTotalTime = total
            lastIdleTime = idleTime
        } catch (_: Exception) {}

        return -1.0f
    }

    private fun readAllCoreFrequencies() {
        var directReadSuccess = true

        // Try direct read first
        for (i in 0 until coreCount) {
            var read = false
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (f.exists() && f.canRead()) {
                    val v = f.readText().trim().toIntOrNull()
                    if (v != null && v > 0) {
                        coreFrequencies[i] = v
                        read = true
                    }
                }
            } catch (_: Exception) {}

            if (!read) {
                directReadSuccess = false
                break
            }
        }

        if (directReadSuccess) return

        // Single batch elevated read for ALL cores at once (<1ms)
        val out = ShellUtils.exec("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null")
        if (out.isNotBlank()) {
            val lines = out.lines().mapNotNull { it.trim().toIntOrNull() }
            for (i in lines.indices) {
                if (i < coreFrequencies.size && lines[i] > 0) {
                    coreFrequencies[i] = lines[i]
                }
            }
        }
    }

    private fun findCpuThermalZone() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.exists()) {
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
                if (zones != null) {
                    for (zone in zones) {
                        val typeFile = File(zone, "type")
                        if (typeFile.exists() && typeFile.canRead()) {
                            val type = typeFile.readText().trim().lowercase()
                            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("ap") || type.contains("cluster") || type.contains("mtktscpu") || type.contains("cpu-1-0") || type.contains("cpu-0-0")) {
                                val tempFile = File(zone, "temp")
                                if (tempFile.exists() && tempFile.canRead()) {
                                    cpuThermalPath = tempFile.absolutePath
                                    return
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback single-line scan via shell
        val out = ShellUtils.exec("for tz in /sys/class/thermal/thermal_zone*; do t=\$(cat \$tz/type 2>/dev/null); case \$t in *cpu*|*soc*|*tsens*|*ap*|*cluster*|*mtktscpu*) echo \$tz/temp; break;; esac; done")
        if (out.isNotBlank()) {
            cpuThermalPath = out.lines().firstOrNull()?.trim()
        }
    }

    private fun readCpuTemperature(): Float {
        val now = System.currentTimeMillis()
        if (cpuThermalPath == null && now - lastThermalDetectTimeMs > 3000) {
            findCpuThermalZone()
            lastThermalDetectTimeMs = now
        }

        val path = cpuThermalPath ?: return -10000.0f
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val tempStr = file.readText().trim()
                val tempRaw = tempStr.toFloatOrNull()
                if (tempRaw != null) {
                    return scaleTemperature(tempRaw)
                }
            }
        } catch (_: Exception) {}

        val out = ShellUtils.exec("cat $path 2>/dev/null")
        val raw = out.trim().toFloatOrNull()
        if (raw != null) {
            return scaleTemperature(raw)
        }

        return -10000.0f
    }

    private fun scaleTemperature(raw: Float): Float {
        return when {
            raw > 10000f -> raw / 1000f
            raw > 100f -> raw / 10f
            else -> raw
        }
    }
}
