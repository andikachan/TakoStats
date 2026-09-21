package ndika.monitor.booster.presentation

import ndika.monitor.booster.model.BoostProgressState
import ndika.monitor.booster.model.WatchdogStatus

data class GameBoosterUiState(
    val isShizukuAvailable: Boolean = false,
    val isShizukuPermissionGranted: Boolean = false,
    val isBoostActive: Boolean = false,
    val boostProgressState: BoostProgressState = BoostProgressState.Idle,
    val watchdogStatus: WatchdogStatus = WatchdogStatus(),
    val isWatchdogActive: Boolean = false,
    val customWhitelist: Set<String> = emptySet(),
    val statusMessage: String = ""
)
