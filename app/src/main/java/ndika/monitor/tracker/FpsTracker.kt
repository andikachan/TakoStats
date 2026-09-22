package ndika.monitor.tracker

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.recorder.SessionRecorder
import ndika.monitor.shizuku.ShizukuManager
import ndika.monitor.util.ShellUtils
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class FpsTracker(private val context: Context? = null) : ITracker, Choreographer.FrameCallback {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trackingJob: Job? = null
    private var isRunning = false

    @Volatile
    private var currentFps = 60.0f

    @Volatile
    private var currentLayerName = ""

    @Volatile
    private var currentPackageName = ""

    @Volatile
    private var maxDisplayRefreshRate = 60.0f

    private var lastSeenTimestampNanos = 0L
    private var lastHardwareFrameTimeMs = 0L
    private var lastLayerScanTimeMs = 0L

    // Sliding window of frame presentation timestamps in nanoseconds (last 1000ms)
    private val frameTimestampHistory = Collections.synchronizedList(mutableListOf<Long>())
    private val recentFrameTimes = Collections.synchronizedList(mutableListOf<Float>())
    private val maxFrameHistory = 120

    // Live display VSYNC baseline
    private var choreoFrameCount = 0
    private var choreoStartTimeMs = 0L
    @Volatile
    private var choreoFps = 60.0f

    init {
        detectDisplayRefreshRate()
    }

    private fun detectDisplayRefreshRate() {
        try {
            if (context != null) {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.display
                } else {
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay
                }
                val rate = display?.mode?.refreshRate ?: display?.refreshRate ?: 60.0f
                if (rate in 30.0f..240.0f) {
                    maxDisplayRefreshRate = rate
                    choreoFps = rate
                    currentFps = rate
                }
            }
        } catch (_: Exception) {}
    }

    override fun start() {
        if (isRunning) return
        isRunning = true
        lastSeenTimestampNanos = 0L
        lastHardwareFrameTimeMs = 0L
        lastLayerScanTimeMs = 0L
        frameTimestampHistory.clear()
        recentFrameTimes.clear()
        detectDisplayRefreshRate()

        mainHandler.post {
            choreoFrameCount = 0
            choreoStartTimeMs = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback(this)
        }

        startPollingLoop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        choreoFrameCount++
        val now = SystemClock.uptimeMillis()
        val elapsed = now - choreoStartTimeMs
        if (elapsed >= 500) {
            val fps = (choreoFrameCount * 1000.0f) / elapsed
            choreoFps = fps.coerceIn(0.0f, maxDisplayRefreshRate)
            choreoFrameCount = 0
            choreoStartTimeMs = now
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startPollingLoop() {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val isElevated = ShizukuManager.isPermissionGranted() || ShellUtils.isRootAvailable()
                    val now = SystemClock.uptimeMillis()

                    if (isElevated) {
                        // Re-scan active layer periodically or when stale
                        if (currentPackageName.isBlank() || (now - lastLayerScanTimeMs > 2000) || (now - lastHardwareFrameTimeMs > 2500)) {
                            findActiveForegroundAppAndLayer()
                            lastLayerScanTimeMs = now
                        }

                        var framesFetched = false

                        // Engine 1: SurfaceFlinger Latency
                        if (currentLayerName.isNotBlank()) {
                            framesFetched = pollSurfaceFlingerLatency(currentLayerName)
                        }

                        // Engine 2: GfxInfo Framestats Fallback
                        if (!framesFetched && currentPackageName.isNotBlank()) {
                            framesFetched = pollGfxInfoFramestats(currentPackageName)
                        }

                        // Engine 3: Auto-detect active SurfaceFlinger layer if unknown
                        if (!framesFetched && (now - lastHardwareFrameTimeMs > 2000)) {
                            framesFetched = autoDetectActiveSurfaceFlingerLayer()
                        }
                    }
                } catch (_: Exception) {}

                delay(250L) // Fast 4Hz polling
            }
        }
    }

    private fun findActiveForegroundAppAndLayer() {
        var fgPkg = ""
        val focusOut = ShellUtils.exec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'")
        if (focusOut.isNotBlank()) {
            val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(focusOut)
            if (match != null) {
                val p = match.groupValues[1]
                if (!p.contains("systemui", ignoreCase = true)) {
                    fgPkg = p
                }
            }
        }

        if (fgPkg.isBlank()) {
            val actOut = ShellUtils.exec("dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity'")
            if (actOut.isNotBlank()) {
                val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(actOut)
                if (match != null) {
                    val p = match.groupValues[1]
                    if (!p.contains("systemui", ignoreCase = true)) {
                        fgPkg = p
                    }
                }
            }
        }

        if (fgPkg.isNotBlank()) {
            currentPackageName = fgPkg
        }

        val sfListOut = ShellUtils.exec("dumpsys SurfaceFlinger --list 2>/dev/null")
        if (sfListOut.isBlank()) return

        val lines = sfListOut.lines().map { it.trim() }.filter { it.isNotBlank() }
        var matchedLayer: String? = null

        if (fgPkg.isNotBlank()) {
            // Priority 1: SurfaceView with package name
            matchedLayer = lines.firstOrNull { l ->
                l.contains(fgPkg, ignoreCase = true) &&
                        (l.contains("surfaceview", ignoreCase = true) || l.contains("SurfaceView -", ignoreCase = true)) &&
                        !l.startsWith("Background", ignoreCase = true) &&
                        !l.startsWith("Dim", ignoreCase = true) &&
                        !l.startsWith("Snapshot", ignoreCase = true)
            }

            // Priority 2: Main window layer for package
            if (matchedLayer == null) {
                matchedLayer = lines.firstOrNull { l ->
                    l.contains(fgPkg, ignoreCase = true) &&
                            !l.startsWith("Background", ignoreCase = true) &&
                            !l.startsWith("Dim", ignoreCase = true) &&
                            !l.startsWith("Snapshot", ignoreCase = true) &&
                            !l.contains("Overlay", ignoreCase = true)
                }
            }
        }

        if (matchedLayer != null && matchedLayer != currentLayerName) {
            currentLayerName = matchedLayer
            lastSeenTimestampNanos = 0L
            frameTimestampHistory.clear()
        }
    }

    private fun autoDetectActiveSurfaceFlingerLayer(): Boolean {
        val sfListOut = ShellUtils.exec("dumpsys SurfaceFlinger --list 2>/dev/null")
        if (sfListOut.isBlank()) return false

        val systemBlacklist = arrayOf(
            "StatusBar", "NavigationBar", "NotificationShade", "InputMethod",
            "ScreenDecor", "TakoStats", "Magnification", "PointerLocation",
            "Wallpaper", "Letterbox", "Dim Layer", "Background for", "ndika.monitor"
        )

        val candidateLayers = sfListOut.lines()
            .map { it.trim() }
            .filter { l -> l.isNotBlank() && systemBlacklist.none { sys -> l.contains(sys, ignoreCase = true) } }
            .take(6)

        for (layer in candidateLayers) {
            if (pollSurfaceFlingerLatency(layer)) {
                currentLayerName = layer
                return true
            }
        }
        return false
    }

    private fun pollSurfaceFlingerLatency(layerName: String): Boolean {
        val out = ShellUtils.exec("dumpsys SurfaceFlinger --latency \"$layerName\" 2>/dev/null")
        if (out.isBlank()) return false

        val lines = out.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return false

        // Line 0: Refresh period in nanoseconds
        val refreshPeriodNanos = lines[0].toLongOrNull() ?: 16666666L
        if (refreshPeriodNanos > 0L) {
            val calculatedHz = (1_000_000_000.0 / refreshPeriodNanos.toDouble()).toFloat()
            if (calculatedHz in 30.0f..240.0f) {
                maxDisplayRefreshRate = calculatedHz
            }
        }

        val frameDataList = mutableListOf<FrameData>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split("\\s+".toRegex())
            if (parts.size >= 3) {
                val appDesiredTime = parts[0].toLongOrNull() ?: 0L
                val actualPresentTime = parts[1].toLongOrNull() ?: 0L
                val driverReadyTime = parts[2].toLongOrNull() ?: 0L

                val validPresentTime = if (actualPresentTime > 0L && actualPresentTime != Long.MAX_VALUE && actualPresentTime != 0x7fffffffffffffffL) {
                    actualPresentTime
                } else if (driverReadyTime > 0L && driverReadyTime != Long.MAX_VALUE && driverReadyTime != 0x7fffffffffffffffL) {
                    driverReadyTime
                } else {
                    0L
                }

                if (validPresentTime > 0L) {
                    frameDataList.add(FrameData(appDesiredTime, validPresentTime, driverReadyTime))
                }
            }
        }

        if (frameDataList.isEmpty()) return false
        return processNewFrames(frameDataList, refreshPeriodNanos)
    }

    private fun pollGfxInfoFramestats(pkg: String): Boolean {
        val out = ShellUtils.exec("dumpsys gfxinfo $pkg framestats 2>/dev/null")
        if (out.isBlank() || !out.contains("---PROFILEDATA---")) return false

        var inProfile = false
        val frameDataList = mutableListOf<FrameData>()

        for (line in out.lines()) {
            val trimmed = line.trim()
            if (trimmed.contains("---PROFILEDATA---")) {
                inProfile = !inProfile
                continue
            }
            if (!inProfile || trimmed.startsWith("Flags") || trimmed.isBlank()) continue

            val parts = trimmed.split(",")
            if (parts.size >= 14) {
                val intendedVsync = parts[1].toLongOrNull() ?: 0L
                val completedTs = parts[13].toLongOrNull() ?: 0L
                val gpuDoneTs = parts[12].toLongOrNull() ?: completedTs

                val validPresentTime = if (completedTs > 0L && completedTs != Long.MAX_VALUE && completedTs != 0x7fffffffffffffffL) {
                    completedTs
                } else {
                    intendedVsync
                }

                if (validPresentTime > 0L) {
                    frameDataList.add(FrameData(intendedVsync, validPresentTime, gpuDoneTs))
                }
            }
        }

        if (frameDataList.isEmpty()) return false
        return processNewFrames(frameDataList, 16666666L)
    }

    private data class FrameData(
        val desiredTime: Long,
        val presentTime: Long,
        val driverReadyTime: Long
    )

    private fun processNewFrames(frames: List<FrameData>, refreshPeriodNanos: Long): Boolean {
        val newFrames = if (lastSeenTimestampNanos > 0L) {
            frames.filter { it.presentTime > lastSeenTimestampNanos }
        } else {
            frames.takeLast(60)
        }

        if (newFrames.isEmpty()) return false

        val nowMs = SystemClock.uptimeMillis()
        var prevTs = if (lastSeenTimestampNanos > 0L) lastSeenTimestampNanos else newFrames.first().presentTime
        var totalGpuTimeNanos = 0L
        var totalFrameTimeNanos = 0L

        for (frame in newFrames) {
            val ts = frame.presentTime
            if (ts > prevTs) {
                val deltaNanos = ts - prevTs
                val deltaMs = deltaNanos / 1_000_000.0f
                if (deltaMs in 0.5f..1000.0f) {
                    SessionRecorder.recordFrameTime(deltaMs)
                    synchronized(recentFrameTimes) {
                        if (recentFrameTimes.size >= maxFrameHistory) {
                            recentFrameTimes.removeAt(0)
                        }
                        recentFrameTimes.add(deltaMs)
                    }

                    // Hardware GPU render time estimation from driver ready fence
                    if (frame.desiredTime > 0L && frame.driverReadyTime > frame.desiredTime) {
                        val gpuDuration = frame.driverReadyTime - frame.desiredTime
                        if (gpuDuration in 100_000L..100_000_000L) {
                            totalGpuTimeNanos += gpuDuration
                            totalFrameTimeNanos += deltaNanos
                        }
                    }
                }
                prevTs = ts
            }
        }

        // Forward hardware GPU render load to GpuTracker
        if (totalFrameTimeNanos > 0L && totalGpuTimeNanos > 0L) {
            val hwGpuUsage = ((totalGpuTimeNanos.toDouble() / totalFrameTimeNanos.toDouble()) * 100.0).toFloat()
            GpuTracker.setHardwareGpuUsage(hwGpuUsage.coerceIn(0.0f, 100.0f))
        }

        lastSeenTimestampNanos = newFrames.last().presentTime
        lastHardwareFrameTimeMs = nowMs

        // Append new timestamps to sliding history buffer (1000ms window)
        val cutoffNanos = lastSeenTimestampNanos - 1_000_000_000L
        synchronized(frameTimestampHistory) {
            for (f in newFrames) {
                frameTimestampHistory.add(f.presentTime)
            }
            frameTimestampHistory.removeAll { it < cutoffNanos }

            val count = frameTimestampHistory.size
            if (count >= 2) {
                val spanNanos = frameTimestampHistory.last() - frameTimestampHistory.first()
                if (spanNanos > 0L) {
                    val rawFps = ((count - 1) * 1_000_000_000.0 / spanNanos).toFloat()
                    // Strict hardware cap to display refresh rate
                    currentFps = min(rawFps, maxDisplayRefreshRate).coerceAtLeast(0.0f)
                }
            } else if (recentFrameTimes.isNotEmpty()) {
                val lastFt = recentFrameTimes.last()
                if (lastFt > 0f) {
                    val rawFps = 1000.0f / lastFt
                    currentFps = min(rawFps, maxDisplayRefreshRate).coerceAtLeast(0.0f)
                }
            }
        }

        return true
    }

    override fun update(metrics: PerformanceMetrics) {
        val now = SystemClock.uptimeMillis()
        val hasRecentHardwareFrames = (now - lastHardwareFrameTimeMs < 1500)

        if (hasRecentHardwareFrames && currentFps > 0f) {
            metrics.fps = min(currentFps, maxDisplayRefreshRate)
        } else {
            // Live render VSYNC rate bounded by screen max Hz
            metrics.fps = min(choreoFps, maxDisplayRefreshRate)
        }

        if (currentLayerName.isNotBlank()) {
            metrics.layerName = currentLayerName
        } else if (currentPackageName.isNotBlank()) {
            metrics.layerName = currentPackageName
        } else {
            metrics.layerName = "Active Window"
        }
    }

    override fun stop() {
        isRunning = false
        trackingJob?.cancel()
        trackingJob = null
        mainHandler.post {
            Choreographer.getInstance().removeFrameCallback(this)
        }
        frameTimestampHistory.clear()
        recentFrameTimes.clear()
    }
}
