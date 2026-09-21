package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
import java.io.File

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

        // 2. Read CPU Frequency (Per-core in kHz)
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

        // Fallback usage estimation if proc/stat was unreadable
        if (metrics.cpuUsage == 0.0f && maxFreqKhz > 0) {
            metrics.cpuUsage = estimateCpuUsageFromFrequencies()
        }

        // 3. Read CPU Temperature
        metrics.cpuTemperature = readCpuTemperature()
    }

    private fun readCpuUsageFromProcStat(): Float {
        var statLine: String? = null

        // Try direct file read
        try {
            val file = File("/proc/stat")
            if (file.exists() && file.canRead()) {
                statLine = file.bufferedReader().use { it.readLine() }
            }
        } catch (_: Exception) {}

        // Fallback to elevated exec
        if (statLine.isNullOrBlank()) {
            val out = ShellUtils.exec("cat /proc/stat 2>/dev/null | head -n 1")
            if (out.startsWith("cpu")) {
                statLine = out
            }
        }

        if (statLine.isNullOrBlank()) return -1f

        try {
            val parts = statLine.trim().split("\\s+".toRegex())
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
                    val v = text.toIntOrNull()
                    if (v != null && v > 0) return v
                }
            } catch (_: Exception) {}
        }

        // Fallback elevated read
        val out = ShellUtils.exec("cat /sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq 2>/dev/null")
        val parsed = out.trim().toIntOrNull()
        if (parsed != null && parsed > 0) {
            return parsed
        }

        return 0
    }

    private fun estimateCpuUsageFromFrequencies(): Float {
        var sumLoad = 0f
        var count = 0
        for (i in 0 until coreCount) {
            val cur = coreFrequencies[i]
            if (cur > 0) {
                val max = readSysfsInt("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                val min = readSysfsInt("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
                if (max > min && min >= 0) {
                    val load = ((cur - min).toFloat() / (max - min).toFloat()) * 100f
                    sumLoad += load
                    count++
                }
            }
        }
        return if (count > 0) (sumLoad / count).coerceIn(0f, 100f) else 0f
    }

    private fun readSysfsInt(path: String): Int {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                return f.readText().trim().toIntOrNull() ?: 0
            }
        } catch (_: Exception) {}
        val out = ShellUtils.exec("cat $path 2>/dev/null")
        return out.trim().toIntOrNull() ?: 0
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
                            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("ap") || type.contains("cluster") || type.contains("mtktscpu")) {
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

        // Fallback scan via shell
        val out = ShellUtils.exec("for tz in /sys/class/thermal/thermal_zone*; do t=\$(cat \$tz/type 2>/dev/null); case \$t in *cpu*|*soc*|*tsens*|*ap*|*cluster*) echo \$tz/temp; break;; esac; done")
        if (out.isNotBlank()) {
            cpuThermalPath = out.lines().firstOrNull()?.trim()
        }
    }

    private fun readCpuTemperature(): Float {
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
