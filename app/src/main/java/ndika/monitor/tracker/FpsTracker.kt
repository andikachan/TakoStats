package ndika.monitor.tracker

import android.os.SystemClock
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

class FpsTracker : ITracker {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var trackingJob: Job? = null
    private var isRunning = false

    @Volatile
    private var currentFps = -1.0f

    @Volatile
    private var currentLayerName = ""

    @Volatile
    private var currentPackageName = ""

    private var lastSeenTimestampNanos = 0L
    private var lastFrameReceivedTimeMs = 0L
    private var lastLayerScanTimeMs = 0L

    private val recentFrameTimes = Collections.synchronizedList(mutableListOf<Float>())
    private val maxFrameHistory = 120

    override fun start() {
        if (isRunning) return
        isRunning = true
        lastSeenTimestampNanos = 0L
        lastFrameReceivedTimeMs = SystemClock.uptimeMillis()
        lastLayerScanTimeMs = 0L
        recentFrameTimes.clear()

        startPollingLoop()
    }

    private fun startPollingLoop() {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val isElevated = ShizukuManager.isPermissionGranted() || ShellUtils.isRootAvailable()
                    if (!isElevated) {
                        currentFps = -1.0f
                        currentLayerName = ""
                        delay(1000L)
                        continue
                    }

                    val now = SystemClock.uptimeMillis()

                    // Re-scan active layer if needed (every 2.5s or if no layer/stale frames)
                    if (currentLayerName.isBlank() || (now - lastLayerScanTimeMs > 2500) || (now - lastFrameReceivedTimeMs > 2000)) {
                        findActiveSurfaceFlingerLayer()
                        lastLayerScanTimeMs = now
                    }

                    if (currentLayerName.isNotBlank()) {
                        pollSurfaceFlingerLatency(currentLayerName)
                    }

                    // Check for frozen/static screen (no new frames for > 1500ms)
                    if (now - lastFrameReceivedTimeMs > 1500 && lastSeenTimestampNanos > 0L) {
                        currentFps = 0.0f
                    }
                } catch (_: Exception) {}

                delay(250L) // Fast, responsive polling
            }
        }
    }

    private fun findActiveSurfaceFlingerLayer() {
        // 1. Get focused window/activity info
        var fgPkg = ""
        val focusOut = ShellUtils.exec("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'")
        if (focusOut.isNotBlank()) {
            val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(focusOut)
            if (match != null) {
                fgPkg = match.groupValues[1]
            }
        }

        if (fgPkg.isBlank() || fgPkg.contains("systemui", ignoreCase = true) || fgPkg.contains("ndika.monitor", ignoreCase = true)) {
            val actOut = ShellUtils.exec("dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity'")
            if (actOut.isNotBlank()) {
                val match = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)").find(actOut)
                if (match != null) {
                    val p = match.groupValues[1]
                    if (!p.contains("systemui", ignoreCase = true) && !p.contains("ndika.monitor", ignoreCase = true)) {
                        fgPkg = p
                    }
                }
            }
        }

        if (fgPkg.isNotBlank()) {
            currentPackageName = fgPkg
        }

        // 2. Query SurfaceFlinger layer list
        val sfListOut = ShellUtils.exec("dumpsys SurfaceFlinger --list 2>/dev/null")
        if (sfListOut.isBlank()) {
            return
        }

        val lines = sfListOut.lines().map { it.trim() }.filter { it.isNotBlank() }
        var matchedLayer: String? = null

        // If we have a target foreground package
        if (fgPkg.isNotBlank()) {
            // Priority 1: SurfaceView with package name (game render surface)
            matchedLayer = lines.firstOrNull { l ->
                l.contains(fgPkg, ignoreCase = true) &&
                        l.contains("surfaceview", ignoreCase = true) &&
                        !l.startsWith("Background", ignoreCase = true) &&
                        !l.startsWith("Dim", ignoreCase = true) &&
                        !l.startsWith("Snapshot", ignoreCase = true)
            }

            // Priority 2: Main Activity / Window layer for package
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

        // Fallback: Pick top visible non-system layer
        if (matchedLayer == null) {
            val systemBlacklist = arrayOf(
                "StatusBar", "NavigationBar", "NotificationShade", "InputMethod",
                "ScreenDecor", "TakoStats", "ndika.monitor", "Magnification",
                "PointerLocation", "Wallpaper", "Letterbox", "Dim Layer", "Background for"
            )
            matchedLayer = lines.firstOrNull { l ->
                systemBlacklist.none { sys -> l.contains(sys, ignoreCase = true) }
            }
        }

        if (matchedLayer != null && matchedLayer != currentLayerName) {
            currentLayerName = matchedLayer
            lastSeenTimestampNanos = 0L // Reset timestamp bookmark for new layer
        }
    }

    private fun pollSurfaceFlingerLatency(layerName: String) {
        val out = ShellUtils.exec("dumpsys SurfaceFlinger --latency \"$layerName\" 2>/dev/null")
        if (out.isBlank()) return

        val lines = out.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return

        val timestamps = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split("\\s+".toRegex())
            if (parts.size >= 3) {
                val presentTime = parts[1].toLongOrNull() ?: 0L
                // 0 or Long.MAX_VALUE (0x7fffffffffffffffL) indicates dropped or unpresented frame
                if (presentTime > 0L && presentTime != Long.MAX_VALUE && presentTime != 0x7fffffffffffffffL) {
                    timestamps.add(presentTime)
                }
            }
        }

        if (timestamps.isEmpty()) return

        val newTimestamps = if (lastSeenTimestampNanos > 0L) {
            timestamps.filter { it > lastSeenTimestampNanos }
        } else {
            // Initial seed: take the last batch of valid frames
            timestamps.takeLast(60)
        }

        if (newTimestamps.isNotEmpty()) {
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
            lastFrameReceivedTimeMs = nowMs

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
                        val avgFt = recentFrameTimes.takeLast(15).average().toFloat()
                        if (avgFt > 0f) {
                            currentFps = (1000.0f / avgFt).coerceIn(0.0f, 240.0f)
                        }
                    }
                }
            }
        }
    }

    override fun update(metrics: PerformanceMetrics) {
        val isElevated = ShizukuManager.isPermissionGranted() || ShellUtils.isRootAvailable()
        if (!isElevated) {
            metrics.fps = -1.0f
            metrics.layerName = "Shizuku Required"
            return
        }

        if (currentFps >= 0.0f) {
            metrics.fps = currentFps
        } else {
            metrics.fps = 0.0f
        }

        if (currentLayerName.isNotBlank()) {
            metrics.layerName = currentLayerName
        } else if (currentPackageName.isNotBlank()) {
            metrics.layerName = currentPackageName
        }
    }

    override fun stop() {
        isRunning = false
        trackingJob?.cancel()
        trackingJob = null
    }
}
