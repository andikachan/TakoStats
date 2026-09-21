package ndika.monitor.booster.watchdog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import ndika.monitor.booster.model.WatchdogStatus
import kotlin.coroutines.coroutineContext

class ThermalMemoryWatchdog(
    private val context: Context
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    companion object {
        const val OVERHEAT_THRESHOLD_CELSIUS = 40.0f
        const val LOW_RAM_THRESHOLD_MB = 400L
    }

    fun observeStatus(intervalMs: Long = 3000L): Flow<WatchdogStatus> = flow {
        while (coroutineContext.isActive) {
            val status = sampleHardwareStatus()
            emit(status)
            delay(intervalMs)
        }
    }

    fun sampleHardwareStatus(): WatchdogStatus {
        // 1. Battery temperature reading (tenths of a degree Celsius)
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 32.0f

        // 2. Physical RAM status reading
        activityManager.getMemoryInfo(memoryInfo)
        val availMb = memoryInfo.availMem / (1024 * 1024)
        val totalMb = memoryInfo.totalMem / (1024 * 1024)
        val usedMb = (totalMb - availMb).coerceAtLeast(0)
        val usedPercent = if (totalMb > 0) ((usedMb.toDouble() / totalMb) * 100).toInt() else 0

        val isOverheating = tempCelsius >= OVERHEAT_THRESHOLD_CELSIUS
        val isLowRam = availMb < LOW_RAM_THRESHOLD_MB

        return WatchdogStatus(
            batteryTempC = tempCelsius,
            isOverheating = isOverheating,
            availableRamMb = availMb,
            totalRamMb = totalMb,
            usedRamPercent = usedPercent,
            isLowRam = isLowRam,
            timestamp = System.currentTimeMillis()
        )
    }
}
