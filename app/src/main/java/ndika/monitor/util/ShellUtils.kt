package ndika.monitor.util

import ndika.monitor.shizuku.ShizukuManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellUtils {

    fun exec(command: String, preferElevated: Boolean = true): String {
        if (preferElevated && ShizukuManager.isPermissionGranted()) {
            try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
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
        } catch (e: Exception) {
            ""
        }
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
