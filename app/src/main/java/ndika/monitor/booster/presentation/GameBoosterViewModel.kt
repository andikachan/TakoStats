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
import ndika.monitor.booster.model.BoostProgressState
import ndika.monitor.booster.model.WatchdogAlert
import ndika.monitor.booster.watchdog.ThermalMemoryWatchdog

class GameBoosterViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: IGameBoosterRepository = GameBoosterRepositoryImpl(application)
    private val watchdog: ThermalMemoryWatchdog = ThermalMemoryWatchdog(application)

    private val _uiState = MutableStateFlow(GameBoosterUiState())
    val uiState: StateFlow<GameBoosterUiState> = _uiState.asStateFlow()

    val watchdogAlerts: SharedFlow<WatchdogAlert> = watchdog.alertEvents

    init {
        checkShizukuStatus()
        observeWatchdog()
    }

    fun checkShizukuStatus() {
        val isReady = repository.isShizukuReady()
        _uiState.update { it.copy(
            isShizukuAvailable = isReady,
            isShizukuPermissionGranted = isReady
        ) }
    }

    private fun observeWatchdog() {
        viewModelScope.launch {
            watchdog.status.collect { status ->
                _uiState.update { it.copy(
                    watchdogStatus = status,
                    isWatchdogActive = watchdog.isRunning()
                ) }
            }
        }
    }

    fun toggleWatchdog(enable: Boolean) {
        if (enable) {
            watchdog.start()
        } else {
            watchdog.stop()
        }
        _uiState.update { it.copy(isWatchdogActive = watchdog.isRunning()) }
    }

    fun applyPreGameBoost() {
        viewModelScope.launch {
            if (!_uiState.value.isShizukuAvailable) {
                checkShizukuStatus()
                if (!_uiState.value.isShizukuAvailable) {
                    _uiState.update { it.copy(
                        boostProgressState = BoostProgressState.Error("Shizuku is not connected or permission not granted.")
                    ) }
                    return@launch
                }
            }

            // Start watchdog automatically during boost if not running
            if (!watchdog.isRunning()) {
                watchdog.start()
            }

            _uiState.update { it.copy(isBoostActive = true) }

            repository.executePreGameBoost(_uiState.value.customWhitelist).collect { progress ->
                _uiState.update { state ->
                    state.copy(
                        boostProgressState = progress,
                        isBoostActive = progress is BoostProgressState.InProgress || progress is BoostProgressState.Success
                    )
                }
            }
        }
    }

    fun applyPostGameRestore() {
        viewModelScope.launch {
            val result = repository.executePostGameRestore()
            _uiState.update { state ->
                state.copy(
                    isBoostActive = false,
                    boostProgressState = BoostProgressState.Idle,
                    statusMessage = if (result.isSuccess) "Default system power state restored." else "Restore failed: ${result.output}"
                )
            }
        }
    }

    fun addCustomWhitelist(pkg: String) {
        if (pkg.isBlank()) return
        _uiState.update { it.copy(customWhitelist = it.customWhitelist + pkg.trim()) }
    }

    fun removeCustomWhitelist(pkg: String) {
        _uiState.update { it.copy(customWhitelist = it.customWhitelist - pkg) }
    }

    override fun onCleared() {
        super.onCleared()
        watchdog.stop()
    }
}
