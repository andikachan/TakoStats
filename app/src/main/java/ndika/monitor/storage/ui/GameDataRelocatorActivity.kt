package ndika.monitor.storage.ui

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.databinding.ActivityGameDataRelocatorBinding
import ndika.monitor.databinding.ItemGameRelocatorBinding
import ndika.monitor.shizuku.ShizukuManager
import ndika.monitor.storage.GameDataRelocator
import ndika.monitor.storage.StorageManager
import ndika.monitor.storage.StoragePermissionHelper
import ndika.monitor.storage.StorageType
import ndika.monitor.storage.StorageVolumeInfo
import ndika.monitor.storage.model.GameStorageInfo

class GameDataRelocatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameDataRelocatorBinding
    private val allGamesList = mutableListOf<GameStorageInfo>()
    private val filteredList = mutableListOf<GameStorageInfo>()
    private lateinit var adapter: GameStorageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameDataRelocatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        updatePermissionBanner()
        loadGamesStorageData()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        loadGamesStorageData()
    }

    private fun updatePermissionBanner() {
        val hasAllFiles = StoragePermissionHelper.hasAllFilesAccess(this)
        val hasUsage = StoragePermissionHelper.hasUsageStatsPermission(this)
        val hasShizukuOrRoot = StoragePermissionHelper.hasShizukuOrRootAccess()

        val needsBanner = !hasAllFiles || (!hasUsage && !hasShizukuOrRoot)
        binding.cardPermissionWarning.visibility = if (needsBanner) View.VISIBLE else View.GONE
        binding.btnGrantPermission.visibility = if (!hasAllFiles) View.VISIBLE else View.GONE
        binding.btnGrantUsageAccess.visibility = if (!hasUsage) View.VISIBLE else View.GONE
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = GameStorageAdapter(
            items = filteredList,
            onRelocateClick = { game -> showRelocateTargetDialog(game) },
            onRestoreClick = { game -> showRestoreConfirmationDialog(game) }
        )
        binding.rvGameStorageList.layoutManager = LinearLayoutManager(this)
        binding.rvGameStorageList.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            StoragePermissionHelper.requestAllFilesAccess(this)
        }

        binding.btnGrantUsageAccess.setOnClickListener {
            StoragePermissionHelper.requestUsageStatsPermission(this)
        }

        binding.searchGames.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val q = newText?.trim()?.lowercase() ?: ""
                filteredList.clear()
                if (q.isEmpty()) {
                    filteredList.addAll(allGamesList)
                } else {
                    filteredList.addAll(allGamesList.filter {
                        it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                    })
                }
                adapter.notifyDataSetChanged()
                binding.tvEmptyState.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
                return true
            }
        })

        binding.btnReMountAll.setOnClickListener {
            lifecycleScope.launch {
                val count = GameDataRelocator.ensureAllRelocatedGamesMounted(this@GameDataRelocatorActivity)
                Toast.makeText(this@GameDataRelocatorActivity, "Verified and mounted $count external game data links.", Toast.LENGTH_SHORT).show()
                loadGamesStorageData()
            }
        }
    }

    private fun loadGamesStorageData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmptyState.visibility = View.GONE

        lifecycleScope.launch {
            val games = GameDataRelocator.getInstalledGamesStorageInfo(this@GameDataRelocatorActivity)
            allGamesList.clear()
            allGamesList.addAll(games)

            val query = binding.searchGames.query?.toString()?.trim()?.lowercase() ?: ""
            filteredList.clear()
            if (query.isEmpty()) {
                filteredList.addAll(allGamesList)
            } else {
                filteredList.addAll(allGamesList.filter {
                    it.appName.lowercase().contains(query) || it.packageName.lowercase().contains(query)
                })
            }

            binding.progressBar.visibility = View.GONE
            adapter.notifyDataSetChanged()
            binding.tvEmptyState.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showRelocateTargetDialog(game: GameStorageInfo) {
        val allVolumes = StorageManager.getAvailableVolumes(this)
        // Only show external / removable / custom storage volumes
        val externalVolumes = allVolumes.filter { it.type != StorageType.INTERNAL_APP }

        if (externalVolumes.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No External Storage Found")
                .setMessage("Please insert a MicroSD card or connect a USB OTG flash drive to move game data and free up internal memory.")
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val items = externalVolumes.map { v ->
            "${v.name}\n${v.capacitySummary} - ${v.description}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Select Destination Storage")
            .setItems(items) { _, which ->
                val chosenVolume = externalVolumes[which]
                startRelocationProcess(game, chosenVolume)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun startRelocationProcess(game: GameStorageInfo, targetVolume: StorageVolumeInfo) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Relocating ${game.appName}")
            setMessage("Moving game data to ${targetVolume.name}...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GameDataRelocator.relocateGameData(
                    context = this@GameDataRelocatorActivity,
                    gamePkg = game.packageName,
                    targetVolume = targetVolume
                ) { percent, status ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.progress = percent
                        progressDialog.setMessage(status)
                    }
                }
            }

            progressDialog.dismiss()

            result.onSuccess { msg ->
                MaterialAlertDialogBuilder(this@GameDataRelocatorActivity)
                    .setTitle("Relocation Complete")
                    .setMessage(msg)
                    .setPositiveButton(R.string.ok, null)
                    .show()
                loadGamesStorageData()
            }.onFailure { err ->
                MaterialAlertDialogBuilder(this@GameDataRelocatorActivity)
                    .setTitle("Relocation Failed")
                    .setMessage(err.message ?: "Failed to relocate game data.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private fun showRestoreConfirmationDialog(game: GameStorageInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore to Internal Storage")
            .setMessage("Move ${game.appName} data back from ${game.targetVolumeName ?: "external storage"} to internal phone memory?")
            .setPositiveButton("Restore") { _, _ ->
                startRestoreProcess(game)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun startRestoreProcess(game: GameStorageInfo) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Restoring ${game.appName}")
            setMessage("Moving data back to internal storage...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GameDataRelocator.restoreGameData(
                    context = this@GameDataRelocatorActivity,
                    gamePkg = game.packageName
                ) { percent, status ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        progressDialog.progress = percent
                        progressDialog.setMessage(status)
                    }
                }
            }

            progressDialog.dismiss()

            result.onSuccess { msg ->
                Toast.makeText(this@GameDataRelocatorActivity, msg, Toast.LENGTH_SHORT).show()
                loadGamesStorageData()
            }.onFailure { err ->
                MaterialAlertDialogBuilder(this@GameDataRelocatorActivity)
                    .setTitle("Restore Failed")
                    .setMessage(err.message ?: "Failed to restore game data.")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
        }
    }

    private class GameStorageAdapter(
        private val items: List<GameStorageInfo>,
        private val onRelocateClick: (GameStorageInfo) -> Unit,
        private val onRestoreClick: (GameStorageInfo) -> Unit
    ) : RecyclerView.Adapter<GameStorageAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemGameRelocatorBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemGameRelocatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvGameName.text = item.appName
            holder.binding.tvGamePackage.text = item.packageName
            holder.binding.tvStorageSizeSummary.text = item.sizeSummary

            if (item.icon != null) {
                holder.binding.imgGameIcon.setImageDrawable(item.icon)
            } else {
                holder.binding.imgGameIcon.setImageResource(R.drawable.ic_stat_fps)
            }

            if (item.isRelocated) {
                holder.binding.tvStorageStatusBadge.text = if (item.isMounted) "RELOCATED (MOUNTED)" else "RELOCATED (UNLINKED)"
                holder.binding.tvStorageStatusBadge.setBackgroundColor(Color.parseColor(if (item.isMounted) "#2E7D32" else "#E65100"))
                holder.binding.tvRelocatedPathDesc.visibility = View.VISIBLE
                holder.binding.tvRelocatedPathDesc.text = "Target: ${item.targetVolumeName ?: "External Storage"}\n${item.targetDataPath ?: ""}"

                holder.binding.btnRestoreToInternal.visibility = View.VISIBLE
                holder.binding.btnRelocateToExternal.text = "Change Drive"
            } else {
                holder.binding.tvStorageStatusBadge.text = "INTERNAL"
                holder.binding.tvStorageStatusBadge.setBackgroundColor(Color.parseColor("#1976D2"))
                holder.binding.tvRelocatedPathDesc.visibility = View.GONE

                holder.binding.btnRestoreToInternal.visibility = View.GONE
                holder.binding.btnRelocateToExternal.text = "Move to SD / OTG"
            }

            holder.binding.btnRelocateToExternal.setOnClickListener {
                onRelocateClick(item)
            }

            holder.binding.btnRestoreToInternal.setOnClickListener {
                onRestoreClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GameDataRelocatorActivity::class.java)
            context.startActivity(intent)
        }
    }
}
