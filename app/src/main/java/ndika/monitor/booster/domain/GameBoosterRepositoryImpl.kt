package ndika.monitor.booster.domain

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import ndika.monitor.booster.executor.IShizukuShellExecutor
import ndika.monitor.booster.executor.ShizukuShellExecutor
import ndika.monitor.booster.model.AppPurgeResult
import ndika.monitor.booster.model.BoostProgressState
import ndika.monitor.booster.model.BoostStep
import ndika.monitor.booster.model.ShellResult

class GameBoosterRepositoryImpl(
    private val context: Context,
    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()
) : IGameBoosterRepository {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager

    // Essential system & communication packages that must NEVER be terminated
    private val essentialSystemWhitelist = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.play.games",
        "moe.shizuku.privileged.api",
        "rikka.shizuku",
        context.packageName
    )

    // Essential daily communication & critical apps whitelist
    private val essentialUserWhitelist = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.discord",
        "com.google.android.dialer",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.samsung.android.dialer",
        "com.vivo.browser",
        "com.android.camera"
    )

    override fun isShizukuReady(): Boolean {
        return shellExecutor.isAvailable()
    }

    override suspend fun trimStorage(): ShellResult {
        // Runs Android storage TRIM on /data, /cache and internal eMMC/UFS partitions
        return shellExecutor.execute("sm fstrim", timeoutMs = 15_000L)
    }

    override suspend fun purgeBackgroundApps(customWhitelist: Set<String>): AppPurgeResult {
        val resolvedWhitelist = buildFullWhitelist(customWhitelist)
        val targetPackages = getPurgeableThirdPartyPackages(resolvedWhitelist)

        val stopped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (pkg in targetPackages) {
            val res = shellExecutor.execute("am force-stop $pkg", timeoutMs = 2000L)
            if (res.isSuccess) {
                stopped.add(pkg)
            } else {
                failed.add(pkg)
            }
        }

        // Drop user-space cached processes safely
        shellExecutor.execute("am kill-all", timeoutMs = 3000L)

        return AppPurgeResult(
            stoppedPackages = stopped,
            failedPackages = failed,
            skippedWhitelistedCount = resolvedWhitelist.size
        )
    }

    override suspend fun setFixedPerformanceMode(enable: Boolean): ShellResult {
        // Enables / disables sustained fixed performance mode on supported Android 11+ PowerHAL
        val cmd = "cmd power set-fixed-performance-mode-enabled $enable"
        return shellExecutor.execute(cmd, timeoutMs = 5000L)
    }

    override fun executePreGameBoost(customWhitelist: Set<String>): Flow<BoostProgressState> = flow {
        if (!isShizukuReady()) {
            emit(BoostProgressState.Error("Shizuku is not running or permission is missing. Please authorize Shizuku."))
            return@flow
        }

        val logDetails = mutableListOf<String>()
        val initialRamMb = getAvailableRamMb()

        emit(BoostProgressState.InProgress(
            currentStep = BoostStep.STORAGE_TRIM,
            progressPercent = 10,
            message = "Trimming flash storage (fstrim) to eliminate eMMC/UFS latency..."
        ))

        // 1. Auto Storage Trim
        val trimResult = trimStorage()
        val isTrimmed = trimResult.isSuccess
        if (isTrimmed) {
            logDetails.add(" Storage TRIM complete: flash I/O optimized.")
        } else {
            logDetails.add(" Storage TRIM warning: ${trimResult.output}")
        }

        emit(BoostProgressState.InProgress(
            currentStep = BoostStep.BACKGROUND_APP_PURGE,
            progressPercent = 45,
            message = "Purging memory-hogging background apps & cached services..."
        ))

        // 2. Background App Purge
        val purgeResult = purgeBackgroundApps(customWhitelist)
        logDetails.add(" Purged ${purgeResult.stoppedPackages.size} background applications.")

        emit(BoostProgressState.InProgress(
            currentStep = BoostStep.FIXED_PERFORMANCE_MODE,
            progressPercent = 80,
            message = "Enabling Android Fixed Performance Mode..."
        ))

        // 3. Fixed Performance Mode
        val perfResult = setFixedPerformanceMode(true)
        val isPerfEnabled = perfResult.isSuccess
        if (isPerfEnabled) {
            logDetails.add(" Fixed Performance Mode enabled (PowerHAL).")
        } else {
            logDetails.add(" Fixed Performance Mode: Not supported on this ROM kernel (skipped).")
        }

        val finalRamMb = getAvailableRamMb()
        val freedRam = maxOf(0L, finalRamMb - initialRamMb)

        emit(BoostProgressState.InProgress(
            currentStep = BoostStep.FIXED_PERFORMANCE_MODE,
            progressPercent = 100,
            message = "Boost complete! Freed ${freedRam} MB RAM."
        ))

        emit(BoostProgressState.Success(
            isStorageTrimmed = isTrimmed,
            purgedAppsCount = purgeResult.stoppedPackages.size,
            isPerformanceModeActive = isPerfEnabled,
            freedRamMb = freedRam,
            logDetails = logDetails
        ))
    }.flowOn(Dispatchers.IO)

    override suspend fun executePostGameRestore(): ShellResult {
        // Disable Fixed Performance Mode to allow standard DVFS battery saver behavior
        return setFixedPerformanceMode(false)
    }

    private fun getAvailableRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    private fun buildFullWhitelist(custom: Set<String>): Set<String> {
        val set = mutableSetOf<String>()
        set.addAll(essentialSystemWhitelist)
        set.addAll(essentialUserWhitelist)
        set.addAll(custom)

        // Dynamically resolve Default Home Launcher
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfo?.activityInfo?.packageName?.let { set.add(it) }
        } catch (_: Exception) {}

        // Dynamically resolve Default Input Method (Keyboard)
        try {
            val defaultIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!defaultIme.isNullOrBlank()) {
                val imePkg = defaultIme.substringBefore("/")
                if (imePkg.isNotBlank()) set.add(imePkg)
            }
        } catch (_: Exception) {}

        return set
    }

    private fun getPurgeableThirdPartyPackages(whitelist: Set<String>): List<String> {
        val result = mutableListOf<String>()
        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (app in installedApps) {
                val pkg = app.packageName
                if (whitelist.contains(pkg)) continue

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // Purge non-system 3rd party apps or heavy bloatware
                if (!isSystem || isUpdatedSystem) {
                    result.add(pkg)
                }
            }
        } catch (_: Exception) {}

        return result
    }
}
