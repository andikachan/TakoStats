package ndika.monitor.tracker

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
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

class FpsTracker : ITracker, Choreographer.FrameCallback {

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

    private var lastSeenTimestampNanos = 0L
    private var lastHardwareFrameTimeMs = 0L
    private var lastLayerScanTimeMs = 0L

    private val recentFrameTimes = Collections.synchronizedList(mutableListOf<Float>())
    private val maxFrameHistory = 120

    // Live display VSYNC baseline
    private var lastChoreoFrameNanos = 0L
    private var choreoFrameCount = 0
    private var choreoStartTimeMs = 0L
    @Volatile
    private var choreoFps = 60.0f

    override fun start() {
        if (isRunning) return
        isRunning = true
        lastSeenTimestampNanos = 0L
        lastHardwareFrameTimeMs = 0L
        lastLayerScanTimeMs = 0L
        recentFrameTimes.clear()

        mainHandler.post {
            lastChoreoFrameNanos = 0L
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
            choreoFps = fps.coerceIn(0.0f, 240.0f)
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
                        l.contains("surfaceview", ignoreCase = true) &&
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

        val timestamps = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split("\\s+".toRegex())
            if (parts.size >= 3) {
                val presentTime = parts[1].toLongOrNull() ?: 0L
                if (presentTime > 0L && presentTime != Long.MAX_VALUE && presentTime != 0x7fffffffffffffffL) {
                    timestamps.add(presentTime)
                }
            }
        }

        if (timestamps.isEmpty()) return false
        return processNewTimestamps(timestamps)
    }

    private fun pollGfxInfoFramestats(pkg: String): Boolean {
        val out = ShellUtils.exec("dumpsys gfxinfo $pkg framestats 2>/dev/null")
        if (out.isBlank() || !out.contains("---PROFILEDATA---")) return false

        var inProfile = false
        val timestamps = mutableListOf<Long>()

        for (line in out.lines()) {
            val trimmed = line.trim()
            if (trimmed.contains("---PROFILEDATA---")) {
                inProfile = !inProfile
                continue
            }
            if (!inProfile || trimmed.startsWith("Flags") || trimmed.isBlank()) continue

            val parts = trimmed.split(",")
            if (parts.size >= 14) {
                val completedTs = parts[13].toLongOrNull() ?: parts[1].toLongOrNull() ?: 0L
                if (completedTs > 0L && completedTs != Long.MAX_VALUE && completedTs != 0x7fffffffffffffffL) {
                    timestamps.add(completedTs)
                }
            }
        }

        if (timestamps.isEmpty()) return false
        return processNewTimestamps(timestamps)
    }

    private fun processNewTimestamps(timestamps: List<Long>): Boolean {
        val newTimestamps = if (lastSeenTimestampNanos > 0L) {
            timestamps.filter { it > lastSeenTimestampNanos }
        } else {
            timestamps.takeLast(60)
        }

        if (newTimestamps.isEmpty()) return false

        val nowMs = SystemClock.uptimeMillis()
        var prevTs = if (lastSeenTimestampNanos > 0L) lastSeenTimestampNanos else newTimestamps.first()

        for (ts in newTimestamps) {
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
                }
                prevTs = ts
            }
        }

        lastSeenTimestampNanos = newTimestamps.last()
        lastHardwareFrameTimeMs = nowMs

        // Calculate instantaneous FPS
        if (newTimestamps.size >= 2) {
            val totalDurationNanos = newTimestamps.last() - newTimestamps.first()
            if (totalDurationNanos > 0L) {
                val fps = ((newTimestamps.size - 1) * 1_000_000_000.0 / totalDurationNanos).toFloat()
                currentFps = fps.coerceIn(0.0f, 240.0f)
            }
        } else {
            synchronized(recentFrameTimes) {
                if (recentFrameTimes.isNotEmpty()) {
                    val lastFt = recentFrameTimes.last()
                    if (lastFt > 0f) {
                        currentFps = (1000.0f / lastFt).coerceIn(0.0f, 240.0f)
                    }
                }
            }
        }

        return true
    }

    override fun update(metrics: PerformanceMetrics) {
        val now = SystemClock.uptimeMillis()
        val hasRecentHardwareFrames = (now - lastHardwareFrameTimeMs < 2000)

        if (hasRecentHardwareFrames && currentFps > 0f) {
            metrics.fps = currentFps
        } else {
            // Fallback to active render VSYNC rate
            metrics.fps = choreoFps
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
    }
}
