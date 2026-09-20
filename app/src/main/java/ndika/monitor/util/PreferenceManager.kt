package ndika.monitor.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import androidx.preference.PreferenceManager as AndroidXPrefManager
import ndika.monitor.model.OverlayConfig

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = AndroidXPrefManager.getDefaultSharedPreferences(context)

    fun loadOverlayConfig(): OverlayConfig {
        return OverlayConfig(
            showFps = prefs.getBoolean("show_fps", true),
            showLayerName = prefs.getBoolean("show_layer_name", false),
            showCpuUsage = prefs.getBoolean("show_cpu_usage", true),
            showCpuFrequency = prefs.getBoolean("show_cpu_freq", true),
            showCpuTemperature = prefs.getBoolean("show_cpu_temp", false),
            showGpuUsage = prefs.getBoolean("show_gpu_usage", true),
            showGpuTemperature = prefs.getBoolean("show_gpu_temp", false),
            showBatteryTemperature = prefs.getBoolean("show_bat_temp", true),
            showBatteryCurrent = prefs.getBoolean("show_bat_cur", false),
            showBatteryVoltage = prefs.getBoolean("show_bat_volt", false),
            showBatteryPower = prefs.getBoolean("show_bat_pwr", false),
            showMemoryMb = prefs.getBoolean("show_mem_mb", false),
            showMemoryPercentage = prefs.getBoolean("show_mem_pct", false),
            showUploadSpeed = prefs.getBoolean("show_ul", false),
            showDownloadSpeed = prefs.getBoolean("show_dl", false),
            showPerCoreCpu = prefs.getBoolean("show_cores", false),

            textSizeSp = prefs.getInt("text_size", 12),
            textColor = prefs.getInt("text_color", Color.WHITE),
            showBackground = prefs.getBoolean("show_bg", true),
            backgroundColor = prefs.getInt("bg_color", Color.argb(160, 0, 0, 0)),

            gravity = parseGravity(prefs.getString("overlay_position", "top_right")),
            offsetX = prefs.getInt("offset_x", 16),
            offsetY = prefs.getInt("offset_y", 16),
            updateIntervalMs = prefs.getString("update_interval", "1000")?.toLongOrNull() ?: 1000L,
            isDraggable = prefs.getBoolean("is_draggable", true)
        )
    }

    fun saveOverlayConfig(config: OverlayConfig) {
        prefs.edit().apply {
            putBoolean("show_fps", config.showFps)
            putBoolean("show_layer_name", config.showLayerName)
            putBoolean("show_cpu_usage", config.showCpuUsage)
            putBoolean("show_cpu_freq", config.showCpuFrequency)
            putBoolean("show_cpu_temp", config.showCpuTemperature)
            putBoolean("show_gpu_usage", config.showGpuUsage)
            putBoolean("show_gpu_temp", config.showGpuTemperature)
            putBoolean("show_bat_temp", config.showBatteryTemperature)
            putBoolean("show_bat_cur", config.showBatteryCurrent)
            putBoolean("show_bat_volt", config.showBatteryVoltage)
            putBoolean("show_bat_pwr", config.showBatteryPower)
            putBoolean("show_mem_mb", config.showMemoryMb)
            putBoolean("show_mem_pct", config.showMemoryPercentage)
            putBoolean("show_ul", config.showUploadSpeed)
            putBoolean("show_dl", config.showDownloadSpeed)
            putBoolean("show_cores", config.showPerCoreCpu)

            putInt("text_size", config.textSizeSp)
            putInt("text_color", config.textColor)
            putBoolean("show_bg", config.showBackground)
            putInt("bg_color", config.backgroundColor)

            putInt("offset_x", config.offsetX)
            putInt("offset_y", config.offsetY)
            putBoolean("is_draggable", config.isDraggable)
            apply()
        }
    }

    private fun parseGravity(pos: String?): Int {
        return when (pos) {
            "top_left" -> Gravity.TOP or Gravity.START
            "top_center" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "top_right" -> Gravity.TOP or Gravity.END
            "center_left" -> Gravity.CENTER_VERTICAL or Gravity.START
            "center" -> Gravity.CENTER
            "center_right" -> Gravity.CENTER_VERTICAL or Gravity.END
            "bottom_left" -> Gravity.BOTTOM or Gravity.START
            "bottom_center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            "bottom_right" -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.END
        }
    }

    var isServiceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(value) = prefs.edit().putBoolean("service_running", value).apply()
}
