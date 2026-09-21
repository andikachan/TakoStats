package ndika.monitor.booster.domain

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
        // Step 1: Shizuku verification
        emit(BoostProgress(BoostStep.CHECKING_SHIZUKU, "Checking Shizuku ADB access...", 10))
        if (!isShizukuReady()) {
            emit(BoostProgress(BoostStep.ERROR, "Shizuku is not running or permission is missing.", 0))
            return@flow
        }

        // Step 2: Auto Storage Trim (fstrim) for eMMC 5.1 & UFS storage
        emit(BoostProgress(BoostStep.EXECUTING_FSTRIM, "Trimming internal storage blocks (fstrim)...", 30))
        shellExecutor.execute("sm fstrim", timeoutMs = 25000)

        // Step 3: Aggressive RAM purge for background apps
        emit(BoostProgress(BoostStep.PURGING_BACKGROUND_APPS, "Terminating background apps & freeing RAM...", 50))
        val pm = context.packageManager
        val installedPkgs = try {
            pm.getInstalledPackages(0).map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val myPackage = context.packageName
        val targetsToPurge = TargetPackages.PURGE_CANDIDATES
            .filter { pkg ->
                installedPkgs.contains(pkg) &&
                !TargetPackages.CRITICAL_WHITELIST.contains(pkg) &&
                pkg != myPackage
            }

        var purgedCount = 0
        if (targetsToPurge.isNotEmpty()) {
            targetsToPurge.chunked(3).forEach { chunk ->
                val commands = chunk.joinToString(" ; ") { "am force-stop $it" }
                shellExecutor.execute(commands, timeoutMs = 8000)
                purgedCount += chunk.size
            }
        }
        emit(BoostProgress(BoostStep.PURGING_BACKGROUND_APPS, "Purged $purgedCount background apps.", 70))

        // Step 4: Fixed Performance Mode (PowerHAL clock lock)
        emit(BoostProgress(BoostStep.SETTING_PERFORMANCE_MODE, "Enabling Fixed Performance Mode...", 90))
        shellExecutor.execute("cmd power set-fixed-performance-mode-enabled true", timeoutMs = 5000)

        // Step 5: Completed
        emit(BoostProgress(BoostStep.COMPLETED, "Optimization complete! Device is ready for gaming.", 100))
    }.flowOn(Dispatchers.IO)

    override fun executePostGameRestore(): Flow<BoostProgress> = flow {
        emit(BoostProgress(BoostStep.CHECKING_SHIZUKU, "Checking Shizuku status...", 20))
        if (!isShizukuReady()) {
            emit(BoostProgress(BoostStep.ERROR, "Shizuku is not available to restore defaults.", 0))
            return@flow
        }

        emit(BoostProgress(BoostStep.SETTING_PERFORMANCE_MODE, "Disabling Fixed Performance Mode...", 60))
        shellExecutor.execute("cmd power set-fixed-performance-mode-enabled false", timeoutMs = 5000)

        emit(BoostProgress(BoostStep.RESTORED, "System defaults successfully restored.", 100))
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
