package com.tensorix.antigravityplayer.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Structured Crash & Anomaly Diagnostics Recorder.
 * Captures lifecycle, audio subsystem, and permission context without logging sensitive user data.
 */
object CrashDiagnostics {

    private const val TAG = "CrashDiagnostics"
    private const val MAX_EVENTS = 50

    data class DiagnosticEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val exceptionType: String,
        val message: String,
        val subsystem: String,
        val lifecycleStage: String,
        val apiLevel: Int = Build.VERSION.SDK_INT,
        val manufacturer: String = Build.MANUFACTURER ?: "Unknown",
        val model: String = Build.MODEL ?: "Unknown",
        val audioRoute: String? = null,
        val permissionState: String? = null
    )

    private val events = ConcurrentLinkedDeque<DiagnosticEvent>()

    @Volatile
    var lastFatalReason: String? = null
        private set

    fun record(
        subsystem: String,
        stage: String,
        throwable: Throwable,
        audioRoute: String? = null,
        permissionState: String? = null
    ) {
        val event = DiagnosticEvent(
            exceptionType = throwable.javaClass.name,
            message = throwable.message ?: "No message",
            subsystem = subsystem,
            lifecycleStage = stage,
            audioRoute = audioRoute,
            permissionState = permissionState
        )

        events.addLast(event)
        while (events.size > MAX_EVENTS) {
            events.pollFirst()
        }

        lastFatalReason = "[$subsystem][$stage] ${throwable.javaClass.simpleName}: ${throwable.message}"
        runCatching {
            Log.e(TAG, "🚨 [ANOMALY DETECTED] Subsystem: $subsystem | Stage: $stage | Error: ${throwable.message}", throwable)
        }
    }

    fun getRecentEvents(): List<DiagnosticEvent> {
        return events.toList()
    }

    fun installGlobalHandler(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(
                    subsystem = "GLOBAL_THREAD",
                    stage = "UNCAUGHT_EXCEPTION (Thread: ${thread.name})",
                    throwable = throwable
                )
            } catch (e: Exception) {
                // Ignore failure in handler
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
