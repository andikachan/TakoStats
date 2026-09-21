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
import ndika.monitor.booster.model.BoostConstants
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.BoostStepType

class GameBoosterRepositoryImpl(
    private val context: Context,
    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()
) : IGameBoosterRepository {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager

    override fun checkPrerequisites(): Boolean {
        return shellExecutor.isShizukuAvailable() && shellExecutor.hasPermission()
    }

    override fun runPreGameBoost(): Flow<BoostProgress> = flow {
        if (!checkPrerequisites()) {
            emit(BoostProgress.Error("Shizuku ADB permission is required. Please start Shizuku and grant access."))
            return@flow
        }

        val logSummary = mutableListOf<String>()
        val initialRamMb = queryAvailableRamMb()

        // Step 1: Auto Storage Trim (fstrim)
        emit(BoostProgress.InProgress(
            step = BoostStepType.FSTRIM,
            progressPercentage = 15,
            message = "Executing storage TRIM (sm fstrim) to clear flash write amplification..."
        ))

        val trimResult = shellExecutor.executeCommand("sm fstrim", timeoutMs = 15000L)
        if (trimResult.isSuccess) {
            logSummary.add("• Storage TRIM: Flash controller wear leveled & I/O latency optimized.")
        } else {
            logSummary.add("• Storage TRIM: Warning (${trimResult.output})")
        }

        // Step 2: Background App Purge
        emit(BoostProgress.InProgress(
            step = BoostStepType.APP_PURGE,
            progressPercentage = 50,
            message = "Purging heavy background applications and reclaiming RAM..."
        ))

        val purgeablePackages = resolvePurgeablePackages()
        var purgedCount = 0

        for (pkg in purgeablePackages) {
            val res = shellExecutor.executeCommand("am force-stop $pkg", timeoutMs = 2000L)
            if (res.isSuccess) {
                purgedCount++
            }
        }

        // Reclaim general cached background services
        shellExecutor.executeCommand("am kill-all", timeoutMs = 2000L)
        logSummary.add("• Background Purge: Terminated $purgedCount background processes.")

        // Step 3: Fixed Performance Mode (PowerHAL)
        emit(BoostProgress.InProgress(
            step = BoostStepType.FIXED_PERFORMANCE,
            progressPercentage = 85,
            message = "Activating Android sustained performance mode..."
        ))

        val perfResult = shellExecutor.executeCommand("cmd power set-fixed-performance-mode-enabled true", timeoutMs = 5000L)
        val isPerfModeActive = perfResult.isSuccess
        if (isPerfModeActive) {
            logSummary.add("• PowerHAL: Fixed Performance Mode successfully locked.")
        } else {
            logSummary.add("• PowerHAL: Fixed Performance Mode not supported by this device vendor (skipped).")
        }

        val finalRamMb = queryAvailableRamMb()
        val freedRam = maxOf(0L, finalRamMb - initialRamMb)

        emit(BoostProgress.InProgress(
            step = BoostStepType.COMPLETE,
            progressPercentage = 100,
            message = "Game Booster optimization complete! Reclaimed ${freedRam} MB RAM."
        ))

        emit(BoostProgress.Success(
            isStorageTrimmed = trimResult.isSuccess,
            purgedAppsCount = purgedCount,
            isPerformanceModeActive = isPerfModeActive,
            freedRamMb = freedRam,
            summaryLogs = logSummary
        ))
    }.flowOn(Dispatchers.IO)

    override fun restorePostGame(): Flow<BoostProgress> = flow {
        if (!checkPrerequisites()) {
            emit(BoostProgress.Error("Shizuku is not running."))
            return@flow
        }

        emit(BoostProgress.InProgress(
            step = BoostStepType.FIXED_PERFORMANCE,
            progressPercentage = 50,
            message = "Restoring default system governor & power state..."
        ))

        // Disable Fixed Performance Mode
        val res = shellExecutor.executeCommand("cmd power set-fixed-performance-mode-enabled false", timeoutMs = 5000L)

        emit(BoostProgress.Success(
            isStorageTrimmed = false,
            purgedAppsCount = 0,
            isPerformanceModeActive = false,
            freedRamMb = 0L,
            summaryLogs = listOf(
                if (res.isSuccess) "• Power state reverted to standard dynamic voltage/frequency scaling (DVFS)."
                else "• Power restore notice: ${res.output}"
            )
        ))
    }.flowOn(Dispatchers.IO)

    private fun queryAvailableRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    private fun resolvePurgeablePackages(): Set<String> {
        val resolved = mutableSetOf<String>()
        val fullWhitelist = buildDynamicWhitelist()

        // 1. Add known heavy bloatware targets that are currently installed
        for (pkg in BoostConstants.DEFAULT_PURGEABLE_PACKAGES) {
            if (!fullWhitelist.contains(pkg) && isPackageInstalled(pkg)) {
                resolved.add(pkg)
            }
        }

        // 2. Scan installed third-party apps
        try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (app in installed) {
                val pkg = app.packageName
                if (fullWhitelist.contains(pkg)) continue

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                if (!isSystem || isUpdatedSystem) {
                    resolved.add(pkg)
                }
            }
        } catch (_: Exception) {}

        return resolved
    }

    private fun buildDynamicWhitelist(): Set<String> {
        val whitelist = mutableSetOf<String>()
        whitelist.addAll(BoostConstants.ESSENTIAL_WHITELIST_PACKAGES)
        whitelist.add(context.packageName)

        // Dynamic Home Launcher resolution
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfo?.activityInfo?.packageName?.let { whitelist.add(it) }
        } catch (_: Exception) {}

        // Dynamic Active Keyboard / IME resolution
        try {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!ime.isNullOrBlank()) {
                val imePkg = ime.substringBefore("/")
                if (imePkg.isNotBlank()) whitelist.add(imePkg)
            }
        } catch (_: Exception) {}

        return whitelist
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
