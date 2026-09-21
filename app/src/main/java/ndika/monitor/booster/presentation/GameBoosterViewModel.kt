package ndika.monitor.booster.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ndika.monitor.booster.domain.GameBoosterRepositoryImpl
import ndika.monitor.booster.domain.IGameBoosterRepository
import ndika.monitor.booster.model.BoostStep
import ndika.monitor.booster.watchdog.ThermalMemoryWatchdog

class GameBoosterViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: IGameBoosterRepository = GameBoosterRepositoryImpl(application)
    private val watchdog = ThermalMemoryWatchdog(application)

    private val _uiState = MutableStateFlow(GameBoosterUiState())
    val uiState: StateFlow<GameBoosterUiState> = _uiState.asStateFlow()

    init {
        checkShizukuStatus()
        startHardwareWatchdog()
        loadInstalledGames()
    }

    fun checkShizukuStatus() {
        val ready = repository.isShizukuReady()
        _uiState.update { it.copy(isShizukuReady = ready) }
    }

    private fun startHardwareWatchdog() {
        viewModelScope.launch {
            watchdog.observeStatus(3000L).collectLatest { status ->
                _uiState.update { it.copy(watchdog = status) }
            }
        }
    }

    fun loadInstalledGames() {
        viewModelScope.launch(Dispatchers.IO) {
            val games = repository.getInstalledGames()
            _uiState.update { it.copy(installedGames = games) }
        }
    }

    fun runBoost(onCompleted: (() -> Unit)? = null) {
        if (_uiState.value.isBoosting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBoosting = true, errorMessage = null) }
            repository.executePreGameBoost().collect { progress ->
                _uiState.update {
                    it.copy(
                        boostProgress = progress,
                        errorMessage = if (progress.step == BoostStep.ERROR) progress.message else null
                    )
                }
                if (progress.step == BoostStep.COMPLETED) {
                    onCompleted?.invoke()
                }
            }
            _uiState.update { it.copy(isBoosting = false) }
        }
    }

    fun runRestore() {
        if (_uiState.value.isBoosting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBoosting = true, errorMessage = null) }
            repository.executePostGameRestore().collect { progress ->
                _uiState.update {
                    it.copy(
                        boostProgress = progress,
                        errorMessage = if (progress.step == BoostStep.ERROR) progress.message else null
                    )
                }
            }
            _uiState.update { it.copy(isBoosting = false) }
        }
    }

    fun launchGame(context: Context, packageName: String) {
        runBoost {
            viewModelScope.launch(Dispatchers.Main) {
                try {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    }
                } catch (_: Exception) {}
            }
        }
    }
}
