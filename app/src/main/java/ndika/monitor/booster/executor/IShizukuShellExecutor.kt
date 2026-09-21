package ndika.monitor.booster.executor

import ndika.monitor.booster.model.ShellResult

interface IShizukuShellExecutor {
    suspend fun execute(command: String, timeoutMs: Long = 15000): ShellResult
    fun isAvailable(): Boolean
    fun hasPermission(): Boolean
}
