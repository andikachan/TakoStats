package ndika.monitor.booster.model

enum class BoostStep {
    STORAGE_TRIM,
    BACKGROUND_APP_PURGE,
    FIXED_PERFORMANCE_MODE,
    RESTORE_DEFAULT
}

sealed class BoostProgressState {
    object Idle : BoostProgressState()
    
    data class InProgress(
        val currentStep: BoostStep,
        val progressPercent: Int,
        val message: String
    ) : BoostProgressState()
    
    data class Success(
        val isStorageTrimmed: Boolean,
        val purgedAppsCount: Int,
        val isPerformanceModeActive: Boolean,
        val freedRamMb: Long,
        val logDetails: List<String>
    ) : BoostProgressState()
    
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : BoostProgressState()
}

data class AppPurgeResult(
    val stoppedPackages: List<String>,
    val failedPackages: List<String>,
    val skippedWhitelistedCount: Int
)
