package ndika.monitor.booster.model

data class WatchdogStatus(
    val batteryTempC: Float = 0.0f,
    val isOverheating: Boolean = false,
    val availableRamMb: Long = 0L,
    val totalRamMb: Long = 0L,
    val usedRamPercent: Int = 0,
    val isLowRam: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
