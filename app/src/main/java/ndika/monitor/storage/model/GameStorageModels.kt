package ndika.monitor.storage.model

import android.graphics.drawable.Drawable
import ndika.monitor.storage.StorageManager

data class GameStorageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isGameCategory: Boolean = false,
    val dataSizeBytes: Long = 0L,
    val obbSizeBytes: Long = 0L,
    val totalSizeBytes: Long = 0L,
    val isRelocated: Boolean = false,
    val targetVolumeId: String? = null,
    val targetVolumeName: String? = null,
    val targetDataPath: String? = null,
    val targetObbPath: String? = null,
    val isMounted: Boolean = false
) {
    val dataSizeFormatted: String
        get() = StorageManager.formatFileSize(dataSizeBytes)

    val obbSizeFormatted: String
        get() = StorageManager.formatFileSize(obbSizeBytes)

    val totalSizeFormatted: String
        get() = StorageManager.formatFileSize(totalSizeBytes)

    val sizeSummary: String
        get() = "Data: $dataSizeFormatted | OBB: $obbSizeFormatted (Total: $totalSizeFormatted)"
}
