package ndika.monitor.data

import org.json.JSONObject
import java.io.Serializable

data class TelemetrySample(
    val timestampMs: Long,
    val fps: Float,
    val cpuUsage: Float,
    val cpuFreqGhz: Float,
    val cpuTemp: Float,
    val gpuUsage: Float,
    val gpuTemp: Float,
    val batTemp: Float,
    val skinTemp: Float,
    val voltageV: Float,
    val currentA: Float,
    val powerW: Float,
    val framePowerMj: Float,
    val memMb: Int
) : Serializable {

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("t", timestampMs)
            put("fps", fps)
            put("cpu", cpuUsage)
            put("frq", cpuFreqGhz)
            put("cput", cpuTemp)
            put("gpu", gpuUsage)
            put("gput", gpuTemp)
            put("batt", batTemp)
            put("skint", skinTemp)
            put("v", voltageV)
            put("a", currentA)
            put("w", powerW)
            put("fpw", framePowerMj)
            put("mem", memMb)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TelemetrySample {
            return TelemetrySample(
                timestampMs = json.optLong("t", 0L),
                fps = json.optDouble("fps", 0.0).toFloat(),
                cpuUsage = json.optDouble("cpu", 0.0).toFloat(),
                cpuFreqGhz = json.optDouble("frq", 0.0).toFloat(),
                cpuTemp = json.optDouble("cput", 0.0).toFloat(),
                gpuUsage = json.optDouble("gpu", 0.0).toFloat(),
                gpuTemp = json.optDouble("gput", 0.0).toFloat(),
                batTemp = json.optDouble("batt", 0.0).toFloat(),
                skinTemp = json.optDouble("skint", 0.0).toFloat(),
                voltageV = json.optDouble("v", 0.0).toFloat(),
                currentA = json.optDouble("a", 0.0).toFloat(),
                powerW = json.optDouble("w", 0.0).toFloat(),
                framePowerMj = json.optDouble("fpw", 0.0).toFloat(),
                memMb = json.optInt("mem", 0)
            )
        }
    }
}
