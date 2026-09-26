package ndika.monitor.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import ndika.monitor.data.BenchmarkDatabaseHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.Locale

enum class StorageType {
    INTERNAL_APP,
    INTERNAL_SHARED,
    SD_CARD,
    USB_OTG,
    CUSTOM
}

data class StorageVolumeInfo(
    val id: String,
    val name: String,
    val description: String,
    val type: StorageType,
    val path: File?,
    val uriString: String? = null,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val isAvailable: Boolean = true,
    val isRemovable: Boolean = false
) {
    val freeSpaceFormatted: String
        get() = StorageManager.formatFileSize(freeBytes)

    val totalSpaceFormatted: String
        get() = StorageManager.formatFileSize(totalBytes)

    val capacitySummary: String
        get() = if (totalBytes > 0) "$freeSpaceFormatted free of $totalSpaceFormatted" else "Available"
}

object StorageManager {

    private const val PREF_STORAGE_ID = "selected_storage_volume_id"
    private const val PREF_CUSTOM_PATH = "custom_storage_path"
    private const val PREF_CUSTOM_URI = "custom_storage_uri"
    private const val PREF_AUTO_EXPORT_EXTERNAL = "auto_export_to_external"

    const val ID_INTERNAL_APP = "internal_app"
    const val ID_INTERNAL_SHARED = "internal_shared"
    const val ID_SD_CARD_PREFIX = "sd_card_"
    const val ID_USB_OTG_PREFIX = "usb_otg_"
    const val ID_CUSTOM = "custom_saf"

    fun getAvailableVolumes(context: Context): List<StorageVolumeInfo> {
        val list = mutableListOf<StorageVolumeInfo>()

        // 1. Internal Private App Storage
        val internalAppDir = context.filesDir
        val internalAppStats = getStatFsSafe(internalAppDir)
        list.add(
            StorageVolumeInfo(
                id = ID_INTERNAL_APP,
                name = "Internal App Storage",
                description = "Private sandbox: ${internalAppDir.absolutePath}",
                type = StorageType.INTERNAL_APP,
                path = internalAppDir,
                totalBytes = internalAppStats.first,
                freeBytes = internalAppStats.second,
                isAvailable = true,
                isRemovable = false
            )
        )

        // 2. Internal Shared Storage (/sdcard/TakoStats)
        val sharedDir = File(Environment.getExternalStorageDirectory(), "TakoStats")
        val sharedStats = getStatFsSafe(Environment.getExternalStorageDirectory())
        list.add(
            StorageVolumeInfo(
                id = ID_INTERNAL_SHARED,
                name = "Internal Shared Storage",
                description = "Public folder: /sdcard/TakoStats",
                type = StorageType.INTERNAL_SHARED,
                path = sharedDir,
                totalBytes = sharedStats.first,
                freeBytes = sharedStats.second,
                isAvailable = true,
                isRemovable = false
            )
        )

        // 3. Removable MicroSD Cards and USB OTG Flash Drives
        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
        var sdCardIndex = 1
        var usbIndex = 1

        for (i in 1 until externalDirs.size) {
            val dir = externalDirs[i] ?: continue
            val stats = getStatFsSafe(dir)
            val isRemovable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Environment.isExternalStorageRemovable(dir)
            } else {
                true
            }

            val pathStr = dir.absolutePath
            val isUsb = pathStr.contains("usb", ignoreCase = true) || pathStr.contains("otg", ignoreCase = true)

            val type = if (isUsb) StorageType.USB_OTG else StorageType.SD_CARD
            val name = if (isUsb) "USB Flash Drive (OTG $usbIndex)" else "MicroSD Card $sdCardIndex"
            val id = if (isUsb) "${ID_USB_OTG_PREFIX}$usbIndex" else "${ID_SD_CARD_PREFIX}$sdCardIndex"

            val takoDir = File(dir, "TakoStats")

            list.add(
                StorageVolumeInfo(
                    id = id,
                    name = name,
                    description = pathStr.substringBefore("/Android/data"),
                    type = type,
                    path = takoDir,
                    totalBytes = stats.first,
                    freeBytes = stats.second,
                    isAvailable = dir.canWrite() || dir.exists(),
                    isRemovable = isRemovable
                )
            )

            if (isUsb) usbIndex++ else sdCardIndex++
        }

        // 4. Custom Folder (if previously selected by user)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val customPath = prefs.getString(PREF_CUSTOM_PATH, null)
        val customUri = prefs.getString(PREF_CUSTOM_URI, null)

        if (!customPath.isNullOrBlank() || !customUri.isNullOrBlank()) {
            val customFile = if (!customPath.isNullOrBlank()) File(customPath) else null
            val stats = customFile?.let { getStatFsSafe(it) } ?: Pair(0L, 0L)
            list.add(
                StorageVolumeInfo(
                    id = ID_CUSTOM,
                    name = "Custom Folder",
                    description = customPath ?: customUri ?: "Custom chosen directory",
                    type = StorageType.CUSTOM,
                    path = customFile,
                    uriString = customUri,
                    totalBytes = stats.first,
                    freeBytes = stats.second,
                    isAvailable = true,
                    isRemovable = false
                )
            )
        }

        return list
    }

    fun getActiveVolume(context: Context): StorageVolumeInfo {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val selectedId = prefs.getString(PREF_STORAGE_ID, ID_INTERNAL_APP) ?: ID_INTERNAL_APP
        val volumes = getAvailableVolumes(context)

        return volumes.find { it.id == selectedId } ?: volumes.first()
    }

    fun setActiveVolume(
        context: Context,
        volumeId: String,
        customPath: String? = null,
        customUri: String? = null
    ) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().apply {
            putString(PREF_STORAGE_ID, volumeId)
            if (customPath != null) putString(PREF_CUSTOM_PATH, customPath)
            if (customUri != null) putString(PREF_CUSTOM_URI, customUri)
            apply()
        }
    }

    fun isAutoExportToExternal(context: Context): Boolean {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_AUTO_EXPORT_EXTERNAL, false)
    }

    fun setAutoExportToExternal(context: Context, enabled: Boolean) {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(PREF_AUTO_EXPORT_EXTERNAL, enabled).apply()
    }

    fun getExportDirectory(context: Context): File {
        val active = getActiveVolume(context)
        val baseDir = when (active.type) {
            StorageType.INTERNAL_APP -> File(context.filesDir, "exports")
            StorageType.INTERNAL_SHARED -> File(Environment.getExternalStorageDirectory(), "TakoStats/Exports")
            StorageType.SD_CARD, StorageType.USB_OTG -> File(active.path ?: context.filesDir, "Exports")
            StorageType.CUSTOM -> if (active.path != null) File(active.path, "Exports") else File(context.filesDir, "exports")
        }

        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    fun migrateData(
        context: Context,
        targetVolume: StorageVolumeInfo,
        onProgress: (progressPercent: Int, statusText: String) -> Unit
    ): Result<String> {
        return try {
            onProgress(10, "Preparing storage migration...")

            val targetBaseDir = when (targetVolume.type) {
                StorageType.INTERNAL_APP -> context.filesDir
                StorageType.INTERNAL_SHARED -> File(Environment.getExternalStorageDirectory(), "TakoStats")
                StorageType.SD_CARD, StorageType.USB_OTG -> targetVolume.path ?: context.filesDir
                StorageType.CUSTOM -> targetVolume.path ?: context.filesDir
            }

            if (!targetBaseDir.exists()) {
                targetBaseDir.mkdirs()
            }

            // 1. Migrate Database
            onProgress(30, "Migrating benchmark database...")
            val dbFile = context.getDatabasePath(BenchmarkDatabaseHelper.TABLE_RECORDS)
            val dbTargetFile = File(targetBaseDir, "ndimonitor_benchmarks_backup.db")

            var dbBytesCopied = 0L
            if (dbFile != null && dbFile.exists()) {
                dbBytesCopied = copyFile(dbFile, dbTargetFile)
            }

            // 2. Migrate Exported ZIP & CSV Files
            onProgress(60, "Migrating recorded benchmark files...")
            val currentExportDir = getExportDirectory(context)
            val targetExportDir = File(targetBaseDir, "Exports").apply { mkdirs() }

            var filesCount = 0
            var filesBytes = 0L

            if (currentExportDir.exists() && currentExportDir.absolutePath != targetExportDir.absolutePath) {
                val files = currentExportDir.listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.isFile) {
                        val dest = File(targetExportDir, f.name)
                        filesBytes += copyFile(f, dest)
                        filesCount++
                        f.delete() // Free up internal space
                    }
                }
            }

            onProgress(90, "Updating storage configuration...")
            setActiveVolume(
                context = context,
                volumeId = targetVolume.id,
                customPath = targetVolume.path?.absolutePath,
                customUri = targetVolume.uriString
            )

            onProgress(100, "Migration completed successfully!")
            val totalFreedStr = formatFileSize(dbBytesCopied + filesBytes)
            Result.success("Moved $filesCount files ($totalFreedStr) to ${targetVolume.name}.")
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun cleanTemporaryCaches(context: Context): Long {
        var freedBytes = 0L
        try {
            val cacheExportDir = File(context.cacheDir, "exports")
            if (cacheExportDir.exists()) {
                cacheExportDir.listFiles()?.forEach {
                    freedBytes += it.length()
                    it.delete()
                }
            }

            context.cacheDir.listFiles()?.forEach {
                if (it.name.endsWith(".tmp") || it.name.endsWith(".log")) {
                    freedBytes += it.length()
                    it.delete()
                }
            }
        } catch (_: Exception) {}
        return freedBytes
    }

    private fun copyFile(source: File, destination: File): Long {
        var bytesCopied = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesCopied += read
                }
                output.flush()
            }
        }
        return bytesCopied
    }

    private fun getStatFsSafe(file: File?): Pair<Long, Long> {
        if (file == null || !file.exists()) return Pair(0L, 0L)
        return try {
            val stat = StatFs(file.absolutePath)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            Pair(totalBlocks * blockSize, availableBlocks * blockSize)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}
