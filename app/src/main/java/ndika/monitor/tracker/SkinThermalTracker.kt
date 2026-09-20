package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import java.io.File

class SkinThermalTracker : ITracker {

    private var skinThermalPath: String? = null

    init {
        findSkinThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        metrics.skinTemperature = readSkinTemperature()
    }

    private fun findSkinThermalZone() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (!thermalDir.exists()) return

            val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return
            for (zone in zones) {
                val typeFile = File(zone, "type")
                if (typeFile.exists() && typeFile.canRead()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (type.contains("skin") || type.contains("shell") || type.contains("quiet_therm") || type.contains("surface")) {
                        val tempFile = File(zone, "temp")
                        if (tempFile.exists() && tempFile.canRead()) {
                            skinThermalPath = tempFile.absolutePath
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun readSkinTemperature(): Float {
        val path = skinThermalPath ?: return -10000.0f
        try {
            val file = File(path)
            if (file.exists()) {
                val tempStr = file.readText().trim()
                val tempRaw = tempStr.toFloatOrNull() ?: return -10000.0f
                return if (tempRaw > 1000) tempRaw / 1000f else tempRaw
            }
        } catch (_: Exception) {}
        return -10000.0f
    }
}
