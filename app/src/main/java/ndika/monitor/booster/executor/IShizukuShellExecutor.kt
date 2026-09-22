package ndika.monitor.booster.executor

import ndika.monitor.booster.model.ShellResult

/**
 * Interface defining asynchronous ADB shell execution via Shizuku.
 */
interface IShizukuShellExecutor {
    /**
     * Executes an ADB shell command asynchronously with a timeout.
     */
    suspend fun executeCommand(command: String, timeoutMs: Long = 10000L): ShellResult

    /**
     * Checks if the Shizuku IPC binder service is alive.
     */
    fun isShizukuAvailable(): Boolean

    /**
     * Checks if the caller application has been granted runtime permission by Shizuku.
     */
    fun hasPermission(): Boolean
}
