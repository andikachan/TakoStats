package ndika.monitor.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.databinding.ActivityAppListBinding
import ndika.monitor.databinding.ItemAppBinding
import ndika.monitor.util.PreferenceManager

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var preferenceManager: PreferenceManager
    private val appList = mutableListOf<AppItem>()
    private var filteredList = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable?,
        var isTarget: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        loadInstalledApps()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText ?: "")
                return true
            }
        })
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        filteredList = if (q.isEmpty()) {
            ArrayList(appList)
        } else {
            appList.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }.toMutableList()
        }
        adapter.notifyDataSetChanged()
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        val selectedTargets = preferenceManager.targetApps.toMutableSet()

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val items = mutableListOf<AppItem>()

            for (app in installed) {
                // Filter out current app and apps without launcher intent unless they are user apps
                if (app.packageName == packageName) continue
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (isSystem && launchIntent == null) continue

                val label = pm.getApplicationLabel(app).toString()
                val icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null }
                val isTarget = selectedTargets.contains(app.packageName)

                items.add(AppItem(label, app.packageName, icon, isTarget))
            }

            items.sortBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                appList.clear()
                appList.addAll(items)
                filteredList = ArrayList(appList)
                adapter.notifyDataSetChanged()
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = filteredList[position]
            holder.binding.textAppName.text = item.name
            holder.binding.textPackageName.text = item.packageName
            if (item.icon != null) {
                holder.binding.imgAppIcon.setImageDrawable(item.icon)
            }
            holder.binding.checkTarget.isChecked = item.isTarget

            holder.binding.checkTarget.setOnCheckedChangeListener { _, isChecked ->
                item.isTarget = isChecked
                val currentTargets = preferenceManager.targetApps.toMutableSet()
                if (isChecked) {
                    currentTargets.add(item.packageName)
                } else {
                    currentTargets.remove(item.packageName)
                }
                preferenceManager.targetApps = currentTargets
            }

            holder.itemView.setOnClickListener {
                holder.binding.checkTarget.toggle()
            }
        }

        override fun getItemCount(): Int = filteredList.size
    }
}
