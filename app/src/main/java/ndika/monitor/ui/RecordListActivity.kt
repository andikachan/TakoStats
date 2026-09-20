package ndika.monitor.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.data.BenchmarkDatabaseHelper
import ndika.monitor.data.SessionRecord
import ndika.monitor.databinding.ActivityRecordListBinding
import ndika.monitor.databinding.ItemRecordBinding
import java.util.Locale

class RecordListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordListBinding
    private lateinit var db: BenchmarkDatabaseHelper
    private val recordsList = mutableListOf<SessionRecord>()
    private lateinit var adapter: RecordAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = BenchmarkDatabaseHelper.getInstance(this)

        setupToolbar()
        setupRecyclerView()
    }

    override fun onResume() {
        super.onResume()
        loadRecords()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, R.string.delete)?.setIcon(R.drawable.ic_delete_24)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            if (recordsList.isEmpty()) {
                Toast.makeText(this, R.string.no_records_found, Toast.LENGTH_SHORT).show()
                return true
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.deleteAllRecords()
                        withContext(Dispatchers.Main) {
                            loadRecords()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        adapter = RecordAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadRecords() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.getAllRecords()
            withContext(Dispatchers.Main) {
                recordsList.clear()
                recordsList.addAll(list)
                adapter.notifyDataSetChanged()
                binding.layoutEmpty.visibility = if (recordsList.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    inner class RecordAdapter : RecyclerView.Adapter<RecordAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = recordsList[position]
            holder.binding.textRecordAppName.text = item.appName
            holder.binding.textRecordDate.text = "${item.getFormattedDate()} • ${item.getFormattedDuration()}"
            holder.binding.badgeAvgFps.text = String.format(Locale.US, "%.1f FPS", item.avgFps)
            holder.binding.textStatP95.text = String.format(Locale.US, "%.1f FPS", item.p95Fps)
            holder.binding.textStatMaxFt.text = String.format(Locale.US, "%.1f ms", item.maxFrameTimeMs)
            holder.binding.textStatPower.text = String.format(Locale.US, "%.2f W", item.avgPowerWatts)

            holder.itemView.setOnClickListener {
                val intent = Intent(this@RecordListActivity, RecordActivity::class.java).apply {
                    putExtra(RecordActivity.EXTRA_RECORD_ID, item.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount(): Int = recordsList.size
    }
}
