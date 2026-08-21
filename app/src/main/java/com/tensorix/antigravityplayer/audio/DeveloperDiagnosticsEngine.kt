package com.tensorix.antigravityplayer.audio

import android.os.Build
import android.os.Process
import android.os.SystemClock

data class PerformanceMetrics(
    val usedMemoryMb: Long,
    val totalMemoryMb: Long,
    val maxMemoryMb: Long,
    val uptimeSeconds: Long,
    val threadCount: Int,
    val lowMemoryRisk: Boolean
)

/**
 * Real-time Performance & Audiophile Developer Diagnostics Engine
 * Monitors JVM heap footprint, low-latency audio buffer health, and hardware path integrity.
 */
object DeveloperDiagnosticsEngine {

    private val startTimeMs = SystemClock.elapsedRealtime()

    fun samplePerformance(): PerformanceMetrics {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory

        val uptimeSec = (SystemClock.elapsedRealtime() - startTimeMs) / 1000
        val threadCount = Thread.activeCount()

        return PerformanceMetrics(
            usedMemoryMb = usedMemory,
            totalMemoryMb = totalMemory,
            maxMemoryMb = maxMemory,
            uptimeSeconds = uptimeSec,
            threadCount = threadCount,
            lowMemoryRisk = usedMemory > (maxMemory * 0.85)
        )
    }

    fun generateDiagnosticsReport(snapshot: AudiophilePlaybackSnapshot): String {
        val track = snapshot.track
        val output = snapshot.output
        val metrics = samplePerformance()

        return buildString {
            appendLine("=== ANTIGRAVITY AUDIOPHILE DIAGNOSTICS REPORT ===")
            appendLine("Track: ${track.title} by ${track.artist}")
            appendLine("Source: ${track.codec} | ${track.bitDepth}-bit | ${track.sampleRateHz} Hz | ${track.bitrateKbps} kbps")
            appendLine("Active Route: ${output.activeRoute?.deviceName ?: "Default Speaker"}")
            appendLine("Bit-Perfect State: ${output.bitPerfectState.label}")
            appendLine("Float Sink Active: ${output.playbackPath}")
            appendLine("Memory: ${metrics.usedMemoryMb}MB / ${metrics.maxMemoryMb}MB (Heap: ${metrics.totalMemoryMb}MB)")
            appendLine("Android OS: SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            appendLine("==================================================")
        }
    }
}
