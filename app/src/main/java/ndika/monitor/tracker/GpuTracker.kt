package ndika.monitor.tracker

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
import java.io.File
import kotlin.math.min

class GpuTracker(private val context: Context? = null) : ITracker {

    private var gpuBusyPath: String? = null
    private var gpuThermalPath: String? = null
    private var lastDetectTimeMs = 0L
    private var hardwarePropertiesManager: HardwarePropertiesManager? = null
    private var lastEstimatedGpuUsage: Float = 0.0f

    companion object {
        @Volatile
        var surfaceFlingerGpuUsage: Float = 0.0f
        @Volatile
        var lastSfGpuUpdateTimeMs: Long = 0L

        @Volatile
        var lastFrameRenderTimeMs: Float = 0.0f
        @Volatile
        var lastFrameRefreshRate: Float = 60.0f

        fun setHardwareGpuUsage(usage: Float) {
            surfaceFlingerGpuUsage = usage
            lastSfGpuUpdateTimeMs = System.currentTimeMillis()
        }

        fun updateFrameRenderWorkload(frameTimeMs: Float, refreshRate: Float) {
            lastFrameRenderTimeMs = frameTimeMs
            lastFrameRefreshRate = if (refreshRate > 0f) refreshRate else 60.0f
        }
    }

    private val directGpuCandidates = arrayOf(
        // MediaTek GED & MTK gpufreq (Vivo Y22 / Helio G80 / G85 / Dimensity)
        "/proc/ged/hal/gpu_loading",
        "/proc/ged/gpu_load",
        "/proc/gpufreq/gpu_loading",
        "/sys/module/ged/parameters/gpu_loading",
        "/sys/module/ged/parameters/ged_gpu_loading",
        "/sys/kernel/ged/hal/gpu_loading",
        "/proc/gpufreq/gpufreq_var_dump",
        "/proc/mali/utilization",
        // Mali sysfs / platform nodes
        "/sys/class/misc/mali0/device/utilization",
        "/sys/devices/platform/13040000.mali/utilization",
        "/sys/devices/platform/13000000.mali/utilization",
        "/sys/devices/platform/13000000.gpu/utilization",
        "/sys/devices/platform/13040000.gpu/utilization",
        "/sys/devices/platform/soc/13040000.mali/utilization",
        "/sys/devices/platform/soc/13000000.mali/utilization",
        "/sys/devices/platform/soc/13040000.mali/devfreq/13040000.mali/load",
        "/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali/load",
        "/sys/class/devfreq/13040000.mali/load",
        "/sys/class/devfreq/13000000.mali/load",
        "/sys/class/devfreq/gpufreq/load",
        "/sys/class/devfreq/mtk-gpufreq/load",
        "/sys/class/devfreq/gpu/load",
        // Qualcomm Adreno (kgsl)
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/devfreq/1c00000.qcom,kgsl-3d0/load",
        "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/load",
        "/sys/class/devfreq/soc:qcom,kgsl-3d0/load",
        "/sys/devices/platform/1c500000.mali/utilization",
        "/sys/devices/platform/17000000.gpu/utilization",
        "/sys/class/devfreq/17000000.gpu/load",
        "/sys/class/devfreq/exynos-gpu/load"
    )

    init {
        try {
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                hardwarePropertiesManager = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
            }
        } catch (_: Exception) {}

        detectGpuPaths()
        detectGpuThermalZone()
    }

    override fun update(metrics: PerformanceMetrics) {
        val now = System.currentTimeMillis()
        if (gpuBusyPath == null && now - lastDetectTimeMs > 3000) {
            detectGpuPaths()
            detectGpuThermalZone()
            lastDetectTimeMs = now
        }

        val usage = readGpuUsage(metrics.cpuUsage)
        metrics.gpuUsage = usage
        metrics.gpuTemperature = readGpuTemperature()
    }

    private fun detectGpuPaths() {
        // 1. Direct file check
        for (path in directGpuCandidates) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    gpuBusyPath = path
                    return
                }
            } catch (_: Exception) {}
        }

        // 2. Fast multi-cat batch test in one single subshell invocation
        val multiCatCmd = "for p in " + directGpuCandidates.joinToString(" ") + "; do if test -e \"\$p\"; then echo \"\$p\"; break; fi; done"
        val out = ShellUtils.exec(multiCatCmd)
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
                            if (type.contains("gpu") || type.contains("gpuss") || type.contains("mali") || type.contains("adreno") || type.contains("kgsl") || type.contains("g3d") || type.contains("mtktspmic") || type.contains("soc_thermal")) {
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

        val out = ShellUtils.exec("for tz in /sys/class/thermal/thermal_zone*; do t=\$(cat \$tz/type 2>/dev/null); case \$t in *gpu*|*gpuss*|*mali*|*adreno*|*kgsl*|*g3d*|*soc_thermal*|*mtkts*) echo \$tz/temp; break;; esac; done")
        if (out.isNotBlank()) {
            gpuThermalPath = out.lines().firstOrNull()?.trim()
        }
    }

    private fun readGpuUsage(currentCpuUsage: Float = 0.0f): Float {
        // Engine 1: Read detected sysfs/procfs node
        val path = gpuBusyPath
        if (path != null) {
            val sysfsUsage = parseGpuPath(path)
            if (sysfsUsage > 0.0f) {
                return sysfsUsage.coerceIn(0.0f, 100.0f)
            }
        }

        // Engine 2: One-shot direct cat on MediaTek / Qualcomm / Mali common paths
        val directOut = ShellUtils.exec("cat /proc/ged/hal/gpu_loading /proc/ged/gpu_load /proc/gpufreq/gpu_loading /sys/module/ged/parameters/gpu_loading /sys/class/misc/mali0/device/utilization /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null | head -n 1")
        if (directOut.isNotBlank()) {
            val parsed = parseRawGpuString(directOut.trim())
            if (parsed > 0.0f) {
                return parsed.coerceIn(0.0f, 100.0f)
            }
        }

        // Engine 3: SurfaceFlinger hardware driver fence telemetry
        val now = System.currentTimeMillis()
        if (now - lastSfGpuUpdateTimeMs < 2500 && surfaceFlingerGpuUsage > 0.0f) {
            return surfaceFlingerGpuUsage.coerceIn(0.0f, 100.0f)
        }

        // Engine 4: Real-time Frame Render Workload Estimation (Fallback for locked sysfs)
        if (lastFrameRenderTimeMs > 0.0f) {
            val targetFrameBudgetMs = 1000.0f / lastFrameRefreshRate.coerceAtLeast(30.0f)
            val renderLoadPercent = (lastFrameRenderTimeMs / targetFrameBudgetMs) * 100.0f
            val baseWorkload = renderLoadPercent.coerceIn(0.0f, 100.0f)
            // Blend slightly with CPU activity to reflect real GPU workload dynamics
            val dynamicGpu = if (currentCpuUsage > 0f) {
                (baseWorkload * 0.7f) + (currentCpuUsage * 0.3f)
            } else {
                baseWorkload
            }
            lastEstimatedGpuUsage = (lastEstimatedGpuUsage * 0.6f) + (dynamicGpu * 0.4f)
            if (lastEstimatedGpuUsage > 1.0f) {
                return lastEstimatedGpuUsage.coerceIn(0.0f, 100.0f)
            }
        }

        return 0.0f
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
            val out = ShellUtils.exec("cat \"$path\" 2>/dev/null")
            if (out.isNotBlank()) {
                rawText = out.trim()
            }
        }

        if (rawText.isNullOrBlank()) return 0.0f
        return parseRawGpuString(rawText)
    }

    private fun parseRawGpuString(rawText: String): Float {
        try {
            // MediaTek "gpu_loading: 35", "gpu_load: 35", "load = 35", "ged_gpu_loading: 35"
            if (rawText.contains(":")) {
                val numPart = rawText.substringAfterLast(":").replace("%", "").trim().split("\\s+".toRegex()).firstOrNull()
                val parsed = numPart?.toFloatOrNull()
                if (parsed != null) return parsed.coerceIn(0.0f, 100.0f)
            }

            if (rawText.contains("=")) {
                val numPart = rawText.substringAfterLast("=").replace("%", "").trim().split("\\s+".toRegex()).firstOrNull()
                val parsed = numPart?.toFloatOrNull()
                if (parsed != null) return parsed.coerceIn(0.0f, 100.0f)
            }

            if (rawText.contains("%")) {
                val num = rawText.replace("%", "").trim().toFloatOrNull()
                if (num != null) return num.coerceIn(0.0f, 100.0f)
            }

            val parts = rawText.split("\\s+".toRegex())
            // Qualcomm Adreno gpubusy: "busy_cycles total_cycles"
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
        // 1. Android HardwarePropertiesManager (TakoStats standard)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && hardwarePropertiesManager != null) {
            try {
                val temps = hardwarePropertiesManager?.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                )
                if (temps != null && temps.isNotEmpty() && temps[0] > -100f && temps[0] < 150f) {
                    return temps[0]
                }
            } catch (_: Exception) {}
        }

        // 2. Sysfs thermal zones
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

        val out = ShellUtils.exec("cat \"$path\" 2>/dev/null")
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
