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
        var isPlugged = false
        var voltageMilliVolts = 0
        var batteryTempTenths = -100000

        // 1. Read sticky battery intent
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                isPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
                    isPlugged = true
                }
                voltageMilliVolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                batteryTempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -100000)
            }
        } catch (_: Exception) {}

        // 2. Battery Temperature
        if (batteryTempTenths != -100000) {
            metrics.batteryTemperature = batteryTempTenths / 10.0f
        } else if (tempPath != null) {
            try {
                val f = File(tempPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toFloatOrNull() ?: -100000f
                    metrics.batteryTemperature = if (raw > 100f) raw / 10.0f else raw
                }
            } catch (_: Exception) {}
        }

        // 3. Read Current in microAmperes
        var currentMicroAmp = Int.MIN_VALUE
        try {
            if (batteryManager != null) {
                val cur = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (cur != Int.MIN_VALUE && cur != 0) {
                    currentMicroAmp = cur
                }
            }
        } catch (_: Exception) {}

        // Fallback sysfs for current
        if (currentMicroAmp == Int.MIN_VALUE && currentNowPath != null) {
            try {
                val f = File(currentNowPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toIntOrNull() ?: Int.MIN_VALUE
                    if (raw != Int.MIN_VALUE) {
                        currentMicroAmp = raw
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback sysfs for voltage
        if (voltageMilliVolts <= 0 && voltageNowPath != null) {
            try {
                val f = File(voltageNowPath!!)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toIntOrNull() ?: 0
                    voltageMilliVolts = if (raw > 100_000) raw / 1000 else raw
                }
            } catch (_: Exception) {}
        }

        // 4. Normalize Current & Check Charging State (Exact TakoStats logic)
        if (currentMicroAmp != Int.MIN_VALUE) {
            if (abs(currentMicroAmp) < 5000) {
                currentMicroAmp *= 1000
            }
            if (isPlugged && currentMicroAmp < 0) {
                currentMicroAmp = -currentMicroAmp
            } else if (!isPlugged && currentMicroAmp >= 0) {
                currentMicroAmp = -currentMicroAmp
            }
        }

        val isDischarging = !isPlugged && (currentMicroAmp < 0 || currentMicroAmp == Int.MIN_VALUE)
        metrics.isCharging = !isDischarging

        if (isDischarging && voltageMilliVolts > 0 && currentMicroAmp != Int.MIN_VALUE) {
            val currentA = abs(currentMicroAmp) / 1_000_000.0f
            val voltageV = voltageMilliVolts / 1000.0f
            val powerW = currentA * voltageV

            metrics.batteryCurrentAmp = currentA
            metrics.batteryVoltageVolts = voltageV
            metrics.batteryPowerWatts = powerW
            metrics.framePowerWatts = if (metrics.fps > 0f) powerW / metrics.fps else 0.0f
        } else {
            metrics.batteryCurrentAmp = Float.MIN_VALUE
            metrics.batteryVoltageVolts = Float.MIN_VALUE
            metrics.batteryPowerWatts = Float.MIN_VALUE
            metrics.framePowerWatts = Float.MIN_VALUE
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
