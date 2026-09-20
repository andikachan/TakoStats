package ndika.monitor.model

import android.os.Bundle
import java.io.Serializable
import java.util.Locale

data class PerformanceMetrics(
    var fps: Float = 0.0f,
    var layerName: String = "",
    var cpuUsage: Float = 0.0f,
    var cpuFrequencyGhz: Float = 0.0f,
    var cpuTemperature: Float = 0.0f,
    var gpuUsage: Float = 0.0f,
    var gpuTemperature: Float = 0.0f,
    var batteryTemperature: Float = 0.0f,
    var skinTemperature: Float = 0.0f,
    var batteryCurrentAmp: Float = 0.0f,
    var batteryVoltageVolts: Float = 0.0f,
    var batteryPowerWatts: Float = 0.0f,
    var framePowerMilliJoules: Float = 0.0f,
    var memoryUsageMb: Int = 0,
    var memoryUsagePercentage: Float = 0.0f,
    var uploadSpeedBytesPerSec: Long = 0L,
    var downloadSpeedBytesPerSec: Long = 0L,
    var coreFrequencies: IntArray = IntArray(0),
    var timestamp: Long = System.currentTimeMillis()
) : Serializable {

    fun formatTemperature(celsius: Float, unit: String): String {
        return if (unit.equals("fahrenheit", ignoreCase = true)) {
            val fahrenheit = (celsius * 9f / 5f) + 32f
            String.format(Locale.US, "%.1f °F", fahrenheit)
        } else {
            String.format(Locale.US, "%.1f °C", celsius)
        }
    }

    fun toBundle(): Bundle {
        return Bundle().apply {
            putFloat(KEY_FPS, fps)
            putString(KEY_LAYER, layerName)
            putFloat(KEY_CPU_USAGE, cpuUsage)
            putFloat(KEY_CPU_FREQ, cpuFrequencyGhz)
            putFloat(KEY_CPU_TEMP, cpuTemperature)
            putFloat(KEY_GPU_USAGE, gpuUsage)
            putFloat(KEY_GPU_TEMP, gpuTemperature)
            putFloat(KEY_BAT_TEMP, batteryTemperature)
            putFloat(KEY_SKIN_TEMP, skinTemperature)
            putFloat(KEY_BAT_CUR, batteryCurrentAmp)
            putFloat(KEY_BAT_VOLT, batteryVoltageVolts)
            putFloat(KEY_BAT_PWR, batteryPowerWatts)
            putFloat(KEY_FRAME_PWR, framePowerMilliJoules)
            putInt(KEY_MEM_MB, memoryUsageMb)
            putFloat(KEY_MEM_PCT, memoryUsagePercentage)
            putLong(KEY_NET_UL, uploadSpeedBytesPerSec)
            putLong(KEY_NET_DL, downloadSpeedBytesPerSec)
            putIntArray(KEY_CORES, coreFrequencies)
            putLong(KEY_TIMESTAMP, timestamp)
        }
    }

    companion object {
        const val KEY_FPS = "fps"
        const val KEY_LAYER = "layer"
        const val KEY_CPU_USAGE = "cpu_usage"
        const val KEY_CPU_FREQ = "cpu_freq"
        const val KEY_CPU_TEMP = "cpu_temp"
        const val KEY_GPU_USAGE = "gpu_usage"
        const val KEY_GPU_TEMP = "gpu_temp"
        const val KEY_BAT_TEMP = "bat_temp"
        const val KEY_SKIN_TEMP = "skin_temp"
        const val KEY_BAT_CUR = "bat_cur"
        const val KEY_BAT_VOLT = "bat_volt"
        const val KEY_BAT_PWR = "bat_pwr"
        const val KEY_FRAME_PWR = "frame_pwr"
        const val KEY_MEM_MB = "mem_mb"
        const val KEY_MEM_PCT = "mem_pct"
        const val KEY_NET_UL = "net_ul"
        const val KEY_NET_DL = "net_dl"
        const val KEY_CORES = "cores"
        const val KEY_TIMESTAMP = "timestamp"

        fun fromBundle(bundle: Bundle?): PerformanceMetrics {
            if (bundle == null) return PerformanceMetrics()
            return PerformanceMetrics(
                fps = bundle.getFloat(KEY_FPS, 0.0f),
                layerName = bundle.getString(KEY_LAYER, ""),
                cpuUsage = bundle.getFloat(KEY_CPU_USAGE, 0.0f),
                cpuFrequencyGhz = bundle.getFloat(KEY_CPU_FREQ, 0.0f),
                cpuTemperature = bundle.getFloat(KEY_CPU_TEMP, 0.0f),
                gpuUsage = bundle.getFloat(KEY_GPU_USAGE, 0.0f),
                gpuTemperature = bundle.getFloat(KEY_GPU_TEMP, 0.0f),
                batteryTemperature = bundle.getFloat(KEY_BAT_TEMP, 0.0f),
                skinTemperature = bundle.getFloat(KEY_SKIN_TEMP, 0.0f),
                batteryCurrentAmp = bundle.getFloat(KEY_BAT_CUR, 0.0f),
                batteryVoltageVolts = bundle.getFloat(KEY_BAT_VOLT, 0.0f),
                batteryPowerWatts = bundle.getFloat(KEY_BAT_PWR, 0.0f),
                framePowerMilliJoules = bundle.getFloat(KEY_FRAME_PWR, 0.0f),
                memoryUsageMb = bundle.getInt(KEY_MEM_MB, 0),
                memoryUsagePercentage = bundle.getFloat(KEY_MEM_PCT, 0.0f),
                uploadSpeedBytesPerSec = bundle.getLong(KEY_NET_UL, 0L),
                downloadSpeedBytesPerSec = bundle.getLong(KEY_NET_DL, 0L),
                coreFrequencies = bundle.getIntArray(KEY_CORES) ?: IntArray(0),
                timestamp = bundle.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
            )
        }
    }
}
