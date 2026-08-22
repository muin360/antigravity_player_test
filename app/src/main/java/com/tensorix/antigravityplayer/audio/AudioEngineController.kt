package com.tensorix.antigravityplayer.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single Authoritative Controller for the Antigravity Audio Engine.
 * Coordinates between Native Oboe, Media3, and Hardware Verification.
 */
@UnstableApi
object AudioEngineController {

    private val _snapshot = MutableStateFlow<CanonicalAudioRuntimeSnapshot?>(null)
    val snapshot: StateFlow<CanonicalAudioRuntimeSnapshot?> = _snapshot.asStateFlow()

    fun updateSnapshot(context: Context, trackInfo: AudioTrackInfo, isDspActive: Boolean) {
        val outputManager = com.tensorix.antigravityplayer.player.PlaybackService.instance?.audioOutputManager
        val state = outputManager?.scanOutputState(trackInfo, isDspActive)
        _snapshot.value = state?.canonicalSnapshot
    }

    fun setSnapshot(snapshot: CanonicalAudioRuntimeSnapshot?) {
        _snapshot.value = snapshot
    }

    fun invalidate() {
        _snapshot.value = null
        HardwareHiFiVerifier.invalidateCache()
    }

    fun getBitPerfectState(): BitPerfectState {
        return _snapshot.value?.bitPerfect?.state ?: BitPerfectState.UNKNOWN
    }

    /**
     * Reconfigures audio engine for a route change event without destroying the Media3 player.
     */
    fun reconfigureForRouteChange(context: Context, newRoute: AudioRouteCapability?) {
        invalidate()
        val service = com.tensorix.antigravityplayer.player.PlaybackService.instance
        service?.refreshAudiophileState()

        val routeType = newRoute?.routeType
        if (routeType == AudioOutputRouteType.WIRED_HEADSET ||
            routeType == AudioOutputRouteType.WIRED_HEADPHONES ||
            routeType == AudioOutputRouteType.USB_DAC ||
            routeType == AudioOutputRouteType.USB_DEVICE
        ) {
            val appContext = context.applicationContext ?: context
            AudioInitializationCoordinator.triggerOptionalVendorProbe(appContext)
        }
    }
}
