package ndika.monitor.booster.domain

import kotlinx.coroutines.flow.Flow
import ndika.monitor.booster.model.AppPurgeResult
import ndika.monitor.booster.model.BoostProgressState
import ndika.monitor.booster.model.ShellResult

interface IGameBoosterRepository {
    fun isShizukuReady(): Boolean
    suspend fun trimStorage(): ShellResult
    suspend fun purgeBackgroundApps(customWhitelist: Set<String> = emptySet()): AppPurgeResult
    suspend fun setFixedPerformanceMode(enable: Boolean): ShellResult
    fun executePreGameBoost(customWhitelist: Set<String> = emptySet()): Flow<BoostProgressState>
    suspend fun executePostGameRestore(): ShellResult
}
