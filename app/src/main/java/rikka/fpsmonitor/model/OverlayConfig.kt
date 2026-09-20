package rikka.fpsmonitor.model

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import java.io.Serializable

data class OverlayConfig(
    var showFps: Boolean = true,
    var showLayerName: Boolean = false,
    var showCpuUsage: Boolean = true,
    var showCpuFrequency: Boolean = true,
    var showCpuTemperature: Boolean = false,
    var showGpuUsage: Boolean = true,
    var showGpuTemperature: Boolean = false,
    var showBatteryTemperature: Boolean = true,
    var showBatteryCurrent: Boolean = false,
    var showBatteryVoltage: Boolean = false,
    var showBatteryPower: Boolean = false,
    var showMemoryMb: Boolean = false,
    var showMemoryPercentage: Boolean = false,
    var showUploadSpeed: Boolean = false,
    var showDownloadSpeed: Boolean = false,
    var showPerCoreCpu: Boolean = false,

    var textSizeSp: Int = 12,
    var textColor: Int = Color.WHITE,
    var showBackground: Boolean = true,
    var backgroundColor: Int = Color.argb(160, 0, 0, 0), // 0xA0000000

    var gravity: Int = Gravity.TOP or Gravity.END, // 0x35 (Top-Right)
    var offsetX: Int = 16,
    var offsetY: Int = 16,
    var paddingDp: Int = 8,
    var cornerRadiusDp: Float = 12f,
    var updateIntervalMs: Long = 1000L,
    var isDraggable: Boolean = true
) : Serializable {

    fun toBundle(): Bundle {
        return Bundle().apply {
            putBoolean("show_fps", showFps)
            putBoolean("show_layer_name", showLayerName)
            putBoolean("show_cpu_usage", showCpuUsage)
            putBoolean("show_cpu_freq", showCpuFrequency)
            putBoolean("show_cpu_temp", showCpuTemperature)
            putBoolean("show_gpu_usage", showGpuUsage)
            putBoolean("show_gpu_temp", showGpuTemperature)
            putBoolean("show_bat_temp", showBatteryTemperature)
            putBoolean("show_bat_cur", showBatteryCurrent)
            putBoolean("show_bat_volt", showBatteryVoltage)
            putBoolean("show_bat_pwr", showBatteryPower)
            putBoolean("show_mem_mb", showMemoryMb)
            putBoolean("show_mem_pct", showMemoryPercentage)
            putBoolean("show_ul", showUploadSpeed)
            putBoolean("show_dl", showDownloadSpeed)
            putBoolean("show_cores", showPerCoreCpu)

            putInt("text_size", textSizeSp)
            putInt("text_color", textColor)
            putBoolean("show_bg", showBackground)
            putInt("bg_color", backgroundColor)

            putInt("gravity", gravity)
            putInt("offset_x", offsetX)
            putInt("offset_y", offsetY)
            putLong("interval", updateIntervalMs)
            putBoolean("draggable", isDraggable)
        }
    }

    companion object {
        fun fromBundle(bundle: Bundle?): OverlayConfig {
            if (bundle == null) return OverlayConfig()
            return OverlayConfig(
                showFps = bundle.getBoolean("show_fps", true),
                showLayerName = bundle.getBoolean("show_layer_name", false),
                showCpuUsage = bundle.getBoolean("show_cpu_usage", true),
                showCpuFrequency = bundle.getBoolean("show_cpu_freq", true),
                showCpuTemperature = bundle.getBoolean("show_cpu_temp", false),
                showGpuUsage = bundle.getBoolean("show_gpu_usage", true),
                showGpuTemperature = bundle.getBoolean("show_gpu_temp", false),
                showBatteryTemperature = bundle.getBoolean("show_bat_temp", true),
                showBatteryCurrent = bundle.getBoolean("show_bat_cur", false),
                showBatteryVoltage = bundle.getBoolean("show_bat_volt", false),
                showBatteryPower = bundle.getBoolean("show_bat_pwr", false),
                showMemoryMb = bundle.getBoolean("show_mem_mb", false),
                showMemoryPercentage = bundle.getBoolean("show_mem_pct", false),
                showUploadSpeed = bundle.getBoolean("show_ul", false),
                showDownloadSpeed = bundle.getBoolean("show_dl", false),
                showPerCoreCpu = bundle.getBoolean("show_cores", false),

                textSizeSp = bundle.getInt("text_size", 12),
                textColor = bundle.getInt("text_color", Color.WHITE),
                showBackground = bundle.getBoolean("show_bg", true),
                backgroundColor = bundle.getInt("bg_color", Color.argb(160, 0, 0, 0)),

                gravity = bundle.getInt("gravity", Gravity.TOP or Gravity.END),
                offsetX = bundle.getInt("offset_x", 16),
                offsetY = bundle.getInt("offset_y", 16),
                updateIntervalMs = bundle.getLong("interval", 1000L),
                isDraggable = bundle.getBoolean("draggable", true)
            )
        }
    }
}
