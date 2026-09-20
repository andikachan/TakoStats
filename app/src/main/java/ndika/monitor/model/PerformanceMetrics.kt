package ndika.monitor.model

import android.os.Bundle
import java.io.Serializable
import java.util.Locale

data class PerformanceMetrics(
    var fps: Float = 0.0f,
    var layerName: String = "",
    var cpuUsage: Float = 0.0f,
    var cpuFrequencyGhz: Float = 0.0f,
    var coreFrequencies: IntArray = IntArray(0),
    var cpuTemperature: Float = -10000.0f,
    var gpuUsage: Float = 0.0f,
    var gpuTemperature: Float = -10000.0f,
    var batteryTemperature: Float = -10000.0f,
    var skinTemperature: Float = -10000.0f,
    var isCharging: Boolean = false,
    var batteryCurrentAmp: Float = Float.MIN_VALUE,
    var batteryVoltageVolts: Float = Float.MIN_VALUE,
    var batteryPowerWatts: Float = Float.MIN_VALUE,
    var framePowerWatts: Float = Float.MIN_VALUE,
    var memoryUsageMb: Int = 0,
    var memoryUsagePercentage: Float = 0.0f,
    var uploadSpeedBytesPerSec: Long = 0L,
    var downloadSpeedBytesPerSec: Long = 0L,
    var timestamp: Long = System.currentTimeMillis()
) : Serializable {

    fun formatTemperature(celsius: Float, isCelsius: Boolean): String {
        return if (isCelsius) {
            String.format(Locale.ROOT, "%.1f", celsius)
        } else {
            val fahrenheit = ((celsius * 9.0f) / 5.0f) + 32.0f
            String.format(Locale.ROOT, "%.1f", fahrenheit)
        }
    }

    fun formatTemperature(celsius: Float, unit: String): String {
        val isCelsius = !unit.equals("fahrenheit", ignoreCase = true)
        return formatTemperature(celsius, isCelsius)
    }

    fun toBundle(): Bundle {
        return Bundle().apply {
            putFloat(KEY_FPS, fps)
            putString(KEY_LAYER, layerName)
            putFloat(KEY_CPU_USAGE, cpuUsage)
            putFloat(KEY_CPU_FREQ, cpuFrequencyGhz)
            putIntArray(KEY_CORES, coreFrequencies)
            putFloat(KEY_CPU_TEMP, cpuTemperature)
            putFloat(KEY_GPU_USAGE, gpuUsage)
            putFloat(KEY_GPU_TEMP, gpuTemperature)
            putFloat(KEY_BAT_TEMP, batteryTemperature)
            putFloat(KEY_SKIN_TEMP, skinTemperature)
            putBoolean(KEY_IS_CHARGING, isCharging)
            putFloat(KEY_BAT_CUR, batteryCurrentAmp)
            putFloat(KEY_BAT_VOLT, batteryVoltageVolts)
            putFloat(KEY_BAT_PWR, batteryPowerWatts)
            putFloat(KEY_FRAME_PWR, framePowerWatts)
            putInt(KEY_MEM_MB, memoryUsageMb)
            putFloat(KEY_MEM_PCT, memoryUsagePercentage)
            putLong(KEY_NET_UL, uploadSpeedBytesPerSec)
            putLong(KEY_NET_DL, downloadSpeedBytesPerSec)
            putLong(KEY_TIMESTAMP, timestamp)
        }
    }

    companion object {
        const val KEY_FPS = "fps"
        const val KEY_LAYER = "layer"
        const val KEY_CPU_USAGE = "cpu_usage"
        const val KEY_CPU_FREQ = "cpu_freq"
        const val KEY_CORES = "cores"
        const val KEY_CPU_TEMP = "cpu_temp"
        const val KEY_GPU_USAGE = "gpu_usage"
        const val KEY_GPU_TEMP = "gpu_temp"
        const val KEY_BAT_TEMP = "bat_temp"
        const val KEY_SKIN_TEMP = "skin_temp"
        const val KEY_IS_CHARGING = "is_charging"
        const val KEY_BAT_CUR = "bat_cur"
        const val KEY_BAT_VOLT = "bat_volt"
        const val KEY_BAT_PWR = "bat_pwr"
        const val KEY_FRAME_PWR = "frame_pwr"
        const val KEY_MEM_MB = "mem_mb"
        const val KEY_MEM_PCT = "mem_pct"
        const val KEY_NET_UL = "net_ul"
        const val KEY_NET_DL = "net_dl"
        const val KEY_TIMESTAMP = "timestamp"

        fun fromBundle(bundle: Bundle?): PerformanceMetrics {
            if (bundle == null) return PerformanceMetrics()
            return PerformanceMetrics(
                fps = bundle.getFloat(KEY_FPS, 0.0f),
                layerName = bundle.getString(KEY_LAYER, ""),
                cpuUsage = bundle.getFloat(KEY_CPU_USAGE, 0.0f),
                cpuFrequencyGhz = bundle.getFloat(KEY_CPU_FREQ, 0.0f),
                coreFrequencies = bundle.getIntArray(KEY_CORES) ?: IntArray(0),
                cpuTemperature = bundle.getFloat(KEY_CPU_TEMP, -10000.0f),
                gpuUsage = bundle.getFloat(KEY_GPU_USAGE, 0.0f),
                gpuTemperature = bundle.getFloat(KEY_GPU_TEMP, -10000.0f),
                batteryTemperature = bundle.getFloat(KEY_BAT_TEMP, -10000.0f),
                skinTemperature = bundle.getFloat(KEY_SKIN_TEMP, -10000.0f),
                isCharging = bundle.getBoolean(KEY_IS_CHARGING, false),
                batteryCurrentAmp = bundle.getFloat(KEY_BAT_CUR, Float.MIN_VALUE),
                batteryVoltageVolts = bundle.getFloat(KEY_BAT_VOLT, Float.MIN_VALUE),
                batteryPowerWatts = bundle.getFloat(KEY_BAT_PWR, Float.MIN_VALUE),
                framePowerWatts = bundle.getFloat(KEY_FRAME_PWR, Float.MIN_VALUE),
                memoryUsageMb = bundle.getInt(KEY_MEM_MB, 0),
                memoryUsagePercentage = bundle.getFloat(KEY_MEM_PCT, 0.0f),
                uploadSpeedBytesPerSec = bundle.getLong(KEY_NET_UL, 0L),
                downloadSpeedBytesPerSec = bundle.getLong(KEY_NET_DL, 0L),
                timestamp = bundle.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
            )
        }
    }
}
