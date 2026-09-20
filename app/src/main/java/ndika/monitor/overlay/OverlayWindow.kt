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

class OverlayWindow(
    private val context: Context,
    var config: OverlayConfig = OverlayConfig()
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootLayout: FrameLayout? = null
    private var primaryTextView: TextView? = null
    private var secondaryTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShown = false
    private var typeface: Typeface? = null

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
            background = createBackgroundDrawable()
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        // Primary metrics TextView
        primaryTextView = TextView(context).apply {
            typeface = this@OverlayWindow.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp.toFloat())
            setTextColor(config.textColor)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            text = "FPS --.-"
        }
        container.addView(primaryTextView)

        // Secondary layer TextView
        secondaryTextView = TextView(context).apply {
            typeface = this@OverlayWindow.typeface
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (config.textSizeSp * 0.85f))
            setTextColor(config.textColor)
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
            visibility = if (config.showLayerName) View.VISIBLE else View.GONE
        }
        container.addView(secondaryTextView)

        rootLayout?.addView(container)

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
            cornerRadius = config.cornerRadiusDp * density
            if (config.showBackground) {
                setColor(config.backgroundColor)
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    fun updateMetrics(metrics: PerformanceMetrics) {
        if (!isShown) return

        val lines = mutableListOf<String>()
        val unit = config.temperatureUnit

        if (config.showFps) {
            lines.add(String.format(Locale.US, "FPS  %5.1f", metrics.fps))
        }

        if (config.showCpuUsage) {
            lines.add(String.format(Locale.US, "CPU  %5.1f %%", metrics.cpuUsage))
        }

        if (config.showCpuFrequency && metrics.cpuFrequencyGhz > 0f) {
            lines.add(String.format(Locale.US, "FRQ  %5.2f GHz", metrics.cpuFrequencyGhz))
        }

        if (config.showCpuTemperature && metrics.cpuTemperature > 0f) {
            val formattedTemp = metrics.formatTemperature(metrics.cpuTemperature, unit)
            lines.add(String.format(Locale.US, "CPUT %s", formattedTemp))
        }

        if (config.showGpuUsage && metrics.gpuUsage > 0f) {
            lines.add(String.format(Locale.US, "GPU  %5.1f %%", metrics.gpuUsage))
        }

        if (config.showGpuTemperature && metrics.gpuTemperature > 0f) {
            val formattedTemp = metrics.formatTemperature(metrics.gpuTemperature, unit)
            lines.add(String.format(Locale.US, "GPUT %s", formattedTemp))
        }

        if (config.showBatteryTemperature && metrics.batteryTemperature > 0f) {
            val formattedTemp = metrics.formatTemperature(metrics.batteryTemperature, unit)
            lines.add(String.format(Locale.US, "BAT  %s", formattedTemp))
        }

        if (config.showSkinTemperature && metrics.skinTemperature > 0f) {
            val formattedTemp = metrics.formatTemperature(metrics.skinTemperature, unit)
            lines.add(String.format(Locale.US, "SKIN %s", formattedTemp))
        }

        if (config.showBatteryCurrent && metrics.batteryCurrentAmp > 0f) {
            lines.add(String.format(Locale.US, "CUR  %5.2f A", metrics.batteryCurrentAmp))
        }

        if (config.showBatteryVoltage && metrics.batteryVoltageVolts > 0f) {
            lines.add(String.format(Locale.US, "VOLT %5.2f V", metrics.batteryVoltageVolts))
        }

        if (config.showBatteryPower && metrics.batteryPowerWatts > 0f) {
            lines.add(String.format(Locale.US, "PWR  %5.2f W", metrics.batteryPowerWatts))
        }

        if (config.showFramePower && metrics.framePowerMilliJoules > 0f) {
            lines.add(String.format(Locale.US, "FPWR %5.1f mJ", metrics.framePowerMilliJoules))
        }

        if (config.showMemoryMb && metrics.memoryUsageMb > 0) {
            lines.add(String.format(Locale.US, "RAM  %5d MB", metrics.memoryUsageMb))
        }

        if (config.showMemoryPercentage && metrics.memoryUsagePercentage > 0f) {
            lines.add(String.format(Locale.US, "MEM  %5.1f %%", metrics.memoryUsagePercentage))
        }

        if (config.showDownloadSpeed && metrics.downloadSpeedBytesPerSec > 0) {
            val speedStr = formatNetworkSpeed(metrics.downloadSpeedBytesPerSec)
            lines.add(String.format(Locale.US, "DL   %s", speedStr))
        }

        if (config.showUploadSpeed && metrics.uploadSpeedBytesPerSec > 0) {
            val speedStr = formatNetworkSpeed(metrics.uploadSpeedBytesPerSec)
            lines.add(String.format(Locale.US, "UL   %s", speedStr))
        }

        val formattedText = if (lines.isNotEmpty()) {
            lines.joinToString("\n")
        } else {
            String.format(Locale.US, "FPS  %5.1f", metrics.fps)
        }

        primaryTextView?.text = formattedText

        if (config.showLayerName && metrics.layerName.isNotBlank()) {
            secondaryTextView?.visibility = View.VISIBLE
            secondaryTextView?.text = metrics.layerName
        } else {
            secondaryTextView?.visibility = View.GONE
        }
    }

    private fun formatNetworkSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%5.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%5.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%5d B/s", bytesPerSec)
        }
    }

    fun applyConfig(newConfig: OverlayConfig) {
        this.config = newConfig
        rootLayout?.background = createBackgroundDrawable()
        primaryTextView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, config.textSizeSp.toFloat())
            setTextColor(config.textColor)
        }
        secondaryTextView?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (config.textSizeSp * 0.85f))
            setTextColor(config.textColor)
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
