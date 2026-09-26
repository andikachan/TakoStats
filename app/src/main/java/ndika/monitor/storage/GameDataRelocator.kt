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
    private const val MANIFEST_FILE_NAME = "relocation_manifest.json"

    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()

    suspend fun getInstalledGamesStorageInfo(context: Context): List<GameStorageInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GameStorageInfo>()
        val packageManager = context.packageManager
        val relocatedMap = getRelocatedGamesMap(context)
        val activeMounts = queryActiveMountPoints()
        val (shellSysSizes, shellDataSizes, shellObbSizes) = queryAllAndroidDataSizesViaShell()

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
                var sysDataSize = 0L
                var dataSize = 0L
                var obbSize = 0L

                if (isRelocated) {
                    val destSys = relocationRecord?.targetSystemDataPath
                    val destData = relocationRecord?.targetDataPath
                    val destObb = relocationRecord?.targetObbPath

                    val sSys = if (!destSys.isNullOrBlank()) queryPathSizeViaShell(destSys) else 0L
                    sysDataSize = if (sSys > 0L) sSys else if (!destSys.isNullOrBlank()) getDirectorySize(File(destSys)) else 0L

                    val sData = if (!destData.isNullOrBlank()) queryPathSizeViaShell(destData) else 0L
                    dataSize = if (sData > 0L) sData else if (!destData.isNullOrBlank()) getDirectorySize(File(destData)) else 0L

                    val sObb = if (!destObb.isNullOrBlank()) queryPathSizeViaShell(destObb) else 0L
                    obbSize = if (sObb > 0L) sObb else if (!destObb.isNullOrBlank()) getDirectorySize(File(destObb)) else 0L
                } else {
                    sysDataSize = shellSysSizes[app.packageName] ?: 0L
                    dataSize = shellDataSizes[app.packageName] ?: 0L
                    obbSize = shellObbSizes[app.packageName] ?: 0L

                    // Fallback 1: Package-specific shell query
                    if (sysDataSize <= 0L && dataSize <= 0L && obbSize <= 0L) {
                        val (pSys, pData, pObb) = queryPackageStorageViaShell(app.packageName)
                        if (pSys > 0L) sysDataSize = pSys
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
                    if (sysDataSize <= 0L) {
                        val sysDir = File("/data/data/${app.packageName}")
                        if (sysDir.exists()) {
                            sysDataSize = getDirectorySize(sysDir)
                        }
                    }

                    // Fallback 3: System StorageStatsManager (API 26+)
                    if (sysDataSize <= 0L && dataSize <= 0L && obbSize <= 0L) {
                        val statsSize = getStorageStatsDataSize(context, app.packageName)
                        if (statsSize > 0L) {
                            sysDataSize = statsSize
                        }
                    }

                    // Fallback 4: Base APK and Split APK sizes
                    if (sysDataSize <= 0L && dataSize <= 0L && obbSize <= 0L) {
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
                        systemDataSizeBytes = sysDataSize,
                        dataSizeBytes = dataSize,
                        obbSizeBytes = obbSize,
                        totalSizeBytes = sysDataSize + dataSize + obbSize,
                        isRelocated = isRelocated,
                        targetVolumeId = relocationRecord?.targetVolumeId,
                        targetVolumeName = relocationRecord?.targetVolumeName,
                        targetSystemDataPath = relocationRecord?.targetSystemDataPath,
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

    private suspend fun queryAllAndroidDataSizesViaShell(): Triple<Map<String, Long>, Map<String, Long>, Map<String, Long>> {
        val sysDataMap = mutableMapOf<String, Long>()
        val dataMap = mutableMapOf<String, Long>()
        val obbMap = mutableMapOf<String, Long>()
        try {
            val cmd = "du -sk /data/data/* /data/user/0/* /sdcard/Android/data/* /sdcard/Android/obb/* /storage/emulated/0/Android/data/* /storage/emulated/0/Android/obb/* /data/media/0/Android/data/* /data/media/0/Android/obb/* 2>/dev/null"
            val res = shellExecutor.executeCommand(cmd, timeoutMs = 8000L)
            parseDuLines(res.stdout, sysDataMap, dataMap, obbMap)
        } catch (_: Exception) {}
        return Triple(sysDataMap, dataMap, obbMap)
    }

    private suspend fun queryPackageStorageViaShell(pkg: String): Triple<Long, Long, Long> {
        var sysDataSize = 0L
        var dataSize = 0L
        var obbSize = 0L
        try {
            val cmd = "du -sk /data/data/$pkg /data/user/0/$pkg /sdcard/Android/data/$pkg /storage/emulated/0/Android/data/$pkg /data/media/0/Android/data/$pkg /sdcard/Android/obb/$pkg /storage/emulated/0/Android/obb/$pkg /data/media/0/Android/obb/$pkg 2>/dev/null"
            val res = shellExecutor.executeCommand(cmd, timeoutMs = 3000L)
            res.stdout.lines().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) {
                    val kb = parts[0].toLongOrNull() ?: 0L
                    val path = parts[1]
                    if (kb > 0L) {
                        val sizeBytes = kb * 1024L
                        if (path.contains("/data/data/") || path.contains("/data/user/0/")) {
                            if (sysDataSize == 0L) sysDataSize = sizeBytes
                        } else if (path.contains("/Android/data/") || path.contains("/Android/data")) {
                            if (dataSize == 0L) dataSize = sizeBytes
                        } else if (path.contains("/Android/obb/") || path.contains("/Android/obb")) {
                            if (obbSize == 0L) obbSize = sizeBytes
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return Triple(sysDataSize, dataSize, obbSize)
    }

    private fun parseDuLines(
        output: String,
        sysDataMap: MutableMap<String, Long>,
        dataMap: MutableMap<String, Long>,
        obbMap: MutableMap<String, Long>
    ) {
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
                    if (path.contains("/data/data/") || path.contains("/data/user/0/")) {
                        sysDataMap[pkg] = maxOf(sysDataMap[pkg] ?: 0L, sizeBytes)
                    } else if (path.contains("/Android/data") || path.contains("/data/media/0/Android/data")) {
                        dataMap[pkg] = (dataMap[pkg] ?: 0L) + sizeBytes
                    } else if (path.contains("/Android/obb") || path.contains("/data/media/0/Android/obb")) {
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

            val srcSysDir = "/data/data/$gamePkg"
            val srcUser0Dir = "/data/user/0/$gamePkg"
            val srcDataDir = "/sdcard/Android/data/$gamePkg"
            val srcObbDir = "/sdcard/Android/obb/$gamePkg"

            val targetRoot = targetVolume.path?.absolutePath
                ?: return@withContext Result.failure(Exception("Target storage path is not accessible."))

            val destSysDir = "$targetRoot/TakoStats/GameData/$gamePkg/system_data"
            val destDataDir = "$targetRoot/TakoStats/GameData/$gamePkg/data"
            val destObbDir = "$targetRoot/TakoStats/GameData/$gamePkg/obb"

            onProgress(10, "Creating destination directories on ${targetVolume.name}...")
            shellExecutor.executeCommand("mkdir -p \"$destSysDir\" \"$destDataDir\" \"$destObbDir\"", timeoutMs = 3000L)

            // 1. Direct Move System Data (/data/data/$pkg)
            onProgress(25, "Directly moving internal system /data/data to ${targetVolume.name}...")
            val copySysCmd = """
                mkdir -p "$destSysDir" 2>/dev/null
                if [ -d "$srcSysDir" ] && [ "$$(ls -A "$srcSysDir" 2>/dev/null)" ]; then
                    cp -rf "$srcSysDir/." "$destSysDir/" 2>/dev/null || cp -a "$srcSysDir/." "$destSysDir/" 2>/dev/null
                    if [ -d "$destSysDir" ] && [ "$$(ls -A "$destSysDir" 2>/dev/null)" ]; then
                        rm -rf "$srcSysDir" 2>/dev/null
                        mkdir -p "$srcSysDir" 2>/dev/null
                        chmod 777 "$srcSysDir" 2>/dev/null
                    fi
                elif [ -d "$srcUser0Dir" ] && [ "$$(ls -A "$srcUser0Dir" 2>/dev/null)" ]; then
                    cp -rf "$srcUser0Dir/." "$destSysDir/" 2>/dev/null || cp -a "$srcUser0Dir/." "$destSysDir/" 2>/dev/null
                    if [ -d "$destSysDir" ] && [ "$$(ls -A "$destSysDir" 2>/dev/null)" ]; then
                        rm -rf "$srcUser0Dir" 2>/dev/null
                        mkdir -p "$srcUser0Dir" 2>/dev/null
                        chmod 777 "$srcUser0Dir" 2>/dev/null
                    fi
                fi
            """.trimIndent()
            shellExecutor.executeCommand(copySysCmd, timeoutMs = 180000L)

            // 2. Direct Move Android Data
            onProgress(50, "Directly moving game data to ${targetVolume.name}...")
            val copyDataCmd = """
                mkdir -p "$destDataDir" 2>/dev/null
                cp -rf "$srcDataDir/." "$destDataDir/" 2>/dev/null || cp -rf "/data/media/0/Android/data/$gamePkg/." "$destDataDir/" 2>/dev/null || cp -a "$srcDataDir/." "$destDataDir/" 2>/dev/null
                if [ -d "$destDataDir" ] && [ "$$(ls -A "$destDataDir" 2>/dev/null)" ]; then
                    rm -rf "$srcDataDir" 2>/dev/null
                    rm -rf "/data/media/0/Android/data/$gamePkg" 2>/dev/null
                    mkdir -p "$srcDataDir" 2>/dev/null
                fi
            """.trimIndent()
            shellExecutor.executeCommand(copyDataCmd, timeoutMs = 180000L)

            // 3. Direct Move Android OBB
            onProgress(70, "Directly moving OBB assets to ${targetVolume.name}...")
            val copyObbCmd = """
                mkdir -p "$destObbDir" 2>/dev/null
                cp -rf "$srcObbDir/." "$destObbDir/" 2>/dev/null || cp -rf "/data/media/0/Android/obb/$gamePkg/." "$destObbDir/" 2>/dev/null || cp -a "$srcObbDir/." "$destObbDir/" 2>/dev/null
                if [ -d "$destObbDir" ] && [ "$$(ls -A "$destObbDir" 2>/dev/null)" ]; then
                    rm -rf "$srcObbDir" 2>/dev/null
                    rm -rf "/data/media/0/Android/obb/$gamePkg" 2>/dev/null
                    mkdir -p "$srcObbDir" 2>/dev/null
                fi
            """.trimIndent()
            shellExecutor.executeCommand(copyObbCmd, timeoutMs = 180000L)

            // 4. Verify destination files & calculate total freed size
            onProgress(80, "Verifying relocated files...")
            val destSysFile = File(destSysDir)
            val destDataFile = File(destDataDir)
            val destObbFile = File(destObbDir)
            var totalFreedBytes = queryPathSizeViaShell(destSysDir) + queryPathSizeViaShell(destDataDir) + queryPathSizeViaShell(destObbDir)
            if (totalFreedBytes <= 0L) {
                totalFreedBytes = getDirectorySize(destSysFile) + getDirectorySize(destDataFile) + getDirectorySize(destObbFile)
            }

            // 5. Ensure source mount points exist and permissions are set
            onProgress(85, "Creating empty mount points in internal storage...")
            shellExecutor.executeCommand(
                "mkdir -p \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null; chmod 777 \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null",
                timeoutMs = 3000L
            )

            // 6. Global & Local Mount Bind
            onProgress(92, "Mounting external directory links...")
            // System Data mount
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destSysDir\\\" \\\"$srcSysDir\\\"\" 2>/dev/null || mount -o bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null; mount --bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null",
                timeoutMs = 4000L
            )
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destSysDir\\\" \\\"$srcUser0Dir\\\"\" 2>/dev/null || mount -o bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null; mount --bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null",
                timeoutMs = 4000L
            )
            // Fallback symlinks for system data
            shellExecutor.executeCommand("ln -sfn \"$destSysDir\" \"$srcSysDir\" 2>/dev/null; ln -sf \"$destSysDir\"/* \"$srcSysDir/\" 2>/dev/null", timeoutMs = 3000L)
            shellExecutor.executeCommand("ln -sfn \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null; ln -sf \"$destSysDir\"/* \"$srcUser0Dir/\" 2>/dev/null", timeoutMs = 3000L)

            // Android Data mount
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destDataDir\\\" \\\"$srcDataDir\\\"\" 2>/dev/null || mount -o bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null; mount --bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null",
                timeoutMs = 4000L
            )
            shellExecutor.executeCommand("ln -sfn \"$destDataDir\" \"$srcDataDir\" 2>/dev/null; ln -sf \"$destDataDir\"/* \"$srcDataDir/\" 2>/dev/null", timeoutMs = 3000L)

            // Android OBB mount
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destObbDir\\\" \\\"$srcObbDir\\\"\" 2>/dev/null || mount -o bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null; mount --bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null",
                timeoutMs = 4000L
            )
            shellExecutor.executeCommand("ln -sfn \"$destObbDir\" \"$srcObbDir\" 2>/dev/null; ln -sf \"$destObbDir\"/* \"$srcObbDir/\" 2>/dev/null", timeoutMs = 3000L)

            // SELinux context & permissions
            shellExecutor.executeCommand("restorecon -R \"$srcSysDir\" \"$srcUser0Dir\" 2>/dev/null; chmod -R 775 \"$destSysDir\" \"$srcSysDir\" 2>/dev/null", timeoutMs = 3000L)

            // Notify MediaScanner
            shellExecutor.executeCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$srcDataDir\" 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$destDataDir\" 2>/dev/null", timeoutMs = 2000L)

            // 7. Save Persistent Relocation Record (SharedPreferences + External Storage Manifest)
            saveRelocationRecord(
                context = context,
                packageName = gamePkg,
                targetVolumeId = targetVolume.id,
                targetVolumeName = targetVolume.name,
                targetSystemDataPath = destSysDir,
                targetDataPath = destDataDir,
                targetObbPath = destObbDir
            )

            onProgress(100, "Game data successfully moved to ${targetVolume.name}!")
            val freedStr = StorageManager.formatFileSize(totalFreedBytes)
            Result.success("Successfully moved $gamePkg ($freedStr) directly to ${targetVolume.name}. Internal memory is now free.")
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

            val srcSysDir = "/data/data/$gamePkg"
            val srcUser0Dir = "/data/user/0/$gamePkg"
            val srcDataDir = "/sdcard/Android/data/$gamePkg"
            val srcObbDir = "/sdcard/Android/obb/$gamePkg"

            onProgress(20, "Unmounting external directory links...")
            shellExecutor.executeCommand(
                "nsenter -t 1 -m umount -l \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null || su -mm -c \"umount -l \\\"$srcSysDir\\\" \\\"$srcUser0Dir\\\" \\\"$srcDataDir\\\" \\\"$srcObbDir\\\"\" 2>/dev/null || umount -l \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null",
                timeoutMs = 4000L
            )
            shellExecutor.executeCommand("mkdir -p \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null", timeoutMs = 3000L)

            // Restore System Data
            if (!record.targetSystemDataPath.isNullOrBlank()) {
                onProgress(40, "Directly moving internal system /data/data back...")
                val restoreSysCmd = """
                    mkdir -p "$srcSysDir" "$srcUser0Dir" 2>/dev/null
                    cp -rf "${record.targetSystemDataPath}/." "$srcSysDir/" 2>/dev/null || cp -a "${record.targetSystemDataPath}/." "$srcSysDir/" 2>/dev/null
                    if [ -d "$srcSysDir" ] && [ "$$(ls -A "$srcSysDir" 2>/dev/null)" ]; then
                        rm -rf "${record.targetSystemDataPath}" 2>/dev/null
                        restorecon -R "$srcSysDir" "$srcUser0Dir" 2>/dev/null
                        chmod -R 775 "$srcSysDir" 2>/dev/null
                    fi
                """.trimIndent()
                shellExecutor.executeCommand(restoreSysCmd, timeoutMs = 180000L)
            }

            // Restore Android Data
            onProgress(60, "Directly moving game data back to internal storage...")
            if (!record.targetDataPath.isNullOrBlank()) {
                val restoreDataCmd = """
                    mkdir -p "$srcDataDir" 2>/dev/null
                    cp -rf "${record.targetDataPath}/." "$srcDataDir/" 2>/dev/null || cp -a "${record.targetDataPath}/." "$srcDataDir/" 2>/dev/null
                    if [ -d "$srcDataDir" ] && [ "$$(ls -A "$srcDataDir" 2>/dev/null)" ]; then
                        rm -rf "${record.targetDataPath}" 2>/dev/null
                    fi
                """.trimIndent()
                shellExecutor.executeCommand(restoreDataCmd, timeoutMs = 180000L)
            }

            // Restore Android OBB
            onProgress(80, "Directly moving OBB assets back to internal storage...")
            if (!record.targetObbPath.isNullOrBlank()) {
                val restoreObbCmd = """
                    mkdir -p "$srcObbDir" 2>/dev/null
                    cp -rf "${record.targetObbPath}/." "$srcObbDir/" 2>/dev/null || cp -a "${record.targetObbPath}/." "$srcObbDir/" 2>/dev/null
                    if [ -d "$srcObbDir" ] && [ "$$(ls -A "$srcObbDir" 2>/dev/null)" ]; then
                        rm -rf "${record.targetObbPath}" 2>/dev/null
                    fi
                """.trimIndent()
                shellExecutor.executeCommand(restoreObbCmd, timeoutMs = 180000L)
            }

            removeRelocationRecord(context, gamePkg)
            onProgress(100, "Game data successfully restored to internal storage.")
            Result.success("Restored $gamePkg directly back to internal phone memory.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun ensureGameMounted(context: Context, gamePkg: String): Boolean = withContext(Dispatchers.IO) {
        val record = getRelocatedGamesMap(context)[gamePkg] ?: return@withContext true
        val destSysDir = record.targetSystemDataPath
        val destDataDir = record.targetDataPath
        val destObbDir = record.targetObbPath

        val srcSysDir = "/data/data/$gamePkg"
        val srcUser0Dir = "/data/user/0/$gamePkg"
        val srcDataDir = "/sdcard/Android/data/$gamePkg"
        val srcObbDir = "/sdcard/Android/obb/$gamePkg"

        shellExecutor.executeCommand("mkdir -p \"$srcSysDir\" \"$srcUser0Dir\" \"$srcDataDir\" \"$srcObbDir\" 2>/dev/null", timeoutMs = 2000L)

        if (!destSysDir.isNullOrBlank()) {
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destSysDir\\\" \\\"$srcSysDir\\\"\" 2>/dev/null || mount -o bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null; mount --bind \"$destSysDir\" \"$srcSysDir\" 2>/dev/null",
                timeoutMs = 2000L
            )
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destSysDir\\\" \\\"$srcUser0Dir\\\"\" 2>/dev/null || mount -o bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null; mount --bind \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null",
                timeoutMs = 2000L
            )
            shellExecutor.executeCommand("ln -sfn \"$destSysDir\" \"$srcSysDir\" 2>/dev/null; ln -sf \"$destSysDir\"/* \"$srcSysDir/\" 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("ln -sfn \"$destSysDir\" \"$srcUser0Dir\" 2>/dev/null; ln -sf \"$destSysDir\"/* \"$srcUser0Dir/\" 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("restorecon -R \"$srcSysDir\" \"$srcUser0Dir\" 2>/dev/null; chmod -R 775 \"$destSysDir\" \"$srcSysDir\" 2>/dev/null", timeoutMs = 2000L)
        }

        if (!destDataDir.isNullOrBlank()) {
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destDataDir\\\" \\\"$srcDataDir\\\"\" 2>/dev/null || mount -o bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null; mount --bind \"$destDataDir\" \"$srcDataDir\" 2>/dev/null",
                timeoutMs = 2000L
            )
            shellExecutor.executeCommand("ln -sfn \"$destDataDir\" \"$srcDataDir\" 2>/dev/null; ln -sf \"$destDataDir\"/* \"$srcDataDir/\" 2>/dev/null", timeoutMs = 2000L)
        }

        if (!destObbDir.isNullOrBlank()) {
            shellExecutor.executeCommand(
                "nsenter -t 1 -m mount -o bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null || su -mm -c \"mount -o bind \\\"$destObbDir\\\" \\\"$srcObbDir\\\"\" 2>/dev/null || mount -o bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null; mount --bind \"$destObbDir\" \"$srcObbDir\" 2>/dev/null",
                timeoutMs = 2000L
            )
            shellExecutor.executeCommand("ln -sfn \"$destObbDir\" \"$srcObbDir\" 2>/dev/null; ln -sf \"$destObbDir\"/* \"$srcObbDir/\" 2>/dev/null", timeoutMs = 2000L)
        }

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
        return res.stdout.lines().filter {
            it.contains("Android/data") || it.contains("Android/obb") || it.contains("/data/data/") || it.contains("/data/user/0/") || it.contains("TakoStats")
        }
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
        val targetSystemDataPath: String? = null,
        val targetDataPath: String? = null,
        val targetObbPath: String? = null
    )

    private fun getRelocatedGamesMap(context: Context): Map<String, RelocationRecord> {
        val map = mutableMapOf<String, RelocationRecord>()

        // 1. Read from SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RELOCATED_GAMES, "{}") ?: "{}"
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
                    targetSystemDataPath = obj.optString("targetSystemDataPath").takeIf { it.isNotBlank() },
                    targetDataPath = obj.optString("targetDataPath").takeIf { it.isNotBlank() },
                    targetObbPath = obj.optString("targetObbPath").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {}

        // 2. Scan external storage drives and /sdcard for relocation_manifest.json to auto-restore if app data was cleared!
        try {
            val availableVolumes = StorageManager.getAvailableVolumes(context)
            for (vol in availableVolumes) {
                val root = vol.path ?: continue
                val manifestFile = File(root, "TakoStats/GameData/$MANIFEST_FILE_NAME")
                if (manifestFile.exists() && manifestFile.canRead()) {
                    try {
                        val content = manifestFile.readText()
                        val mRoot = JSONObject(content)
                        val mKeys = mRoot.keys()
                        while (mKeys.hasNext()) {
                            val pkg = mKeys.next()
                            val obj = mRoot.getJSONObject(pkg)
                            val rec = RelocationRecord(
                                packageName = pkg,
                                targetVolumeId = obj.optString("targetVolumeId", vol.id),
                                targetVolumeName = obj.optString("targetVolumeName", vol.name),
                                targetSystemDataPath = obj.optString("targetSystemDataPath", "${root.absolutePath}/TakoStats/GameData/$pkg/system_data").takeIf { it.isNotBlank() },
                                targetDataPath = obj.optString("targetDataPath", "${root.absolutePath}/TakoStats/GameData/$pkg/data").takeIf { it.isNotBlank() },
                                targetObbPath = obj.optString("targetObbPath", "${root.absolutePath}/TakoStats/GameData/$pkg/obb").takeIf { it.isNotBlank() }
                            )
                            map[pkg] = rec
                        }
                    } catch (_: Exception) {}
                } else {
                    // Fallback: Check if folders exist in <root>/TakoStats/GameData/
                    val gameDataDir = File(root, "TakoStats/GameData")
                    if (gameDataDir.exists() && gameDataDir.isDirectory) {
                        gameDataDir.listFiles()?.forEach { pkgDir ->
                            if (pkgDir.isDirectory && pkgDir.name.contains(".")) {
                                val pkg = pkgDir.name
                                if (!map.containsKey(pkg)) {
                                    val sysDataDir = File(pkgDir, "system_data")
                                    val dataDir = File(pkgDir, "data")
                                    val obbDir = File(pkgDir, "obb")
                                    if (sysDataDir.exists() || dataDir.exists() || obbDir.exists()) {
                                        map[pkg] = RelocationRecord(
                                            packageName = pkg,
                                            targetVolumeId = vol.id,
                                            targetVolumeName = vol.name,
                                            targetSystemDataPath = if (sysDataDir.exists()) sysDataDir.absolutePath else null,
                                            targetDataPath = if (dataDir.exists()) dataDir.absolutePath else null,
                                            targetObbPath = if (obbDir.exists()) obbDir.absolutePath else null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sync back to SharedPreferences
            saveAllRelocationRecords(context, map)
        } catch (_: Exception) {}

        return map
    }

    private fun saveRelocationRecord(
        context: Context,
        packageName: String,
        targetVolumeId: String,
        targetVolumeName: String,
        targetSystemDataPath: String?,
        targetDataPath: String?,
        targetObbPath: String?
    ) {
        val currentMap = getRelocatedGamesMap(context).toMutableMap()
        currentMap[packageName] = RelocationRecord(
            packageName = packageName,
            targetVolumeId = targetVolumeId,
            targetVolumeName = targetVolumeName,
            targetSystemDataPath = targetSystemDataPath,
            targetDataPath = targetDataPath,
            targetObbPath = targetObbPath
        )
        saveAllRelocationRecords(context, currentMap)
    }

    private fun removeRelocationRecord(context: Context, packageName: String) {
        val currentMap = getRelocatedGamesMap(context).toMutableMap()
        currentMap.remove(packageName)
        saveAllRelocationRecords(context, currentMap)
    }

    private fun saveAllRelocationRecords(context: Context, map: Map<String, RelocationRecord>) {
        val root = JSONObject()
        for ((pkg, rec) in map) {
            val obj = JSONObject().apply {
                put("targetVolumeId", rec.targetVolumeId)
                put("targetVolumeName", rec.targetVolumeName)
                put("targetSystemDataPath", rec.targetSystemDataPath ?: "")
                put("targetDataPath", rec.targetDataPath ?: "")
                put("targetObbPath", rec.targetObbPath ?: "")
            }
            root.put(pkg, obj)
        }
        val jsonStr = root.toString(2)

        // 1. Save to SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RELOCATED_GAMES, jsonStr).apply()

        // 2. Save to internal /sdcard/TakoStats/GameData/
        try {
            val internalManifest = File(Environment.getExternalStorageDirectory(), "TakoStats/GameData/$MANIFEST_FILE_NAME")
            internalManifest.parentFile?.mkdirs()
            internalManifest.writeText(jsonStr)
        } catch (_: Exception) {}

        // 3. Save to all available external drives
        try {
            val availableVolumes = StorageManager.getAvailableVolumes(context)
            for (vol in availableVolumes) {
                val vPath = vol.path ?: continue
                if (vol.type != StorageType.INTERNAL_APP) {
                    val extManifest = File(vPath, "TakoStats/GameData/$MANIFEST_FILE_NAME")
                    extManifest.parentFile?.mkdirs()
                    extManifest.writeText(jsonStr)
                }
            }
        } catch (_: Exception) {}
    }
}
