package ndika.monitor.booster.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.WatchdogAlertEvent
import ndika.monitor.booster.presentation.GameBoosterUiState
import ndika.monitor.booster.presentation.GameBoosterViewModel
import ndika.monitor.databinding.ActivityGameBoosterBinding
import ndika.monitor.shizuku.ShizukuManager
import java.util.Locale

class GameBoosterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBoosterBinding
    private val viewModel: GameBoosterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBoosterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        binding.btnGrantShizuku.setOnClickListener {
            ShizukuManager.requestPermission(SHIZUKU_REQ_CODE)
        }

        binding.btnBoostNow.setOnClickListener {
            viewModel.boostGame()
        }

        binding.btnRestoreDefaults.setOnClickListener {
            viewModel.restoreDefaults()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateShizukuBanner(state.isShizukuReady)
                        updateWatchdogView(state)
                        updateBoostProgressView(state)

                        if (!state.statusMessage.isNullOrBlank() && !state.isBoosting) {
                            Toast.makeText(this@GameBoosterActivity, state.statusMessage, Toast.LENGTH_SHORT).show()
                        }

                        if (!state.errorMessage.isNullOrBlank()) {
                            Snackbar.make(binding.root, state.errorMessage, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }

                launch {
                    viewModel.alertEvents.collect { alert ->
                        handleWatchdogAlert(alert)
                    }
                }
            }
        }
    }

    private fun updateShizukuBanner(isShizukuReady: Boolean) {
        if (isShizukuReady) {
            binding.tvShizukuTitle.text = "Shizuku ADB Active"
            binding.tvShizukuDesc.text = "Elevated system executor connected and ready."
            binding.btnGrantShizuku.visibility = View.GONE
            binding.imgShizukuIcon.setColorFilter(getColor(android.R.color.holo_green_dark))
        } else {
            binding.tvShizukuTitle.text = "Shizuku Not Authorized"
            binding.tvShizukuDesc.text = "Shizuku is required for fstrim, app purge & PowerHAL."
            binding.btnGrantShizuku.visibility = View.VISIBLE
            binding.imgShizukuIcon.setColorFilter(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun updateWatchdogView(state: GameBoosterUiState) {
        val watchdog = state.watchdogData

        binding.tvBatteryTemp.text = String.format(Locale.ROOT, "%.1f °C", watchdog.batteryTemp)
        binding.tvThermalThrottleWarning.visibility = if (watchdog.isOverheating) View.VISIBLE else View.GONE

        binding.tvAvailableRam.text = "${watchdog.availableRamMb} MB"
        binding.tvLowRamWarning.visibility = if (watchdog.isLowRam) View.VISIBLE else View.GONE

        binding.progressRamUsage.progress = watchdog.ramUsagePercent.toInt().coerceIn(0, 100)
    }

    private fun updateBoostProgressView(state: GameBoosterUiState) {
        when (val progress = state.boostProgress) {
            is BoostProgress.Idle -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true
                binding.btnRestoreDefaults.isEnabled = true
            }
            is BoostProgress.InProgress -> {
                binding.progressBoost.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.text = progress.message
                binding.btnBoostNow.isEnabled = false
                binding.btnRestoreDefaults.isEnabled = false
                binding.cardBoostResults.visibility = View.GONE
            }
            is BoostProgress.Success -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true
                binding.btnRestoreDefaults.isEnabled = true

                binding.cardBoostResults.visibility = View.VISIBLE
                val reportBuilder = StringBuilder()
                reportBuilder.append("✨ Boost Status: SUCCESS\n")
                if (progress.freedRamMb > 0) {
                    reportBuilder.append("• Freed RAM: ${progress.freedRamMb} MB\n")
                }
                if (progress.purgedAppsCount > 0) {
                    reportBuilder.append("• Purged Apps: ${progress.purgedAppsCount}\n")
                }
                reportBuilder.append("• Storage TRIM: ${if (progress.isStorageTrimmed) "Optimized" else "Skipped"}\n")
                reportBuilder.append("• PowerHAL Fixed Perf: ${if (progress.isPerformanceModeActive) "Enabled" else "Standard"}\n\n")
                reportBuilder.append("Details:\n")
                for (line in progress.summaryLogs) {
                    reportBuilder.append(line).append("\n")
                }
                binding.tvBoostResultsLog.text = reportBuilder.toString()
            }
            is BoostProgress.Error -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true
                binding.btnRestoreDefaults.isEnabled = true
                Snackbar.make(binding.root, progress.errorMessage, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun handleWatchdogAlert(alert: WatchdogAlertEvent) {
        when (alert) {
            is WatchdogAlertEvent.OverheatingAlert -> {
                Snackbar.make(
                    binding.root,
                    "⚠️ Thermal Alert: Battery temperature is ${alert.tempCelsius}°C. Thermal throttling may occur.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            is WatchdogAlertEvent.LowRamAlert -> {
                Snackbar.make(
                    binding.root,
                    "⚠️ Low Memory Alert: Free RAM dropped to ${alert.freeRamMb} MB. Stuttering risk.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 1001

        fun start(context: Context) {
            val intent = Intent(context, GameBoosterActivity::class.java)
            context.startActivity(intent)
        }
    }
}
