package ndika.monitor.tracker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ndika.monitor.model.PerformanceMetrics
import java.io.File
import kotlin.math.abs

class BatteryTracker(private val context: Context) : ITracker {

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private var currentNowPath: String? = null
    private var voltageNowPath: String? = null
    private var tempPath: String? = null

    init {
        detectBatterySysfsPaths()
    }

    override fun update(metrics: PerformanceMetrics) {
        var tempC = 0f
        var voltageV = 0f
        var currentA = 0f

        // 1. Try sticky broadcast Intent first
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                tempC = tempRaw / 10.0f

                val voltRaw = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                voltageV = voltRaw / 1000.0f
            }
        } catch (_: Exception) {}

        // 2. BatteryManager Property for Current
        try {
            if (batteryManager != null) {
                val currentMicroAmp = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (currentMicroAmp != Int.MIN_VALUE && currentMicroAmp != 0) {
                    currentA = abs(currentMicroAmp) / 1_000_000.0f
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback to /sys/class/power_supply/battery
        if (currentA == 0f && currentNowPath != null) {
            try {
                val f = File(currentNowPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = abs(f.readText().trim().toLongOrNull() ?: 0L)
                    currentA = if (raw > 10_000) raw / 1_000_000.0f else raw / 1000.0f
                }
            } catch (_: Exception) {}
        }

        if (voltageV == 0f && voltageNowPath != null) {
            try {
                val f = File(voltageNowPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toLongOrNull() ?: 0L
                    voltageV = if (raw > 100_000) raw / 1_000_000.0f else raw / 1000.0f
                }
            } catch (_: Exception) {}
        }

        if (tempC == 0f && tempPath != null) {
            try {
                val f = File(tempPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toFloatOrNull() ?: 0f
                    tempC = if (raw > 100) raw / 10.0f else raw
                }
            } catch (_: Exception) {}
        }

        metrics.batteryTemperature = tempC
        metrics.batteryVoltageVolts = voltageV
        metrics.batteryCurrentAmp = currentA
        val powerWatts = voltageV * currentA
        metrics.batteryPowerWatts = powerWatts

        // Calculate power consumption per frame in milliJoules (mJ = W * 1000 / FPS)
        if (metrics.fps > 0f && powerWatts > 0f) {
            metrics.framePowerMilliJoules = (powerWatts * 1000.0f) / metrics.fps
        } else {
            metrics.framePowerMilliJoules = 0.0f
        }
    }

    private fun detectBatterySysfsPaths() {
        val base = "/sys/class/power_supply/battery"
        val curCandidates = arrayOf("$base/current_now", "$base/batt_current", "/sys/class/power_supply/bms/current_now")
        for (c in curCandidates) {
            if (File(c).exists()) {
                currentNowPath = c
                break
            }
        }

        val voltCandidates = arrayOf("$base/voltage_now", "$base/batt_vol", "/sys/class/power_supply/bms/voltage_now")
        for (v in voltCandidates) {
            if (File(v).exists()) {
                voltageNowPath = v
                break
            }
        }

        val tempCandidates = arrayOf("$base/temp", "$base/batt_temp")
        for (t in tempCandidates) {
            if (File(t).exists()) {
                tempPath = t
                break
            }
        }
    }
}
