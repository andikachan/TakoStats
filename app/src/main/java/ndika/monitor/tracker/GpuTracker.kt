package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import java.io.File

class GpuTracker : ITracker {

    private var gpuBusyPath: String? = null
    private var gpuThermalPath: String? = null

    init {
        detectGpuPaths()
        detectGpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        metrics.gpuUsage = readGpuUsage()
        metrics.gpuTemperature = readGpuTemperature()
    }

    private fun detectGpuPaths() {
        val candidates = arrayOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/devices/platform/13000000.mali/utilization",
            "/sys/devices/platform/13040000.mali/utilization",
            "/sys/devices/platform/mali.0/utilization",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/devices/soc/1c00000.qcom,kgsl-3d0/kgsl/kgsl-3d0/gpu_busy_percentage"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                gpuBusyPath = path
                break
            }
        }
    }

    private fun detectGpuThermalZone() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (!thermalDir.exists()) return

            val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return
            for (zone in zones) {
                val typeFile = File(zone, "type")
                if (typeFile.exists() && typeFile.canRead()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.contains("gpu") || type.contains("gpuss") || type.contains("mali") || type.contains("adreno")) {
                        val tempFile = File(zone, "temp")
                        if (tempFile.exists() && tempFile.canRead()) {
                            gpuThermalPath = tempFile.absolutePath
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun readGpuUsage(): Float {
        val path = gpuBusyPath ?: return 0f
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val line = file.readText().trim()
                if (line.contains("%")) {
                    val num = line.replace("%", "").trim().toFloatOrNull() ?: 0f
                    return num.coerceIn(0f, 100f)
                }
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busy = parts[0].toLongOrNull() ?: 0L
                    val total = parts[1].toLongOrNull() ?: 0L
                    if (total > 0) {
                        return ((busy.toFloat() / total.toFloat()) * 100f).coerceIn(0f, 100f)
                    }
                }
                val singleNum = line.toFloatOrNull() ?: 0f
                return singleNum.coerceIn(0f, 100f)
            }
        } catch (_: Exception) {}
        return 0f
    }

    private fun readGpuTemperature(): Float {
        val path = gpuThermalPath ?: return 0f
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val raw = file.readText().trim().toFloatOrNull() ?: 0f
                return if (raw > 1000) raw / 1000f else raw
            }
        } catch (_: Exception) {}
        return 0f
    }
}
