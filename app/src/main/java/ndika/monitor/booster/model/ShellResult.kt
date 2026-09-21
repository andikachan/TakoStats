package ndika.monitor.booster.model

data class ShellResult(
    val isSuccess: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val output: String
        get() = if (stdout.isNotBlank()) stdout else stderr

    companion object {
        fun success(stdout: String = "", exitCode: Int = 0): ShellResult =
            ShellResult(isSuccess = true, exitCode = exitCode, stdout = stdout, stderr = "")

        fun failure(stderr: String = "", exitCode: Int = -1, stdout: String = ""): ShellResult =
            ShellResult(isSuccess = false, exitCode = exitCode, stdout = stdout, stderr = stderr)
    }
}
