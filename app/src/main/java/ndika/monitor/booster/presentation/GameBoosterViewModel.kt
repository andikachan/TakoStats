package ndika.monitor.booster.presentation

import android.app.Application
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
import ndika.monitor.booster.model.BoostProgress
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
        observeWatchdogMetrics()
        watchdog.start(3000L)
    }

    fun refreshStatus() {
        val ready = repository.checkPrerequisites()
        _uiState.update { it.copy(isShizukuReady = ready) }
    }

    private fun observeWatchdogMetrics() {
        viewModelScope.launch {
            watchdog.watchdogStatus.collect { metrics ->
                _uiState.update { it.copy(watchdogData = metrics) }
            }
        }
    }

    fun boostGame() {
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

            repository.runPreGameBoost().collect { progress ->
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

    fun restoreDefaults() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBoosting = true, errorMessage = null) }

            repository.restorePostGame().collect { progress ->
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

    override fun onCleared() {
        super.onCleared()
        watchdog.stop()
    }
}
