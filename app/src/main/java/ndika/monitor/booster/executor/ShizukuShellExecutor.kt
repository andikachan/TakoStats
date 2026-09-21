package ndika.monitor.booster.executor

import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ndika.monitor.booster.model.ShellResult
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

class ShizukuShellExecutor : IShizukuShellExecutor {

    private var newProcessMethod: Method? = null

    init {
        findShizukuNewProcessMethod()
    }

    private fun findShizukuNewProcessMethod() {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            newProcessMethod = method
        } catch (_: Exception) {}
    }

    override fun isShizukuAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override fun isPermissionGranted(): Boolean {
        return try {
            if (isShizukuAlive()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun isAvailable(): Boolean {
        return isShizukuAlive() && isPermissionGranted()
    }

    override suspend fun execute(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!isShizukuAlive()) {
            return@withContext ShellResult.failure("Shizuku service is not running. Please start Shizuku.")
        }
        if (!isPermissionGranted()) {
            return@withContext ShellResult.failure("Shizuku permission has not been granted.")
        }

        val startTime = SystemClock.elapsedRealtime()
        var process: Process? = null

        try {
            val res = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val cmdArray = arrayOf("sh", "-c", command)
                    
                    if (newProcessMethod == null) {
                        findShizukuNewProcessMethod()
                    }
                    
                    val method = newProcessMethod 
                        ?: return@coroutineScope ShellResult.failure("Shizuku.newProcess API reflection failed.")

                    process = method.invoke(null, cmdArray, null, null) as? Process
                        ?: return@coroutineScope ShellResult.failure("Failed to spawn Shizuku process.")

                    val proc = process!!

                    // Asynchronously consume stdout and stderr to prevent OS pipe deadlock
                    val stdoutDeferred = async(Dispatchers.IO) {
                        proc.inputStream.bufferedReader().use(BufferedReader::readText)
                    }
                    val stderrDeferred = async(Dispatchers.IO) {
                        proc.errorStream.bufferedReader().use(BufferedReader::readText)
                    }

                    val exitCode = proc.waitFor()
                    val stdoutText = stdoutDeferred.await().trim()
                    val stderrText = stderrDeferred.await().trim()
                    val duration = SystemClock.elapsedRealtime() - startTime

                    ShellResult(
                        isSuccess = (exitCode == 0),
                        exitCode = exitCode,
                        stdout = stdoutText,
                        stderr = stderrText,
                        executionDurationMs = duration,
                        errorMessage = if (exitCode != 0 && stderrText.isNotBlank()) stderrText else null
                    )
                }
            }

            if (res == null) {
                process?.destroyForcibly()
                val duration = SystemClock.elapsedRealtime() - startTime
                ShellResult.failure("Command timed out after ${timeoutMs}ms: $command", exitCode = -1, durationMs = duration)
            } else {
                res
            }
        } catch (e: Exception) {
            process?.destroyForcibly()
            val duration = SystemClock.elapsedRealtime() - startTime
            ShellResult.failure("Shell execution error: ${e.message}", exitCode = -1, durationMs = duration)
        }
    }

    override suspend fun executeBatch(commands: List<String>, timeoutMs: Long): List<ShellResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ShellResult>()
        val singleTimeout = (timeoutMs / maxOf(1, commands.size)).coerceAtLeast(2000L)
        
        for (cmd in commands) {
            val res = execute(cmd, singleTimeout)
            results.add(res)
        }
        results
    }
}
