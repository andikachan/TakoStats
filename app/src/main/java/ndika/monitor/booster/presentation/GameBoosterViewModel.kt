package ndika.monitor.booster.presentation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ndika.monitor.booster.domain.GameBoosterRepositoryImpl
import ndika.monitor.booster.domain.IGameBoosterRepository
import ndika.monitor.booster.model.BoostMode
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.GameBoostConfig
import ndika.monitor.booster.model.WatchdogAlertEvent
import ndika.monitor.booster.watchdog.ThermalMemoryWatchdog

class GameBoosterViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: IGameBoosterRepository = GameBoosterRepositoryImpl(application)
    private val watchdog: ThermalMemoryWatchdog = ThermalMemoryWatchdog(application)

    private val _uiState = MutableStateFlow(GameBoosterUiState())
    val uiState: StateFlow<GameBoosterUiState> = _uiState.asStateFlow()

    val alertEvents: SharedFlow<WatchdogAlertEvent> = watchdog.alertEvents

    init {
        refreshStatus()
        loadInstalledGames()
        observeWatchdogMetrics()
        watchdog.start(3000L)
    }

    fun refreshStatus() {
        val ready = repository.checkPrerequisites()
        _uiState.update { it.copy(isShizukuReady = ready) }
    }

    fun loadInstalledGames() {
        viewModelScope.launch {
            val games = repository.getInstalledGames()
            _uiState.update { state ->
                val selected = state.selectedGame ?: games.firstOrNull { it.isGameCategory } ?: games.firstOrNull()
                state.copy(
                    installedGames = games,
                    selectedGame = selected,
                    config = state.config.copy(
                        targetGamePackage = selected?.packageName,
                        targetGameName = selected?.appName
                    )
                )
            }
        }
    }

    fun selectGame(game: GameAppInfo) {
        _uiState.update { state ->
            state.copy(
                selectedGame = game,
                config = state.config.copy(
                    targetGamePackage = game.packageName,
                    targetGameName = game.appName
                )
            )
        }
    }

    fun setBoostMode(mode: BoostMode) {
        _uiState.update { state ->
            state.copy(
                config = state.config.copy(mode = mode)
            )
        }
    }

    fun updateConfig(modifier: (GameBoostConfig) -> GameBoostConfig) {
        _uiState.update { state ->
            state.copy(config = modifier(state.config))
        }
    }

    private fun observeWatchdogMetrics() {
        viewModelScope.launch {
            watchdog.watchdogStatus.collect { metrics ->
                _uiState.update { it.copy(watchdogData = metrics) }
            }
        }
    }

    fun boostGame(launchAfterBoost: Boolean = false) {
        viewModelScope.launch {
            if (!repository.checkPrerequisites()) {
                refreshStatus()
                if (!_uiState.value.isShizukuReady) {
                    _uiState.update { it.copy(
                        errorMessage = "Shizuku ADB service is disconnected or permission is not granted."
                    ) }
                    return@launch
                }
            }

            _uiState.update { it.copy(
                isBoosting = true,
                errorMessage = null,
                statusMessage = null
            ) }

            val config = _uiState.value.config

            repository.runPreGameBoost(config).collect { progress ->
                when (progress) {
                    is BoostProgress.Idle -> {
                        _uiState.update { it.copy(isBoosting = false, boostProgress = progress) }
                    }
                    is BoostProgress.InProgress -> {
                        _uiState.update { it.copy(
                            isBoosting = true,
                            currentStep = progress.step.name,
                            progress = progress.progressPercentage,
                            statusMessage = progress.message,
                            boostProgress = progress
                        ) }
                    }
                    is BoostProgress.Success -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            progress = 100,
                            isPerformanceModeActive = progress.isPerformanceModeActive,
                            boostProgress = progress,
                            statusMessage = "Optimized successfully! Reclaimed ${progress.freedRamMb} MB."
                        ) }

                        if (launchAfterBoost && !config.targetGamePackage.isNullOrBlank()) {
                            launchGamePackage(config.targetGamePackage)
                        }
                    }
                    is BoostProgress.Error -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            errorMessage = progress.errorMessage,
                            boostProgress = progress
                        ) }
                    }
                }
            }
        }
    }

    fun compileTargetAppAot() {
        val targetPkg = _uiState.value.selectedGame?.packageName ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isBoosting = true, errorMessage = null) }
            val mode = if (_uiState.value.config.mode == BoostMode.EXTREME_BEAST) "everything" else "speed"

            repository.compileAppAot(targetPkg, mode).collect { progress ->
                when (progress) {
                    is BoostProgress.Success -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            boostProgress = progress,
                            statusMessage = "AOT compilation complete for $targetPkg!"
                        ) }
                    }
                    is BoostProgress.Error -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            errorMessage = progress.errorMessage,
                            boostProgress = progress
                        ) }
                    }
                    is BoostProgress.InProgress -> {
                        _uiState.update { it.copy(
                            isBoosting = true,
                            currentStep = progress.step.name,
                            progress = progress.progressPercentage,
                            statusMessage = progress.message,
                            boostProgress = progress
                        ) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBoosting = true, errorMessage = null) }

            repository.restorePostGame(_uiState.value.config).collect { progress ->
                when (progress) {
                    is BoostProgress.Success -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            isPerformanceModeActive = false,
                            boostProgress = progress,
                            statusMessage = "Default system governor & DVFS scaling restored."
                        ) }
                    }
                    is BoostProgress.Error -> {
                        _uiState.update { it.copy(
                            isBoosting = false,
                            errorMessage = progress.errorMessage,
                            boostProgress = progress
                        ) }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun launchGamePackage(packageName: String) {
        try {
            val app = getApplication<Application>()
            val intent = app.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                app.startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        watchdog.stop()
    }
}
