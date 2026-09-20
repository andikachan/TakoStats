package ndika.monitor.tracker

import ndika.monitor.model.PerformanceMetrics

interface ITracker {
    fun start() {}
    fun update(metrics: PerformanceMetrics)
    fun stop() {}
}
