package ndika.monitor.booster.domain

import kotlinx.coroutines.flow.Flow
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.GameBoostConfig

/**
 * Domain repository contract for Game Booster operations.
 */
interface IGameBoosterRepository {
    /**
     * Executes the comprehensive pre-game optimization pipeline.
     */
    fun runPreGameBoost(config: GameBoostConfig = GameBoostConfig()): Flow<BoostProgress>

    /**
     * Restores default system power, thermal, and governor parameters post-gaming.
     */
    fun restorePostGame(config: GameBoostConfig = GameBoostConfig()): Flow<BoostProgress>

    /**
     * Verifies whether Shizuku service is running and permission is granted.
     */
    fun checkPrerequisites(): Boolean

    /**
     * Retrieves all installed user apps & games categorized.
     */
    suspend fun getInstalledGames(): List<GameAppInfo>

    /**
     * Directly optimizes/compiles an application using ART AOT compiler.
     */
    fun compileAppAot(packageName: String, compileMode: String = "speed"): Flow<BoostProgress>
}
