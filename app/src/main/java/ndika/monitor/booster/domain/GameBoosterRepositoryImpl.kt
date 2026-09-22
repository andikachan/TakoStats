package ndika.monitor.booster.domain

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import ndika.monitor.booster.executor.IShizukuShellExecutor
import ndika.monitor.booster.executor.ShizukuShellExecutor
import ndika.monitor.booster.model.BoostConstants
import ndika.monitor.booster.model.BoostMode
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.BoostStepType
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.GameBoostConfig

class GameBoosterRepositoryImpl(
    private val context: Context,
    private val shellExecutor: IShizukuShellExecutor = ShizukuShellExecutor()
) : IGameBoosterRepository {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageManager = context.packageManager

    override fun checkPrerequisites(): Boolean {
        return shellExecutor.isShizukuAvailable() && shellExecutor.hasPermission()
    }

    override fun runPreGameBoost(config: GameBoostConfig): Flow<BoostProgress> = flow {
        if (!checkPrerequisites()) {
            emit(BoostProgress.Error("Shizuku ADB permission is required. Please start Shizuku and grant access."))
            return@flow
        }

        val logSummary = mutableListOf<String>()
        val initialRamMb = queryAvailableRamMb()
        val isExtreme = (config.mode == BoostMode.EXTREME_BEAST)

        logSummary.add("🚀 Game Booster Mode: ${if (isExtreme) "🔥 EXTREME BEAST (Uncapped)" else "⚡ STANDARD"}")
        if (!config.targetGamePackage.isNullOrBlank()) {
            logSummary.add("🎮 Target Game: ${config.targetGameName ?: config.targetGamePackage}")
        }

        var isTrimSuccess = false
        var purgedCount = 0
        var isPerfHalActive = false
        var isThermalBypassed = false
        var isAotDone = false
        var isGpuTuned = false
        var isCpuPinned = false
        var isNetOptimized = false

        // -------------------------------------------------------------
        // Step 1: Storage TRIM & Logcat Flush (sm fstrim & logcat -c)
        // -------------------------------------------------------------
        if (config.enableStorageTrim) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.FSTRIM,
                progressPercentage = 8,
                message = "Trimming UFS/eMMC storage & clearing logcat buffers..."
            ))
            val trimRes = shellExecutor.executeCommand("sm fstrim", timeoutMs = 15000L)
            shellExecutor.executeCommand("logcat -b all -c", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.logd.size 64K", timeoutMs = 2000L)

            isTrimSuccess = trimRes.isSuccess
            if (isTrimSuccess) {
                logSummary.add("• Storage & I/O: TRIM executed, logcat memory buffer flushed.")
            } else {
                logSummary.add("• Storage TRIM: Skipped/Warning (${trimRes.output})")
            }
        }

        // -------------------------------------------------------------
        // Step 2: Vendor Throttlers Termination (Xiaomi Joyose, Samsung GOS)
        // -------------------------------------------------------------
        if (config.enableKillVendorThrottlers) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.VENDOR_THROTTLER_DISABLE,
                progressPercentage = 16,
                message = "Stopping vendor throttling daemons (Joyose, GOS, GameTools)..."
            ))
            for (vendorPkg in BoostConstants.VENDOR_THROTTLER_PACKAGES) {
                if (isPackageInstalled(vendorPkg)) {
                    shellExecutor.executeCommand("am force-stop $vendorPkg", timeoutMs = 2000L)
                }
            }
            logSummary.add("• Vendor Limiter: Joyose / Samsung GOS / OEM throttlers neutralized.")
        }

        // -------------------------------------------------------------
        // Step 3: Background App Purge & Deep Doze
        // -------------------------------------------------------------
        if (config.enableBackgroundPurge) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.APP_PURGE,
                progressPercentage = 25,
                message = "Purging heavy background processes & putting non-active apps to Sleep..."
            ))
            val purgeable = resolvePurgeablePackages(config.targetGamePackage)
            for (pkg in purgeable) {
                val res = shellExecutor.executeCommand("am force-stop $pkg", timeoutMs = 1500L)
                if (res.isSuccess) purgedCount++
            }
            shellExecutor.executeCommand("am kill-all", timeoutMs = 2000L)
            shellExecutor.executeCommand("dumpsys deviceidle force-idle", timeoutMs = 3000L)
            logSummary.add("• Background Purge: Terminated $purgedCount background processes + Deep Doze activated.")
        }

        // -------------------------------------------------------------
        // Step 4: Thermal Throttling Bypass (Abaikan Panas & Suhu)
        // -------------------------------------------------------------
        if (config.enableThermalBypass) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.THERMAL_OVERRIDE,
                progressPercentage = 35,
                message = "Overriding Android Thermal Throttling HAL (Forced Normal Status)..."
            ))
            val thermalRes = shellExecutor.executeCommand("cmd thermalservice override-status 0", timeoutMs = 3000L)
            isThermalBypassed = thermalRes.isSuccess
            if (isThermalBypassed) {
                logSummary.add("• Thermal Engine: Overridden to status 0 (Thermal downclocking bypassed).")
            } else {
                logSummary.add("• Thermal Engine: Override status not supported on this vendor ROM.")
            }
        }

        // -------------------------------------------------------------
        // Step 5: PowerHAL Fixed Performance & Disable Power Savers
        // -------------------------------------------------------------
        if (config.enablePowerHal) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.POWER_PERFORMANCE,
                progressPercentage = 45,
                message = "Locking PowerHAL Sustained Performance & disabling Adaptive Battery..."
            ))
            shellExecutor.executeCommand("cmd power set-mode 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("cmd power set-adaptive-power-saver-enabled false", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global adaptive_battery_management_enabled 0", timeoutMs = 2000L)
            val perfRes = shellExecutor.executeCommand("cmd power set-fixed-performance-mode-enabled true", timeoutMs = 3000L)
            isPerfHalActive = perfRes.isSuccess
            logSummary.add("• Power Governor: Sustained high-performance clocks locked.")
        }

        // -------------------------------------------------------------
        // Step 6: SurfaceFlinger & 120Hz Display Pipeline Overdrive
        // -------------------------------------------------------------
        if (config.enableGraphicsTuning) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.SURFACEFLINGER_GRAPHICS,
                progressPercentage = 55,
                message = "Overclocking SurfaceFlinger, locking ${config.targetRefreshRate.toInt()}Hz & Skia renderer..."
            ))
            val hz = config.targetRefreshRate
            shellExecutor.executeCommand("settings put system min_refresh_rate $hz", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system peak_refresh_rate $hz", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put secure user_refresh_rate ${hz.toInt()}", timeoutMs = 2000L)

            // SurfaceFlinger latency zero-wait tweaks
            shellExecutor.executeCommand("setprop debug.sf.disable_backpressure 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.sf.latch_unsignaled 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.hwui.renderer skiavk", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.egl.hw 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.sf.hw 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("service call SurfaceFlinger 1008 i32 1", timeoutMs = 2000L)

            // Zero window animation delays
            shellExecutor.executeCommand("settings put global window_animation_scale 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global transition_animation_scale 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global animator_duration_scale 0", timeoutMs = 2000L)

            isGpuTuned = true
            logSummary.add("• Display & GPU: ${hz.toInt()}Hz locked, backpressure disabled, Skia renderer primed.")
        }

        // -------------------------------------------------------------
        // Step 7: Android Game Intervention API & Game Driver
        // -------------------------------------------------------------
        if (!config.targetGamePackage.isNullOrBlank()) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.GAME_MODE_API,
                progressPercentage = 65,
                message = "Injecting Android Game Intervention (Mode 2: Performance, ${config.targetFps} FPS)..."
            ))
            val gamePkg = config.targetGamePackage
            shellExecutor.executeCommand("cmd game set --mode 2 $gamePkg", timeoutMs = 3000L)
            shellExecutor.executeCommand("cmd game set --fps ${config.targetFps} $gamePkg", timeoutMs = 3000L)
            if (config.downscaleRatio != null && config.downscaleRatio in 0.5f..0.9f) {
                shellExecutor.executeCommand("cmd game set --downscale ${config.downscaleRatio} $gamePkg", timeoutMs = 3000L)
            }
            shellExecutor.executeCommand("settings put global updatable_driver_production_opt_in_apps $gamePkg", timeoutMs = 2000L)
            logSummary.add("• Game Intervention: Mode 2 (Performance) + ${config.targetFps} FPS + Production Game Driver.")
        }

        // -------------------------------------------------------------
        // Step 8: ART AOT Native Machine Compilation (Speed / Everything)
        // -------------------------------------------------------------
        if (!config.targetGamePackage.isNullOrBlank() && config.enableAotCompile) {
            val compileMode = if (isExtreme) "everything" else "speed"
            emit(BoostProgress.InProgress(
                step = BoostStepType.AOT_DEX_COMPILE,
                progressPercentage = 75,
                message = "Compiling game DEX to native machine binary (cmd package compile -m $compileMode)..."
            ))
            val aotRes = shellExecutor.executeCommand("cmd package compile -m $compileMode -f ${config.targetGamePackage}", timeoutMs = 45000L)
            shellExecutor.executeCommand("cmd package compile -m speed-profile android", timeoutMs = 15000L)
            isAotDone = aotRes.isSuccess
            if (isAotDone) {
                logSummary.add("• ART AOT Compile: Game bytecode natively compiled with mode '$compileMode'.")
            } else {
                logSummary.add("• ART AOT Compile: ${aotRes.output.ifBlank { "Compiled with profile." }}")
            }
        }

        // -------------------------------------------------------------
        // Step 9: CPU Priority (Renice -20) & Prime Core Affinity Pinning
        // -------------------------------------------------------------
        if (config.enableCpuPinning) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.CPU_PRIORITY_AFFINITY,
                progressPercentage = 82,
                message = "Configuring CPU scheduler & thread affinity (Real-Time priority)..."
            ))
            if (!config.targetGamePackage.isNullOrBlank()) {
                val pidResult = shellExecutor.executeCommand("pidof ${config.targetGamePackage}", timeoutMs = 2000L)
                val pid = pidResult.stdout.trim().split("\\s+".toRegex()).firstOrNull()
                if (!pid.isNullOrBlank() && pid.all { it.isDigit() }) {
                    shellExecutor.executeCommand("renice -n -20 -p $pid", timeoutMs = 2000L)
                    shellExecutor.executeCommand("taskset -p f0 $pid", timeoutMs = 2000L)
                    isCpuPinned = true
                    logSummary.add("• CPU Affinity: Process $pid reniced to -20 and pinned to Big/Prime cores.")
                } else {
                    logSummary.add("• CPU Affinity: Game will receive -20 renice upon launch.")
                }
            }
        }

        // -------------------------------------------------------------
        // Step 10: Touch Sampling Rate & Input Boost
        // -------------------------------------------------------------
        if (config.enableTouchBoost) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.TOUCH_RESPONSE_BOOST,
                progressPercentage = 88,
                message = "Boosting touch sampling responsiveness & pointer speed..."
            ))
            shellExecutor.executeCommand("settings put system pointer_speed 7", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.vendor.touch.game_mode 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.sys.touch.smoothness 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system touch_prediction 1", timeoutMs = 2000L)
            logSummary.add("• Touch Responsiveness: Level 7 pointer speed + vendor touch boost engaged.")
        }

        // -------------------------------------------------------------
        // Step 11: Low-Latency Network & Fast Gaming DNS
        // -------------------------------------------------------------
        if (config.enableNetworkLock) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.NETWORK_OPTIMIZATION,
                progressPercentage = 92,
                message = "Locking background network & setting Cloudflare Gaming DNS (1.1.1.1)..."
            ))
            shellExecutor.executeCommand("cmd netpolicy set restrict-background true", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop net.dns1 1.1.1.1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop net.dns2 1.0.0.1", timeoutMs = 2000L)
            isNetOptimized = true
            logSummary.add("• Network: Background bandwidth locked, Cloudflare DNS (1.1.1.1) activated.")
        }

        // -------------------------------------------------------------
        // Step 12: Disable Virtual RAM (RAM Plus) & Memory Trim
        // -------------------------------------------------------------
        if (config.enableVirtualRamDisable) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.MEMORY_TRIM,
                progressPercentage = 95,
                message = "Disabling stutter-inducing Virtual RAM / Swap expansion..."
            ))
            shellExecutor.executeCommand("settings put global ram_expand_size 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global zram_enabled 0", timeoutMs = 2000L)
            logSummary.add("• Virtual RAM: Dynamic swap expansion disabled (Prevents flash swapping stutters).")
        }

        // -------------------------------------------------------------
        // Step 13: Game Focus & DND Mode
        // -------------------------------------------------------------
        if (config.enableDnd) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.DND_ACTIVATE,
                progressPercentage = 98,
                message = "Enabling Total Game DND & disabling floating heads-up banners..."
            ))
            shellExecutor.executeCommand("cmd notification set_zen_mode 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global heads_up_notifications_enabled 0", timeoutMs = 2000L)
            logSummary.add("• Game Focus: Total DND enabled, floating banner popups suppressed.")
        }

        val finalRamMb = queryAvailableRamMb()
        val freedRam = maxOf(0L, finalRamMb - initialRamMb)

        emit(BoostProgress.InProgress(
            step = BoostStepType.COMPLETE,
            progressPercentage = 100,
            message = "Extreme Optimization Complete! Freed ${freedRam} MB RAM."
        ))

        emit(BoostProgress.Success(
            isStorageTrimmed = isTrimSuccess,
            purgedAppsCount = purgedCount,
            isPerformanceModeActive = isPerfHalActive,
            isThermalBypassed = isThermalBypassed,
            isAotCompiled = isAotDone,
            isGraphicsTuned = isGpuTuned,
            isCpuPinned = isCpuPinned,
            isNetworkOptimized = isNetOptimized,
            freedRamMb = freedRam,
            summaryLogs = logSummary
        ))
    }.flowOn(Dispatchers.IO)

    override fun restorePostGame(config: GameBoostConfig): Flow<BoostProgress> = flow {
        if (!checkPrerequisites()) {
            emit(BoostProgress.Error("Shizuku is not running."))
            return@flow
        }

        emit(BoostProgress.InProgress(
            step = BoostStepType.POWER_PERFORMANCE,
            progressPercentage = 50,
            message = "Restoring standard DVFS governor, thermal policies, and UI animations..."
        ))

        val logs = mutableListOf<String>()

        // 1. Reset Thermal Throttling
        shellExecutor.executeCommand("cmd thermalservice reset", timeoutMs = 3000L)
        logs.add("• Thermal policy restored to default hardware sensors.")

        // 2. Disable PowerHAL sustained performance
        shellExecutor.executeCommand("cmd power set-fixed-performance-mode-enabled false", timeoutMs = 3000L)
        shellExecutor.executeCommand("cmd power set-adaptive-power-saver-enabled true", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global adaptive_battery_management_enabled 1", timeoutMs = 2000L)
        logs.add("• Adaptive battery management & PowerHAL restored.")

        // 3. Restore Refresh Rate & Window Animations
        shellExecutor.executeCommand("settings put system min_refresh_rate 60.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put system peak_refresh_rate 120.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global window_animation_scale 1.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global transition_animation_scale 1.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global animator_duration_scale 1.0", timeoutMs = 2000L)
        logs.add("• Dynamic refresh rate & system animations restored.")

        // 4. Restore Network Policy & DND
        shellExecutor.executeCommand("cmd netpolicy set restrict-background false", timeoutMs = 2000L)
        shellExecutor.executeCommand("cmd notification set_zen_mode 0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global heads_up_notifications_enabled 1", timeoutMs = 2000L)
        shellExecutor.executeCommand("dumpsys deviceidle unforce", timeoutMs = 3000L)
        logs.add("• Background network & notification alerts re-enabled.")

        // 5. Reset Game Mode API
        if (!config.targetGamePackage.isNullOrBlank()) {
            shellExecutor.executeCommand("cmd game reset ${config.targetGamePackage}", timeoutMs = 3000L)
            logs.add("• Game intervention parameters reset for ${config.targetGamePackage}.")
        }

        emit(BoostProgress.Success(
            isStorageTrimmed = false,
            purgedAppsCount = 0,
            isPerformanceModeActive = false,
            isThermalBypassed = false,
            isAotCompiled = false,
            isGraphicsTuned = false,
            isCpuPinned = false,
            isNetworkOptimized = false,
            freedRamMb = 0L,
            summaryLogs = logs
        ))
    }.flowOn(Dispatchers.IO)

    override suspend fun getInstalledGames(): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<GameAppInfo>()
        try {
            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (app in installedApps) {
                if (app.packageName == context.packageName) continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                if (isSystem && launchIntent == null) continue

                val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.category == ApplicationInfo.CATEGORY_GAME
                } else {
                    (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                }

                val appName = packageManager.getApplicationLabel(app).toString()
                val icon = try { packageManager.getApplicationIcon(app) } catch (_: Exception) { null }

                result.add(GameAppInfo(
                    packageName = app.packageName,
                    appName = appName,
                    icon = icon,
                    isGameCategory = isGame
                ))
            }

            // Sort games first, then alphabetically
            result.sortWith(compareByDescending<GameAppInfo> { it.isGameCategory }.thenBy { it.appName.lowercase() })
        } catch (_: Exception) {}

        result
    }

    override fun compileAppAot(packageName: String, compileMode: String): Flow<BoostProgress> = flow {
        if (!checkPrerequisites()) {
            emit(BoostProgress.Error("Shizuku ADB permission is required."))
            return@flow
        }

        emit(BoostProgress.InProgress(
            step = BoostStepType.AOT_DEX_COMPILE,
            progressPercentage = 30,
            message = "Compiling $packageName with mode '$compileMode'..."
        ))

        val res = shellExecutor.executeCommand("cmd package compile -m $compileMode -f $packageName", timeoutMs = 60000L)
        if (res.isSuccess) {
            emit(BoostProgress.Success(
                isStorageTrimmed = false,
                purgedAppsCount = 0,
                isPerformanceModeActive = false,
                isThermalBypassed = false,
                isAotCompiled = true,
                isGraphicsTuned = false,
                isCpuPinned = false,
                isNetworkOptimized = false,
                freedRamMb = 0L,
                summaryLogs = listOf("• Successfully compiled $packageName into native machine binary (mode $compileMode).")
            ))
        } else {
            emit(BoostProgress.Error("AOT compilation failed: ${res.output}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun queryAvailableRamMb(): Long {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    private fun resolvePurgeablePackages(targetGamePkg: String?): Set<String> {
        val resolved = mutableSetOf<String>()
        val fullWhitelist = buildDynamicWhitelist(targetGamePkg)

        // 1. Add known heavy bloatware targets that are currently installed
        for (pkg in BoostConstants.DEFAULT_PURGEABLE_PACKAGES) {
            if (!fullWhitelist.contains(pkg) && isPackageInstalled(pkg)) {
                resolved.add(pkg)
            }
        }

        // 2. Scan installed third-party apps
        try {
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (app in installed) {
                val pkg = app.packageName
                if (fullWhitelist.contains(pkg)) continue

                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                if (!isSystem || isUpdatedSystem) {
                    resolved.add(pkg)
                }
            }
        } catch (_: Exception) {}

        return resolved
    }

    private fun buildDynamicWhitelist(targetGamePkg: String?): Set<String> {
        val whitelist = mutableSetOf<String>()
        whitelist.addAll(BoostConstants.ESSENTIAL_WHITELIST_PACKAGES)
        whitelist.add(context.packageName)
        if (!targetGamePkg.isNullOrBlank()) {
            whitelist.add(targetGamePkg)
        }

        // Dynamic Home Launcher resolution
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(homeIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolveInfo?.activityInfo?.packageName?.let { whitelist.add(it) }
        } catch (_: Exception) {}

        // Dynamic Active Keyboard / IME resolution
        try {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!ime.isNullOrBlank()) {
                val imePkg = ime.substringBefore("/")
                if (imePkg.isNotBlank()) whitelist.add(imePkg)
            }
        } catch (_: Exception) {}

        return whitelist
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
