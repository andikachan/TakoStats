package ndika.monitor.booster.watchdog

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ndika.monitor.booster.model.WatchdogAlert
import ndika.monitor.booster.model.WatchdogStatus

class ThermalMemoryWatchdog(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val batteryIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    private val _status = MutableStateFlow(WatchdogStatus())
    val status: StateFlow<WatchdogStatus> = _status.asStateFlow()

    private val _alertEvents = MutableSharedFlow<WatchdogAlert>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<WatchdogAlert> = _alertEvents.asSharedFlow()

    private var watchdogJob: Job? = null
    private var lastAlertTempTime = 0L
    private var lastAlertRamTime = 0L

    companion object {
        const val THERMAL_THROTTLE_THRESHOLD_CELSIUS = 40.0f
        const val LOW_MEMORY_THRESHOLD_MB = 400L
        private const val ALERT_DEBOUNCE_MS = 15_000L // 15 seconds alert cooldown
    }

    fun start(intervalMs: Long = 2000L) {
        if (watchdogJob != null && watchdogJob?.isActive == true) return

        watchdogJob = coroutineScope.launch {
            while (isActive) {
                try {
                    val currentStatus = sampleCurrentStatus()
                    _status.value = currentStatus

                    checkAndEmitAlerts(currentStatus)
                } catch (_: Exception) {}

                delay(intervalMs)
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun isRunning(): Boolean {
        return watchdogJob?.isActive == true
    }

    private fun sampleCurrentStatus(): WatchdogStatus {
        // 1. Read battery temperature natively
        val batteryIntent = context.registerReceiver(null, batteryIntentFilter)
        val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 30.0f

        // 2. Read physical RAM status natively
        activityManager.getMemoryInfo(memoryInfo)
        val availMb = memoryInfo.availMem / (1024 * 1024)
        val totalMb = memoryInfo.totalMem / (1024 * 1024)

        val isThermalThrottling = (tempCelsius >= THERMAL_THROTTLE_THRESHOLD_CELSIUS)
        val isLowMemory = (availMb < LOW_MEMORY_THRESHOLD_MB)

        return WatchdogStatus(
            batteryTempCelsius = tempCelsius,
            isThermalThrottling = isThermalThrottling,
            availableRamMb = availMb,
            totalRamMb = totalMb,
            isLowMemory = isLowMemory,
            timestamp = System.currentTimeMillis()
        )
    }

    private suspend fun checkAndEmitAlerts(status: WatchdogStatus) {
        val now = System.currentTimeMillis()

        // Thermal alert debounce
        if (status.isThermalThrottling && (now - lastAlertTempTime > ALERT_DEBOUNCE_MS)) {
            _alertEvents.emit(WatchdogAlert.HighTemperature(status.batteryTempCelsius))
            lastAlertTempTime = now
        }

        // Low memory alert debounce
        if (status.isLowMemory && (now - lastAlertRamTime > ALERT_DEBOUNCE_MS)) {
            _alertEvents.emit(WatchdogAlert.LowMemory(status.availableRamMb))
            lastAlertRamTime = now
        }
    }
}
