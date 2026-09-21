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
import ndika.monitor.R
import ndika.monitor.booster.model.BoostProgressState
import ndika.monitor.booster.model.WatchdogAlert
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
        viewModel.checkShizukuStatus()
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
            viewModel.applyPreGameBoost()
        }

        binding.btnRestoreDefaults.setOnClickListener {
            viewModel.applyPostGameRestore()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        updateShizukuBanner(state.isShizukuAvailable)
                        updateWatchdogView(state)
                        updateBoostProgressView(state.boostProgressState)

                        if (state.statusMessage.isNotBlank()) {
                            Toast.makeText(this@GameBoosterActivity, state.statusMessage, Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                launch {
                    viewModel.watchdogAlerts.collect { alert ->
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

    private fun updateWatchdogView(state: ndika.monitor.booster.presentation.GameBoosterUiState) {
        val watchdog = state.watchdogStatus

        binding.tvBatteryTemp.text = String.format(Locale.ROOT, "%.1f °C", watchdog.batteryTempCelsius)
        binding.tvThermalThrottleWarning.visibility = if (watchdog.isThermalThrottling) View.VISIBLE else View.GONE

        binding.tvAvailableRam.text = "${watchdog.availableRamMb} MB"
        binding.tvLowRamWarning.visibility = if (watchdog.isLowMemory) View.VISIBLE else View.GONE

        binding.progressRamUsage.progress = watchdog.ramUsagePercent.toInt().coerceIn(0, 100)
    }

    private fun updateBoostProgressView(state: BoostProgressState) {
        when (state) {
            is BoostProgressState.Idle -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true
            }
            is BoostProgressState.InProgress -> {
                binding.progressBoost.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.text = state.message
                binding.btnBoostNow.isEnabled = false
                binding.cardBoostResults.visibility = View.GONE
            }
            is BoostProgressState.Success -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true

                binding.cardBoostResults.visibility = View.VISIBLE
                val reportBuilder = StringBuilder()
                reportBuilder.append("✨ Boost Status: SUCCESS\n")
                reportBuilder.append(" Freed RAM: ${state.freedRamMb} MB\n")
                reportBuilder.append(" Purged Apps: ${state.purgedAppsCount}\n")
                reportBuilder.append(" Storage TRIM: ${if (state.isStorageTrimmed) "Optimized" else "Skipped"}\n")
                reportBuilder.append(" PowerHAL Fixed Perf: ${if (state.isPerformanceModeActive) "Enabled" else "N/A"}\n\n")
                reportBuilder.append("Details:\n")
                for (line in state.logDetails) {
                    reportBuilder.append(line).append("\n")
                }
                binding.tvBoostResultsLog.text = reportBuilder.toString()
            }
            is BoostProgressState.Error -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                binding.btnBoostNow.isEnabled = true
                Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun handleWatchdogAlert(alert: WatchdogAlert) {
        when (alert) {
            is WatchdogAlert.HighTemperature -> {
                Snackbar.make(
                    binding.root,
                    "⚠️ High Thermal Alert: Device battery is ${alert.tempCelsius}°C. Thermal throttling may occur.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            is WatchdogAlert.LowMemory -> {
                Snackbar.make(
                    binding.root,
                    "⚠️ Low Memory Alert: Free RAM dropped to ${alert.availableMb} MB. Background apps may stutter.",
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
