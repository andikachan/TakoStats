package ndika.monitor.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.model.OverlayConfig
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.recorder.SessionRecorder
import ndika.monitor.tracker.BatteryTracker
import ndika.monitor.tracker.CpuTracker
import ndika.monitor.tracker.FpsTracker
import ndika.monitor.tracker.GpuTracker
import ndika.monitor.tracker.ITracker
import ndika.monitor.tracker.MemoryTracker
import ndika.monitor.tracker.NetworkTracker
import ndika.monitor.tracker.SkinThermalTracker
import ndika.monitor.ui.MainActivity
import ndika.monitor.util.PreferenceManager

class StandaloneOverlayService : Service() {

    private var overlayWindow: OverlayWindow? = null
    private val trackers = mutableListOf<ITracker>()
    private val currentMetrics = PerformanceMetrics()

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var updateJob: Job? = null
    private lateinit var preferenceManager: PreferenceManager
    private var isOverlayVisible = true

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        val config = preferenceManager.loadOverlayConfig()

        overlayWindow = OverlayWindow(this, config)

        // Initialize trackers
        trackers.add(FpsTracker())
        trackers.add(CpuTracker())
        trackers.add(GpuTracker())
        trackers.add(BatteryTracker(this))
        trackers.add(SkinThermalTracker())
        trackers.add(MemoryTracker(this))
        trackers.add(NetworkTracker())

        trackers.forEach { it.start() }

        startForegroundServiceNotification()
        overlayWindow?.show()
        isOverlayVisible = true
        startTrackingLoop()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "fpsmonitor_overlay_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMainIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toggle Visibility Intent
        val toggleVisIntent = Intent(this, StandaloneOverlayService::class.java).apply {
            action = ACTION_TOGGLE_VISIBILITY
        }
        val pendingToggleVis = PendingIntent.getService(
            this, 1, toggleVisIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toggle Recording Intent
        val toggleRecIntent = Intent(this, StandaloneOverlayService::class.java).apply {
            action = ACTION_TOGGLE_RECORDING
        }
        val pendingToggleRec = PendingIntent.getService(
            this, 2, toggleRecIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val visActionTitle = if (isOverlayVisible) getString(R.string.action_hide_overlay) else getString(R.string.action_show_overlay)
        val recActionTitle = if (SessionRecorder.isRecording()) getString(R.string.action_stop_record) else getString(R.string.action_start_record)

        val notifTitle = if (SessionRecorder.isRecording()) {
            getString(R.string.recording_active)
        } else {
            getString(R.string.overlay_running)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(notifTitle)
            .setContentText(getString(R.string.tap_to_configure))
            .setSmallIcon(if (SessionRecorder.isRecording()) R.drawable.ic_record_24 else R.drawable.ic_stat_fps)
            .setContentIntent(pendingMainIntent)
            .addAction(R.drawable.ic_tune_24, visActionTitle, pendingToggleVis)
            .addAction(if (SessionRecorder.isRecording()) R.drawable.ic_stop_24 else R.drawable.ic_record_24, recActionTitle, pendingToggleRec)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startTrackingLoop() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                // Update tracker values in background thread
                trackers.forEach { tracker ->
                    try {
                        tracker.update(currentMetrics)
                    } catch (_: Exception) {}
                }

                // If recording is active, snapshot telemetry
                if (SessionRecorder.isRecording()) {
                    SessionRecorder.recordTelemetrySnapshot(currentMetrics)
                }

                // Update UI on Main thread
                withContext(Dispatchers.Main) {
                    if (isOverlayVisible) {
                        overlayWindow?.updateMetrics(currentMetrics)
                    }
                }

                delay(overlayWindow?.config?.updateIntervalMs ?: 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (SessionRecorder.isRecording()) {
                    SessionRecorder.stopRecording(this)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                val newConfig = preferenceManager.loadOverlayConfig()
                overlayWindow?.applyConfig(newConfig)
            }
            ACTION_TOGGLE_VISIBILITY -> {
                if (isOverlayVisible) {
                    overlayWindow?.hide()
                    isOverlayVisible = false
                } else {
                    overlayWindow?.show()
                    isOverlayVisible = true
                }
                startForegroundServiceNotification()
            }
            ACTION_TOGGLE_RECORDING -> {
                if (SessionRecorder.isRecording()) {
                    val saved = SessionRecorder.stopRecording(this)
                    preferenceManager.isRecordingRunning = false
                    Toast.makeText(this, R.string.record_saved, Toast.LENGTH_SHORT).show()
                } else {
                    SessionRecorder.startRecording("Gaming Benchmark", "")
                    preferenceManager.isRecordingRunning = true
                    Toast.makeText(this, R.string.start_recording, Toast.LENGTH_SHORT).show()
                }
                startForegroundServiceNotification()
            }
            ACTION_START_RECORD -> {
                val appName = intent.getStringExtra("app_name") ?: "Game Benchmark"
                val pkgName = intent.getStringExtra("package_name") ?: ""
                SessionRecorder.startRecording(appName, pkgName)
                preferenceManager.isRecordingRunning = true
                startForegroundServiceNotification()
            }
            ACTION_STOP_RECORD -> {
                val record = SessionRecorder.stopRecording(this)
                preferenceManager.isRecordingRunning = false
                Toast.makeText(this, R.string.record_saved, Toast.LENGTH_SHORT).show()
                startForegroundServiceNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        if (SessionRecorder.isRecording()) {
            SessionRecorder.stopRecording(this)
        }
        trackers.forEach { it.stop() }
        trackers.clear()
        overlayWindow?.hide()
        overlayWindow = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ndika.monitor.action.START_OVERLAY"
        const val ACTION_STOP = "ndika.monitor.action.STOP_OVERLAY"
        const val ACTION_RELOAD_CONFIG = "ndika.monitor.action.RELOAD_CONFIG"
        const val ACTION_TOGGLE_VISIBILITY = "ndika.monitor.action.TOGGLE_VISIBILITY"
        const val ACTION_TOGGLE_RECORDING = "ndika.monitor.action.TOGGLE_RECORDING"
        const val ACTION_START_RECORD = "ndika.monitor.action.START_RECORD"
        const val ACTION_STOP_RECORD = "ndika.monitor.action.STOP_RECORD"

        fun start(context: Context) {
            val intent = Intent(context, StandaloneOverlayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StandaloneOverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun reloadConfig(context: Context) {
            val intent = Intent(context, StandaloneOverlayService::class.java).apply {
                action = ACTION_RELOAD_CONFIG
            }
            context.startService(intent)
        }

        fun startRecording(context: Context, appName: String, packageName: String) {
            val intent = Intent(context, StandaloneOverlayService::class.java).apply {
                action = ACTION_START_RECORD
                putExtra("app_name", appName)
                putExtra("package_name", packageName)
            }
            context.startService(intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, StandaloneOverlayService::class.java).apply {
                action = ACTION_STOP_RECORD
            }
            context.startService(intent)
        }
    }
}
