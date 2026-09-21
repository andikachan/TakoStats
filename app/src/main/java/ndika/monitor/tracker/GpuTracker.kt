package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
import java.io.File

class GpuTracker : ITracker {

    private var gpuBusyPath: String? = null
    private var gpuThermalPath: String? = null

    private val candidates = arrayOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/devices/platform/13040000.mali/utilization",
        "/sys/devices/platform/mali.0/utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/soc/1c00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/devices/platform/gpusys/gpu_busy",
        "/sys/class/devfreq/gpufreq/cur_freq"
    )

    init {
        detectGpuPaths()
        detectGpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        metrics.gpuUsage = readGpuUsage()
        metrics.gpuTemperature = readGpuTemperature()
    }

    private fun detectGpuPaths() {
        for (path in candidates) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    gpuBusyPath = path
                    return
                }
            } catch (_: Exception) {}
        }

        // Fallback check with shell
        for (path in candidates) {
            val out = ShellUtils.exec("if [ -f $path ]; then echo $path; fi")
            if (out.isNotBlank()) {
                gpuBusyPath = path
                return
            }
        }
    }

    private fun detectGpuThermalZone() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.exists()) {
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
                if (zones != null) {
                    for (zone in zones) {
                        val typeFile = File(zone, "type")
                        if (typeFile.exists() && typeFile.canRead()) {
                            val type = typeFile.readText().trim().lowercase()
                            if (type.contains("gpu") || type.contains("gpuss") || type.contains("mali") || type.contains("adreno") || type.contains("kgsl") || type.contains("g3d")) {
                                val tempFile = File(zone, "temp")
                                if (tempFile.exists() && tempFile.canRead()) {
                                    gpuThermalPath = tempFile.absolutePath
                                    return
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val out = ShellUtils.exec("for tz in /sys/class/thermal/thermal_zone*; do t=\$(cat \$tz/type 2>/dev/null); case \$t in *gpu*|*gpuss*|*mali*|*adreno*|*kgsl*|*g3d*) echo \$tz/temp; break;; esac; done")
        if (out.isNotBlank()) {
            gpuThermalPath = out.lines().firstOrNull()?.trim()
        }
    }

    private fun readGpuUsage(): Float {
        val path = gpuBusyPath ?: return 0f
        var rawText: String? = null

        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                rawText = file.readText().trim()
            }
        } catch (_: Exception) {}

        if (rawText.isNullOrBlank()) {
            val out = ShellUtils.exec("cat $path 2>/dev/null")
            if (out.isNotBlank()) {
                rawText = out.trim()
            }
        }

        if (rawText.isNullOrBlank()) return 0f

        try {
            if (rawText.contains("%")) {
                val num = rawText.replace("%", "").trim().toFloatOrNull() ?: 0f
                return num.coerceIn(0f, 100f)
            }
            val parts = rawText.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val busy = parts[0].toLongOrNull() ?: 0L
                val total = parts[1].toLongOrNull() ?: 0L
                if (total > 0) {
                    return ((busy.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)
                }
            }
            val singleNum = rawText.toFloatOrNull() ?: 0f
            return singleNum.coerceIn(0f, 100f)
        } catch (_: Exception) {}

        return 0f
    }

    private fun readGpuTemperature(): Float {
        val path = gpuThermalPath ?: return -10000.0f
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val raw = file.readText().trim().toFloatOrNull()
                if (raw != null) {
                    return scaleTemperature(raw)
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
