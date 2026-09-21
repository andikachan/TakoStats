package ndika.monitor.booster.executor

import ndika.monitor.booster.model.ShellResult

interface IShizukuShellExecutor {
    fun isShizukuAlive(): Boolean
    fun isPermissionGranted(): Boolean
    fun isAvailable(): Boolean
    suspend fun execute(command: String, timeoutMs: Long = 10_000L): ShellResult
    suspend fun executeBatch(commands: List<String>, timeoutMs: Long = 20_000L): List<ShellResult>
}
