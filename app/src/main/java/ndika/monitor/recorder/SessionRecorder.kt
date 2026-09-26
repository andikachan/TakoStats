package ndika.monitor.recorder

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ndika.monitor.data.BenchmarkDatabaseHelper
import ndika.monitor.data.SessionRecord
import ndika.monitor.data.TelemetrySample
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ExportHelper
import org.json.JSONArray
import java.util.Collections
import kotlin.math.max

object SessionRecorder {

    @Volatile
    private var isRecordingActive = false
    private var recordingStartTimeMs = 0L
    private var currentAppName = "Game Session"
    private var currentPackageName = ""

    private val frameTimesList = Collections.synchronizedList(ArrayList<Float>(50000))
    private val telemetrySamplesList = Collections.synchronizedList(ArrayList<TelemetrySample>(3600))

    private val _recordingState = MutableStateFlow(false)
    val recordingState: StateFlow<Boolean> = _recordingState

    private val _recordedFramesCount = MutableStateFlow(0)
    val recordedFramesCount: StateFlow<Int> = _recordedFramesCount

    fun isRecording(): Boolean = isRecordingActive

    fun startRecording(appName: String = "Game Session", packageName: String = "") {
        synchronized(this) {
            frameTimesList.clear()
            telemetrySamplesList.clear()
            currentAppName = appName
            currentPackageName = packageName
            recordingStartTimeMs = System.currentTimeMillis()
            isRecordingActive = true
            _recordingState.value = true
            _recordedFramesCount.value = 0
        }
    }

    fun recordFrameTime(deltaMs: Float) {
        if (!isRecordingActive) return
        if (deltaMs > 0f && deltaMs < 1000f) {
            frameTimesList.add(deltaMs)
            if (frameTimesList.size % 60 == 0) {
                _recordedFramesCount.value = frameTimesList.size
            }
        }
    }

    fun recordTelemetrySnapshot(metrics: PerformanceMetrics) {
        if (!isRecordingActive) return
        val sample = TelemetrySample(
            timestampMs = System.currentTimeMillis(),
            fps = metrics.fps,
            cpuUsage = metrics.cpuUsage,
            cpuFreqGhz = metrics.cpuFrequencyGhz,
            cpuTemp = metrics.cpuTemperature,
            gpuUsage = metrics.gpuUsage,
            gpuTemp = metrics.gpuTemperature,
            batTemp = metrics.batteryTemperature,
            skinTemp = metrics.skinTemperature,
            voltageV = metrics.batteryVoltageVolts,
            currentA = metrics.batteryCurrentAmp,
            powerW = metrics.batteryPowerWatts,
            framePowerMj = if (metrics.framePowerWatts != Float.MIN_VALUE) metrics.framePowerWatts else 0.0f,
            memMb = metrics.memoryUsageMb
        )
        telemetrySamplesList.add(sample)
    }

    fun stopRecording(context: Context): SessionRecord? {
        synchronized(this) {
            if (!isRecordingActive) return null
            isRecordingActive = false
            _recordingState.value = false

            val endTimeMs = System.currentTimeMillis()
            val durationMs = max(1000L, endTimeMs - recordingStartTimeMs)
            val framesCopy: List<Float>
            val telemetryCopy: List<TelemetrySample>

            synchronized(frameTimesList) {
                framesCopy = ArrayList(frameTimesList)
            }
            synchronized(telemetrySamplesList) {
                telemetryCopy = ArrayList(telemetrySamplesList)
            }

            val totalFrames = framesCopy.size
            val avgFps = if (durationMs > 0) (totalFrames.toFloat() * 1000f / durationMs.toFloat()) else 0f

            // Calculate percentiles
            var p95Fps = 0f
            var p99Fps = 0f
            var maxFrameTime = 0f

            if (framesCopy.isNotEmpty()) {
                val sorted = framesCopy.sorted()
                val idx95 = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
                val idx99 = (sorted.size * 0.99).toInt().coerceIn(0, sorted.size - 1)
                val ft95 = sorted[idx95]
                val ft99 = sorted[idx99]
                p95Fps = if (ft95 > 0f) 1000f / ft95 else 0f
                p99Fps = if (ft99 > 0f) 1000f / ft99 else 0f
                maxFrameTime = sorted.last()
            }

            // Averages from telemetry
            var sumCpu = 0f
            var sumCpuTemp = 0f
            var sumGpu = 0f
            var sumPower = 0f
            var sumFramePower = 0f

            if (telemetryCopy.isNotEmpty()) {
                for (s in telemetryCopy) {
                    sumCpu += s.cpuUsage
                    sumCpuTemp += s.cpuTemp
                    sumGpu += s.gpuUsage
                    sumPower += s.powerW
                    sumFramePower += s.framePowerMj
                }
            }

            val count = max(1, telemetryCopy.size)
            val avgCpu = sumCpu / count
            val avgCpuTemp = sumCpuTemp / count
            val avgGpu = sumGpu / count
            val avgPower = sumPower / count
            val avgFramePower = if (avgFps > 0f) (avgPower * 1000f / avgFps) else (sumFramePower / count)

            // Serialize CSV
            val frameTimesCsv = framesCopy.joinToString(",") { String.format("%.2f", it) }

            // Serialize Telemetry JSON
            val jsonArr = JSONArray()
            for (s in telemetryCopy) {
                jsonArr.put(s.toJson())
            }

            val record = SessionRecord(
                appName = currentAppName,
                packageName = currentPackageName,
                startTimeMs = recordingStartTimeMs,
                endTimeMs = endTimeMs,
                durationMs = durationMs,
                avgFps = avgFps,
                p95Fps = p95Fps,
                p99Fps = p99Fps,
                maxFrameTimeMs = maxFrameTime,
                avgCpuUsage = avgCpu,
                avgCpuTemp = avgCpuTemp,
                avgGpuUsage = avgGpu,
                avgPowerWatts = avgPower,
                avgFramePowerMj = avgFramePower,
                totalFrames = totalFrames,
                frameTimesCsv = frameTimesCsv,
                telemetryJson = jsonArr.toString()
            )

            // Save to database
            val db = BenchmarkDatabaseHelper.getInstance(context)
            val id = db.insertRecord(record)
            record.id = id

            // Auto-export to external storage (SD Card / Flash Drive / Custom folder)
            if (ndika.monitor.storage.StorageManager.isAutoExportToExternal(context)) {
                try {
                    ExportHelper.exportRecordToZip(context, record)
                } catch (_: Exception) {}
            }

            frameTimesList.clear()
            telemetrySamplesList.clear()
            return record
        }
    }
}
