package ndika.monitor.booster.model

/**
 * Data class representing the result of a shell command execution via Shizuku.
 *
 * @param isSuccess True if the process exited with code 0.
 * @param exitCode The process exit code (0 for success, non-zero for failure).
 * @param stdout Standard output stream content.
 * @param stderr Standard error stream content.
 * @param executionDurationMs Time taken in milliseconds for the command to execute.
 * @param errorMessage Optional human-readable error description.
 */
data class ShellResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionDurationMs: Long = 0L,
    val errorMessage: String? = null
) {
    val output: String
        get() = if (stdout.isNotBlank()) stdout.trim() else stderr.trim()

    companion object {
        fun success(stdout: String = "", durationMs: Long = 0L): ShellResult {
            return ShellResult(
                isSuccess = true,
                exitCode = 0,
                stdout = stdout,
                stderr = "",
                executionDurationMs = durationMs
            )
        }

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
    }
}
