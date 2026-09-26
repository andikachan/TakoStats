package ndika.monitor.ui

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.databinding.ActivitySettingsBinding
import ndika.monitor.storage.StorageManager
import ndika.monitor.storage.StorageType
import ndika.monitor.storage.StorageVolumeInfo
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val documentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                handleCustomFolderSelected(uri)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            setupStoragePreferences()
        }

        private fun setupStoragePreferences() {
            val context = requireContext()

            // 1. Storage Location Preference
            val storagePref = findPreference<Preference>("storage_location")
            updateStorageLocationSummary(storagePref)

            storagePref?.setOnPreferenceClickListener {
                showStorageSelectionDialog(storagePref)
                true
            }

            // 2. Data Migration Preference
            val migratePref = findPreference<Preference>("migrate_data")
            migratePref?.setOnPreferenceClickListener {
                showMigrationConfirmationDialog()
                true
            }

            // 3. Auto Export Preference
            val autoExportPref = findPreference<SwitchPreferenceCompat>("auto_export_to_external")
            autoExportPref?.isChecked = StorageManager.isAutoExportToExternal(context)
            autoExportPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                StorageManager.setAutoExportToExternal(context, enabled)
                true
            }

            // 4. Game Data and OBB Relocator Preference
            val relocatorPref = findPreference<Preference>("game_relocator")
            relocatorPref?.setOnPreferenceClickListener {
                ndika.monitor.storage.ui.GameDataRelocatorActivity.start(context)
                true
            }

            // 5. Clean Cache Preference
            val cleanCachePref = findPreference<Preference>("clean_cache")
            cleanCachePref?.setOnPreferenceClickListener {
                val freed = StorageManager.cleanTemporaryCaches(context)
                val freedStr = StorageManager.formatFileSize(freed)
                Toast.makeText(context, getString(R.string.storage_cleaned, freedStr), Toast.LENGTH_SHORT).show()
                true
            }
        }

        private fun updateStorageLocationSummary(pref: Preference?) {
            val context = context ?: return
            val active = StorageManager.getActiveVolume(context)
            pref?.summary = "${active.name} (${active.capacitySummary})\n${active.description}"
        }

        private fun showStorageSelectionDialog(pref: Preference?) {
            val context = context ?: return
            val volumes = StorageManager.getAvailableVolumes(context)
            val active = StorageManager.getActiveVolume(context)

            val items = mutableListOf<String>()
            var selectedIndex = 0

            for (i in volumes.indices) {
                val v = volumes[i]
                if (v.id == active.id) {
                    selectedIndex = i
                }
                items.add("${v.name}\n${v.capacitySummary} - ${v.description}")
            }

            // Add custom folder picker option at the bottom
            items.add(getString(R.string.choose_custom_folder))

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.select_storage_dialog_title)
                .setSingleChoiceItems(items.toTypedArray(), selectedIndex) { dialog, which ->
                    dialog.dismiss()
                    if (which < volumes.size) {
                        val chosen = volumes[which]
                        StorageManager.setActiveVolume(context, chosen.id, chosen.path?.absolutePath, chosen.uriString)
                        updateStorageLocationSummary(pref)
                        Toast.makeText(context, "${chosen.name} selected as active storage.", Toast.LENGTH_SHORT).show()
                    } else {
                        // Open System Document Tree Picker
                        try {
                            documentTreeLauncher.launch(null)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Cannot open file manager: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun handleCustomFolderSelected(uri: Uri) {
            val context = context ?: return
            try {
                // Persist folder permission across reboots
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                val path = uri.path
                StorageManager.setActiveVolume(
                    context = context,
                    volumeId = StorageManager.ID_CUSTOM,
                    customPath = path,
                    customUri = uri.toString()
                )

                val storagePref = findPreference<Preference>("storage_location")
                updateStorageLocationSummary(storagePref)
                Toast.makeText(context, "Custom storage directory set: $path", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to set custom storage: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showMigrationConfirmationDialog() {
            val context = context ?: return
            val active = StorageManager.getActiveVolume(context)

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.migrate_data_title)
                .setMessage("Move all benchmark records, database, and export files to:\n\n${active.name} (${active.description})\n\nThis will safely relocate files and free up your internal phone storage.")
                .setPositiveButton("Migrate") { _, _ ->
                    startMigration(active)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        @Suppress("DEPRECATION")
        private fun startMigration(targetVolume: StorageVolumeInfo) {
            val context = context ?: return
            val progressDialog = ProgressDialog(context).apply {
                setTitle(getString(R.string.migrate_data_title))
                setMessage(getString(R.string.migrating_data_progress))
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setCancelable(false)
                show()
            }

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    StorageManager.migrateData(context, targetVolume) { percent, status ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            progressDialog.progress = percent
                            progressDialog.setMessage(status)
                        }
                    }
                }

                progressDialog.dismiss()

                result.onSuccess { msg ->
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.storage_migration_success)
                        .setMessage(msg)
                        .setPositiveButton(R.string.ok, null)
                        .show()

                    val storagePref = findPreference<Preference>("storage_location")
                    updateStorageLocationSummary(storagePref)
                }.onFailure { err ->
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.storage_migration_failed)
                        .setMessage(err.message ?: "Unknown error occurred during data migration.")
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }
    }
}
