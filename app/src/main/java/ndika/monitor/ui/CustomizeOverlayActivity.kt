package ndika.monitor.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import ndika.monitor.databinding.ActivityCustomizeOverlayBinding
import ndika.monitor.model.OverlayConfig
import ndika.monitor.overlay.StandaloneOverlayService
import ndika.monitor.ui.dialog.ColorPickerDialog
import ndika.monitor.util.PreferenceManager

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
        binding.cbSkinTemp.isChecked = config.showSkinTemperature
        binding.cbBatCur.isChecked = config.showBatteryCurrent
        binding.cbBatVolt.isChecked = config.showBatteryVoltage
        binding.cbBatPwr.isChecked = config.showBatteryPower
        binding.cbFramePwr.isChecked = config.showFramePower
        binding.cbMemMb.isChecked = config.showMemoryMb
        binding.cbMemPct.isChecked = config.showMemoryPercentage
        binding.cbNetUl.isChecked = config.showUploadSpeed
        binding.cbNetDl.isChecked = config.showDownloadSpeed

        binding.switchDraggable.isChecked = config.isDraggable
        binding.switchBackground.isChecked = config.showBackground
        binding.switchHideScreenCaptures.isChecked = config.hideFromScreenCaptures
        binding.switchHideLockScreen.isChecked = config.hideFromLockScreen

        binding.seekTextSize.progress = config.textSizeSp
        binding.textTextSizeValue.text = "${config.textSizeSp} sp"

        binding.seekOffsetX.progress = config.offsetX
        binding.textOffsetXVal.text = "${config.offsetX} dp"

        binding.seekOffsetY.progress = config.offsetY
        binding.textOffsetYVal.text = "${config.offsetY} dp"
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
            config.showSkinTemperature = binding.cbSkinTemp.isChecked
            config.showBatteryCurrent = binding.cbBatCur.isChecked
            config.showBatteryVoltage = binding.cbBatVolt.isChecked
            config.showBatteryPower = binding.cbBatPwr.isChecked
            config.showFramePower = binding.cbFramePwr.isChecked
            config.showMemoryMb = binding.cbMemMb.isChecked
            config.showMemoryPercentage = binding.cbMemPct.isChecked
            config.showUploadSpeed = binding.cbNetUl.isChecked
            config.showDownloadSpeed = binding.cbNetDl.isChecked

            config.isDraggable = binding.switchDraggable.isChecked
            config.showBackground = binding.switchBackground.isChecked
            config.hideFromScreenCaptures = binding.switchHideScreenCaptures.isChecked
            config.hideFromLockScreen = binding.switchHideLockScreen.isChecked

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
        binding.cbSkinTemp.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatCur.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatVolt.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbBatPwr.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbFramePwr.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbMemMb.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbMemPct.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbNetUl.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.cbNetDl.setOnCheckedChangeListener { _, _ -> checkListener() }

        binding.switchDraggable.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.switchBackground.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.switchHideScreenCaptures.setOnCheckedChangeListener { _, _ -> checkListener() }
        binding.switchHideLockScreen.setOnCheckedChangeListener { _, _ -> checkListener() }

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

        binding.seekOffsetX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.offsetX = progress
                binding.textOffsetXVal.text = "$progress dp"
                preferenceManager.saveOverlayConfig(config)
                StandaloneOverlayService.reloadConfig(this@CustomizeOverlayActivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekOffsetY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                config.offsetY = progress
                binding.textOffsetYVal.text = "$progress dp"
                preferenceManager.saveOverlayConfig(config)
                StandaloneOverlayService.reloadConfig(this@CustomizeOverlayActivity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Color Picker Dialogs
        binding.btnTextColor.setOnClickListener {
            ColorPickerDialog(this, config.textColor, showAlpha = true) { selectedColor ->
                config.textColor = selectedColor
                preferenceManager.saveOverlayConfig(config)
                StandaloneOverlayService.reloadConfig(this)
                updatePreview()
            }.show()
        }

        binding.btnBgColor.setOnClickListener {
            ColorPickerDialog(this, config.backgroundColor, showAlpha = true) { selectedColor ->
                config.backgroundColor = selectedColor
                preferenceManager.saveOverlayConfig(config)
                StandaloneOverlayService.reloadConfig(this)
                updatePreview()
            }.show()
        }
    }

    private fun updatePreview() {
        data class Item(val label: String, val value: String, val unit: String)
        val items = mutableListOf<Item>()
        val isCelsius = !config.temperatureUnit.equals("fahrenheit", ignoreCase = true)
        val tempUnitStr = if (isCelsius) "°C" else "°F"

        if (config.showCpuUsage) items.add(Item("CPU", "25.3", " %"))
        if (config.showCpuTemperature) items.add(Item("CPU", "42.0", tempUnitStr))
        if (config.showCpuFrequency) items.add(Item("CPU0", "2400", " MHz"))
        if (config.showGpuUsage) items.add(Item("GPU", "15.0", " %"))
        if (config.showGpuTemperature) items.add(Item("GPU", "40.0", tempUnitStr))
        if (config.showBatteryTemperature) items.add(Item("BAT", "34.5", tempUnitStr))
        if (config.showSkinTemperature) items.add(Item("SKN", "33.0", tempUnitStr))
        if (config.showFps) items.add(Item("FPS", "60.0", ""))
        if (config.showMemoryMb) items.add(Item("MEM", "3840", " MB"))
        if (config.showMemoryPercentage) items.add(Item("MEM", "48.0", " %"))
        if (config.showUploadSpeed) items.add(Item("UL", "120.0", " KiB/s"))
        if (config.showDownloadSpeed) items.add(Item("DL", "1.5", " MiB/s"))
        if (config.showBatteryCurrent) items.add(Item("CUR", "0.450", " A"))
        if (config.showBatteryVoltage) items.add(Item("VOLT", "4.100", " V"))
        if (config.showBatteryPower) items.add(Item("PWR", "1.845", " W"))
        if (config.showFramePower) items.add(Item("FPWR", "0.031", " W"))

        var maxLabelLen = 0
        var maxValueLen = 0
        var maxUnitLen = 0

        for (item in items) {
            if (item.label.length > maxLabelLen) maxLabelLen = item.label.length
            if (item.value.length > maxValueLen) maxValueLen = item.value.length
            if (item.unit.length > maxUnitLen) maxUnitLen = item.unit.length
        }

        val sb = StringBuilder()
        for (item in items) {
            val lPad = maxLabelLen - item.label.length
            sb.append(item.label)
            for (i in 0 until lPad) sb.append(' ')
            sb.append(' ')
            val vPad = maxValueLen - item.value.length
            for (i in 0 until vPad) sb.append(' ')
            sb.append(item.value)
            sb.append(item.unit)
            val uPad = maxUnitLen - item.unit.length
            for (i in 0 until uPad) sb.append(' ')
            sb.append('\n')
        }

        if (config.showLayerName) {
            sb.append("com.example.game\nMainActivity\n")
        }

        val text = if (sb.isNotEmpty()) sb.substring(0, sb.length - 1) else "FPS   60.0"

        binding.previewOverlayText.textSize = config.textSizeSp.toFloat()
        binding.previewOverlayText.setTextColor(config.textColor)
        binding.previewOverlayText.text = text

        if (config.showBackground) {
            binding.previewOverlayCard.setCardBackgroundColor(config.backgroundColor)
        } else {
            binding.previewOverlayCard.setCardBackgroundColor(Color.TRANSPARENT)
        }
    }
}
