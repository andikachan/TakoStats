package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
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
            if (thermalDir.exists()) {
                val zones = thermalDir.listFiles { f -> f.name.startsWith("thermal_zone") }
                if (zones != null) {
                    for (zone in zones) {
                        val typeFile = File(zone, "type")
                        if (typeFile.exists() && typeFile.canRead()) {
                            val type = typeFile.readText().trim().lowercase()
                            if (type.contains("skin") || type.contains("shell") || type.contains("quiet_therm") || type.contains("surface") || type.contains("chg_skin") || type.contains("board") || type.contains("back")) {
                                val tempFile = File(zone, "temp")
                                if (tempFile.exists() && tempFile.canRead()) {
                                    skinThermalPath = tempFile.absolutePath
                                    return
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val out = ShellUtils.exec("for tz in /sys/class/thermal/thermal_zone*; do t=\$(cat \$tz/type 2>/dev/null); case \$t in *skin*|*shell*|*quiet_therm*|*surface*|*chg_skin*|*board*|*back*) echo \$tz/temp; break;; esac; done")
        if (out.isNotBlank()) {
            skinThermalPath = out.lines().firstOrNull()?.trim()
        }
    }

    private fun readSkinTemperature(): Float {
        val path = skinThermalPath ?: return -10000.0f
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
