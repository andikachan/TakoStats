package rikka.fpsmonitor.tracker

import rikka.fpsmonitor.model.PerformanceMetrics

interface ITracker {
    fun start() {}
    fun update(metrics: PerformanceMetrics)
    fun stop() {}
}
