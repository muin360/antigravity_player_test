package com.tensorix.antigravityplayer

import android.app.Application
import android.util.Log
import com.tensorix.antigravityplayer.util.CrashDiagnostics

/**
 * Antigravity Application Root
 * Initializes structured crash diagnostics and safe application configuration.
 */
class AntigravityApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("AntigravityPlayer", "══════════════════════════════════════════════════════════")
        Log.i("AntigravityPlayer", "🚀 [ANTIGRAVITY AUDIO ENGINE] Core Engine Initializing...")
        
        // 1. Install global structured crash & anomaly handler
        CrashDiagnostics.installGlobalHandler(this)
        
        Log.i("AntigravityPlayer", "💎 [STARTUP] Application initialized safely without blocking main thread.")
        Log.i("AntigravityPlayer", "══════════════════════════════════════════════════════════")
    }
}
