package ndika.monitor.booster.domain

import kotlinx.coroutines.flow.Flow
import ndika.monitor.booster.model.BoostProgress

/**
 * Domain repository contract for Game Booster operations.
 */
interface IGameBoosterRepository {
    /**
     * Executes the comprehensive pre-game optimization pipeline (fstrim, app purge, fixed performance mode).
     */
    fun runPreGameBoost(): Flow<BoostProgress>

    /**
     * Restores default system power and governor parameters post-gaming.
     */
    fun restorePostGame(): Flow<BoostProgress>

    /**
     * Verifies whether Shizuku service is running and permission is granted.
     */
    fun checkPrerequisites(): Boolean
}
