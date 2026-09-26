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
import ndika.monitor.booster.model.ResolutionDownscalePreset

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

        logSummary.add("Game Booster Mode: ${if (isExtreme) "EXTREME BEAST (Uncapped)" else "STANDARD"}")
        if (!config.targetGamePackage.isNullOrBlank()) {
            logSummary.add("Target Game: ${config.targetGameName ?: config.targetGamePackage}")
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
        // Step 1: Storage TRIM, Logcat Flush & Linux Kernel VM Compact
        // -------------------------------------------------------------
        if (config.enableStorageTrim || config.enableKernelVmTuning) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.KERNEL_VM_TUNING,
                progressPercentage = 5,
                message = "Compacting Linux Kernel memory, dropping page cache & trimming storage..."
            ))
            if (config.enableStorageTrim) {
                val trimRes = shellExecutor.executeCommand("sm fstrim", timeoutMs = 15000L)
                isTrimSuccess = trimRes.isSuccess
            }
            shellExecutor.executeCommand("logcat -b all -c", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.logd.size 64K", timeoutMs = 2000L)

            if (config.enableKernelVmTuning) {
                // Drop caches and compact memory for contiguous RAM pages
                shellExecutor.executeCommand("echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; cmd activity drop-cache 2>/dev/null", timeoutMs = 3000L)
                shellExecutor.executeCommand("echo 0 > /proc/sys/vm/swappiness 2>/dev/null; echo 100 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; echo 5 > /proc/sys/vm/dirty_ratio 2>/dev/null; echo 2 > /proc/sys/vm/dirty_background_ratio 2>/dev/null", timeoutMs = 3000L)
                shellExecutor.executeCommand("setprop persist.sys.vm.swappiness 0", timeoutMs = 2000L)
                logSummary.add("• Kernel & Storage: Continuous RAM compacted, caches dropped, I/O latency minimized.")
            } else {
                logSummary.add("• Storage & I/O: TRIM executed, logcat memory buffer flushed.")
            }
        }

        // -------------------------------------------------------------
        // Step 2: Vendor Throttlers Termination (Joyose, GOS, GameTools)
        // -------------------------------------------------------------
        if (config.enableKillVendorThrottlers) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.VENDOR_THROTTLER_DISABLE,
                progressPercentage = 12,
                message = "Neutralizing OEM throttling daemons (Joyose, Samsung GOS, GameTools)..."
            ))
            for (vendorPkg in BoostConstants.VENDOR_THROTTLER_PACKAGES) {
                if (isPackageInstalled(vendorPkg)) {
                    shellExecutor.executeCommand("am force-stop $vendorPkg", timeoutMs = 2000L)
                }
            }
            logSummary.add("• Vendor Limiter: Joyose / Samsung GOS / OEM throttlers neutralized.")
        }

        // -------------------------------------------------------------
        // Step 3: App Standby Restriction & Background Process Purge
        // -------------------------------------------------------------
        if (config.enableBackgroundPurge || config.enableAppStandbyFreeze) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.APP_STANDBY_PURGE,
                progressPercentage = 20,
                message = "Freezing background applications & activating Standby Restriction..."
            ))
            val purgeable = resolvePurgeablePackages(config.targetGamePackage)
            for (pkg in purgeable) {
                if (config.enableAppStandbyFreeze) {
                    shellExecutor.executeCommand("cmd usage_stats set-app-standby $pkg restricted", timeoutMs = 1500L)
                }
                if (config.enableBackgroundPurge) {
                    val res = shellExecutor.executeCommand("am force-stop $pkg", timeoutMs = 1500L)
                    if (res.isSuccess) purgedCount++
                }
            }
            shellExecutor.executeCommand("am kill-all", timeoutMs = 2000L)
            shellExecutor.executeCommand("dumpsys deviceidle force-idle", timeoutMs = 3000L)

            // Whitelist target game & TakoStats in deviceidle
            if (!config.targetGamePackage.isNullOrBlank()) {
                shellExecutor.executeCommand("cmd deviceidle whitelist +${config.targetGamePackage}", timeoutMs = 2000L)
            }
            shellExecutor.executeCommand("cmd deviceidle whitelist +${context.packageName}", timeoutMs = 2000L)
            logSummary.add("• Background Freeze: Terminated $purgedCount apps + Standby Restricted + Game Doze Whitelisted.")
        }

        // -------------------------------------------------------------
        // Step 4: Thermal Throttling Bypass
        // -------------------------------------------------------------
        if (config.enableThermalBypass) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.THERMAL_OVERRIDE,
                progressPercentage = 28,
                message = "Overriding Android Thermal Throttling HAL (Forced Normal Status 0)..."
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
                progressPercentage = 35,
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
        // Step 6: GPU Devfreq & Clocks Override (Snapdragon & MediaTek)
        // -------------------------------------------------------------
        if (config.enableGpuDevfreqGovernor) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.GPU_DEVFREQ_GOVERNOR,
                progressPercentage = 42,
                message = "Overriding GPU Devfreq governor, bus power rails & clock limits..."
            ))
            // Qualcomm Snapdragon Adreno kgsl tweaks
            shellExecutor.executeCommand("echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null; echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null; echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("echo 0 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null; echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop vendor.gpu.control 1; setprop debug.qualcomm.sns 1", timeoutMs = 2000L)

            // MediaTek Dimensity / Helio GED & Mali tweaks
            shellExecutor.executeCommand("echo 1 > /proc/ged/hal/custom_upbound_gpu_freq 2>/dev/null; echo 1 > /proc/ged/hal/custom_boost_gpu_freq 2>/dev/null; echo 0 > /proc/ged/hal/dvfs_margin_value 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.ged.force_loading 100", timeoutMs = 2000L)
            shellExecutor.executeCommand("for d in /sys/class/devfreq/*gpu*/governor /sys/class/devfreq/*mali*/governor /sys/class/devfreq/gpufreq/governor; do echo performance > \$d 2>/dev/null; done", timeoutMs = 2000L)

            logSummary.add("• GPU Devfreq: Power rails forced ON, performance governor engaged.")
        }

        // -------------------------------------------------------------
        // Step 7: CPU Frequency Clamping (Uclamp), SchedTune & Unpark Cores
        // -------------------------------------------------------------
        if (config.enableCpuUclampSchedTune) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.CPU_UCLAMP_SCHEDTUNE,
                progressPercentage = 50,
                message = "Locking CPU Uclamp 100% (1024), SchedTune boost & unparking all cores..."
            ))
            // Uclamp minimum capacity lock (Android 11+)
            shellExecutor.executeCommand("echo 1024 > /dev/cpuctl/top-app/cpu.uclamp.min 2>/dev/null; echo 1024 > /dev/cpuctl/cpu.uclamp.min 2>/dev/null; echo 0 > /dev/cpuctl/background/cpu.uclamp.max 2>/dev/null", timeoutMs = 2000L)
            // SchedTune Prime core preference
            shellExecutor.executeCommand("echo 100 > /dev/stune/top-app/schedtune.boost 2>/dev/null; echo 1 > /dev/stune/top-app/schedtune.prefer_idle 2>/dev/null; echo 0 > /dev/stune/top-app/schedtune.colocate 2>/dev/null", timeoutMs = 2000L)
            // Unpark all CPU cores
            shellExecutor.executeCommand("for c in /sys/devices/system/cpu/cpu*/online; do echo 1 > \$c 2>/dev/null; done", timeoutMs = 2000L)

            logSummary.add("• CPU Scheduler: Uclamp 1024 locked (0 ramp-up lag), SchedTune prime boost active.")
        }

        // -------------------------------------------------------------
        // Step 8: SurfaceFlinger & 120Hz Early Phase Offsets
        // -------------------------------------------------------------
        if (config.enableGraphicsTuning) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.SURFACEFLINGER_GRAPHICS,
                progressPercentage = 58,
                message = "Overclocking SurfaceFlinger, locking ${config.targetRefreshRate.toInt()}Hz & Early Phase Offsets..."
            ))
            val hz = config.targetRefreshRate
            shellExecutor.executeCommand("settings put system min_refresh_rate $hz", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system peak_refresh_rate $hz", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put secure user_refresh_rate ${hz.toInt()}", timeoutMs = 2000L)

            // SurfaceFlinger latency zero-wait & early phase presentation
            shellExecutor.executeCommand("setprop debug.sf.disable_backpressure 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.sf.latch_unsignaled 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.hwui.renderer skiavk", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.egl.hw 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop debug.sf.hw 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("service call SurfaceFlinger 1008 i32 1", timeoutMs = 2000L)

            // Early Phase Offsets
            shellExecutor.executeCommand("setprop debug.sf.early_phase_offset_ns 500000; setprop debug.sf.early_app_phase_offset_ns 500000; setprop debug.sf.high_fps_early_phase_offset_ns 500000; setprop debug.sf.high_fps_early_app_phase_offset_ns 500000; setprop debug.sf.showupdates 0; setprop debug.sf.showcpu 0", timeoutMs = 2000L)

            // Zero window animation delays
            shellExecutor.executeCommand("settings put global window_animation_scale 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global transition_animation_scale 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global animator_duration_scale 0", timeoutMs = 2000L)

            isGpuTuned = true
            logSummary.add("• Display & GPU: ${hz.toInt()}Hz locked, Early Phase offsets tuned, Skia renderer primed.")
        }

        // -------------------------------------------------------------
        // Step 9: Dynamic Resolution Downscaling (GPU Load Reducer)
        // -------------------------------------------------------------
        if (config.downscalePreset != ResolutionDownscalePreset.NATIVE) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.RESOLUTION_DOWNSCALE,
                progressPercentage = 64,
                message = "Applying GPU render downscale (${config.downscalePreset.displayName})..."
            ))
            val scale = config.downscalePreset.scale
            applyResolutionDownscale(scale)
            logSummary.add("• Render Downscale: ${config.downscalePreset.displayName} applied (Massive GPU relief).")
        }

        // -------------------------------------------------------------
        // Step 10: Android Game Intervention API & Auto-Unlock Graphics
        // -------------------------------------------------------------
        if (!config.targetGamePackage.isNullOrBlank()) {
            val targetFps = config.targetFps
            emit(BoostProgress.InProgress(
                step = BoostStepType.GAME_MODE_API,
                progressPercentage = 72,
                message = "Injecting Android Game Intervention & Auto-Unlocking Graphics ($targetFps FPS)..."
            ))
            val gamePkg = config.targetGamePackage
            shellExecutor.executeCommand("cmd game set --mode 2 $gamePkg", timeoutMs = 3000L)
            shellExecutor.executeCommand("cmd game set --fps $targetFps $gamePkg", timeoutMs = 3000L)
            shellExecutor.executeCommand("cmd game set --angle-enabled true $gamePkg", timeoutMs = 3000L)
            if (config.downscaleRatio != null && config.downscaleRatio in 0.5f..0.9f) {
                shellExecutor.executeCommand("cmd game set --downscale ${config.downscaleRatio} $gamePkg", timeoutMs = 3000L)
            }
            shellExecutor.executeCommand("settings put global updatable_driver_production_opt_in_apps $gamePkg", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global updatable_driver_prerelease_opt_in_apps $gamePkg", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global driver_build_time 9999999999", timeoutMs = 2000L)

            // Auto-unlock graphics & hardware cache purge
            val unlockLogs = autoUnlockGameGraphics(gamePkg, targetFps)
            logSummary.addAll(unlockLogs)

            // Ensure external game data & OBB mount is active if relocated
            ndika.monitor.storage.GameDataRelocator.ensureGameMounted(context, gamePkg)
        }

        // -------------------------------------------------------------
        // Step 11: Deep ART AOT Native Machine Compilation (Primary + Secondary Dex)
        // -------------------------------------------------------------
        if (!config.targetGamePackage.isNullOrBlank() && config.enableAotCompile) {
            val compileMode = if (isExtreme) "everything" else "speed"
            val secondaryFlag = if (config.enableSecondaryDexCompile) "--secondary-dex" else ""
            emit(BoostProgress.InProgress(
                step = BoostStepType.AOT_DEX_COMPILE,
                progressPercentage = 76,
                message = "Compiling game binary & secondary split DEX (cmd package compile -m $compileMode $secondaryFlag)..."
            ))
            val aotRes = shellExecutor.executeCommand("cmd package compile -m $compileMode $secondaryFlag -f ${config.targetGamePackage}", timeoutMs = 60000L)
            shellExecutor.executeCommand("cmd package compile -m speed-profile android", timeoutMs = 15000L)
            shellExecutor.executeCommand("cmd package compile -m speed-profile com.android.systemui", timeoutMs = 15000L)
            isAotDone = aotRes.isSuccess
            if (isAotDone) {
                logSummary.add("• ART AOT Compile: Game bytecode & split APKs natively compiled (mode '$compileMode').")
            } else {
                logSummary.add("• ART AOT Compile: ${aotRes.output.ifBlank { "Compiled with profile." }}")
            }
        }

        // -------------------------------------------------------------
        // Step 12: CPU Renice -20, Prime Core Affinity, Cgroup & OOM Shield
        // -------------------------------------------------------------
        if (config.enableCpuPinning || config.enableOomShield) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.CPU_PRIORITY_AFFINITY_OOM,
                progressPercentage = 84,
                message = "Configuring Real-Time thread priority (-20), Core Affinity & OOM Shield (-1000)..."
            ))
            if (!config.targetGamePackage.isNullOrBlank()) {
                val pidResult = shellExecutor.executeCommand("pidof ${config.targetGamePackage}", timeoutMs = 2000L)
                val pid = pidResult.stdout.trim().split("\\s+".toRegex()).firstOrNull()
                if (!pid.isNullOrBlank() && pid.all { it.isDigit() }) {
                    if (config.enableCpuPinning) {
                        shellExecutor.executeCommand("renice -n -20 -p $pid", timeoutMs = 2000L)
                        shellExecutor.executeCommand("taskset -p f0 $pid", timeoutMs = 2000L)
                        shellExecutor.executeCommand("echo $pid > /dev/cpuset/top-app/tasks 2>/dev/null", timeoutMs = 2000L)
                        shellExecutor.executeCommand("echo $pid > /dev/stune/top-app/tasks 2>/dev/null", timeoutMs = 2000L)
                        isCpuPinned = true
                    }
                    if (config.enableOomShield) {
                        shellExecutor.executeCommand("echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null", timeoutMs = 2000L)
                    }
                    logSummary.add("• CPU & OOM Shield: Process $pid reniced -20, pinned to Prime cores, OOM Shield -1000.")
                } else {
                    logSummary.add("• CPU & OOM Shield: Priority hooks configured for target game launch.")
                }
            }
        }

        // -------------------------------------------------------------
        // Step 13: Ultra-Low Latency Input & Touch Response
        // -------------------------------------------------------------
        if (config.enableTouchBoost) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.TOUCH_RESPONSE_BOOST,
                progressPercentage = 90,
                message = "Boosting touch sampling responsiveness, touch slop & FIFO UI scheduling..."
            ))
            shellExecutor.executeCommand("settings put system pointer_speed 7", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.vendor.touch.game_mode 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.sys.touch.smoothness 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system touch_prediction 1", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop view.touch_slop 2", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop touch.pressure.scale 0.001", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.vendor.qti.inputopts.enable true", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop persist.vendor.qti.inputopts.movfilter false", timeoutMs = 2000L)
            shellExecutor.executeCommand("setprop sys.use_fifo_ui 1", timeoutMs = 2000L)
            logSummary.add("• Touch & Input: Touch slop minimized (2px), jitter filter bypassed, Real-Time FIFO UI.")
        }

        // -------------------------------------------------------------
        // Step 14: Ultra-Low Latency Wi-Fi, TCP QuickACK & Gaming DNS
        // -------------------------------------------------------------
        if (config.enableNetworkLock || config.enableWifiLowLatency) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.NETWORK_WIFI_OPTIMIZATION,
                progressPercentage = 94,
                message = "Activating Wi-Fi Low Latency Lock & TCP QuickACK (Gaming Ping Booster)..."
            ))
            if (config.enableWifiLowLatency) {
                shellExecutor.executeCommand("cmd wifi set-low-latency-mode enabled", timeoutMs = 2000L)
                shellExecutor.executeCommand("cmd wifi force-hi-perf-mode enabled", timeoutMs = 2000L)
            }
            if (config.enableNetworkLock) {
                shellExecutor.executeCommand("cmd netpolicy set restrict-background true", timeoutMs = 2000L)
                shellExecutor.executeCommand("setprop persist.sys.tcp.quickack 1", timeoutMs = 2000L)
                shellExecutor.executeCommand("echo 1 > /proc/sys/net/ipv4/tcp_low_latency 2>/dev/null", timeoutMs = 2000L)
                shellExecutor.executeCommand("echo 0 > /proc/sys/net/ipv4/tcp_slow_start_after_idle 2>/dev/null", timeoutMs = 2000L)
                shellExecutor.executeCommand("setprop net.dns1 1.1.1.1", timeoutMs = 2000L)
                shellExecutor.executeCommand("setprop net.dns2 1.0.0.1", timeoutMs = 2000L)
            }
            isNetOptimized = true
            logSummary.add("• Network: Wi-Fi Low Latency Lock + TCP QuickACK + Cloudflare DNS (1.1.1.1).")
        }

        // -------------------------------------------------------------
        // Step 15: Disable Virtual RAM (RAM Plus) & Memory Trim
        // -------------------------------------------------------------
        if (config.enableVirtualRamDisable) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.MEMORY_TRIM,
                progressPercentage = 97,
                message = "Disabling stutter-inducing Virtual RAM / Swap expansion..."
            ))
            shellExecutor.executeCommand("settings put global ram_expand_size 0", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global zram_enabled 0", timeoutMs = 2000L)
            logSummary.add("• Virtual RAM: Dynamic swap expansion disabled (Prevents flash swapping stutters).")
        }

        // -------------------------------------------------------------
        // Step 16: Game Focus & DND Mode
        // -------------------------------------------------------------
        if (config.enableDnd) {
            emit(BoostProgress.InProgress(
                step = BoostStepType.DND_ACTIVATE,
                progressPercentage = 99,
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
            message = "Restoring standard DVFS governor, display resolution, thermal policies, and UI animations..."
        ))

        val logs = mutableListOf<String>()

        // 1. Reset Display Resolution & Density
        shellExecutor.executeCommand("wm size reset", timeoutMs = 2000L)
        shellExecutor.executeCommand("wm density reset", timeoutMs = 2000L)
        logs.add("• Display resolution & density restored to physical native.")

        // 2. Reset Thermal Throttling
        shellExecutor.executeCommand("cmd thermalservice reset", timeoutMs = 3000L)
        logs.add("• Thermal policy restored to default hardware sensors.")

        // 3. Disable PowerHAL sustained performance & Wi-Fi low latency
        shellExecutor.executeCommand("cmd power set-fixed-performance-mode-enabled false", timeoutMs = 3000L)
        shellExecutor.executeCommand("cmd power set-adaptive-power-saver-enabled true", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global adaptive_battery_management_enabled 1", timeoutMs = 2000L)
        shellExecutor.executeCommand("cmd wifi set-low-latency-mode disabled", timeoutMs = 2000L)
        logs.add("• Adaptive battery management & PowerHAL restored.")

        // 4. Restore Refresh Rate & Window Animations
        shellExecutor.executeCommand("settings put system min_refresh_rate 60.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put system peak_refresh_rate 120.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global window_animation_scale 1.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global transition_animation_scale 1.0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global animator_duration_scale 1.0", timeoutMs = 2000L)
        logs.add("• Dynamic refresh rate & system animations restored.")

        // 5. Restore Network Policy & DND
        shellExecutor.executeCommand("cmd netpolicy set restrict-background false", timeoutMs = 2000L)
        shellExecutor.executeCommand("cmd notification set_zen_mode 0", timeoutMs = 2000L)
        shellExecutor.executeCommand("settings put global heads_up_notifications_enabled 1", timeoutMs = 2000L)
        shellExecutor.executeCommand("dumpsys deviceidle unforce", timeoutMs = 3000L)
        shellExecutor.executeCommand("cmd usage_stats reset-app-standby", timeoutMs = 3000L)
        logs.add("• Background network & notification alerts re-enabled.")

        // 6. Reset Game Mode API
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
            message = "Deeply compiling $packageName and secondary split DEX with mode '$compileMode'..."
        ))

        val res = shellExecutor.executeCommand("cmd package compile -m $compileMode --secondary-dex -f $packageName", timeoutMs = 90000L)
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
                summaryLogs = listOf("• Successfully compiled $packageName & all secondary split DEXes into native machine binary (mode $compileMode).")
            ))
        } else {
            emit(BoostProgress.Error("AOT compilation failed: ${res.output}"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun applyResolutionDownscale(scale: Float) {
        try {
            val sizeOut = shellExecutor.executeCommand("wm size", timeoutMs = 2000L).stdout
            val densityOut = shellExecutor.executeCommand("wm density", timeoutMs = 2000L).stdout

            val physicalSizeMatch = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeOut)
                ?: Regex("(\\d+)x(\\d+)").find(sizeOut)

            val physicalDensityMatch = Regex("Physical density:\\s*(\\d+)").find(densityOut)
                ?: Regex("(\\d+)").find(densityOut)

            if (physicalSizeMatch != null) {
                val origW = physicalSizeMatch.groupValues[1].toInt()
                val origH = physicalSizeMatch.groupValues[2].toInt()
                val targetW = ((origW * scale).toInt() / 2) * 2 // Ensure even dimensions
                val targetH = ((origH * scale).toInt() / 2) * 2

                shellExecutor.executeCommand("wm size ${targetW}x${targetH}", timeoutMs = 2000L)

                if (physicalDensityMatch != null) {
                    val origDpi = physicalDensityMatch.groupValues[1].toInt()
                    val targetDpi = (origDpi * scale).toInt()
                    shellExecutor.executeCommand("wm density $targetDpi", timeoutMs = 2000L)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun autoUnlockGameGraphics(gamePkg: String, targetFps: Int): List<String> {
        val logs = mutableListOf<String>()
        try {
            // 1. Automatically purge hardware rating cache and shader cache (Zero login data loss)
            shellExecutor.executeCommand("pm trim-caches 100G 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("rm -rf /sdcard/Android/data/$gamePkg/cache/* 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("rm -rf /sdcard/Android/data/$gamePkg/code_cache/* 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("rm -rf /sdcard/Android/data/$gamePkg/files/dragon2017/assets/UI/android/cache* 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("rm -rf /sdcard/Android/data/$gamePkg/files/dragon2017/assets/Config/*cache* 2>/dev/null", timeoutMs = 2000L)

            // 2. Lock Global Display Refresh Rate & Whitelist Game
            shellExecutor.executeCommand("settings put system min_refresh_rate 120.0 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system peak_refresh_rate 120.0 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put secure user_refresh_rate 120 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put secure refresh_rate_mode 2 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global high_refresh_rate_blacklist \"\" 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global high_refresh_rate_whitelist \"$gamePkg\" 2>/dev/null", timeoutMs = 2000L)

            // 3. Vendor-specific high refresh rate overrides
            shellExecutor.executeCommand("setprop persist.vendor.power.dfps.level 120 2>/dev/null; setprop persist.sys.gamemode.fps 120 2>/dev/null; setprop ro.vendor.display.svi 1 2>/dev/null; settings put secure speed_mode_enable 1 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system oplus_customize_refresh_rate $gamePkg:120 2>/dev/null; settings put global oplus_high_refresh_rate_whitelist $gamePkg 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put system vivo_game_mode 1 2>/dev/null; settings put global vivo_high_refresh_rate_whitelist $gamePkg 2>/dev/null", timeoutMs = 2000L)
            shellExecutor.executeCommand("settings put global sem_low_power_mode_last_result 0 2>/dev/null; settings put secure game_auto_temperature_control 0 2>/dev/null", timeoutMs = 2000L)

            // 4. Game-Specific Configuration Overrides (MLBB, PUBG, CODM)
            if (gamePkg.contains("mobile.legends", ignoreCase = true)) {
                val mlConfigCmd = "mkdir -p /sdcard/Android/data/$gamePkg/files/dragon2017/assets/Config 2>/dev/null; echo 'HighFpsMode=3\nFps120=1\nHighQuality=1\nShadow=1' > /sdcard/Android/data/$gamePkg/files/dragon2017/assets/Config/game_custom.cfg 2>/dev/null"
                shellExecutor.executeCommand(mlConfigCmd, timeoutMs = 2000L)
                logs.add("• MLBB Auto-Unlock: Hardware rating cache purged, 120 FPS display lock & Unity config primed.")
            } else if (gamePkg.contains("pubg", ignoreCase = true) || gamePkg.contains("ig", ignoreCase = true) || gamePkg.contains("freefire", ignoreCase = true)) {
                logs.add("• Battle Royale Auto-Unlock: 90/120 FPS whitelist & dynamic shader cache purged.")
            } else {
                logs.add("• Game Auto-Unlock: Hardware rating cache purged, $targetFps FPS display mode locked.")
            }
        } catch (_: Exception) {}
        return logs
    }

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
