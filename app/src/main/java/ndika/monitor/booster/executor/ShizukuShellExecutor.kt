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
import java.io.InputStreamReader
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
        val startTime = SystemClock.elapsedRealtime()

        // 1. Try Shizuku if available and granted
        if (isShizukuAvailable() && hasPermission()) {
            val shizukuRes = executeViaShizuku(command, timeoutMs, startTime)
            if (shizukuRes.isSuccess || shizukuRes.stdout.isNotBlank()) {
                return@withContext shizukuRes
            }
        }

        // 2. Try Root (su)
        val rootRes = executeViaRoot(command, timeoutMs, startTime)
        if (rootRes.isSuccess || rootRes.stdout.isNotBlank()) {
            return@withContext rootRes
        }

        // 3. Fallback to standard process (sh)
        return@withContext executeViaStandardSh(command, timeoutMs, startTime)
    }

    private suspend fun executeViaShizuku(command: String, timeoutMs: Long, startTime: Long): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val executionResult = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    if (newProcessMethod == null) {
                        resolveShizukuProcessMethod()
                    }

                    val method = newProcessMethod
                        ?: return@coroutineScope ShellResult.failure("Shizuku reflection method unresolved.")

                    val cmd = arrayOf("sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; $command")
                    process = try {
                        method.invoke(null, cmd, null, null) as? Process
                    } catch (e: InvocationTargetException) {
                        val target = e.targetException
                        return@coroutineScope ShellResult.failure("Process spawn error: ${target?.message ?: e.message}")
                    } catch (e: Exception) {
                        return@coroutineScope ShellResult.failure("Shizuku error: ${e.message}")
                    }

                    val proc = process
                        ?: return@coroutineScope ShellResult.failure("Failed to instantiate Shizuku process.")

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
                ShellResult.failure("Shizuku timed out after ${timeoutMs}ms", exitCode = -1)
            } else {
                executionResult
            }
        } catch (e: Exception) {
            process?.destroyForcibly()
            ShellResult.failure("Shizuku execution failed: ${e.message}", exitCode = -1)
        }
    }

    private suspend fun executeViaRoot(command: String, timeoutMs: Long, startTime: Long): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val executionResult = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    process = Runtime.getRuntime().exec(arrayOf("su", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; $command"))
                    val proc = process ?: return@coroutineScope ShellResult.failure("Failed to spawn su process.")

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
                ShellResult.failure("su timed out after ${timeoutMs}ms", exitCode = -1)
            } else {
                executionResult
            }
        } catch (e: Exception) {
            process?.destroyForcibly()
            ShellResult.failure("su execution failed: ${e.message}", exitCode = -1)
        }
    }

    private suspend fun executeViaStandardSh(command: String, timeoutMs: Long, startTime: Long): ShellResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val executionResult = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "export PATH=/system/bin:/system/xbin:\$PATH; $command"))
                    val proc = process ?: return@coroutineScope ShellResult.failure("Failed to spawn sh process.")

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
                ShellResult.failure("sh timed out after ${timeoutMs}ms", exitCode = -1)
            } else {
                executionResult
            }
        } catch (e: Exception) {
            process?.destroyForcibly()
            ShellResult.failure("sh execution failed: ${e.message}", exitCode = -1)
        }
    }
}
