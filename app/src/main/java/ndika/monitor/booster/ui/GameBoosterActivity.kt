package ndika.monitor.booster.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import ndika.monitor.R
import ndika.monitor.booster.model.BoostMode
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.WatchdogAlertEvent
import ndika.monitor.booster.presentation.GameBoosterUiState
import ndika.monitor.booster.presentation.GameBoosterViewModel
import ndika.monitor.databinding.ActivityGameBoosterBinding
import ndika.monitor.databinding.DialogSelectGameBinding
import ndika.monitor.databinding.ItemGameSelectBinding
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

        binding.rgBoostMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbExtremeBeast) {
                viewModel.setBoostMode(BoostMode.EXTREME_BEAST)
                binding.tvModeBadge.text = "EXTREME BEAST"
                binding.tvModeBadge.setBackgroundColor(Color.parseColor("#D32F2F"))
                binding.tvModeDescription.text = "Maximum uncapped performance. Thermal throttling bypassed, PowerHAL fixed clocks locked, 100% AOT native compilation, and background frozen."
            } else {
                viewModel.setBoostMode(BoostMode.STANDARD)
                binding.tvModeBadge.text = "STANDARD"
                binding.tvModeBadge.setBackgroundColor(Color.parseColor("#1976D2"))
                binding.tvModeDescription.text = "Balanced gaming optimization. Clears RAM, trims flash storage, and optimizes display refresh rate without extreme thermal bypass."
            }
        }

        binding.rgResolutionPreset.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbResNative -> {
                    viewModel.setResolutionPreset(ndika.monitor.booster.model.ResolutionDownscalePreset.NATIVE)
                    binding.tvResolutionBadge.text = "100% NATIVE"
                    binding.tvResolutionBadge.setBackgroundColor(Color.parseColor("#2E7D32"))
                }
                R.id.rbRes85 -> {
                    viewModel.setResolutionPreset(ndika.monitor.booster.model.ResolutionDownscalePreset.BALANCED_85)
                    binding.tvResolutionBadge.text = "85% RESOLUTION"
                    binding.tvResolutionBadge.setBackgroundColor(Color.parseColor("#1976D2"))
                }
                R.id.rbRes70 -> {
                    viewModel.setResolutionPreset(ndika.monitor.booster.model.ResolutionDownscalePreset.SPEED_70)
                    binding.tvResolutionBadge.text = "70% RESOLUTION"
                    binding.tvResolutionBadge.setBackgroundColor(Color.parseColor("#E65100"))
                }
                R.id.rbRes50 -> {
                    viewModel.setResolutionPreset(ndika.monitor.booster.model.ResolutionDownscalePreset.EXTREME_50)
                    binding.tvResolutionBadge.text = "50% TURBO"
                    binding.tvResolutionBadge.setBackgroundColor(Color.parseColor("#D32F2F"))
                }
            }
        }

        binding.btnChangeGame.setOnClickListener {
            showGameSelectionDialog()
        }

        binding.btnCompileAotOnly.setOnClickListener {
            viewModel.compileTargetAppAot()
        }

        binding.btnBoostAndLaunch.setOnClickListener {
            viewModel.boostGame(launchAfterBoost = true)
        }

        binding.btnBoostNow.setOnClickListener {
            viewModel.boostGame(launchAfterBoost = false)
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
                        updateSelectedGameView(state.selectedGame)
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
            binding.tvShizukuDesc.text = "Shizuku is required for thermal bypass, PowerHAL & AOT compile."
            binding.btnGrantShizuku.visibility = View.VISIBLE
            binding.imgShizukuIcon.setColorFilter(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun updateSelectedGameView(game: GameAppInfo?) {
        if (game != null) {
            binding.tvSelectedGameName.text = game.appName
            binding.tvSelectedGamePackage.text = game.packageName
            if (game.icon != null) {
                binding.imgSelectedGameIcon.setImageDrawable(game.icon)
            } else {
                binding.imgSelectedGameIcon.setImageResource(R.drawable.ic_stat_fps)
            }
            binding.btnBoostAndLaunch.text = "BOOST & LAUNCH ${game.appName.uppercase()}"
            binding.btnCompileAotOnly.text = "AOT Native Compile (${game.appName})"
            binding.btnCompileAotOnly.visibility = View.VISIBLE
        } else {
            binding.tvSelectedGameName.text = "No Target Game Selected"
            binding.tvSelectedGamePackage.text = "Tap 'Change' to select game for AOT compile & core pinning"
            binding.imgSelectedGameIcon.setImageResource(R.drawable.ic_stat_fps)
            binding.btnBoostAndLaunch.text = "BOOST & LAUNCH GAME"
            binding.btnCompileAotOnly.visibility = View.GONE
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
                setButtonsEnabled(true)
            }
            is BoostProgress.InProgress -> {
                binding.progressBoost.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.visibility = View.VISIBLE
                binding.tvBoostProgressMessage.text = progress.message
                setButtonsEnabled(false)
                binding.cardBoostResults.visibility = View.GONE
            }
            is BoostProgress.Success -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                setButtonsEnabled(true)

                binding.cardBoostResults.visibility = View.VISIBLE
                val reportBuilder = StringBuilder()
                reportBuilder.append("BOOST STATUS: 100% SUCCESS\n")
                if (progress.freedRamMb > 0) {
                    reportBuilder.append("• Freed RAM: ${progress.freedRamMb} MB\n")
                }
                if (progress.purgedAppsCount > 0) {
                    reportBuilder.append("• Purged Background Apps: ${progress.purgedAppsCount}\n")
                }
                reportBuilder.append("• Thermal Override: ${if (progress.isThermalBypassed) "Engaged (Status 0)" else "Standard"}\n")
                reportBuilder.append("• PowerHAL Fixed Mode: ${if (progress.isPerformanceModeActive) "Locked" else "Standard"}\n")
                reportBuilder.append("• 120Hz & SurfaceFlinger: ${if (progress.isGraphicsTuned) "Overdriven" else "Standard"}\n")
                reportBuilder.append("• AOT Native Machine Compile: ${if (progress.isAotCompiled) "Completed" else "Ready"}\n\n")
                reportBuilder.append("Execution Details:\n")
                for (line in progress.summaryLogs) {
                    reportBuilder.append(line).append("\n")
                }
                binding.tvBoostResultsLog.text = reportBuilder.toString()
            }
            is BoostProgress.Error -> {
                binding.progressBoost.visibility = View.GONE
                binding.tvBoostProgressMessage.visibility = View.GONE
                setButtonsEnabled(true)
                Snackbar.make(binding.root, progress.errorMessage, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnBoostAndLaunch.isEnabled = enabled
        binding.btnBoostNow.isEnabled = enabled
        binding.btnRestoreDefaults.isEnabled = enabled
        binding.btnCompileAotOnly.isEnabled = enabled
        binding.btnChangeGame.isEnabled = enabled
    }

    private fun showGameSelectionDialog() {
        val games = viewModel.uiState.value.installedGames
        if (games.isEmpty()) {
            Toast.makeText(this, "Loading installed games...", Toast.LENGTH_SHORT).show()
            viewModel.loadInstalledGames()
            return
        }

        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogSelectGameBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val filteredGames = mutableListOf<GameAppInfo>()
        filteredGames.addAll(games)

        val adapter = GameSelectAdapter(filteredGames) { selected ->
            viewModel.selectGame(selected)
            dialog.dismiss()
        }

        dialogBinding.rvGameList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvGameList.adapter = adapter

        dialogBinding.searchGames.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText?.trim()?.lowercase() ?: ""
                filteredGames.clear()
                if (q.isEmpty()) {
                    filteredGames.addAll(games)
                } else {
                    filteredGames.addAll(games.filter {
                        it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                    })
                }
                adapter.notifyDataSetChanged()
                return true
            }
        })

        dialogBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun handleWatchdogAlert(alert: WatchdogAlertEvent) {
        when (alert) {
            is WatchdogAlertEvent.OverheatingAlert -> {
                Snackbar.make(
                    binding.root,
                    "Thermal Notice: Battery temperature is ${alert.tempCelsius}°C. Hardware cooling recommended.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            is WatchdogAlertEvent.LowRamAlert -> {
                Snackbar.make(
                    binding.root,
                    "Low Memory Notice: Free RAM is ${alert.freeRamMb} MB.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private class GameSelectAdapter(
        private val list: List<GameAppInfo>,
        private val onSelect: (GameAppInfo) -> Unit
    ) : RecyclerView.Adapter<GameSelectAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemGameSelectBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemGameSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.binding.tvGameTitle.text = item.appName
            holder.binding.tvGamePackage.text = item.packageName
            if (item.icon != null) {
                holder.binding.imgGameIcon.setImageDrawable(item.icon)
            } else {
                holder.binding.imgGameIcon.setImageResource(R.drawable.ic_stat_fps)
            }
            holder.binding.tvGameBadge.visibility = if (item.isGameCategory) View.VISIBLE else View.GONE

            holder.binding.root.setOnClickListener {
                onSelect(item)
            }
        }

        override fun getItemCount(): Int = list.size
    }

    companion object {
        private const val SHIZUKU_REQ_CODE = 1001

        fun start(context: Context) {
            val intent = Intent(context, GameBoosterActivity::class.java)
            context.startActivity(intent)
        }
    }
}
