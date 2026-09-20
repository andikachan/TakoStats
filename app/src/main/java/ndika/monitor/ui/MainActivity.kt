package ndika.monitor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.databinding.ActivityMainBinding
import ndika.monitor.model.PerformanceMetrics
import ndika.monitor.overlay.StandaloneOverlayService
import ndika.monitor.recorder.SessionRecorder
import ndika.monitor.shizuku.ShizukuManager
import ndika.monitor.tracker.BatteryTracker
import ndika.monitor.tracker.CpuTracker
import ndika.monitor.tracker.GpuTracker
import ndika.monitor.tracker.MemoryTracker
import ndika.monitor.tracker.SkinThermalTracker
import ndika.monitor.util.PreferenceManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferenceManager: PreferenceManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionsAndStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        setupToolbar()
        setupListeners()
        observeRecordingState()
        startLivePreview()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStatus()
        updateTargetAppsSummary()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupListeners() {
        // Toggle Overlay Switch
        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!checkOverlayPermission()) {
                    binding.switchOverlay.isChecked = false
                    requestOverlayPermission()
                    return@setOnCheckedChangeListener
                }
                StandaloneOverlayService.start(this)
                preferenceManager.isServiceRunning = true
            } else {
                StandaloneOverlayService.stop(this)
                preferenceManager.isServiceRunning = false
            }
            updateServiceStatusBadge(isChecked)
        }

        // Toggle Recording Button
        binding.btnToggleRecording.setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }

            if (SessionRecorder.isRecording()) {
                val record = SessionRecorder.stopRecording(this)
                preferenceManager.isRecordingRunning = false
                Toast.makeText(this, R.string.record_saved, Toast.LENGTH_SHORT).show()
                if (record != null) {
                    val intent = Intent(this, RecordActivity::class.java).apply {
                        putExtra(RecordActivity.EXTRA_RECORD_ID, record.id)
                    }
                    startActivity(intent)
                }
            } else {
                if (!preferenceManager.isServiceRunning) {
                    StandaloneOverlayService.start(this)
                    preferenceManager.isServiceRunning = true
                    binding.switchOverlay.isChecked = true
                }
                SessionRecorder.startRecording("Gaming Benchmark", "")
                preferenceManager.isRecordingRunning = true
                Toast.makeText(this, R.string.start_recording, Toast.LENGTH_SHORT).show()
            }
        }

        // Benchmark Records Card
        binding.cardRecords.setOnClickListener {
            startActivity(Intent(this, RecordListActivity::class.java))
        }

        // Target Apps Card
        binding.cardTargetApps.setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        // Customize Overlay Card
        binding.cardCustomize.setOnClickListener {
            startActivity(Intent(this, CustomizeOverlayActivity::class.java))
        }

        // Shizuku Permission Card
        binding.cardShizuku.setOnClickListener {
            if (ShizukuManager.isShizukuAvailable()) {
                if (!ShizukuManager.isPermissionGranted()) {
                    ShizukuManager.requestPermission(101)
                } else {
                    Toast.makeText(this, R.string.shizuku_authorized, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
            }
        }

        // Settings Button
        binding.cardSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeRecordingState() {
        lifecycleScope.launch {
            SessionRecorder.recordingState.collect { isRec ->
                if (isRec) {
                    binding.textRecordStatusTitle.text = getString(R.string.recording_active)
                    binding.textRecordStatusDesc.text = "Capturing frame times and hardware telemetry..."
                    binding.btnToggleRecording.text = getString(R.string.stop_recording)
                    binding.imgRecordStatus.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                } else {
                    binding.textRecordStatusTitle.text = getString(R.string.start_recording)
                    binding.textRecordStatusDesc.text = "Tap button to record telemetry & frame times"
                    binding.btnToggleRecording.text = getString(R.string.start_recording)
                    binding.imgRecordStatus.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                }
            }
        }

        lifecycleScope.launch {
            SessionRecorder.recordedFramesCount.collect { count ->
                if (SessionRecorder.isRecording() && count > 0) {
                    binding.textRecordStatusDesc.text = "Recorded ${count} frames..."
                }
            }
        }
    }

    private fun updateTargetAppsSummary() {
        val targets = preferenceManager.targetApps
        binding.textTargetAppsSummary.text = if (targets.isEmpty()) {
            getString(R.string.choose_apps_summary)
        } else {
            "${targets.size} apps selected for target monitoring"
        }
    }

    private fun checkPermissionsAndStatus() {
        val hasOverlay = checkOverlayPermission()
        val isShizukuGranted = ShizukuManager.isPermissionGranted()

        binding.textOverlayPermissionStatus.text = if (hasOverlay) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_missing)
        }

        binding.textShizukuStatus.text = if (isShizukuGranted) {
            getString(R.string.shizuku_authorized)
        } else if (ShizukuManager.isShizukuAvailable()) {
            getString(R.string.shizuku_available_tap_auth)
        } else {
            getString(R.string.shizuku_not_running)
        }

        val isRunning = preferenceManager.isServiceRunning
        binding.switchOverlay.isChecked = isRunning
        updateServiceStatusBadge(isRunning)
    }

    private fun updateServiceStatusBadge(isRunning: Boolean) {
        if (isRunning) {
            binding.badgeStatus.text = getString(R.string.service_running)
            binding.badgeStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.badgeStatus.text = getString(R.string.service_stopped)
            binding.badgeStatus.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, R.string.grant_overlay_permission, Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startLivePreview() {
        val cpuTracker = CpuTracker()
        val gpuTracker = GpuTracker()
        val batTracker = BatteryTracker(this)
        val skinTracker = SkinThermalTracker()
        val memTracker = MemoryTracker(this)
        val metrics = PerformanceMetrics()

        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                cpuTracker.update(metrics)
                gpuTracker.update(metrics)
                batTracker.update(metrics)
                skinTracker.update(metrics)
                memTracker.update(metrics)

                val config = preferenceManager.loadOverlayConfig()
                val unit = config.temperatureUnit

                withContext(Dispatchers.Main) {
                    binding.textPreviewCpu.text = String.format(Locale.US, "%.1f %% (%.2f GHz)", metrics.cpuUsage, metrics.cpuFrequencyGhz)
                    binding.textPreviewGpu.text = String.format(Locale.US, "%.1f %%", metrics.gpuUsage)
                    val batStr = metrics.formatTemperature(metrics.batteryTemperature, unit)
                    binding.textPreviewBattery.text = String.format(Locale.US, "%s (%.2f W)", batStr, metrics.batteryPowerWatts)
                    binding.textPreviewMemory.text = String.format(Locale.US, "%d MB (%.1f %%)", metrics.memoryUsageMb, metrics.memoryUsagePercentage)
                }
                delay(1000L)
            }
        }
    }
}
