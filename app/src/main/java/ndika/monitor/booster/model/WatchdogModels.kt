package ndika.monitor.booster.model

data class WatchdogStatus(
    val batteryTempCelsius: Float = 0.0f,
    val isThermalThrottling: Boolean = false, // True if >= 40.0°C
    val availableRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val isLowMemory: Boolean = false, // True if < 400 MB
    val timestamp: Long = System.currentTimeMillis()
) {
    val ramUsagePercent: Float
        get() = if (totalRamMb > 0) {
            ((totalRamMb - availableRamMb).toFloat() / totalRamMb.toFloat()) * 100.0f
        } else {
            0.0f
        }

    val isCritical: Boolean
        get() = isThermalThrottling || isLowMemory
}

sealed class WatchdogAlert {
    data class HighTemperature(val tempCelsius: Float) : WatchdogAlert()
    data class LowMemory(val availableMb: Long) : WatchdogAlert()
}
