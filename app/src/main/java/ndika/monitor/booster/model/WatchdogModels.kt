package ndika.monitor.booster.model

/**
 * Data class representing the real-time hardware status of battery temperature and physical RAM.
 *
 * @param batteryTemp Battery temperature in degrees Celsius (°C).
 * @param isOverheating True if battery temperature >= 40.0°C (thermal throttling threshold).
 * @param availableRamMb Available/free physical RAM in Megabytes.
 * @param totalRamMb Total physical RAM in Megabytes.
 * @param isLowRam True if available RAM < 400 MB (critical memory threshold).
 */
data class WatchdogModels(
    val batteryTemp: Float = 0.0f,
    val isOverheating: Boolean = false,
    val availableRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val isLowRam: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val ramUsagePercent: Float
        get() = if (totalRamMb > 0) {
            (((totalRamMb - availableRamMb).toFloat() / totalRamMb.toFloat()) * 100.0f).coerceIn(0.0f, 100.0f)
        } else {
            0.0f
        }

    val isCriticalState: Boolean
        get() = isOverheating || isLowRam
}

sealed class WatchdogAlertEvent {
    data class OverheatingAlert(val tempCelsius: Float) : WatchdogAlertEvent()
    data class LowRamAlert(val freeRamMb: Long) : WatchdogAlertEvent()
}
