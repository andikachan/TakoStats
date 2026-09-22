package ndika.monitor.booster.executor

import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ndika.monitor.booster.model.ShellResult
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class ShizukuShellExecutor : IShizukuShellExecutor {

    private var newProcessMethod: Method? = null

    init {
        resolveShizukuProcessMethod()
    }

    private fun resolveShizukuProcessMethod() {
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

    override fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override fun hasPermission(): Boolean {
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

    override suspend fun executeCommand(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!isShizukuAvailable()) {
            return@withContext ShellResult.failure("Shizuku binder is not available or disconnected.")
        }
        if (!hasPermission()) {
            return@withContext ShellResult.failure("Shizuku permission has not been granted by user.")
        }

        val startTime = SystemClock.elapsedRealtime()
        var process: Process? = null

        try {
            val executionResult = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    if (newProcessMethod == null) {
                        resolveShizukuProcessMethod()
                    }

                    val method = newProcessMethod
                        ?: return@coroutineScope ShellResult.failure("Shizuku reflection method unresolved.")

                    val cmd = arrayOf("sh", "-c", command)
                    process = try {
                        method.invoke(null, cmd, null, null) as? Process
                    } catch (e: InvocationTargetException) {
                        val target = e.targetException
                        if (target is SecurityException) {
                            return@coroutineScope ShellResult.failure("Shizuku SecurityException: ${target.message}")
                        } else if (target is DeadObjectException || target is RemoteException) {
                            return@coroutineScope ShellResult.failure("Shizuku IPC DeadObjectException: Shizuku service was terminated.")
                        }
                        return@coroutineScope ShellResult.failure("Process spawn error: ${target?.message ?: e.message}")
                    } catch (e: SecurityException) {
                        return@coroutineScope ShellResult.failure("Shizuku SecurityException: ${e.message}")
                    } catch (e: IllegalStateException) {
                        return@coroutineScope ShellResult.failure("Shizuku IllegalStateException: ${e.message}")
                    }

                    val proc = process
                        ?: return@coroutineScope ShellResult.failure("Failed to instantiate Shizuku process.")

                    // Asynchronously read stdout and stderr to prevent pipe deadlock
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

            if (executionResult == null) {
                process?.destroyForcibly()
                val duration = SystemClock.elapsedRealtime() - startTime
                ShellResult.failure("Execution timed out after ${timeoutMs}ms for: $command", exitCode = -1, durationMs = duration)
            } else {
                executionResult
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroyForcibly()
            val duration = SystemClock.elapsedRealtime() - startTime
            ShellResult.failure("Command timed out: ${e.message}", exitCode = -1, durationMs = duration)
        } catch (e: SecurityException) {
            process?.destroyForcibly()
            val duration = SystemClock.elapsedRealtime() - startTime
            ShellResult.failure("Shizuku SecurityException: ${e.message}", exitCode = -1, durationMs = duration)
        } catch (e: Exception) {
            process?.destroyForcibly()
            val duration = SystemClock.elapsedRealtime() - startTime
            ShellResult.failure("Shell execution failed: ${e.message}", exitCode = -1, durationMs = duration)
        }
    }
}
