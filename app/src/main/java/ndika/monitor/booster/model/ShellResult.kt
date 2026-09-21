package ndika.monitor.booster.model

/**
 * Result data class for shell command execution.
 */
data class ShellResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionDurationMs: Long,
    val errorMessage: String? = null
) {
    val output: String
        get() = if (stdout.isNotBlank()) stdout.trim() else stderr.trim()

    companion object {
        fun failure(error: String, exitCode: Int = -1, durationMs: Long = 0L): ShellResult {
            return ShellResult(
                isSuccess = false,
                exitCode = exitCode,
                stdout = "",
                stderr = error,
                executionDurationMs = durationMs,
                errorMessage = error
            )
        }

        fun success(output: String, durationMs: Long = 0L): ShellResult {
            return ShellResult(
                isSuccess = true,
                exitCode = 0,
                stdout = output,
                stderr = "",
                executionDurationMs = durationMs
            )
        }
    }
}
