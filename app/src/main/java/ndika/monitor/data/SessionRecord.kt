package ndika.monitor.data

import org.json.JSONArray
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionRecord(
    var id: Long = 0L,
    var appName: String = "",
    var packageName: String = "",
    var startTimeMs: Long = 0L,
    var endTimeMs: Long = 0L,
    var durationMs: Long = 0L,
    var avgFps: Float = 0.0f,
    var p95Fps: Float = 0.0f,
    var p99Fps: Float = 0.0f,
    var maxFrameTimeMs: Float = 0.0f,
    var avgCpuUsage: Float = 0.0f,
    var avgCpuTemp: Float = 0.0f,
    var avgGpuUsage: Float = 0.0f,
    var avgPowerWatts: Float = 0.0f,
    var avgFramePowerMj: Float = 0.0f,
    var totalFrames: Int = 0,
    var frameTimesCsv: String = "", // Comma-separated frame delta times in milliseconds
    var telemetryJson: String = ""   // JSON array of TelemetrySample objects
) : Serializable {

    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(startTimeMs))
    }

    fun getFormattedDuration(): String {
        val totalSec = durationMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%dm %ds", min, sec)
    }

    fun parseFrameTimes(): FloatArray {
        if (frameTimesCsv.isBlank()) return FloatArray(0)
        return try {
            val parts = frameTimesCsv.split(",")
            val result = FloatArray(parts.size)
            for (i in parts.indices) {
                result[i] = parts[i].trim().toFloatOrNull() ?: 16.6f
            }
            result
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    fun parseTelemetrySamples(): List<TelemetrySample> {
        if (telemetryJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(telemetryJson)
            val list = ArrayList<TelemetrySample>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(TelemetrySample.fromJson(obj))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
