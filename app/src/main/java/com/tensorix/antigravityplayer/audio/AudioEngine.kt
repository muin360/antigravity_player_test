package com.tensorix.antigravityplayer.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SINGLE AUTHORITATIVE OPERATIONAL COMPONENT: AudioEngine
 *
 * Responsibilities:
 * - Current output mode & active route
 * - Active sink (OboeAudioSink direct / DefaultAudioSink normal)
 * - Native stream lifecycle and state
 * - Serialized route reconfiguration
 * - DSP mode & Bit-Perfect state machine
 * - Single recovery authority
 * - Output telemetry & canonical runtime snapshot
 */
@UnstableApi
object AudioEngine {
    private const val TAG = "AudioEngine"

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val routeMutex = Mutex()

    private val _snapshot = MutableStateFlow<CanonicalAudioRuntimeSnapshot?>(null)
    val snapshot: StateFlow<CanonicalAudioRuntimeSnapshot?> = _snapshot.asStateFlow()

    private val _activeRoute = MutableStateFlow<AudioRouteCapability?>(null)
    val activeRoute: StateFlow<AudioRouteCapability?> = _activeRoute.asStateFlow()

    private val _bitPerfectRequested = MutableStateFlow(false)
    val bitPerfectRequested: StateFlow<Boolean> = _bitPerfectRequested.asStateFlow()

    private val _bitPerfectState = MutableStateFlow(BitPerfectState.DISABLED)
    val bitPerfectState: StateFlow<BitPerfectState> = _bitPerfectState.asStateFlow()

    private val _recoveryState = MutableStateFlow("NORMAL")
    val recoveryState: StateFlow<String> = _recoveryState.asStateFlow()

    fun getBitPerfectState(): BitPerfectState = _bitPerfectState.value

    fun updateSnapshot(context: Context, trackInfo: AudioTrackInfo, isDspActive: Boolean) {
        val outputManager = com.tensorix.antigravityplayer.player.PlaybackService.instance?.audioOutputManager
        val state = outputManager?.scanOutputState(trackInfo, isDspActive)
        _snapshot.value = state?.canonicalSnapshot
        state?.canonicalSnapshot?.let { canon ->
            _bitPerfectState.value = canon.bitPerfect.state
        }
    }

    fun setSnapshot(snapshot: CanonicalAudioRuntimeSnapshot?) {
        _snapshot.value = snapshot
        snapshot?.let { _bitPerfectState.value = it.bitPerfect.state }
    }

    fun invalidate() {
        _snapshot.value = null
        if (!_bitPerfectRequested.value) {
            _bitPerfectState.value = BitPerfectState.DISABLED
        }
    }

    fun resetForTesting() {
        _snapshot.value = null
        _activeRoute.value = null
        _bitPerfectRequested.value = false
        _bitPerfectState.value = BitPerfectState.DISABLED
        _recoveryState.value = "NORMAL"
    }

    fun setBitPerfectMode(enabled: Boolean) {
        _bitPerfectRequested.value = enabled
        _bitPerfectState.value = if (enabled) BitPerfectState.REQUESTED else BitPerfectState.DISABLED
        invalidate()
    }

    fun setBitPerfectState(state: BitPerfectState) {
        _bitPerfectState.value = state
    }

    /**
     * Serialized non-destructive route reconfiguration.
     * Guaranteed never to rebuild ExoPlayer or drop playback queue/position.
     */
    suspend fun reconfigureRoute(context: Context, newRoute: AudioRouteCapability?) = routeMutex.withLock {
        runCatching { Log.i(TAG, "Reconfiguring route sequentially to: ${newRoute?.routeType?.displayName ?: "UNKNOWN"}") }
        _activeRoute.value = newRoute
        invalidate()

        val service = com.tensorix.antigravityplayer.player.PlaybackService.instance
        
        // 1. Flush native sink if active to prevent stale audio
        service?.activeOboeAudioSink?.flush()

        // 2. Trigger optional vendor probe in background if wired/USB
        val routeType = newRoute?.routeType
        if (routeType == AudioOutputRouteType.WIRED_HEADSET ||
            routeType == AudioOutputRouteType.WIRED_HEADPHONES ||
            routeType == AudioOutputRouteType.USB_DAC ||
            routeType == AudioOutputRouteType.USB_DEVICE
        ) {
            val appContext = runCatching { context.applicationContext }.getOrNull() ?: context
            runCatching { AudioInitializationCoordinator.triggerOptionalVendorProbe(appContext) }
        }

        // 3. Re-evaluate snapshot and update UI
        service?.refreshAudiophileState()
    }

    /**
     * Synchronous bridge for reconfigureRoute when calling from non-coroutine contexts.
     */
    fun reconfigureForRouteChange(context: Context, newRoute: AudioRouteCapability?) {
        engineScope.launch {
            reconfigureRoute(context, newRoute)
        }
    }

    /**
     * Single recovery authority for stream errors reported by native layer or AudioSink.
     */
    fun handleStreamError(errorCode: Int, context: Context) {
        runCatching { Log.w(TAG, "Stream error reported: $errorCode. Executing controlled single-authority recovery.") }
        _recoveryState.value = "RECOVERING"
        invalidate()
        val service = com.tensorix.antigravityplayer.player.PlaybackService.instance
        service?.refreshAudiophileState()
        _recoveryState.value = "NORMAL"
    }
}
