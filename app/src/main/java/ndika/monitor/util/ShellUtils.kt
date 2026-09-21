package ndika.monitor.util

import ndika.monitor.shizuku.ShizukuManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellUtils {

    fun exec(command: String, preferElevated: Boolean = true): String {
        if (preferElevated && ShizukuManager.isPermissionGranted()) {
            val shizukuRes = execShizuku(command)
            if (shizukuRes.isNotEmpty()) {
                return shizukuRes
            }
        }

        if (preferElevated && isRootAvailable()) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                process.waitFor()
                val result = output.toString().trim()
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {}
        }

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun execShizuku(command: String): String {
        try {
            val process = ShizukuManager.newProcess(arrayOf("sh", "-c", command))
            if (process != null) {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                process.waitFor()
                return output.toString().trim()
            }
        } catch (_: Exception) {}
        return ""
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val out = reader.readLine() ?: ""
            process.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }
}
