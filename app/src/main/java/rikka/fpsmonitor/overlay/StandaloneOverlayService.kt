package rikka.fpsmonitor.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.fpsmonitor.R
import rikka.fpsmonitor.model.OverlayConfig
import rikka.fpsmonitor.model.PerformanceMetrics
import rikka.fpsmonitor.tracker.BatteryTracker
import rikka.fpsmonitor.tracker.CpuTracker
import rikka.fpsmonitor.tracker.FpsTracker
import rikka.fpsmonitor.tracker.GpuTracker
import rikka.fpsmonitor.tracker.ITracker
import rikka.fpsmonitor.tracker.MemoryTracker
import rikka.fpsmonitor.tracker.NetworkTracker
import rikka.fpsmonitor.ui.MainActivity
import rikka.fpsmonitor.util.PreferenceManager

class StandaloneOverlayService : Service() {

    private var overlayWindow: OverlayWindow? = null
    private val trackers = mutableListOf<ITracker>()
    private val currentMetrics = PerformanceMetrics()

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var updateJob: Job? = null
    private lateinit var preferenceManager: PreferenceManager

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
        trackers.add(MemoryTracker(this))
        trackers.add(NetworkTracker())

        trackers.forEach { it.start() }

        startForegroundServiceNotification()
        overlayWindow?.show()
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

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.overlay_running))
            .setContentText(getString(R.string.tap_to_configure))
            .setSmallIcon(R.drawable.ic_stat_fps)
            .setContentIntent(pendingIntent)
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

                // Update UI on Main thread
                withContext(Dispatchers.Main) {
                    overlayWindow?.updateMetrics(currentMetrics)
                }

                delay(overlayWindow?.config?.updateIntervalMs ?: 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                val newConfig = preferenceManager.loadOverlayConfig()
                overlayWindow?.applyConfig(newConfig)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        trackers.forEach { it.stop() }
        trackers.clear()
        overlayWindow?.hide()
        overlayWindow = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "rikka.fpsmonitor.action.START_OVERLAY"
        const val ACTION_STOP = "rikka.fpsmonitor.action.STOP_OVERLAY"
        const val ACTION_RELOAD_CONFIG = "rikka.fpsmonitor.action.RELOAD_CONFIG"

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
    }
}
