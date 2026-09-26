package ndika.monitor.storage.model

import android.graphics.drawable.Drawable
import ndika.monitor.storage.StorageManager

data class GameStorageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isGameCategory: Boolean = false,
    val systemDataSizeBytes: Long = 0L,
    val dataSizeBytes: Long = 0L,
    val obbSizeBytes: Long = 0L,
    val totalSizeBytes: Long = 0L,
    val isRelocated: Boolean = false,
    val targetVolumeId: String? = null,
    val targetVolumeName: String? = null,
    val targetSystemDataPath: String? = null,
    val targetDataPath: String? = null,
    val targetObbPath: String? = null,
    val isMounted: Boolean = false
) {
    val systemDataSizeFormatted: String
        get() = StorageManager.formatFileSize(systemDataSizeBytes)

    val dataSizeFormatted: String
        get() = StorageManager.formatFileSize(dataSizeBytes)

    val obbSizeFormatted: String
        get() = StorageManager.formatFileSize(obbSizeBytes)

    val totalSizeFormatted: String
        get() = StorageManager.formatFileSize(totalSizeBytes)

    val sizeSummary: String
        get() = if (systemDataSizeBytes > 0L) {
            "Sys Data (/data): $systemDataSizeFormatted | Data: $dataSizeFormatted | OBB: $obbSizeFormatted\nTotal: $totalSizeFormatted"
        } else {
            "Data: $dataSizeFormatted | OBB: $obbSizeFormatted (Total: $totalSizeFormatted)"
        }
}
