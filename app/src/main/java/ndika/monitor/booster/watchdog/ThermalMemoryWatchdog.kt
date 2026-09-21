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
import ndika.monitor.booster.model.WatchdogAlertEvent
import ndika.monitor.booster.model.WatchdogModels

class ThermalMemoryWatchdog(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    private val _watchdogStatus = MutableStateFlow(WatchdogModels())
    val watchdogStatus: StateFlow<WatchdogModels> = _watchdogStatus.asStateFlow()

    private val _alertEvents = MutableSharedFlow<WatchdogAlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<WatchdogAlertEvent> = _alertEvents.asSharedFlow()

    private var pollingJob: Job? = null
    private var lastOverheatAlertTime = 0L
    private var lastLowRamAlertTime = 0L

    companion object {
        const val OVERHEAT_THRESHOLD_CELSIUS = 40.0f
        const val LOW_RAM_THRESHOLD_MB = 400L
        private const val ALERT_COOLDOWN_MS = 15000L
    }

    fun start(intervalMs: Long = 3000L) {
        if (pollingJob?.isActive == true) return

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val status = sampleHardwareMetrics()
                    _watchdogStatus.value = status
                    evaluateAlerts(status)
                } catch (_: Exception) {}

                delay(intervalMs)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun isRunning(): Boolean = pollingJob?.isActive == true

    private fun sampleHardwareMetrics(): WatchdogModels {
        // 1. Native Battery Temperature Reading
        val batteryIntent = context.registerReceiver(null, batteryFilter)
        val rawTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 32.0f

        // 2. Native Physical RAM Reading
        activityManager.getMemoryInfo(memoryInfo)
        val freeMb = memoryInfo.availMem / (1024 * 1024)
        val totalMb = memoryInfo.totalMem / (1024 * 1024)

        return WatchdogModels(
            batteryTemp = tempCelsius,
            isOverheating = (tempCelsius >= OVERHEAT_THRESHOLD_CELSIUS),
            availableRamMb = freeMb,
            totalRamMb = totalMb,
            isLowRam = (freeMb < LOW_RAM_THRESHOLD_MB),
            timestamp = System.currentTimeMillis()
        )
    }

    private suspend fun evaluateAlerts(status: WatchdogModels) {
        val now = System.currentTimeMillis()

        if (status.isOverheating && (now - lastOverheatAlertTime > ALERT_COOLDOWN_MS)) {
            _alertEvents.emit(WatchdogAlertEvent.OverheatingAlert(status.batteryTemp))
            lastOverheatAlertTime = now
        }

        if (status.isLowRam && (now - lastLowRamAlertTime > ALERT_COOLDOWN_MS)) {
            _alertEvents.emit(WatchdogAlertEvent.LowRamAlert(status.availableRamMb))
            lastLowRamAlertTime = now
        }
    }
}
