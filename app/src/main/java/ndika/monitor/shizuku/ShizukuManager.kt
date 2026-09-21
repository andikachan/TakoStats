package ndika.monitor.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            if (isShizukuAvailable()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (isShizukuAvailable() && !isPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (_: Exception) {}
    }

    fun newProcess(cmd: Array<String>): Process? {
        if (!isPermissionGranted()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (_: Exception) {
            null
        }
    }
}

