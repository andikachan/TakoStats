package ndika.monitor.booster.presentation

import ndika.monitor.booster.model.BoostMode
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.GameBoostConfig
import ndika.monitor.booster.model.WatchdogModels

/**
 * Immutable UI State representing the Game Booster dashboard screen.
 */
data class GameBoosterUiState(
    val isBoosting: Boolean = false,
    val currentStep: String = "",
    val progress: Int = 0,
    val watchdogData: WatchdogModels = WatchdogModels(),
    val errorMessage: String? = null,
    val isShizukuReady: Boolean = false,
    val isPerformanceModeActive: Boolean = false,
    val boostProgress: BoostProgress = BoostProgress.Idle,
    val statusMessage: String? = null,
    val installedGames: List<GameAppInfo> = emptyList(),
    val selectedGame: GameAppInfo? = null,
    val config: GameBoostConfig = GameBoostConfig(mode = BoostMode.EXTREME_BEAST)
)
