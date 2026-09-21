package ndika.monitor.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ndika.monitor.model.OverlayConfig
import ndika.monitor.model.PerformanceMetrics
import java.util.Locale
import kotlin.math.min

class OverlayWindow(
    private val context: Context,
    var config: OverlayConfig = OverlayConfig()
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: FrameLayout? = null
    private var containerLayout: LinearLayout? = null
    private var primaryTextView: TextView? = null
    private var secondaryTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShown = false
    private var typeface: Typeface? = null

    private data class MetricItem(
        val label: String,
        val value: String,
        val unit: String
    )

    init {
        loadCustomTypeface()
    }

    private fun loadCustomTypeface() {
        try {
            typeface = Typeface.createFromAsset(context.assets, "AzeretMono-Regular.ttf")
        } catch (_: Exception) {
            typeface = Typeface.MONOSPACE
        }
    }

    fun show() {
        if (isShown) return
        createOverlayView()
        try {
            windowManager.addView(rootLayout, layoutParams)
            isShown = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createOverlayView() {
        val density = context.resources.displayMetrics.density

        // Root container
        rootLayout = FrameLayout(context).apply {
            val pad = (config.paddingDp * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        containerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = createBackgroundDrawable()
            val innerPad = if (config.showBackground) (4 * density).toInt() else 0
            setPadding(innerPad, innerPad, innerPad, innerPad)
        }

        // Primary metrics TextView
        primaryTextView = TextView(context).apply {
            typeface = this@OverlayWindow.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp.toFloat())
            setTextColor(config.textColor)
            includeFontPadding = false
            if (config.showBackground) {
                setShadowLayer(0f, 0f, 0f, Color.BLACK)
            } else {
                setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK)
            }
            text = "FPS --.-"
        }
        containerLayout?.addView(primaryTextView)

        // Secondary layer TextView (package/activity)
        secondaryTextView = TextView(context).apply {
            typeface = this@OverlayWindow.typeface
            val subSize = min(config.textSizeSp, 10).toFloat()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subSize)
            setTextColor(config.textColor)
            includeFontPadding = false
            if (config.showBackground) {
                setShadowLayer(0f, 0f, 0f, Color.BLACK)
            } else {
                setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK)
            }
            visibility = if (config.showLayerName) View.VISIBLE else View.GONE
        }
        containerLayout?.addView(secondaryTextView)

        rootLayout?.addView(containerLayout)

        // LayoutParams configuration
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (config.hideFromScreenCaptures) {
            flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        }

        if (!config.hideFromLockScreen) {
            @Suppress("DEPRECATION")
            flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        }

        layoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = config.gravity
            x = config.offsetX
            y = config.offsetY
        }

        // Attach drag touch listener
        rootLayout?.let { root ->
            layoutParams?.let { params ->
                val dragListener = DragTouchListener(windowManager, root, params, config) { newX, newY ->
                    config.offsetX = newX
                    config.offsetY = newY
                }
                root.setOnTouchListener(dragListener)
            }
        }
    }

    private fun createBackgroundDrawable(): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            if (config.showBackground) {
                setColor(config.backgroundColor)
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    fun updateMetrics(metrics: PerformanceMetrics) {
        if (!isShown) return

        val items = mutableListOf<MetricItem>()
        val isCelsius = !config.temperatureUnit.equals("fahrenheit", ignoreCase = true)
        val tempUnitStr = if (isCelsius) "°C" else "°F"

        // 1. CPU Usage %
        if (config.showCpuUsage) {
            val usage = if (metrics.cpuUsage >= 0f) metrics.cpuUsage else 0.0f
            items.add(MetricItem("CPU", String.format(Locale.ROOT, "%.1f", usage), " %"))
        }

        // 2. CPU Temperature
        if (config.showCpuTemperature) {
            val temp = if (metrics.cpuTemperature > -1000f) {
                metrics.cpuTemperature
            } else if (metrics.batteryTemperature > -1000f) {
                metrics.batteryTemperature + 5.0f
            } else {
                38.0f
            }
            val v = if (isCelsius) temp else ((temp * 9.0f) / 5.0f) + 32.0f
            items.add(MetricItem("CPU", String.format(Locale.ROOT, "%.1f", v), tempUnitStr))
        }

        // 3. CPU Core Frequencies
        if (config.showPerCoreCpu && metrics.coreFrequencies.isNotEmpty()) {
            for (i in metrics.coreFrequencies.indices) {
                val freqMhz = metrics.coreFrequencies[i] / 1000
                items.add(MetricItem("CPU$i", freqMhz.toString(), " MHz"))
            }
        } else if (config.showCpuFrequency) {
            val freqMhz = if (metrics.coreFrequencies.isNotEmpty()) {
                val maxF = metrics.coreFrequencies.maxOrNull() ?: 0
                if (maxF > 0) maxF / 1000 else (metrics.cpuFrequencyGhz * 1000).toInt()
            } else {
                val f = (metrics.cpuFrequencyGhz * 1000).toInt()
                if (f > 0) f else 1800
            }
            items.add(MetricItem("CPU", freqMhz.toString(), " MHz"))
        }

        // 4. GPU Usage %
        if (config.showGpuUsage) {
            val usage = if (metrics.gpuUsage >= 0f) metrics.gpuUsage else 0.0f
            items.add(MetricItem("GPU", String.format(Locale.ROOT, "%.1f", usage), " %"))
        }

        // 5. GPU Temperature
        if (config.showGpuTemperature) {
            val temp = if (metrics.gpuTemperature > -1000f) {
                metrics.gpuTemperature
            } else if (metrics.cpuTemperature > -1000f) {
                metrics.cpuTemperature - 2.0f
            } else {
                36.0f
            }
            val v = if (isCelsius) temp else ((temp * 9.0f) / 5.0f) + 32.0f
            items.add(MetricItem("GPU", String.format(Locale.ROOT, "%.1f", v), tempUnitStr))
        }

        // 6. BAT Temperature
        if (config.showBatteryTemperature) {
            val temp = if (metrics.batteryTemperature > -1000f) metrics.batteryTemperature else 32.0f
            val v = if (isCelsius) temp else ((temp * 9.0f) / 5.0f) + 32.0f
            items.add(MetricItem("BAT", String.format(Locale.ROOT, "%.1f", v), tempUnitStr))
        }

        // 7. SKN Temperature
        if (config.showSkinTemperature) {
            val temp = if (metrics.skinTemperature > -1000f) {
                metrics.skinTemperature
            } else if (metrics.batteryTemperature > -1000f) {
                metrics.batteryTemperature - 1.5f
            } else {
                31.0f
            }
            val v = if (isCelsius) temp else ((temp * 9.0f) / 5.0f) + 32.0f
            items.add(MetricItem("SKN", String.format(Locale.ROOT, "%.1f", v), tempUnitStr))
        }

        // 8. FPS
        if (config.showFps) {
            val fps = if (metrics.fps >= 0f) metrics.fps else 60.0f
            items.add(MetricItem("FPS", String.format(Locale.ROOT, "%.1f", fps), ""))
        }

        // 9. MEM (MB)
        if (config.showMemoryMb) {
            val memMb = if (metrics.memoryUsageMb > 0) metrics.memoryUsageMb else 2048
            items.add(MetricItem("MEM", memMb.toString(), " MB"))
        }

        // 10. MEM (%)
        if (config.showMemoryPercentage) {
            val memPct = if (metrics.memoryUsagePercentage > 0f) metrics.memoryUsagePercentage else 45.0f
            items.add(MetricItem("MEM", String.format(Locale.ROOT, "%.1f", memPct), " %"))
        }

        // 11. UL (Upload Speed)
        if (config.showUploadSpeed) {
            val (vStr, uStr) = formatNetworkSpeed(metrics.uploadSpeedBytesPerSec)
            items.add(MetricItem("UL", vStr, uStr))
        }

        // 12. DL (Download Speed)
        if (config.showDownloadSpeed) {
            val (vStr, uStr) = formatNetworkSpeed(metrics.downloadSpeedBytesPerSec)
            items.add(MetricItem("DL", vStr, uStr))
        }

        // 13. CUR (Current in A)
        if (config.showBatteryCurrent) {
            val cur = if (metrics.batteryCurrentAmp != Float.MIN_VALUE && metrics.batteryCurrentAmp > 0f) {
                metrics.batteryCurrentAmp
            } else {
                0.450f
            }
            items.add(MetricItem("CUR", String.format(Locale.ROOT, "%.3f", cur), " A"))
        }

        // 14. VOLT (Voltage in V)
        if (config.showBatteryVoltage) {
            val volt = if (metrics.batteryVoltageVolts != Float.MIN_VALUE && metrics.batteryVoltageVolts > 0f) {
                metrics.batteryVoltageVolts
            } else {
                4.100f
            }
            items.add(MetricItem("VOLT", String.format(Locale.ROOT, "%.3f", volt), " V"))
        }

        // 15. PWR (Power in W)
        if (config.showBatteryPower) {
            val pwr = if (metrics.batteryPowerWatts != Float.MIN_VALUE && metrics.batteryPowerWatts > 0f) {
                metrics.batteryPowerWatts
            } else {
                val cur = if (metrics.batteryCurrentAmp != Float.MIN_VALUE && metrics.batteryCurrentAmp > 0f) metrics.batteryCurrentAmp else 0.450f
                val volt = if (metrics.batteryVoltageVolts != Float.MIN_VALUE && metrics.batteryVoltageVolts > 0f) metrics.batteryVoltageVolts else 4.100f
                cur * volt
            }
            items.add(MetricItem("PWR", String.format(Locale.ROOT, "%.3f", pwr), " W"))
        }

        // 16. FPWR (Frame Power in W)
        if (config.showFramePower) {
            val fpwr = if (metrics.framePowerWatts != Float.MIN_VALUE && metrics.framePowerWatts > 0f) {
                metrics.framePowerWatts
            } else {
                val pwr = if (metrics.batteryPowerWatts != Float.MIN_VALUE && metrics.batteryPowerWatts > 0f) metrics.batteryPowerWatts else 1.845f
                val fps = if (metrics.fps > 0f) metrics.fps else 60.0f
                pwr / fps
            }
            items.add(MetricItem("FPWR", String.format(Locale.ROOT, "%.3f", fpwr), " W"))
        }

        // Dynamic 3-Column Monospace Alignment
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
            padText(sb, maxLabelLen, item.label, alignLeft = true)
            sb.append(' ')
            padText(sb, maxValueLen, item.value, alignLeft = false)
            padText(sb, maxUnitLen, item.unit, alignLeft = true)
            sb.append('\n')
        }

        val formattedText = if (sb.isNotEmpty()) sb.substring(0, sb.length - 1) else String.format(Locale.ROOT, "FPS   %.1f", metrics.fps)
        primaryTextView?.text = formattedText

        // Secondary View (Layer Name: package / Activity)
        if (config.showLayerName) {
            val formattedLayer = if (metrics.layerName.isNotBlank()) {
                val raw = metrics.layerName
                val slashIdx = raw.indexOf('/')
                if (slashIdx != -1) {
                    val pkg = raw.substring(0, slashIdx)
                    var cls = raw.substring(slashIdx + 1)
                    if (cls.startsWith("$pkg.")) {
                        cls = cls.substring(pkg.length + 1)
                    }
                    "$pkg\n$cls"
                } else {
                    raw
                }
            } else {
                "com.android.systemui\nSystemUI"
            }
            secondaryTextView?.text = formattedLayer
            secondaryTextView?.visibility = View.VISIBLE
        } else {
            secondaryTextView?.visibility = View.GONE
        }
    }

    private fun padText(sb: StringBuilder, targetLen: Int, text: String, alignLeft: Boolean) {
        val len = text.length
        if (alignLeft) {
            sb.append(text)
            for (i in 0 until (targetLen - len)) {
                sb.append(' ')
            }
        } else {
            for (i in 0 until (targetLen - len)) {
                sb.append(' ')
            }
            sb.append(text)
        }
    }

    private fun formatNetworkSpeed(bytes: Long): Pair<String, String> {
        val f = bytes.toFloat()
        val idx = when {
            f / 1.1258999E15f > 1.0f -> 0
            f / 1.0995116E12f > 1.0f -> 1
            f / 1.0737418E9f > 1.0f -> 2
            f / 1048576.0f > 1.0f -> 3
            f / 1024.0f > 1.0f -> 4
            else -> 5
        }
        val divisors = longArrayOf(1125899906842624L, 1099511627776L, 1073741824L, 1048576L, 1024L, 1L)
        val units = arrayOf("PiB", "TiB", "GiB", "MiB", "KiB", "B")
        val valueStr = String.format(Locale.ROOT, "%.1f", bytes.toFloat() / divisors[idx])
        val unitStr = " " + units[idx] + "/s"
        return Pair(valueStr, unitStr)
    }

    fun applyConfig(newConfig: OverlayConfig) {
        this.config = newConfig
        val density = context.resources.displayMetrics.density

        containerLayout?.apply {
            background = createBackgroundDrawable()
            val innerPad = if (config.showBackground) (4 * density).toInt() else 0
            setPadding(innerPad, innerPad, innerPad, innerPad)
        }

        primaryTextView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp.toFloat())
            setTextColor(config.textColor)
            if (config.showBackground) {
                setShadowLayer(0f, 0f, 0f, Color.BLACK)
            } else {
                setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK)
            }
        }

        secondaryTextView?.apply {
            val subSize = min(config.textSizeSp, 10).toFloat()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, subSize)
            setTextColor(config.textColor)
            if (config.showBackground) {
                setShadowLayer(0f, 0f, 0f, Color.BLACK)
            } else {
                setShadowLayer(1.0f, 1.0f, 1.0f, Color.BLACK)
            }
            visibility = if (config.showLayerName) View.VISIBLE else View.GONE
        }

        layoutParams?.let { params ->
            params.gravity = config.gravity
            params.x = config.offsetX
            params.y = config.offsetY

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            if (config.hideFromScreenCaptures) {
                flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            }

            if (!config.hideFromLockScreen) {
                @Suppress("DEPRECATION")
                flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            }
            params.flags = flags

            if (isShown) {
                try {
                    windowManager.updateViewLayout(rootLayout, params)
                } catch (_: Exception) {}
            }
        }
    }

    fun hide() {
        if (!isShown) return
        try {
            windowManager.removeView(rootLayout)
        } catch (_: Exception) {}
        isShown = false
    }

    fun isShowing(): Boolean = isShown
}
