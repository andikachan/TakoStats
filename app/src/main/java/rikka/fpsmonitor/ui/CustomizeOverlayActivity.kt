package rikka.fpsmonitor.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import rikka.fpsmonitor.R
import rikka.fpsmonitor.databinding.ActivityCustomizeOverlayBinding
import rikka.fpsmonitor.model.OverlayConfig
import rikka.fpsmonitor.overlay.StandaloneOverlayService
import rikka.fpsmonitor.util.PreferenceManager

class CustomizeOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomizeOverlayBinding
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var config: OverlayConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomizeOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferenceManager = PreferenceManager(this)
        config = preferenceManager.loadOverlayConfig()

        setupToolbar()
        populateUiFromConfig()
        setupListeners()
        updatePreview()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun populateUiFromConfig() {
        binding.cbFps.isChecked = config.showFps
        binding.cbLayerName.isChecked = config.showLayerName
        binding.cbCpuUsage.isChecked = config.showCpuUsage
        binding.cbCpuFreq.isChecked = config.showCpuFrequency
        binding.cbCpuTemp.isChecked = config.showCpuTemperature
        binding.cbGpuUsage.isChecked = config.showGpuUsage
        binding.cbGpuTemp.isChecked = config.showGpuTemperature
        binding.cbBatTemp.isChecked = config.showBatteryTemperature
        binding.cbBatCur.isChecked = config.showBatteryCurrent
        binding.cbBatVolt.isChecked = config.showBatteryVoltage
        binding.cbBatPwr.isChecked = config.showBatteryPower
        binding.cbMemMb.isChecked = config.showMemoryMb
        binding.cbMemPct.isChecked = config.showMemoryPercentage
        binding.cbNetUl.isChecked = config.showUploadSpeed
        binding.cbNetDl.isChecked = config.showDownloadSpeed

        binding.switchDraggable.isChecked = config.isDraggable
        binding.switchBackground.isChecked = config.showBackground

        binding.seekTextSize.progress = config.textSizeSp
        binding.textTextSizeValue.text = "${config.textSizeSp} sp"
    }

    private fun setupListeners() {
        val checkListener = {
            config.showFps = binding.cbFps.isChecked
            config.showLayerName = binding.cbLayerName.isChecked
            config.showCpuUsage = binding.cbCpuUsage.isChecked
            config.showCpuFrequency = binding.cbCpuFreq.isChecked
            config.showCpuTemperature = binding.cbCpuTemp.isChecked
            config.showGpuUsage = binding.cbGpuUsage.isChecked
            config.showGpuTemperature = binding.cbGpuTemp.isChecked
            config.showBatteryTemperature = binding.cbBatTemp.isChecked
            config.showBatteryCurrent = binding.cbBatCur.isChecked
            config.showBatteryVoltage = binding.cbBatVolt.isChecked
            config.showBatteryPower = binding.cbBatPwr.isChecked
            config.showMemoryMb = binding.cbMemMb.isChecked
            config.showMemoryPercentage = binding.cbMemPct.isChecked
            config.showUploadSpeed = binding.cbNetUl.isChecked
            config.showDownloadSpeed = binding.cbNetDl.isChecked

            config.isDraggable = binding.switchDraggable.isChecked
            config.showBackground = binding.switchBackground.isChecked

            preferenceManager.saveOverlayConfig(config)
            StandaloneOverlayService.reloadConfig(this)
            updatePreview()
        }

        binding.cbFps.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbLayerName.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbCpuUsage.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbCpuFreq.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbCpuTemp.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbGpuUsage.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbGpuTemp.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatTemp.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatCur.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatVolt.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatPwr.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbMemMb.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbMemPct.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbNetUl.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbNetDl.setOnCheckedChangeListener { _, _ -> checkListener() }

        binding.switchDraggable.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.switchBackground.setOnCheckedChangeListener { _, _ -> checkListener() }

        binding.seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress.coerceAtLeast(8)
                config.textSizeSp = size
                binding.textTextSizeValue.text = "$size sp"
                preferenceManager.saveOverlayConfig(config)
                StandaloneOverlayService.reloadConfig(this@CustomizeOverlayActivity)
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updatePreview() {
        val lines = mutableListOf<String>()
        if (config.showFps) lines.add("FPS   60.0")
        if (config.showCpuUsage) lines.add("CPU   25.3 %")
        if (config.showCpuFrequency) lines.add("FRQ   2.40 GHz")
        if (config.showCpuTemperature) lines.add("CPUT  42.0 °C")
        if (config.showGpuUsage) lines.add("GPU   18.5 %")
        if (config.showGpuTemperature) lines.add("GPUT  40.0 °C")
        if (config.showBatteryTemperature) lines.add("BAT   34.5 °C")
        if (config.showBatteryCurrent) lines.add("CUR   0.45 A")
        if (config.showBatteryVoltage) lines.add("VOLT  4.10 V")
        if (config.showBatteryPower) lines.add("PWR   1.85 W")
        if (config.showMemoryMb) lines.add("RAM   3840 MB")
        if (config.showMemoryPercentage) lines.add("MEM   48.0 %")
        if (config.showDownloadSpeed) lines.add("DL    1.5 MB/s")
        if (config.showUploadSpeed) lines.add("UL    120 KB/s")

        binding.previewOverlayText.textSize = config.textSizeSp.toFloat()
        binding.previewOverlayText.text = if (lines.isNotEmpty()) lines.joinToString("\n") else "FPS   60.0"

        if (config.showBackground) {
            binding.previewOverlayCard.setCardBackgroundColor(config.backgroundColor)
        } else {
            binding.previewOverlayCard.setCardBackgroundColor(Color.TRANSPARENT)
        }
    }
}
