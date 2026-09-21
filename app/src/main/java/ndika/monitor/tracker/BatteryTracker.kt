package ndika.monitor.tracker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.util.ShellUtils
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
            metrics.batteryTemperature = readBatteryTempFromSysfs()
        } else {
            metrics.batteryTemperature = -10000.0f
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
            currentMicroAmp = readSysfsInt(currentNowPath!!)
        }

        // Fallback sysfs for voltage
        if (voltageMilliVolts <= 0 && voltageNowPath != null) {
            val rawVolt = readSysfsInt(voltageNowPath!!)
            if (rawVolt > 0) {
                voltageMilliVolts = if (rawVolt > 100_000) rawVolt / 1000 else rawVolt
            }
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

    private fun readSysfsInt(path: String): Int {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val num = f.readText().trim().toIntOrNull()
                if (num != null) return num
            }
        } catch (_: Exception) {}
        val out = ShellUtils.exec("cat $path 2>/dev/null")
        return out.trim().toIntOrNull() ?: Int.MIN_VALUE
    }

    private fun readBatteryTempFromSysfs(): Float {
        val path = tempPath ?: return -10000.0f
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                val raw = f.readText().trim().toFloatOrNull()
                if (raw != null) {
                    return if (raw > 100f) raw / 10.0f else raw
                }
            }
        } catch (_: Exception) {}
        val out = ShellUtils.exec("cat $path 2>/dev/null")
        val raw = out.trim().toFloatOrNull() ?: return -10000.0f
        return if (raw > 100f) raw / 10.0f else raw
    }

    private fun detectBatterySysfsPaths() {
        val curCandidates = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/batt_current",
            "/sys/class/power_supply/bms/current_now"
        )
        for (c in curCandidates) {
            if (File(c).exists()) {
                currentNowPath = c
                break
            }
        }

        val voltCandidates = arrayOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/batt_vol",
            "/sys/class/power_supply/bms/voltage_now"
        )
        for (v in voltCandidates) {
            if (File(v).exists()) {
                voltageNowPath = v
                break
            }
        }

        val tempCandidates = arrayOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/battery/batt_temp",
            "/sys/class/power_supply/bms/temp"
        )
        for (t in tempCandidates) {
            if (File(t).exists()) {
                tempPath = t
                break
            }
        }
    }
}
