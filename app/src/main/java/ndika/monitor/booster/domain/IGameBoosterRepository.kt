package ndika.monitor.booster.domain

import kotlinx.coroutines.flow.Flow
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo

interface IGameBoosterRepository {
    fun executePreGameBoost(): Flow<BoostProgress>
    fun executePostGameRestore(): Flow<BoostProgress>
    fun getInstalledGames(): List<GameAppInfo>
    fun isShizukuReady(): Boolean
}
