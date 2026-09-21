package ndika.monitor.booster.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import ndika.monitor.booster.model.BoostProgress
import ndika.monitor.booster.model.BoostStep
import ndika.monitor.booster.model.GameAppInfo
import ndika.monitor.booster.model.WatchdogStatus
import ndika.monitor.booster.presentation.GameBoosterUiState
import ndika.monitor.booster.presentation.GameBoosterViewModel
import ndika.monitor.databinding.ActivityGameBoosterBinding
import ndika.monitor.databinding.ItemGameAppBinding
import rikka.shizuku.Shizuku
import java.util.Locale

class GameBoosterActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 2001

        fun start(context: Context) {
            context.startActivity(Intent(context, GameBoosterActivity::class.java))
        }
    }

    private lateinit var binding: ActivityGameBoosterBinding
    private val viewModel: GameBoosterViewModel by viewModels()
    private lateinit var gameAdapter: GameListAdapter

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            viewModel.checkShizukuStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBoosterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupGameRecyclerView()
        setupButtons()
        observeUiState()
        checkBatteryOptimizations()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupGameRecyclerView() {
        gameAdapter = GameListAdapter { game ->
            viewModel.launchGame(this, game.packageName)
        }
        binding.rvGames.apply {
            layoutManager = LinearLayoutManager(this@GameBoosterActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = gameAdapter
        }
    }

    private fun setupButtons() {
        binding.btnGrantShizuku.setOnClickListener {
            requestShizukuPermission()
        }

        binding.btnBoostNow.setOnClickListener {
            viewModel.runBoost()
        }

        binding.btnRestoreDefaults.setOnClickListener {
            viewModel.runRestore()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderShizukuBanner(state.isShizukuReady)
                    renderWatchdog(state.watchdog)
                    renderBoostProgress(state)
                    renderGamesList(state.installedGames)

                    state.errorMessage?.let { error ->
                        Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun renderShizukuBanner(isReady: Boolean) {
        if (isReady) {
            binding.tvShizukuTitle.text = "Shizuku ADB Access (Active)"
            binding.tvShizukuDesc.text = "Elevated system privileges are ready for booster operations."
            binding.imgShizukuIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnGrantShizuku.visibility = View.GONE
        } else {
            binding.tvShizukuTitle.text = "Shizuku Permission Required"
            binding.tvShizukuDesc.text = "Authorize Shizuku to allow storage trimming and process termination."
            binding.imgShizukuIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            binding.btnGrantShizuku.visibility = View.VISIBLE
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderWatchdog(status: WatchdogStatus?) {
        if (status == null) return

        binding.tvBatteryTemp.text = String.format(Locale.US, "%.1f °C", status.batteryTempC)
        binding.tvThermalThrottleWarning.visibility = if (status.isOverheating) View.VISIBLE else View.GONE

        binding.tvAvailableRam.text = "${status.availableRamMb} MB"
        binding.tvLowRamWarning.visibility = if (status.isLowRam) View.VISIBLE else View.GONE

        binding.progressRamUsage.progress = status.usedRamPercent
    }

    private fun renderBoostProgress(state: GameBoosterUiState) {
        val isBoosting = state.isBoosting
        val progress = state.boostProgress

        binding.btnBoostNow.isEnabled = !isBoosting
        binding.btnRestoreDefaults.isEnabled = !isBoosting

        if (isBoosting && progress != null) {
            binding.progressBoost.visibility = View.VISIBLE
            binding.progressBoost.isIndeterminate = false
            binding.progressBoost.progress = progress.progressPercent
            binding.tvBoostProgressMessage.visibility = View.VISIBLE
            binding.tvBoostProgressMessage.text = progress.message
        } else {
            binding.progressBoost.visibility = View.GONE
            binding.tvBoostProgressMessage.visibility = View.GONE
        }

        if (progress != null && (progress.step == BoostStep.COMPLETED || progress.step == BoostStep.RESTORED)) {
            binding.cardBoostResults.visibility = View.VISIBLE
            binding.tvBoostResultsLog.text = "✓ [${progress.step.name}] ${progress.message}"
        }
    }

    private fun renderGamesList(games: List<GameAppInfo>) {
        if (games.isEmpty()) {
            binding.rvGames.visibility = View.GONE
            binding.tvNoGamesDetected.visibility = View.VISIBLE
        } else {
            binding.rvGames.visibility = View.VISIBLE
            binding.tvNoGamesDetected.visibility = View.GONE
            gameAdapter.submitList(games)
        }
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            } else {
                Snackbar.make(binding.root, "Shizuku service is not running on device.", Snackbar.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Snackbar.make(binding.root, "Unable to request Shizuku permission.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private class GameListAdapter(
        private val onGameClick: (GameAppInfo) -> Unit
    ) : RecyclerView.Adapter<GameListAdapter.GameViewHolder>() {

        private val items = mutableListOf<GameAppInfo>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<GameAppInfo>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
            val binding = ItemGameAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return GameViewHolder(binding)
        }

        override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class GameViewHolder(private val itemBinding: ItemGameAppBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(game: GameAppInfo) {
                itemBinding.tvGameName.text = game.appName
                itemBinding.imgGameIcon.setImageDrawable(game.icon)
                itemBinding.root.setOnClickListener {
                    onGameClick(game)
                }
            }
        }
    }
}
