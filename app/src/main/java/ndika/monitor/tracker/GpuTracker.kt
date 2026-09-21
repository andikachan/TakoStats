package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
import java.io.File

class GpuTracker : ITracker {

    private var gpuBusyPath: String? = null
    private var gpuThermalPath: String? = null
    private var lastDetectTimeMs = 0L

    private val fastCandidates = arrayOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/module/ged/parameters/gpu_loading",
        "/proc/ged/gpu_load",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/devices/platform/13040000.mali/utilization",
        "/sys/devices/platform/mali.0/utilization",
        "/sys/class/devfreq/gpufreq/load",
        "/sys/class/devfreq/1c00000.qcom,kgsl-3d0/load",
        "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/load"
    )

    init {
        detectGpuPaths()
        detectGpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        val now = System.currentTimeMillis()
        if (gpuBusyPath == null && now - lastDetectTimeMs > 2500) {
            detectGpuPaths()
            detectGpuThermalZone()
            lastDetectTimeMs = now
        }

        metrics.gpuUsage = readGpuUsage()
        metrics.gpuTemperature = readGpuTemperature()
    }

    private fun detectGpuPaths() {
        // 1. Direct file check (<0.1ms)
        for (path in fastCandidates) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    gpuBusyPath = path
                    return
                }
            } catch (_: Exception) {}
        }

        // 2. Single multi-candidate batch query in ONE process (<5ms)
        val script = "for p in /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/module/ged/parameters/gpu_loading /proc/ged/gpu_load /sys/class/misc/mali0/device/utilization /sys/devices/platform/*.mali/utilization /sys/class/devfreq/*/load; do if [ -f \$p ]; then echo \$p; break; fi; done"
        val out = ShellUtils.exec(script)
        if (out.isNotBlank()) {
            val found = out.lines().firstOrNull()?.trim()
            if (!found.isNullOrBlank() && (found.startsWith("/sys") || found.startsWith("/proc"))) {
                gpuBusyPath = found
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
        val path = gpuBusyPath ?: return 0.0f
        return parseGpuPath(path).coerceAtLeast(0.0f)
    }

    private fun parseGpuPath(path: String): Float {
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

        if (rawText.isNullOrBlank()) return 0.0f

        try {
            // MediaTek "gpu_load: 35" or "35%"
            if (rawText.contains(":")) {
                val numPart = rawText.substringAfter(":").replace("%", "").trim().split("\\s+".toRegex()).firstOrNull()
                val parsed = numPart?.toFloatOrNull()
                if (parsed != null) return parsed.coerceIn(0.0f, 100.0f)
            }

            if (rawText.contains("%")) {
                val num = rawText.replace("%", "").trim().toFloatOrNull()
                if (num != null) return num.coerceIn(0.0f, 100.0f)
            }

            val parts = rawText.split("\\s+".toRegex())
            // Adreno gpubusy: "busy_cycles total_cycles"
            if (parts.size >= 2) {
                val busy = parts[0].toDoubleOrNull() ?: 0.0
                val total = parts[1].toDoubleOrNull() ?: 0.0
                if (total > 0) {
                    return ((busy / total) * 100.0).toFloat().coerceIn(0.0f, 100.0f)
                }
            }

            val singleNum = rawText.toFloatOrNull()
            if (singleNum != null) {
                // Mali 0..255 scale
                if (singleNum > 100.0f && singleNum <= 255.0f) {
                    return ((singleNum / 255.0f) * 100.0f).coerceIn(0.0f, 100.0f)
                }
                return singleNum.coerceIn(0.0f, 100.0f)
            }
        } catch (_: Exception) {}

        return 0.0f
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
