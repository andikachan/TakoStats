package rikka.fpsmonitor.shizuku

import android.os.Bundle
import android.os.RemoteCallbackList
import rikka.fpsmonitor.IRemoteFpsCallback
import rikka.fpsmonitor.IRemoteFpsService
import rikka.fpsmonitor.model.OverlayConfig
import rikka.fpsmonitor.model.PerformanceMetrics

class RemoteFpsService : IRemoteFpsService.Stub() {

    private val callbackList = RemoteCallbackList<IRemoteFpsCallback>()
    private var isMonitoringActive = false
    private var overlayConfig = OverlayConfig()

    override fun registerCallback(callback: IRemoteFpsCallback?) {
        if (callback != null) {
            callbackList.register(callback)
            try {
                callback.onServiceStatusChanged(isMonitoringActive)
            } catch (_: Exception) {}
        }
    }

    override fun unregisterCallback(callback: IRemoteFpsCallback?) {
        if (callback != null) {
            callbackList.unregister(callback)
        }
    }

    override fun updateConfig(config: Bundle?) {
        if (config != null) {
            overlayConfig = OverlayConfig.fromBundle(config)
        }
    }

    override fun startMonitoring() {
        isMonitoringActive = true
        notifyStatusChanged(true)
    }

    override fun stopMonitoring() {
        isMonitoringActive = false
        notifyStatusChanged(false)
    }

    override fun isRunning(): Boolean = isMonitoringActive

    override fun destroy() {
        stopMonitoring()
        callbackList.kill()
    }

    private fun notifyStatusChanged(running: Boolean) {
        val count = callbackList.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbackList.getBroadcastItem(i).onServiceStatusChanged(running)
            } catch (_: Exception) {}
        }
        callbackList.finishBroadcast()
    }

    fun broadcastFrameData(metrics: PerformanceMetrics) {
        if (!isMonitoringActive) return
        val bundle = metrics.toBundle()
        val count = callbackList.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbackList.getBroadcastItem(i).onFrameData(bundle)
            } catch (_: Exception) {}
        }
        callbackList.finishBroadcast()
    }
}
