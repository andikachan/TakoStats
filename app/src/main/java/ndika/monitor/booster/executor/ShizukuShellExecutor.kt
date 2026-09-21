package ndika.monitor.booster.executor

import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ndika.monitor.booster.model.ShellResult
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class ShizukuShellExecutor : IShizukuShellExecutor {

    private var newProcessMethod: Method? = null

    init {
        resolveShizukuProcessMethod()
    }

    private fun resolveShizukuProcessMethod(): Method? {
        if (newProcessMethod != null) return newProcessMethod
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            newProcessMethod = method
            method
        } catch (_: Exception) {
            null
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    override fun hasPermission(): Boolean {
        return try {
            if (isAvailable()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun execute(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShellResult.failure("Shizuku binder is not available or disconnected.")
        }
        if (!hasPermission()) {
            return@withContext ShellResult.failure("Shizuku permission has not been granted by user.")
        }

        var process: Process? = null

        try {
            val executionResult = withTimeoutOrNull(timeoutMs) {
                coroutineScope {
                    val method = resolveShizukuProcessMethod()
                        ?: return@coroutineScope ShellResult.failure("Shizuku newProcess method reflection failed.")

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
                    } catch (e: Exception) {
                        return@coroutineScope ShellResult.failure("Process spawn error: ${e.message}")
                    }

                    val proc = process
                        ?: return@coroutineScope ShellResult.failure("Failed to instantiate Shizuku process.")

                    // Asynchronously read stdout and stderr to prevent OS stream pipe deadlock
                    val stdoutDeferred = async(Dispatchers.IO) {
                        try {
                            proc.inputStream.bufferedReader().use(BufferedReader::readText)
                        } catch (_: IOException) {
                            ""
                        }
                    }
                    val stderrDeferred = async(Dispatchers.IO) {
                        try {
                            proc.errorStream.bufferedReader().use(BufferedReader::readText)
                        } catch (_: IOException) {
                            ""
                        }
                    }

                    val exitCode = proc.waitFor()
                    val stdoutText = stdoutDeferred.await().trim()
                    val stderrText = stderrDeferred.await().trim()

                    ShellResult(
                        isSuccess = (exitCode == 0),
                        exitCode = exitCode,
                        stdout = stdoutText,
                        stderr = stderrText
                    )
                }
            }

            if (executionResult == null) {
                process?.destroyForcibly()
                ShellResult.failure("Execution timed out after ${timeoutMs}ms for: $command", exitCode = -1)
            } else {
                executionResult
            }
        } catch (e: TimeoutCancellationException) {
            process?.destroyForcibly()
            ShellResult.failure("Command timed out: ${e.message}", exitCode = -1)
        } catch (e: SecurityException) {
            process?.destroyForcibly()
            ShellResult.failure("Shizuku SecurityException: ${e.message}", exitCode = -1)
        } catch (e: DeadObjectException) {
            process?.destroyForcibly()
            ShellResult.failure("Shizuku DeadObjectException: Binder died", exitCode = -1)
        } catch (e: RemoteException) {
            process?.destroyForcibly()
            ShellResult.failure("Shizuku RemoteException: ${e.message}", exitCode = -1)
        } catch (e: Exception) {
            process?.destroyForcibly()
            ShellResult.failure("Shell execution failed: ${e.message}", exitCode = -1)
        }
    }
}
