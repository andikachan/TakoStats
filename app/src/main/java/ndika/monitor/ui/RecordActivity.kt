package ndika.monitor.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ndika.monitor.R
import ndika.monitor.data.BenchmarkDatabaseHelper
import ndika.monitor.data.SessionRecord
import ndika.monitor.databinding.ActivityRecordBinding
import ndika.monitor.ui.chart.MultiLineChartView
import ndika.monitor.util.ExportHelper
import java.util.Locale

class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding
    private lateinit var db: BenchmarkDatabaseHelper
    private var record: SessionRecord? = null
    private var recordId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = BenchmarkDatabaseHelper.getInstance(this)
        recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)

        setupToolbar()
        loadRecordData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, R.string.share)?.setIcon(R.drawable.ic_share_24)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu?.add(0, 2, 0, R.string.delete)?.setIcon(R.drawable.ic_delete_24)?.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                record?.let {
                    ExportHelper.shareRecordZip(this, it)
                }
                return true
            }
            2 -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(R.string.delete_confirm_msg)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.deleteRecord(recordId)
                            withContext(Dispatchers.Main) {
                                finish()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadRecordData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = db.getRecordById(recordId)
            withContext(Dispatchers.Main) {
                if (loaded == null) {
                    Toast.makeText(this@RecordActivity, R.string.no_records_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                record = loaded
                bindRecordToUi(loaded)
            }
        }
    }

    private fun bindRecordToUi(rec: SessionRecord) {
        binding.textAppName.text = rec.appName
        binding.textSessionMeta.text = "${rec.getFormattedDate()} • ${rec.getFormattedDuration()} • ${rec.packageName}"

        binding.textAvgFps.text = String.format(Locale.US, "%.1f", rec.avgFps)
        binding.textP95Fps.text = String.format(Locale.US, "%.1f", rec.p95Fps)
        binding.textP99Fps.text = String.format(Locale.US, "%.1f", rec.p99Fps)
        binding.textMaxFt.text = String.format(Locale.US, "%.1f ms", rec.maxFrameTimeMs)
        binding.textTotalFrames.text = String.format(Locale.US, "%,d", rec.totalFrames)
        binding.textAvgPower.text = String.format(Locale.US, "%.2f W", rec.avgPowerWatts)

        val telemetry = rec.parseTelemetrySamples()
        val frameTimes = rec.parseFrameTimes()

        // 1. Setup FPS Chart
        val fpsPoints = if (telemetry.isNotEmpty()) {
            telemetry.map { it.fps }.toFloatArray()
        } else {
            frameTimes.map { if (it > 0f) 1000f / it else 0f }.toFloatArray()
        }
        binding.chartFps.setFpsData(fpsPoints, rec.avgFps, rec.p95Fps, rec.p99Fps)

        // 2. Setup Frame Times Chart
        binding.chartFrametimes.setFrameTimes(frameTimes)

        // 3. Setup CPU Utilization Chart
        val cpuPoints = telemetry.map { it.cpuUsage }.toFloatArray()
        binding.chartCpu.setData(cpuPoints, customMin = 0f, customMax = 100f, unit = " %")

        // 4. Setup Temperature Chart
        val cpuTempPoints = telemetry.map { it.cpuTemp }.toFloatArray()
        val gpuTempPoints = telemetry.map { it.gpuTemp }.toFloatArray()
        val batTempPoints = telemetry.map { it.batTemp }.toFloatArray()
        val skinTempPoints = telemetry.map { it.skinTemp }.toFloatArray()

        val tempSeries = mutableListOf<MultiLineChartView.Series>()
        if (cpuTempPoints.any { it > 0f }) tempSeries.add(MultiLineChartView.Series("CPU", Color.parseColor("#FF5252"), cpuTempPoints))
        if (gpuTempPoints.any { it > 0f }) tempSeries.add(MultiLineChartView.Series("GPU", Color.parseColor("#7C4DFF"), gpuTempPoints))
        if (batTempPoints.any { it > 0f }) tempSeries.add(MultiLineChartView.Series("Battery", Color.parseColor("#00E676"), batTempPoints))
        if (skinTempPoints.any { it > 0f }) tempSeries.add(MultiLineChartView.Series("Skin", Color.parseColor("#FF9800"), skinTempPoints))
        binding.chartTemperature.setSeries(tempSeries, " °C")

        // 5. Setup Power Chart
        val powerPoints = telemetry.map { it.powerW }.toFloatArray()
        val voltPoints = telemetry.map { it.voltageV }.toFloatArray()
        val currPoints = telemetry.map { it.currentA }.toFloatArray()

        val powerSeries = mutableListOf<MultiLineChartView.Series>()
        if (powerPoints.any { it > 0f }) powerSeries.add(MultiLineChartView.Series("Power (W)", Color.parseColor("#FFD600"), powerPoints))
        if (voltPoints.any { it > 0f }) powerSeries.add(MultiLineChartView.Series("Voltage (V)", Color.parseColor("#40C4FF"), voltPoints))
        if (currPoints.any { it > 0f }) powerSeries.add(MultiLineChartView.Series("Current (A)", Color.parseColor("#FF4081"), currPoints))
        binding.chartPower.setSeries(powerSeries, "")
    }

    companion object {
        const val EXTRA_RECORD_ID = "extra_record_id"
    }
}
