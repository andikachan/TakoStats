package ndika.monitor.booster.domain

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import ndika.monitor.booster.executor.IShizukuShellExecutor
import ndika.monitor.booster.executor.ShizukuShellExecutor
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.BoostStep
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.TargetPackages

class GameBoosterRepositoryImpl(
    private val context: Context,
    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()
) : IGameBoosterRepository {

    override fun isShizukuReady(): Boolean {
        return shellExecutor.isAvailable() && shellExecutor.hasPermission()
    }

    override fun executePreGameBoost(): Flow<BoostProgress> = flow {
        val hasElevatedPrivilege = isShizukuReady()
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val myPackage = context.packageName

        val installedPkgs = try {
            pm.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val targetsToPurge = TargetPackages.PURGE_CANDIDATES
            .filter { pkg ->
                installedPkgs.contains(pkg) &&
                !TargetPackages.CRITICAL_WHITELIST.contains(pkg) &&
                pkg != myPackage
            }

        if (hasElevatedPrivilege) {
            // === DEEP SHIZUKU ADB BOOSTER ===
            emit(BoostProgress(BoostStep.CHECKING_SHIZUKU, "Shizuku elevated privileges verified.", 15))

            // 1. Fast Aggressive RAM Purge (am force-stop)
            emit(BoostProgress(BoostStep.PURGING_BACKGROUND_APPS, "Force-stopping background bloatware...", 40))
            var purgedCount = 0
            if (targetsToPurge.isNotEmpty()) {
                targetsToPurge.chunked(4).forEach { chunk ->
                    val commands = chunk.joinToString(" ; ") { "am force-stop $it" }
                    shellExecutor.execute(commands, timeoutMs = 4000)
                    purgedCount += chunk.size
                }
            }
            emit(BoostProgress(BoostStep.PURGING_BACKGROUND_APPS, "Terminated $purgedCount background processes.", 70))

            // 2. Fixed Performance Mode (PowerHAL clock lock)
            emit(BoostProgress(BoostStep.SETTING_PERFORMANCE_MODE, "Enabling Sustained Performance Mode...", 85))
            shellExecutor.execute("cmd power set-fixed-performance-mode-enabled true", timeoutMs = 3000)

            // 3. Fast Storage Trim (fstrim)
            emit(BoostProgress(BoostStep.EXECUTING_FSTRIM, "Trimming storage blocks (fstrim)...", 95))
            shellExecutor.execute("sm fstrim", timeoutMs = 5000)

            emit(BoostProgress(BoostStep.COMPLETED, "Deep Boost Active: $purgedCount apps purged & performance mode locked.", 100))
        } else {
            // === STANDARD NON-ROOT / NON-SHIZUKU FALLBACK ===
            emit(BoostProgress(BoostStep.CHECKING_SHIZUKU, "Shizuku not active. Running Standard RAM Clean...", 20))

            var killedCount = 0
            if (am != null && targetsToPurge.isNotEmpty()) {
                for (pkg in targetsToPurge) {
                    try {
                        am.killBackgroundProcesses(pkg)
                        killedCount++
                    } catch (_: Exception) {}
                }
            }

            emit(BoostProgress(BoostStep.PURGING_BACKGROUND_APPS, "Reclaimed background RAM from $killedCount apps.", 60))

            // Force JVM memory garbage collection
            try {
                Runtime.getRuntime().gc()
                System.gc()
            } catch (_: Exception) {}

            emit(BoostProgress(BoostStep.COMPLETED, "Standard Boost Complete ($killedCount apps cleaned). Enable Shizuku for Deep Kernel Boost.", 100))
        }
    }.flowOn(Dispatchers.IO)

    override fun executePostGameRestore(): Flow<BoostProgress> = flow {
        if (isShizukuReady()) {
            emit(BoostProgress(BoostStep.CHECKING_SHIZUKU, "Verifying elevated privileges...", 30))
            emit(BoostProgress(BoostStep.SETTING_PERFORMANCE_MODE, "Restoring default PowerHAL profile...", 70))
            shellExecutor.execute("cmd power set-fixed-performance-mode-enabled false", timeoutMs = 3000)
            emit(BoostProgress(BoostStep.RESTORED, "System defaults successfully restored.", 100))
        } else {
            emit(BoostProgress(BoostStep.RESTORED, "System is running on standard profile.", 100))
        }
    }.flowOn(Dispatchers.IO)

    override fun getInstalledGames(): List<GameAppInfo> {
        val pm = context.packageManager
        val gamesList = mutableListOf<GameAppInfo>()
        val seenPackages = hashSetOf<String>()
        val myPackage = context.packageName

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo?.packageName ?: continue
                if (pkgName == myPackage || seenPackages.contains(pkgName)) continue

                val appInfo = resolveInfo.activityInfo.applicationInfo ?: continue

                val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                            (appInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0)
                } else {
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0)
                }

                if (isGame) {
                    val appName = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm)
                    gamesList.add(GameAppInfo(appName = appName, packageName = pkgName, icon = icon))
                    seenPackages.add(pkgName)
                }
            }

            // Fallback check: check all installed applications
            if (gamesList.isEmpty()) {
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (app in installedApps) {
                    val pkgName = app.packageName
                    if (pkgName == myPackage || seenPackages.contains(pkgName)) continue

                    val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.category == ApplicationInfo.CATEGORY_GAME ||
                                (app.flags and ApplicationInfo.FLAG_IS_GAME != 0)
                    } else {
                        (app.flags and ApplicationInfo.FLAG_IS_GAME != 0)
                    }

                    if (isGame) {
                        val appName = pm.getApplicationLabel(app).toString()
                        val icon = pm.getApplicationIcon(app)
                        gamesList.add(GameAppInfo(appName = appName, packageName = pkgName, icon = icon))
                        seenPackages.add(pkgName)
                    }
                }
            }
        } catch (_: Exception) {}

        return gamesList.sortedBy { it.appName }
    }
}
