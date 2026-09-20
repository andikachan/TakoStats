package rikka.fpsmonitor.overlay

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import rikka.fpsmonitor.model.OverlayConfig
import kotlin.math.max

class DragTouchListener(
    private val windowManager: WindowManager,
    private val overlayView: View,
    private val layoutParams: WindowManager.LayoutParams,
    private val config: OverlayConfig,
    private val onPositionChanged: ((x: Int, y: Int) -> Unit)? = null
) : View.OnTouchListener {

    private var startRawX = 0f
    private var startRawY = 0f
    private var initialX = 0
    private var initialY = 0

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!config.isDraggable) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawX = event.rawX
                startRawY = event.rawY
                initialX = layoutParams.x
                initialY = layoutParams.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - startRawX).toInt()
                val deltaY = (event.rawY - startRawY).toInt()

                val isRightAligned = (layoutParams.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END ||
                        (layoutParams.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT

                val isBottomAligned = (layoutParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM

                val newX = if (isRightAligned) initialX - deltaX else initialX + deltaX
                val newY = if (isBottomAligned) initialY - deltaY else initialY + deltaY

                layoutParams.x = max(0, newX)
                layoutParams.y = max(0, newY)

                try {
                    windowManager.updateViewLayout(overlayView, layoutParams)
                } catch (_: Exception) {}

                config.offsetX = layoutParams.x
                config.offsetY = layoutParams.y
                onPositionChanged?.invoke(layoutParams.x, layoutParams.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }
        return false
    }
}
