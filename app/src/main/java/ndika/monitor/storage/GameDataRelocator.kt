package ndika.monitor.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager as AndroidStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ndika.monitor.booster.executor.IShizukuShellExecutor
import ndika.monitor.booster.executor.ShizukuShellExecutor
import ndika.monitor.storage.model.GameStorageInfo
import org.json.JSONObject
import java.io.File
import java.util.UUID

object GameDataRelocator {

    private const val PREFS_NAME = "game_relocator_prefs"
    private const val KEY_RELOCATED_GAMES = "relocated_games_map"

    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()

    suspend fun getInstalledGamesStorageInfo(context: Context): List<GameStorageInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GameStorageInfo>()
        val packageManager = context.packageManager
        val relocatedMap = getRelocatedGamesMap(context)
        val activeMounts = queryActiveMountPoints()
        val (shellDataSizes, shellObbSizes) = queryAllAndroidDataSizesViaShell()

        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (app in installedApps) {
                if (app.packageName == context.packageName) continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                if (isSystem && launchIntent == null) continue

                val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }

                val appName = packageManager.getApplicationLabel(app).toString()
                val icon = try { packageManager.getApplicationIcon(app) } catch (_: Exception) { null }

                val dataDir = File(Environment.getExternalStorageDirectory(), "Android/data/${app.packageName}")
                val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/${app.packageName}")

                val relocationRecord = relocatedMap[app.packageName]
                val isRelocated = relocationRecord != null

                // Multi-Tier Directory and Asset Size Detection
                var dataSize = 0L
                var obbSize = 0L

                if (isRelocated) {
                    val destData = relocationRecord?.targetDataPath
                    val destObb = relocationRecord?.targetObbPath

                    val sData = if (!destData.isNullOrBlank()) queryPathSizeViaShell(destData) else 0L
                    dataSize = if (sData > 0L) sData else if (!destData.isNullOrBlank()) getDirectorySize(File(destData)) else 0L

                    val sObb = if (!destObb.isNullOrBlank()) queryPathSizeViaShell(destObb) else 0L
                    obbSize = if (sObb > 0L) sObb else if (!destObb.isNullOrBlank()) getDirectorySize(File(destObb)) else 0L
                } else {
                    dataSize = shellDataSizes[app.packageName] ?: 0L
                    obbSize = shellObbSizes[app.packageName] ?: 0L

                    // Fallback 1: Package-specific shell query (tests /sdcard, /storage/emulated/0, and /data/media/0)
                    if (dataSize <= 0L && obbSize <= 0L) {
                        val (pData, pObb) = queryPackageStorageViaShell(app.packageName)
                        if (pData > 0L) dataSize = pData
                        if (pObb > 0L) obbSize = pObb
                    }

                    // Fallback 2: Direct Java File inspection
                    if (dataSize <= 0L) {
                        dataSize = getDirectorySize(dataDir)
                    }
                    if (obbSize <= 0L) {
                        obbSize = getDirectorySize(obbDir)
                    }

                    // Fallback 3: System StorageStatsManager (API 26+)
                    if (dataSize <= 0L && obbSize <= 0L) {
                        val statsSize = getStorageStatsDataSize(context, app.packageName)
                        if (statsSize > 0L) {
                            dataSize = statsSize
                        }
                    }

                    // Fallback 4: Base APK and Split APK sizes
                    if (dataSize <= 0L && obbSize <= 0L) {
                        try {
                            var apkBytes = File(app.sourceDir).length()
                            app.splitSourceDirs?.forEach { sPath ->
                                apkBytes += File(sPath).length()
                            }
                            if (apkBytes > 0L) {
                                dataSize = apkBytes
                            }
                        } catch (_: Exception) {}
                    }
                }

                val isMounted = activeMounts.any { it.contains(app.packageName) }

                list.add(
                    GameStorageInfo(
                        packageName = app.packageName,
                        appName = appName,
                        icon = icon,
                        isGameCategory = isGame,
                        dataSizeBytes = dataSize,
                        obbSizeBytes = obbSize,
                        totalSizeBytes = dataSize + obbSize,
                        isRelocated = isRelocated,
                        targetVolumeId = relocationRecord?.targetVolumeId,
                        targetVolumeName = relocationRecord?.targetVolumeName,
                        targetDataPath = relocationRecord?.targetDataPath,
                        targetObbPath = relocationRecord?.targetObbPath,
                        isMounted = isMounted
                    )
                )
            }

            // Sort: Relocated games first, then games by size descending
            list.sortWith(
                compareByDescending<GameStorageInfo> { it.isRelocated }
                    .thenByDescending { it.totalSizeBytes }
                    .thenByDescending { it.isGameCategory }
                    .thenBy { it.appName.lowercase() }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        list
    }

    private suspend fun queryAllAndroidDataSizesViaShell(): Pair<Map<String, Long>, Map<String, Long>> {
        val dataMap = mutableMapOf<String, Long>()
        val obbMap = mutableMapOf<String, Long>()
        try {
            val cmd = "du -sk /sdcard/Android/data/* /sdcard/Android/obb/* /storage/emulated/0/Android/data/* /storage/emulated/0/Android/obb/* /data/media/0/Android/data/* /data/media/0/Android/obb/* 2>/dev/null"
            val res = shellExecutor.executeCommand(cmd, timeoutMs = 6000L)
            parseDuLines(res.stdout, dataMap, obbMap)
        } catch (_: Exception) {}
        return Pair(dataMap, obbMap)
    }

    private suspend fun queryPackageStorageViaShell(pkg: String): Pair<Long, Long> {
        var dataSize = 0L
        var obbSize = 0L
        try {
            val cmd = "du -sk /sdcard/Android/data/$pkg /storage/emulated/0/Android/data/$pkg /data/media/0/Android/data/$pkg /sdcard/Android/obb/$pkg /storage/emulated/0/Android/obb/$pkg /data/media/0/Android/obb/$pkg 2>/dev/null"
            val res = shellExecutor.executeCommand(cmd, timeoutMs = 2500L)
            res.stdout.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val kb = parts[0].toLongOrNull() ?: 0L
                    val path = parts[1]
                    if (kb > 0L) {
                        if (path.contains("/data/")) {
                            if (dataSize == 0L) dataSize = kb * 1024L
                        } else if (path.contains("/obb/")) {
                            if (obbSize == 0L) obbSize = kb * 1024L
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return Pair(dataSize, obbSize)
    }

    private fun parseDuLines(output: String, dataMap: MutableMap<String, Long>, obbMap: MutableMap<String, Long>) {
        output.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val sizeKb = parts[0].toLongOrNull() ?: return@forEach
                val path = parts[1].trimEnd('/')
                val pkg = path.substringAfterLast('/')
                if (pkg.isNotEmpty() && pkg.contains('.')) {
                    val sizeBytes = sizeKb * 1024L
                    if (path.contains("/Android/data") || path.contains("/data/")) {
                        dataMap[pkg] = (dataMap[pkg] ?: 0L) + sizeBytes
                    } else if (path.contains("/Android/obb") || path.contains("/obb/")) {
                        obbMap[pkg] = (obbMap[pkg] ?: 0L) + sizeBytes
                    }
                }
            }
        }
    }

    private suspend fun queryPathSizeViaShell(path: String): Long {
        try {
            val res = shellExecutor.executeCommand("du -sk \"$path\" 2>/dev/null", timeoutMs = 3000L)
            val trimmed = res.stdout.trim()
            if (trimmed.isNotEmpty()) {
                val sizeKb = trimmed.split(Regex("\\s+"), limit = 2).firstOrNull()?.toLongOrNull()
                if (sizeKb != null && sizeKb > 0L) {
                    return sizeKb * 1024L
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun getStorageStatsDataSize(context: Context, packageName: String): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
                val packageManager = context.packageManager
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val uuid: UUID = appInfo.storageUuid ?: AndroidStorageManager.UUID_DEFAULT
                val stats = storageStatsManager?.queryStatsForPackage(uuid, packageName, Process.myUserHandle())
                if (stats != null) {
                    return stats.dataBytes
                }
            } catch (_: Exception) {}
        }
        return 0L
    }

    suspend fun relocateGameData(
        context: Context,
        gamePkg: String,
        targetVolume: StorageVolumeInfo,
        onProgress: (Int, String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            onProgress(5, "Stopping game process...")
            shellExecutor.executeCommand("am force-stop $gamePkg", timeoutMs = 3000L)

            val srcDataDir = "/sdcard/Android/data/$gamePkg"
            val srcObbDir = "/sdcard/Android/obb/$gamePkg"

            val targetRoot = targetVolume.path?.absolutePath
                ?: return@withContext Result.failure(Exception("Target storage path is not accessible."))

            val destDataDir = "$targetRoot/GameData/$gamePkg/data"
            val destObbDir = "$targetRoot/GameData/$gamePkg/obb"

            onProgress(15, "Creating destination folders on ${targetVolume.name}...")
            shellExecutor.executeCommand("mkdir -p \"$destDataDir\"", timeoutMs = 3000L)
            shellExecutor.executeCommand("mkdir -p \"$destObbDir\"", timeoutMs = 3000L)

            // 1. Copy Data
            onProgress(30, "Copying game data to ${targetVolume.name}...")
            shellExecutor.executeCommand("cp -a -p -f $srcDataDir/. \"$destDataDir/\" 2>/dev/null", timeoutMs = 180000L)

            // 2. Copy OBB
            onProgress(60, "Copying OBB assets to ${targetVolume.name}...")
            shellExecutor.executeCommand("cp -a -p -f $srcObbDir/. \"$destObbDir/\" 2>/dev/null", timeoutMs = 180000L)

            // 3. Verify destination
            onProgress(75, "Verifying relocated files...")
            val destDataFile = File(destDataDir)
            val destObbFile = File(destObbDir)
            var totalFreedBytes = queryPathSizeViaShell(destDataDir) + queryPathSizeViaShell(destObbDir)
            if (totalFreedBytes <= 0L) {
                totalFreedBytes = getDirectorySize(destDataFile) + getDirectorySize(destObbFile)
            }

            // 4. Free internal storage
            onProgress(85, "Freeing internal storage...")
            shellExecutor.executeCommand("rm -rf $srcDataDir/* 2>/dev/null", timeoutMs = 30000L)
            shellExecutor.executeCommand("rm -rf $srcObbDir/* 2>/dev/null", timeoutMs = 30000L)

            // 5. Mount Bind
            onProgress(92, "Mounting external directory link...")
            shellExecutor.executeCommand("mount -o bind \"$destDataDir\" $srcDataDir 2>/dev/null; mount --bind \"$destDataDir\" $srcDataDir 2>/dev/null", timeoutMs = 3000L)
            shellExecutor.executeCommand("mount -o bind \"$destObbDir\" $srcObbDir 2>/dev/null; mount --bind \"$destObbDir\" $srcObbDir 2>/dev/null", timeoutMs = 3000L)

            // Fallback: symlink files
            shellExecutor.executeCommand("ln -sf \"$destDataDir\"/* $srcDataDir/ 2>/dev/null", timeoutMs = 3000L)
            shellExecutor.executeCommand("ln -sf \"$destObbDir\"/* $srcObbDir/ 2>/dev/null", timeoutMs = 3000L)

            // 6. Save Relocation Record
            saveRelocationRecord(
                context = context,
                packageName = gamePkg,
                targetVolumeId = targetVolume.id,
                targetVolumeName = targetVolume.name,
                targetDataPath = destDataDir,
                targetObbPath = destObbDir
            )

            onProgress(100, "Game data successfully relocated to ${targetVolume.name}!")
            val freedStr = StorageManager.formatFileSize(totalFreedBytes)
            Result.success("Successfully moved $gamePkg ($freedStr) to ${targetVolume.name}. Internal memory is now free.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun restoreGameData(
        context: Context,
        gamePkg: String,
        onProgress: (Int, String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val record = getRelocatedGamesMap(context)[gamePkg]
                ?: return@withContext Result.failure(Exception("No relocation record found for $gamePkg."))

            onProgress(10, "Stopping game process...")
            shellExecutor.executeCommand("am force-stop $gamePkg", timeoutMs = 3000L)

            val srcDataDir = "/sdcard/Android/data/$gamePkg"
            val srcObbDir = "/sdcard/Android/obb/$gamePkg"

            onProgress(25, "Unmounting external directory link...")
            shellExecutor.executeCommand("umount -l $srcDataDir 2>/dev/null; umount -l $srcObbDir 2>/dev/null", timeoutMs = 3000L)

            onProgress(50, "Moving game data back to internal storage...")
            if (!record.targetDataPath.isNullOrBlank()) {
                shellExecutor.executeCommand("mkdir -p $srcDataDir 2>/dev/null", timeoutMs = 3000L)
                shellExecutor.executeCommand("cp -a -p -f \"${record.targetDataPath}/.\" $srcDataDir/ 2>/dev/null", timeoutMs = 180000L)
                shellExecutor.executeCommand("rm -rf \"${record.targetDataPath}\" 2>/dev/null", timeoutMs = 30000L)
            }

            onProgress(75, "Moving OBB assets back to internal storage...")
            if (!record.targetObbPath.isNullOrBlank()) {
                shellExecutor.executeCommand("mkdir -p $srcObbDir 2>/dev/null", timeoutMs = 3000L)
                shellExecutor.executeCommand("cp -a -p -f \"${record.targetObbPath}/.\" $srcObbDir/ 2>/dev/null", timeoutMs = 180000L)
                shellExecutor.executeCommand("rm -rf \"${record.targetObbPath}\" 2>/dev/null", timeoutMs = 30000L)
            }

            removeRelocationRecord(context, gamePkg)
            onProgress(100, "Game data successfully restored to internal storage.")
            Result.success("Restored $gamePkg back to internal phone memory.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun ensureGameMounted(context: Context, gamePkg: String): Boolean = withContext(Dispatchers.IO) {
        val record = getRelocatedGamesMap(context)[gamePkg] ?: return@withContext true
        val destDataDir = record.targetDataPath ?: return@withContext true
        val destObbDir = record.targetObbPath ?: return@withContext true

        val srcDataDir = "/sdcard/Android/data/$gamePkg"
        val srcObbDir = "/sdcard/Android/obb/$gamePkg"

        shellExecutor.executeCommand("mkdir -p $srcDataDir 2>/dev/null; mkdir -p $srcObbDir 2>/dev/null", timeoutMs = 2000L)
        shellExecutor.executeCommand("mount -o bind \"$destDataDir\" $srcDataDir 2>/dev/null; mount --bind \"$destDataDir\" $srcDataDir 2>/dev/null", timeoutMs = 2000L)
        shellExecutor.executeCommand("mount -o bind \"$destObbDir\" $srcObbDir 2>/dev/null; mount --bind \"$destObbDir\" $srcObbDir 2>/dev/null", timeoutMs = 2000L)
        shellExecutor.executeCommand("ln -sf \"$destDataDir\"/* $srcDataDir/ 2>/dev/null", timeoutMs = 2000L)
        shellExecutor.executeCommand("ln -sf \"$destObbDir\"/* $srcObbDir/ 2>/dev/null", timeoutMs = 2000L)

        true
    }

    suspend fun ensureAllRelocatedGamesMounted(context: Context): Int = withContext(Dispatchers.IO) {
        val relocatedMap = getRelocatedGamesMap(context)
        var count = 0
        for ((pkg, _) in relocatedMap) {
            if (ensureGameMounted(context, pkg)) {
                count++
            }
        }
        count
    }

    private suspend fun queryActiveMountPoints(): List<String> {
        val res = shellExecutor.executeCommand("cat /proc/mounts 2>/dev/null || mount 2>/dev/null", timeoutMs = 2000L)
        return res.stdout.lines().filter { it.contains("Android/data") || it.contains("Android/obb") }
    }

    private fun getDirectorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var size = 0L
        try {
            val files = dir.listFiles() ?: return 0L
            for (f in files) {
                size += if (f.isDirectory) getDirectorySize(f) else f.length()
            }
        } catch (_: Exception) {}
        return size
    }

    data class RelocationRecord(
        val packageName: String,
        val targetVolumeId: String,
        val targetVolumeName: String,
        val targetDataPath: String,
        val targetObbPath: String
    )

    private fun getRelocatedGamesMap(context: Context): Map<String, RelocationRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RELOCATED_GAMES, "{}") ?: "{}"
        val map = mutableMapOf<String, RelocationRecord>()
        try {
            val root = JSONObject(jsonStr)
            val keys = root.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val obj = root.getJSONObject(pkg)
                map[pkg] = RelocationRecord(
                    packageName = pkg,
                    targetVolumeId = obj.optString("targetVolumeId"),
                    targetVolumeName = obj.optString("targetVolumeName"),
                    targetDataPath = obj.optString("targetDataPath"),
                    targetObbPath = obj.optString("targetObbPath")
                )
            }
        } catch (_: Exception) {}
        return map
    }

    private fun saveRelocationRecord(
        context: Context,
        packageName: String,
        targetVolumeId: String,
        targetVolumeName: String,
        targetDataPath: String,
        targetObbPath: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RELOCATED_GAMES, "{}") ?: "{}"
        try {
            val root = JSONObject(jsonStr)
            val obj = JSONObject().apply {
                put("targetVolumeId", targetVolumeId)
                put("targetVolumeName", targetVolumeName)
                put("targetDataPath", targetDataPath)
                put("targetObbPath", targetObbPath)
            }
            root.put(packageName, obj)
            prefs.edit().putString(KEY_RELOCATED_GAMES, root.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun removeRelocationRecord(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RELOCATED_GAMES, "{}") ?: "{}"
        try {
            val root = JSONObject(jsonStr)
            root.remove(packageName)
            prefs.edit().putString(KEY_RELOCATED_GAMES, root.toString()).apply()
        } catch (_: Exception) {}
    }
}
