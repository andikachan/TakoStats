package ndika.monitor.booster.presentation

import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.WatchdogStatus

data class GameBoosterUiState(
    val isShizukuReady: Boolean = false,
    val isBoosting: Boolean = false,
    val boostProgress: BoostProgress? = null,
    val watchdog: WatchdogStatus? = null,
    val installedGames: List<GameAppInfo> = emptyList(),
    val errorMessage: String? = null
)
